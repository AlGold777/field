#!/usr/bin/env python3
"""Render a blinded offline SoundField spatial-processing listening test."""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pyloudnorm as pyln
import soundfile as sf
import yaml

from dsp.ambient import apply_w3
from dsp.coherence import analyze_coherence
from dsp.common import db_to_gain, read_stereo, write_pcm24
from dsp.limiter import limit, true_peak
from dsp.msband import apply_w2
from dsp.reflections import apply_reflections
from dsp.xtc import apply_xtc
from signals import generate_synthetic, write_sync_probe


PROTO_VERSION = "0.1.1"
VARIANT_CODES = ("REF", "ANCHOR_MONO", "ANCHOR_OVER", "W2_LO", "W2_HI", "W2_W3", "W2_W3_W6")
SUPPORTED_EXTENSIONS = {".wav", ".flac"}


def load_config(path: str | Path | None = None) -> dict:
    config_path = Path(path) if path else Path(__file__).with_name("config.yaml")
    with config_path.open("r", encoding="utf-8") as file:
        config = yaml.safe_load(file)
    if int(config.get("sample_rate", 0)) != 48_000:
        raise ValueError("Эта версия прототипа работает с частотой 48 кГц")
    return config


def process(stereo: np.ndarray, variant_params: dict, settings: dict | None = None, sample_rate: int = 48_000) -> np.ndarray:
    """Platform-independent process(stereo, variant_params) implementation."""
    config = settings or load_config()
    audio = np.asarray(stereo, dtype=np.float64)
    if audio.ndim != 2 or audio.shape[1] != 2:
        raise ValueError("process() принимает стереомассив формы (samples, 2)")
    kind = variant_params.get("kind", variant_params.get("code", "reference"))
    if kind == "reference" or kind == "REF":
        return audio.copy()
    if kind == "mono" or kind == "ANCHOR_MONO":
        mono = (audio[:, 0] + audio[:, 1]) * 0.5
        return np.column_stack((mono, mono))
    if kind == "over" or kind == "ANCHOR_OVER":
        mid = (audio[:, 0] + audio[:, 1]) * 0.5
        side = (audio[:, 0] - audio[:, 1]) * 0.5 * db_to_gain(float(config["w2"].get("anchor_over_side_db", 8.0)))
        return np.column_stack((mid + side, mid - side))

    n_fft = int(config["stft"]["n_fft"])
    hop = int(config["stft"]["hop"])
    _, weight, _ = analyze_coherence(audio, sample_rate, config["stft"], n_fft, hop)
    profile_name = str(variant_params.get("profile", "lo"))
    profile = "lo" if profile_name in ("lo", "W2_LO") else "hi"
    w2_settings = config["w2"]
    w2_params = {
        "low_hz": w2_settings["low_hz"],
        "high_hz": w2_settings["high_hz"],
        "mid_db": w2_settings[f"{profile}_mid_db"],
        "high_db": w2_settings[f"{profile}_high_db"],
    }
    w2_audio = apply_w2(audio, w2_params, weight, sample_rate, n_fft, hop)
    if kind == "w2" or kind in ("W2_LO", "W2_HI"):
        return w2_audio
    if kind in ("w2_w3", "w2_w3_w6", "W2_W3", "W2_W3_W6"):
        ambient, diffuse = apply_w3(w2_audio, weight, config["ambient"], sample_rate, n_fft, hop)
        if kind in ("w2_w3_w6", "W2_W3_W6"):
            return apply_reflections(ambient, diffuse, config["reflections"], sample_rate)
        return ambient
    raise ValueError(f"Неизвестный тип варианта: {kind}")


def _meter_lufs(audio: np.ndarray, sample_rate: int) -> float:
    loudness = float(pyln.Meter(sample_rate).integrated_loudness(audio))
    if not np.isfinite(loudness):
        raise ValueError("Не удалось измерить LUFS: файл слишком тихий или содержит тишину")
    return loudness


