#!/usr/bin/env python3
"""Reproducible diagnostic bench; never writes listening scores or blind keys."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import tempfile
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pyloudnorm as pyln
import soundfile as sf
from scipy.signal import welch

from analyze_recording import analyze_sync
from dsp.coherence import analyze_coherence
from dsp.limiter import true_peak
from dsp.xtc import apply_xtc, design_xtc_filters, _crossfeed_model
from render import PROTO_VERSION, VARIANT_CODES, _variant_params, process
from signals import _pink_noise
from tests.simulate_sync_recording import simulate


def db(value):
    return float(20 * np.log10(max(float(value), 1e-15)))


def mono_curve(audio, reference, rate):
    f, p = welch(audio.mean(axis=1), rate, nperseg=8192, noverlap=4096)
    _, q = welch(reference.mean(axis=1), rate, nperseg=8192, noverlap=4096)
    band = (f >= 300) & (f <= 5000)
    delta = 10 * np.log10(np.maximum(p[band], 1e-30) / np.maximum(q[band], 1e-30))
    return f[band], delta


def spatial(audio):
    mid = audio.mean(axis=1)
    side = (audio[:, 0] - audio[:, 1]) / 2
    return {"side_mid_db": db(np.linalg.norm(side) / max(np.linalg.norm(mid), 1e-15)),
            "lr_correlation": float(np.corrcoef(audio.T)[0, 1])}


def transient_probes(cfg):
    """Measure anticipatory energy on known bursts; no perceptual verdict."""
    rate = int(cfg["sample_rate"])
    onset, duration = rate // 2, rate // 100
    rng = np.random.default_rng(91)
    burst = rng.normal(0, .05, (duration, 2))
    rows = []
    for kind in ("center", "independent_channels"):
        source = np.zeros((rate, 2))
        source[onset:onset + duration] = burst if kind == "independent_channels" else burst[:, :1]
        for variant in ("W2_LO", "W2_HI", "W2_W3", "W2_W3_W6"):
            rendered = process(source, _variant_params(cfg, variant), cfg, rate)
            rows.append({"signal": kind, "variant": variant,
                "side_mid_db": spatial(rendered)["side_mid_db"],
                "relative_change_db": db(np.linalg.norm(rendered - source) / np.linalg.norm(source)),
                "pre_onset_energy_fraction": float(np.sum(rendered[:onset] ** 2) / np.sum(rendered ** 2)),
                "pre_onset_peak_relative_db": db(np.max(abs(rendered[:onset])) / np.max(abs(rendered))),
                "post_burst_energy_fraction": float(np.sum(rendered[onset + duration:] ** 2) / np.sum(rendered ** 2))})
    return rows


def evaluate(run, output):
    output.mkdir(parents=True, exist_ok=True)
    key = json.loads((run / "key.json").read_text())
    cfg = key["config"]
    rate = int(key["sample_rate"])
    result = {"evaluation_version": PROTO_VERSION, "render_version": key["PROTO_VERSION"],
              "run": str(run.resolve()), "config": cfg, "source_files": key["source_files"],
              "probe_seeds": [11, 23, 47], "sync_seeds": [7, 19, 35],
              "music": [], "probes": [], "ablation": [], "sync": [], "xtc": []}
    grouped = {}
    for item in key["tracks"].values():
        if item.get("kind") == "music":
            grouped.setdefault(item["fragment_id"], {})[item["variant"]] = item
    fig, axes = plt.subplots(len(grouped), 1, figsize=(10, 3 * len(grouped)), squeeze=False)
    for axis, (fragment, items) in zip(axes[:, 0], sorted(grouped.items())):
        reference, _ = sf.read(run / "tracks" / items["REF"]["filename"])
        for variant, item in items.items():
            path = run / "tracks" / item["filename"]
            audio, actual_rate = sf.read(path)
            info = sf.info(path)
            if actual_rate != rate or audio.shape != reference.shape:
                raise ValueError(f"Incompatible track: {path}")
            f, delta = mono_curve(audio, reference, rate)
            all_f, ref_power = welch(reference.mean(axis=1), rate, nperseg=8192, noverlap=4096)
            ref_band_power = ref_power[(all_f >= 300) & (all_f <= 5000)]
            supported = ref_band_power >= ref_band_power.max() * 1e-4
            row = {"fragment": fragment, "variant": variant,
                   "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                   "sample_rate": actual_rate, "subtype": info.subtype, "seconds": info.duration,
                   "lufs": float(pyln.Meter(rate).integrated_loudness(audio)),
                   "true_peak_4x_dbtp": db(true_peak(audio, 4)),
                   "mono_ripple_pp_db": float(np.ptp(delta)), **spatial(audio)}
            row["mono_ripple_ref_above_minus40db_pp_db"] = float(np.ptp(delta[supported]))
            result["music"].append(row)
            if variant in ("W2_LO", "W2_HI", "W2_W3", "W2_W3_W6"):
                axis.semilogx(f, delta - delta.mean(), label=variant)
        axis.set(title=fragment, xlabel="Hz", ylabel="Mono spectrum change, dB")
        axis.grid(alpha=.25)
        axis.legend(ncol=4, fontsize=8)
        # Isolate W3 phase rotation and W6 on the same first 12 seconds.
        source = reference[:rate * 12]
        for theta in (0, 15, 30, 45):
            config = copy.deepcopy(cfg)
            config["ambient"]["theta_max_deg"] = theta
            for variant in ("W2_W3", "W2_W3_W6"):
                audio = process(source, _variant_params(config, variant), config, rate)
                _, delta = mono_curve(audio, source, rate)
                result["ablation"].append({"fragment": fragment, "theta_deg": theta,
                                            "variant": variant, "mono_ripple_pp_db": float(np.ptp(delta))})
        print(f"Music {fragment} complete", flush=True)
    fig.tight_layout()
    fig.savefig(output / "music_mono.png", dpi=150)
    plt.close(fig)

    # Both channels share the same spectral shape; rho=0 stresses the ILD blind spot.
    for seed in (11, 23, 47):
        rng = np.random.default_rng(seed)
        left = _pink_noise(12, rate, rng)
        other = _pink_noise(12, rate, rng)
        left /= np.std(left)
        other /= np.std(other)
        for rho in (0, .25, .5, .75, 1):
            source = .04 * np.column_stack((left, rho * left + np.sqrt(1 - rho**2) * other))
            _, mask, _ = analyze_coherence(source, rate, cfg["stft"], cfg["stft"]["n_fft"], cfg["stft"]["hop"])
            band = np.fft.rfftfreq(cfg["stft"]["n_fft"], 1 / rate) >= 300
            for variant in ("W2_LO", "W2_HI", "W2_W3", "W2_W3_W6"):
                audio = process(source, _variant_params(cfg, variant), cfg, rate)
                _, delta = mono_curve(audio, source, rate)
                result["probes"].append({"seed": seed, "rho": rho, "variant": variant,
                    "mean_diffuse_mask_above_300": float(mask[band].mean()),
                    "mono_ripple_pp_db": float(np.ptp(delta)),
                    "channel_gain_db": [db(np.linalg.norm(audio[:, c]) / np.linalg.norm(source[:, c])) for c in (0, 1)],
                    "finite": bool(np.isfinite(audio).all()), **spatial(audio)})
        print(f"Coherence sweep seed {seed} complete", flush=True)
    result["silence"] = {v: float(np.max(np.abs(process(np.zeros((rate, 2)), _variant_params(cfg, v), cfg, rate)))) for v in VARIANT_CODES}
    result["transients"] = transient_probes(cfg)

    # Simulated recordings are temporary and cannot appear as real UI recordings.
    with tempfile.TemporaryDirectory() as temporary:
        for seed in (7, 19, 35):
            for drift in (-20, 20):
                for jitter in (0, 20, 100):
                    root = Path(temporary)
                    path = simulate(root / "simulation.wav", jitter, 60, seed=seed, recorder_drift_ppm=drift)
                    measured = analyze_sync(path, root / "report", cfg)
                    result["sync"].append({"seed": seed, "drift_ppm": drift, "jitter_us": jitter,
                        "estimated_ppm": measured["estimated_recorder_clock_ppm"],
                        "estimated_sigma_us": measured["jitter_sigma_us"], "pair_count": measured["pair_count"]})
            print(f"Sync seed {seed} complete", flush=True)

    # Verify actual FIRs, not only the ideal matrix inverse; model evidence only.
    params = cfg["xtc"]
    impulse = np.zeros((rate, 2))
    impulse[rate // 4, 0] = 1
    for distance in params["listener_distances_m"]:
        for name, regularization in params["regularization"].items():
            filters, delay = design_xtc_filters(rate, params["speaker_spacing_m"], distance,
                params["head_radius_m"], params["sound_speed_m_s"], params["low_hz"], params["high_hz"],
                regularization, params["fft_size"], params["causal_delay_ms"])
            rendered, _ = apply_xtc(impulse, rate, params, distance, regularization)
            fft_size = 65536
            response = np.fft.rfft(filters, n=fft_size, axis=-1)
            frequencies = np.fft.rfftfreq(fft_size, 1 / rate)
            indices = np.flatnonzero((frequencies >= 600) & (frequencies <= 5500))[::32]
            for actual_distance in sorted(set((distance, .4, .6))):
                before, after = [], []
                for i in indices:
                    transfer = _crossfeed_model(frequencies[i], params["speaker_spacing_m"], actual_distance,
                                                params["head_radius_m"], params["sound_speed_m_s"])
                    combined = transfer @ response[:, :, i]
                    before.append(db(abs(transfer[1, 0]) / abs(transfer[0, 0])))
                    after.append(db(abs(combined[1, 0]) / max(abs(combined[0, 0]), 1e-15)))
                result["xtc"].append({"design_distance_m": distance, "actual_model_distance_m": actual_distance,
                    "regularization": name, "compensation_samples": delay,
                    "remaining_impulse_peak_delay_samples": int(np.argmax(np.max(np.abs(rendered), axis=1)) - rate // 4),
                    "median_crosstalk_improvement_db": float(np.median(np.array(before) - after)),
                    "worst_crosstalk_improvement_db": float(np.min(np.array(before) - after))})

    def finite(value):
        # LUFS = −inf у тишины и NaN-корреляции не должны ронять многочасовой прогон на записи результата.
        if isinstance(value, float):
            return value if np.isfinite(value) else None
        if isinstance(value, dict):
            return {k: finite(v) for k, v in value.items()}
        if isinstance(value, (list, tuple)):
            return [finite(v) for v in value]
        return value
    (output / "evaluation.json").write_text(json.dumps(finite(result), ensure_ascii=False, indent=2, allow_nan=False) + "\n")
    lines = ["# Диагностика алгоритмов", "", f"Анализатор {PROTO_VERSION}; исходный рендер {key['PROTO_VERSION']}.",
             "Это объективные измерения и явно синтетические пробы; слуховых оценок здесь нет.", "",
             "| Вариант | LUFS, min…max | Пик 4×, max dBTP | Моно ripple, max dB | S/M, среднее dB |",
             "|---|---:|---:|---:|---:|"]
    for variant in VARIANT_CODES:
        rows = [r for r in result["music"] if r["variant"] == variant]
        lines.append(f"| {variant} | {min(r['lufs'] for r in rows):.3f}…{max(r['lufs'] for r in rows):.3f} | {max(r['true_peak_4x_dbtp'] for r in rows):.3f} | {max(r['mono_ripple_pp_db'] for r in rows):.3f} | {np.mean([r['side_mid_db'] for r in rows]):.2f} |")
    lines += ["", "![Моно-сумма](music_mono.png)", "", "Все пробы, параметры и SHA-256 WAV: `evaluation.json`."]
    (output / "evaluation.md").write_text("\n".join(lines) + "\n")
    print(output / "evaluation.md", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path, help="Existing render directory containing key.json and tracks/")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    evaluate(args.run, args.output)
