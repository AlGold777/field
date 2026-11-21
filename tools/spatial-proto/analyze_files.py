#!/usr/bin/env python3
"""Analyze rendered files against REF and write report tables and PNG graphs."""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import soundfile as sf
from scipy.signal import csd, welch

from dsp.common import band_power, one_third_octave_centers
from dsp.coherence import analyze_coherence
from render import PROTO_VERSION, VARIANT_CODES, _variant_params, load_config, process
from dsp.limiter import limit
from signals import generate_synthetic


def _read(path: Path) -> tuple[np.ndarray, int]:
    audio, rate = sf.read(str(path), dtype="float64", always_2d=True)
    if audio.shape[1] != 2:
        raise ValueError(f"Не стереофайл: {path}")
    return audio, rate


def _spectra(audio: np.ndarray, sample_rate: int) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    mono = (audio[:, 0] + audio[:, 1]) * 0.5
    frequencies, mono_power = welch(mono, sample_rate, nperseg=min(32768, len(mono)), noverlap=min(16384, len(mono) // 2))
    f, left_power = welch(audio[:, 0], sample_rate, nperseg=min(32768, len(audio)), noverlap=min(16384, len(audio) // 2))
    _, right_power = welch(audio[:, 1], sample_rate, nperseg=min(32768, len(audio)), noverlap=min(16384, len(audio) // 2))
    _, cross = csd(audio[:, 0], audio[:, 1], sample_rate, nperseg=min(32768, len(audio)), noverlap=min(16384, len(audio) // 2))
    coherence = np.abs(cross) / np.sqrt(left_power * right_power + 1e-30)
    return frequencies, mono_power, f, np.clip(coherence, 0.0, 1.0)


def _band_values(frequencies: np.ndarray, values: np.ndarray, centers: np.ndarray) -> list[float]:
    result = []
    for center in centers:
        val = band_power(frequencies, values, float(center))
        result.append(float(val))
    return result


def _welch_shape_ripple_pp(audio: np.ndarray, reference: np.ndarray, sample_rate: int) -> float:
    """High-resolution mono-sum ripple relative to REF over 300 Hz–5 kHz."""
    count = min(8192, len(audio), len(reference))
    if count < 256:
        return float("nan")
    noverlap = min(count // 2, 4096)
    mono = (audio[:, 0] + audio[:, 1]) * 0.5
    ref_mono = (reference[:, 0] + reference[:, 1]) * 0.5
    frequencies, power = welch(mono, sample_rate, nperseg=count, noverlap=noverlap, nfft=8192)
    _, ref_power = welch(ref_mono, sample_rate, nperseg=count, noverlap=noverlap, nfft=8192)
    selected = (frequencies >= 300.0) & (frequencies <= 5000.0)
    delta_db = 10.0 * np.log10(np.maximum(power[selected], 1e-30) / np.maximum(ref_power[selected], 1e-30))
    return float(np.max(delta_db) - np.min(delta_db))


def _click_width_ms(audio: np.ndarray, sample_rate: int) -> float:
    mono = np.abs((audio[:, 0] + audio[:, 1]) * 0.5)
    widths = []
    for second in np.arange(0.5, len(mono) / sample_rate - 0.01, 0.5):
        center = int(round(second * sample_rate))
        radius = int(round(0.008 * sample_rate))
        section = mono[max(0, center - radius) : min(len(mono), center + radius)]
        if not len(section):
            continue
        peak = int(np.argmax(section))
        if section[peak] <= 0:
            continue   # тишина: порог 0 растягивал «ширину щелчка» на всё окно
        threshold = section[peak] * 0.5
        left = peak
        right = peak
        while left > 0 and section[left - 1] >= threshold:
            left -= 1
        while right + 1 < len(section) and section[right + 1] >= threshold:
            right += 1
        widths.append((right - left + 1) * 1000.0 / sample_rate)
    return float(np.median(widths)) if widths else 0.0


def _plot_lines(path: Path, centers: np.ndarray, series: dict[str, list[float]], title: str, ylabel: str, zero: bool = False) -> None:
    fig, axis = plt.subplots(figsize=(9, 4.5), constrained_layout=True)
    for name, values in series.items():
        axis.plot(centers, values, marker="o", markersize=3, label=name)
    if zero:
        axis.axhline(0.0, color="black", linewidth=0.8, alpha=0.6)
    axis.set_xscale("log")
    axis.set_xticks([100, 200, 500, 1000, 2000, 5000, 10000])
    axis.get_xaxis().set_major_formatter(plt.ScalarFormatter())
    axis.set_xlabel("Частота, Гц")
    axis.set_ylabel(ylabel)
    axis.set_title(title)
    axis.grid(True, which="both", alpha=0.25)
    if series:
        axis.legend(ncol=3, fontsize=8)
    fig.savefig(path, dpi=140)
    plt.close(fig)


def _max_keep_nan(current: float, value: float) -> float:
    # Встроенный max(x, nan) молча отбрасывает NaN, и критерий «проходил» бы без данных.
    if not np.isfinite(current) or not np.isfinite(value):
        return float("nan")
    return max(current, value)


def analyze_rendered_files(run_dir: str | Path, config: dict | None = None) -> dict:
    run_dir = Path(run_dir)
    config = config or load_config()
    key_path = run_dir / "key.json"
    if not key_path.exists():
        raise ValueError(f"Нет ключа рендера: {key_path}")
    with key_path.open("r", encoding="utf-8") as file:
        key = json.load(file)
    sample_rate = int(key["sample_rate"])
    centers = one_third_octave_centers(100.0, 12_000.0)
    fragments: dict[str, dict[str, dict]] = defaultdict(dict)
    grouped: dict[str, dict[str, np.ndarray]] = defaultdict(dict)
    for item in key["tracks"].values():
        if item.get("kind") != "music":
            continue
        audio, rate = _read(run_dir / "tracks" / item["filename"])
        grouped[item["fragment_id"]][item["variant"]] = audio
        if rate != sample_rate:
            raise ValueError(f"Частота файла {item['filename']} не совпадает с ключом")
    music_plot: dict[str, list[float]] = {}
    coherence_plot: dict[str, list[float]] = {}
    max_midband_shape_deviation = {variant: 0.0 for variant in VARIANT_CODES}
    max_welch_ripple = {variant: 0.0 for variant in VARIANT_CODES}
    all_lufs_levels: dict[str, list[float]] = defaultdict(list)
    for fragment_id, variants in sorted(grouped.items()):
        if "REF" not in variants:
            raise ValueError(f"В ключе {fragment_id} отсутствует REF")
        ref_f, ref_mono, _, _ = _spectra(variants["REF"], sample_rate)
        midband = (centers >= 200.0) & (centers <= 5000.0)
        fragment_result = {}
        for variant, audio in variants.items():
            freq, mono_power, coh_f, coherence = _spectra(audio, sample_rate)
            deviation = []
            for center in centers:
                ref_power = band_power(ref_f, ref_mono, float(center))
                current_power = band_power(freq, mono_power, float(center))
                deviation.append(float(10.0 * np.log10(max(current_power, 1e-30) / max(ref_power, 1e-30))))
            coh_bands = _band_values(coh_f, coherence, centers)
            shape_deviation = np.asarray(deviation, dtype=np.float64)
            shape_deviation[midband] -= float(np.mean(shape_deviation[midband]))
            highres_ripple = _welch_shape_ripple_pp(audio, variants["REF"], sample_rate)
            fragment_result[variant] = {
                "mono_sum_deviation_db": deviation,
                "mono_sum_shape_deviation_db": shape_deviation.tolist(),
                "interchannel_coherence": coh_bands,
                "max_midband_shape_deviation_db": float(np.max(np.abs(shape_deviation[midband]))),
                "high_res_mono_sum_ripple_pp_db": highres_ripple,
            }
            max_midband_shape_deviation[variant] = _max_keep_nan(
                max_midband_shape_deviation[variant], fragment_result[variant]["max_midband_shape_deviation_db"]
            )
            max_welch_ripple[variant] = _max_keep_nan(max_welch_ripple[variant], highres_ripple)
            source_item = next(x for x in key["tracks"].values() if x["fragment_id"] == fragment_id and x["variant"] == variant)
            all_lufs_levels[variant].append(float(source_item["loudness"]["lufs_after_limiter"]))
        fragments[fragment_id] = fragment_result
    if grouped:
        for variant in VARIANT_CODES:
            if any(variant in fragments[f] for f in fragments):
                shape_values = [fragments[f][variant]["mono_sum_shape_deviation_db"] for f in fragments if variant in fragments[f]]
                music_plot[variant] = list(np.median(np.asarray(shape_values), axis=0))
                coh_values = [fragments[f][variant]["interchannel_coherence"] for f in fragments if variant in fragments[f]]
                coherence_plot[variant] = list(np.median(np.asarray(coh_values), axis=0))

    synthetic_data = generate_synthetic(
        float(config["blind_test"]["synthetic_seconds"]), sample_rate, int(key.get("seed", config.get("random_seed", 0)))
    )
    synthetic_metrics = {}
    pink_plot: dict[str, list[float]] = {}
    click_plot: dict[str, float] = {}
    for signal_name in ("pink_center", "clicks_center", "pan_hard"):
        outputs = {}
        for variant in VARIANT_CODES:
            processed = process(synthetic_data[signal_name], _variant_params(config, variant), config, sample_rate)
            # Objective pink/noise and click probes are not listening tracks. Keep
            # their crest factor intact; run only the output ceiling, without LUFS
            # normalization that would turn sparse clicks into clipped bursts.
            outputs[variant], _ = limit(processed, sample_rate, config["limiter"])
        ref_f, ref_power, _, _ = _spectra(outputs["REF"], sample_rate)
        per_variant = {}
        for variant, audio in outputs.items():
            frequencies, mono_power, _, _ = _spectra(audio, sample_rate)
            deviations = []
            for center in centers:
                reference = band_power(ref_f, ref_power, float(center))
                current = band_power(frequencies, mono_power, float(center))
                deviations.append(float(10.0 * np.log10(max(current, 1e-30) / max(reference, 1e-30))))
            per_variant[variant] = {
                "mono_spectrum_deviation_db": deviations,
                "click_width_ms": _click_width_ms(audio, sample_rate) if signal_name == "clicks_center" else None,
            }
            if signal_name == "pan_hard":
                input_audio = synthetic_data[signal_name]
                input_rms = np.sqrt(np.mean(input_audio * input_audio, axis=0))
                output_rms = np.sqrt(np.mean(audio * audio, axis=0))
                _, pan_weight, _ = analyze_coherence(input_audio, sample_rate, config["stft"])
                frequencies = np.fft.rfftfreq(int(config["stft"]["n_fft"]), 1.0 / sample_rate)
                selected_bins = frequencies >= 300.0
                per_variant[variant]["channel_level_change_db"] = (
                    20.0 * np.log10(np.maximum(output_rms, 1e-15) / np.maximum(input_rms, 1e-15))
                ).tolist()
                per_variant[variant]["mean_diffuse_mask_above_300_hz"] = float(np.mean(pan_weight[selected_bins]))
            if signal_name == "pink_center":
                pink_plot[variant] = deviations
            elif signal_name == "clicks_center":
                click_plot[variant] = float(per_variant[variant]["click_width_ms"])
        synthetic_metrics[signal_name] = per_variant

    limiter_counts = {variant: 0 for variant in VARIANT_CODES}
    for item in key["tracks"].values():
        if item.get("kind") == "music":
            limiter_counts[item["variant"]] += int(item["loudness"].get("trigger_count", 0))
    metrics = {
        "PROTO_VERSION": PROTO_VERSION,
        "sample_rate": sample_rate,
        "one_third_octave_centers_hz": centers.tolist(),
        "fragments": dict(fragments),
        "synthetic": synthetic_metrics,
        "max_midband_mono_sum_shape_deviation_db": max_midband_shape_deviation,
        "max_midband_mono_sum_welch_ripple_pp_db": max_welch_ripple,
        "limiter_trigger_count": limiter_counts,
        "lufs_after_limiter": {variant: values for variant, values in all_lufs_levels.items()},
        "criterion_mono_sum_limit_db": 2.0,
        "criterion_high_res_welch_ripple_target_pp_db": 1.0,
    }
    with (run_dir / "metrics.json").open("w", encoding="utf-8") as file:
        json.dump(metrics, file, ensure_ascii=False, indent=2)
    _plot_lines(run_dir / "report_files_mono_sum.png", centers, music_plot, "АЧХ моно-суммы относительно REF", "Отклонение, дБ", zero=True)
    _plot_lines(run_dir / "report_files_coherence.png", centers, coherence_plot, "Межканальная когерентность", "Когерентность", zero=False)
    _plot_lines(run_dir / "report_files_pink_center.png", centers, pink_plot, "pink_center: моно-АЧХ относительно REF", "Отклонение, дБ", zero=True)
    fig, axis = plt.subplots(figsize=(8, 4), constrained_layout=True)
    axis.bar(list(click_plot), list(click_plot.values()))
    axis.set_ylabel("Медианная ширина на половине пика, мс")
    axis.set_title("clicks_center: ширина транзиента")
    axis.grid(True, axis="y", alpha=0.25)
    fig.savefig(run_dir / "report_files_clicks.png", dpi=140)
    plt.close(fig)

    rows = ["# Объективный отчёт по отрендеренным файлам", "", f"Версия прототипа: `{PROTO_VERSION}`. Частота: {sample_rate} Гц.", ""]
    rows.extend(["| Вариант | Худшее отклонение формы моно-суммы, 200 Гц–5 кГц | Welch ripple p-p, 300 Гц–5 кГц | Средняя когерентность | `pink_center`, худшее отклонение | Щелчок, мс | Срабатывания лимитера |", "|---|---:|---:|---:|---:|---:|---:|"])
    for variant in VARIANT_CODES:
        deviations = [abs(v) for fragment in fragments.values() for v, center in zip(fragment.get(variant, {}).get("mono_sum_shape_deviation_db", []), centers) if 200 <= center <= 5000]
        coherence_values = [v for fragment in fragments.values() for v in fragment.get(variant, {}).get("interchannel_coherence", [])]
        pink_values = [abs(v) for v, center in zip(pink_plot.get(variant, []), centers) if 100 <= center <= 12_000]
        click = click_plot.get(variant, float("nan"))
        rows.append(
            f"| {variant} | {max(deviations, default=float('nan')):.2f} dB | {max_welch_ripple[variant]:.2f} dB | {np.mean(coherence_values) if coherence_values else float('nan'):.3f} | "
            f"{max(pink_values, default=float('nan')):.2f} dB | {click:.2f} | {limiter_counts[variant]} |"
        )
    rows.extend(
        [
            "",
            "Критерий провала по файлам: после вычитания среднего отклонения формы больше 2 дБ хотя бы в одной 1/3-октавной полосе 200 Гц–5 кГц.",
            "Дополнительный ориентир: неравномерность высокого разрешения по Велчу (8192 точки, 300 Гц–5 кГц) — не более 1 дБ пик-пик.",
            "`pan_hard` проверяется отдельно в синтетических метриках; он не входит в музыкальный слепой тест.",
            "Ограничение ILD-защиты: она не распознаёт отдельные источники, если оба края одновременно заполнены похожим по спектру материалом. В слуховой тест включите хотя бы один джазовый трек конца 1950-х–1960-х годов с жёстким разведением инструментов.",
            "",
            "## Графики",
            "",
            "![Моно-сумма](report_files_mono_sum.png)",
            "",
            "![Когерентность](report_files_coherence.png)",
            "",
            "![Pink center](report_files_pink_center.png)",
            "",
            "![Щелчки](report_files_clicks.png)",
            "",
        ]
    )
    pan_hard = synthetic_metrics["pan_hard"]
    rows.extend(
        [
            "",
            "## Контроль `pan_hard`",
            "",
            "| Вариант | Изменение уровня L | Изменение уровня R | Средний вес diffuse выше 300 Гц |",
            "|---|---:|---:|---:|",
        ]
    )
    for variant in VARIANT_CODES:
        item = pan_hard[variant]
        levels = item["channel_level_change_db"]
        rows.append(
            f"| {variant} | {levels[0]:+.2f} dB | {levels[1]:+.2f} dB | {item['mean_diffuse_mask_above_300_hz']:.3f} |"
        )
    (run_dir / "report_files.md").write_text("\n".join(rows), encoding="utf-8")
    return metrics


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Метрики АЧХ, когерентности, центра и транзиентов по WAV-файлам")
    parser.add_argument("run_dir", type=Path, help="папка out/<дата> с key.json и tracks/")
    parser.add_argument("--config", type=Path)
    args = parser.parse_args(argv)
    try:
        metrics = analyze_rendered_files(args.run_dir, load_config(args.config))
        print(f"Отчёт: {args.run_dir / 'report_files.md'}; вариантов: {len(metrics['max_midband_mono_sum_shape_deviation_db'])}")
    except (OSError, ValueError, KeyError) as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