def _loudness_match_and_limit(audio: np.ndarray, config: dict) -> tuple[np.ndarray, dict]:
    sample_rate = int(config["sample_rate"])
    target = float(config["blind_test"]["target_lufs"])
    ceiling = float(config["limiter"]["ceiling_dbtp"])
    source_lufs = _meter_lufs(audio, sample_rate)
    normalized = audio * db_to_gain(target - source_lufs)
    limited, limiter_info = limit(normalized, sample_rate, config["limiter"])
    measured = _meter_lufs(limited, sample_rate)
    correction_db = target - measured
    corrected = limited * db_to_gain(correction_db)
    peak_dbtp = 20.0 * np.log10(max(true_peak(corrected, int(config["limiter"].get("oversample", 4))), 1e-15))
    if peak_dbtp > ceiling + 0.02:
        raise ValueError(
            f"Выравнивание до {target:.1f} LUFS поднимет true peak до {peak_dbtp:.2f} dBTP "
            f"(выше потолка {ceiling:.1f} dBTP); рендер остановлен"
        )
    final_lufs = _meter_lufs(corrected, sample_rate)
    if abs(final_lufs - target) > float(config["blind_test"]["allowed_lufs_delta"]):
        raise ValueError(f"После лимитера громкость {final_lufs:.2f} LUFS, цель {target:.1f} ±0,3 LU")
    limiter_info.update(
        {
            "lufs_before_normalization": source_lufs,
            "lufs_after_limiter": final_lufs,
            "normalization_db": target - source_lufs,
            "post_limiter_trim_db": correction_db,
            "peak_final_dbtp": peak_dbtp,
        }
    )
    return corrected, limiter_info


def _select_fragment(audio: np.ndarray, sample_rate: int, duration_seconds: float, selection_settings: dict) -> tuple[np.ndarray, float]:
    wanted_samples = int(round(duration_seconds * sample_rate))
    if len(audio) < wanted_samples:
        raise ValueError(f"Трек короче требуемого фрагмента {duration_seconds:.1f} с")
    short_seconds = float(selection_settings.get("short_term_seconds", 3.0))
    step_seconds = float(selection_settings.get("step_seconds", 0.5))
    window = int(round(short_seconds * sample_rate))
    step = max(1, int(round(step_seconds * sample_rate)))
    short_lufs = []
    starts = list(range(0, len(audio) - window + 1, step))
    meter = pyln.Meter(sample_rate)
    for start in starts:
        segment = audio[start : start + window]
        try:
            short_lufs.append(float(meter.integrated_loudness(segment)))
        except (ValueError, ZeroDivisionError):
            short_lufs.append(float("-inf"))
    short_lufs = np.asarray(short_lufs)
    segment_windows = max(1, int(np.floor(max(0.0, duration_seconds - short_seconds) / step_seconds)) + 1)
    if len(short_lufs) < segment_windows:
        best_index = 0
    else:
        valid = np.where(np.isfinite(short_lufs), short_lufs, -120.0)
        rolling = np.convolve(valid, np.ones(segment_windows) / segment_windows, mode="valid")
        best_index = int(np.argmax(rolling))
    sample_start = min(best_index * step, len(audio) - wanted_samples)
    return audio[sample_start : sample_start + wanted_samples].copy(), sample_start / sample_rate


def _fragment_for_file(path: Path, config: dict) -> tuple[np.ndarray, float]:
    sample_rate = int(config["sample_rate"])
    audio = read_stereo(path, sample_rate)
    seconds = float(config["fragment_seconds"])
    explicit = config.get("fragment_starts", {})
    start_value = explicit.get(path.name, explicit.get(path.stem))
    if start_value is None:
        return _select_fragment(audio, sample_rate, seconds, config["automatic_selection"])
    start = float(start_value)
    start_sample = int(round(start * sample_rate))
    length = int(round(seconds * sample_rate))
    if start_sample < 0 or start_sample + length > len(audio):
        raise ValueError(f"Время начала {start:g} с для {path.name} не оставляет фрагмент длиной {seconds:g} с")
    return audio[start_sample : start_sample + length].copy(), start


def _input_files(input_dir: str | Path) -> list[Path]:
    directory = Path(input_dir).expanduser()
    if not directory.is_dir():
        raise ValueError(f"Не найдена папка с исходниками: {directory}")
    files = sorted(path for path in directory.iterdir() if path.is_file() and path.suffix.lower() in SUPPORTED_EXTENSIONS)
    if not 3 <= len(files) <= 5:
        raise ValueError(f"В папке ожидается 3–5 стереофайлов WAV/FLAC, найдено: {len(files)}")
    return files


def _new_blind_code(rng: np.random.Generator, used: set[str]) -> str:
    alphabet = np.array(list("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
    while True:
        code = "".join(rng.choice(alphabet, size=3).tolist())
        if code not in used:
            used.add(code)
            return code


def _variant_params(config: dict, variant: str) -> dict:
    params = dict(config["variants"][variant])
    params["code"] = variant
    if "profile" in params:
        params["profile_name"] = params["profile"]
    return params


def _score_template(path: Path, assignments: list[dict], xtc: bool = False) -> None:
    columns = ["fragment", "code", "width", "depth", "center", "coloration", "naturalness", "preference", "shifted_center", "comment"]
    if xtc:
        columns = ["fragment", "code", "distance_m", "width", "depth", "center", "coloration", "naturalness", "preference", "shifted_center", "outside_phone", "comment"]
    with path.open("w", newline="", encoding="utf-8-sig") as file:
        writer = csv.DictWriter(file, fieldnames=columns)
        writer.writeheader()
        for item in assignments:
            row = {"fragment": item["fragment_id"], "code": item["blind_code"]}
            if xtc:
                row["distance_m"] = item.get("distance_m")
            writer.writerow(row)


def _listener_order(key: dict, listener: str, seed: int) -> list[dict]:
    rng = np.random.default_rng(seed ^ int.from_bytes(listener.encode("utf-8"), "little", signed=False) % (2**32))
    items_by_fragment: dict[str, list[dict]] = {}
    for item in key["tracks"].values():
        if item["kind"] != "music":
            continue
        items_by_fragment.setdefault(item["fragment_id"], []).append(item)
    result = []
    key.setdefault("listener_orders", {})[listener] = {}
    for fragment in sorted(items_by_fragment):
        codes = sorted(item["blind_code"] for item in items_by_fragment[fragment])
        rng.shuffle(codes)
        key["listener_orders"][listener][fragment] = codes
        result.append({"fragment": fragment, "codes": codes})
    return result


def render_music(input_dir: str | Path, output_dir: Path, config: dict, seed: int, listeners: list[str] | None) -> dict:
    files = _input_files(input_dir)
    sample_rate = int(config["sample_rate"])
    rng = np.random.default_rng(seed)
    tracks_dir = output_dir / "tracks"
    tracks_dir.mkdir(parents=True, exist_ok=True)
    key = {
        "PROTO_VERSION": PROTO_VERSION,
        "date": date.today().isoformat(),
        "seed": seed,
        "sample_rate": sample_rate,
        "fragment_seconds": float(config["fragment_seconds"]),
        "target_lufs": float(config["blind_test"]["target_lufs"]),
        "source_files": [],
        "config": config,
        "variants": config["variants"],
        "tracks": {},
    }
    used: set[str] = set()
    all_scores = []
    for fragment_index, source_path in enumerate(files, start=1):
        fragment, start_seconds = _fragment_for_file(source_path, config)
        fragment_id = f"J{fragment_index:02d}"
        key["source_files"].append(
            {"fragment_id": fragment_id, "file": source_path.name, "start_seconds": start_seconds, "duration_seconds": float(config["fragment_seconds"])}
        )
        for variant in VARIANT_CODES:
            params = _variant_params(config, variant)
            processed = process(fragment, params, config, sample_rate)
            rendered, level = _loudness_match_and_limit(processed, config)
            blind_code = _new_blind_code(rng, used)
            filename = f"{fragment_id}-{blind_code}.wav"
            write_pcm24(tracks_dir / filename, rendered, sample_rate)
            item = {
                "kind": "music",
                "fragment_id": fragment_id,
                "blind_code": blind_code,
                "filename": filename,
                "variant": variant,
                "processing": params,
                "loudness": level,
            }
            key["tracks"][blind_code] = item
            all_scores.append(item)
        print(f"{fragment_id}: {source_path.name} → {start_seconds:.1f}–{start_seconds + float(config['fragment_seconds']):.1f} с")
    for listener in listeners or []:
        order = _listener_order(key, listener, seed)
        safe_listener = re.sub(r"[^A-Za-zА-Яа-я0-9_-]+", "_", listener).strip("_") or "listener"
        _score_template(output_dir / f"scores_{safe_listener}.csv", all_scores)
        print(f"Порядок для слушателя «{listener}»:")
        for entry in order:
            print(f"  {entry['fragment']}: " + " → ".join(entry["codes"]))
    return key


def render_measurement(output_dir: Path, config: dict, seed: int) -> dict:
    sample_rate = int(config["sample_rate"])
    seconds = float(config["blind_test"]["synthetic_seconds"])
    rng = np.random.default_rng(seed)
    synthetics = generate_synthetic(seconds, sample_rate, seed)
    sequence = [(signal_name, variant) for signal_name in ("pink_center", "pink_stereo", "pan_hard") for variant in VARIANT_CODES]
    rng.shuffle(sequence)
    measure_dir = output_dir / "measure"
    measure_dir.mkdir(parents=True, exist_ok=True)
    pause = np.zeros((int(round(float(config["blind_test"]["measurement_pause_seconds"]) * sample_rate)), 2), dtype=np.float32)
    playlist = {
        "PROTO_VERSION": PROTO_VERSION,
        "date": date.today().isoformat(),
        "sample_rate": sample_rate,
        "signal_seconds": seconds,
        "pause_seconds": len(pause) / sample_rate,
        "tracks": [],
    }
    cursor = 0
    with sf.SoundFile(str(measure_dir / "measure.wav"), "w", samplerate=sample_rate, channels=2, subtype="PCM_24", format="WAV") as output:
        output.write(pause)
        cursor += len(pause)
        for signal_name, variant in sequence:
            rendered = process(synthetics[signal_name], _variant_params(config, variant), config, sample_rate)
            rendered, level = _loudness_match_and_limit(rendered, config)
            start = cursor
            output.write(rendered.astype(np.float32))
            cursor += len(rendered)
            blind_code = _new_blind_code(rng, set(item["blind_code"] for item in playlist["tracks"]))
            playlist["tracks"].append(
                {
                    "blind_code": blind_code,
                    "signal": signal_name,
                    "variant": variant,
                    "start_sample": start,
                    "end_sample": cursor,
                    "start_seconds": start / sample_rate,
                    "end_seconds": cursor / sample_rate,
                    "loudness": level,
                }
            )
            output.write(pause)
            cursor += len(pause)
    with (measure_dir / "key.json").open("w", encoding="utf-8") as file:
        json.dump(playlist, file, ensure_ascii=False, indent=2)
    print(f"Замерный плейлист: {measure_dir / 'measure.wav'} ({len(sequence)} сегментов)")
    return playlist


def render_sync(output_dir: Path, config: dict) -> Path:
    path = output_dir / "sync_probe.wav"
    settings = config["blind_test"]
    write_sync_probe(
        path,
        duration_seconds=float(settings["sync_probe_seconds"]),
        sample_rate=int(config["sample_rate"]),
        chirp_ms=float(settings["sync_chirp_ms"]),
        pair_interval_seconds=float(settings["sync_pair_interval_seconds"]),
        lr_offset_ms=float(settings["sync_lr_offset_ms"]),
    )
    with (output_dir / "sync_probe.json").open("w", encoding="utf-8") as file:
        json.dump(
            {
                "PROTO_VERSION": PROTO_VERSION,
                "date": date.today().isoformat(),
                "sample_rate": int(config["sample_rate"]),
                "duration_seconds": float(settings["sync_probe_seconds"]),
                "chirp_ms": float(settings["sync_chirp_ms"]),
                "pair_interval_seconds": float(settings["sync_pair_interval_seconds"]),
                "lr_offset_ms": float(settings["sync_lr_offset_ms"]),
                "channels": {"L": "chirp at pair start", "R": "chirp at pair start + offset"},
            },
            file,
            ensure_ascii=False,
            indent=2,
        )
    return path


def render_xtc(input_dir: str | Path, output_dir: Path, config: dict, seed: int) -> dict:
    files = _input_files(input_dir)
    sample_rate = int(config["sample_rate"])
    xtc_settings = config["xtc"]
    regularizations = xtc_settings["regularization"]
    output_root = output_dir / "xtc"
    tracks_dir = output_root / "tracks"
    tracks_dir.mkdir(parents=True, exist_ok=True)
    synthetics = generate_synthetic(float(config["blind_test"]["synthetic_seconds"]), sample_rate, seed)
    items: list[tuple[str, np.ndarray]] = []
    for index, path in enumerate(files, start=1):
        fragment, start = _fragment_for_file(path, config)
        items.append((f"J{index:02d}", fragment))
    items.extend((("pink_stereo", synthetics["pink_stereo"]), ("channel_id", synthetics["channel_id"])))
    rng = np.random.default_rng(seed ^ 0x585443)
    used: set[str] = set()
    key = {"PROTO_VERSION": PROTO_VERSION, "date": date.today().isoformat(), "seed": seed, "sample_rate": sample_rate, "config": xtc_settings, "tracks": {}}
    for fragment_id, source in items:
        for distance in xtc_settings["listener_distances_m"]:
            variants = [("REF", None)] + [(f"XTC_{level}", value) for level, value in regularizations.items()]
            for variant, regularization in variants:
                if variant == "REF":
                    processed = source.copy()
                else:
                    processed, xtc_info = apply_xtc(source, sample_rate, xtc_settings, float(distance), float(regularization))
                rendered, loudness = _loudness_match_and_limit(processed, config)
                blind_code = _new_blind_code(rng, used)
                filename = f"{fragment_id}-{blind_code}.wav"
                write_pcm24(tracks_dir / filename, rendered, sample_rate)
                key["tracks"][blind_code] = {
                    "kind": "xtc",
                    "fragment_id": fragment_id,
                    "blind_code": blind_code,
                    "filename": filename,
                    "variant": variant,
                    "distance_m": float(distance),
                    "regularization": regularization,
                    "processing": xtc_info if variant != "REF" else {"kind": "reference"},
                    "loudness": loudness,
                }
    with (output_root / "key.json").open("w", encoding="utf-8") as file:
        json.dump(key, file, ensure_ascii=False, indent=2)
    _score_template(output_root / "scores_xtc.csv", list(key["tracks"].values()), xtc=True)
    print(f"XTC-файлы: {tracks_dir}; закрытый ключ: {output_root / 'key.json'}; шаблон оценок: {output_root / 'scores_xtc.csv'}")
    return key


def _output_folder(root: Path, run_date: str | None) -> Path:
    name = run_date or date.today().isoformat()
    return root.expanduser() / name


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Офлайн-рендер слепого теста ширины и глубины SoundField")
    parser.add_argument("input_dir", nargs="?", help="папка с 3–5 стереотреками WAV/FLAC")
    parser.add_argument("--config", type=Path, help="путь к config.yaml")
    parser.add_argument("--out-root", type=Path, default=Path(__file__).with_name("out"), help="корневая папка результата")
    parser.add_argument("--date", help="имя папки запуска, по умолчанию текущая дата")
    parser.add_argument("--seed", type=int, help="seed для кодов и перемешивания")
    parser.add_argument("--listener", action="append", help="создать индивидуальный порядок и CSV-шаблон оценок; можно повторять")
    parser.add_argument("--measure", action="store_true", help="рендерить плейлист pink_center/pink_stereo/pan_hard для записи телефоном")
    parser.add_argument("--sync-probe", action="store_true", help="рендерить десятиминутный sync_probe.wav")
    parser.add_argument("--xtc", action="store_true", help="рендерить отдельную слепую ветку XTC на одном телефоне")
    args = parser.parse_args(argv)
    try:
        config = load_config(args.config)
        seed = int(args.seed if args.seed is not None else config.get("random_seed", 0))
        output_dir = _output_folder(args.out_root, args.date)
        output_dir.mkdir(parents=True, exist_ok=True)
        if args.input_dir:
            if args.xtc:
                render_xtc(args.input_dir, output_dir, config, seed)
            else:
                key = render_music(args.input_dir, output_dir, config, seed, args.listener)
                key["date"] = args.date or date.today().isoformat()
                key_path = output_dir / "key.json"
                with key_path.open("w", encoding="utf-8") as file:
                    json.dump(key, file, ensure_ascii=False, indent=2)
                print(f"Кодовый ключ (не показывать слушателям): {key_path}")
                from analyze_files import analyze_rendered_files

                analyze_rendered_files(output_dir, config)
        elif not args.measure and not args.sync_probe:
            parser.error("Нужна папка с джазовыми треками или флаг --measure/--sync-probe")
        if args.measure:
            render_measurement(output_dir, config, seed)
        if args.sync_probe:
            print(f"Сигнал синхронизации: {render_sync(output_dir, config)}")
        if args.xtc and not args.input_dir:
            parser.error("Для --xtc нужна папка с 3–5 джазовыми треками")
    except (OSError, ValueError, KeyError) as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
