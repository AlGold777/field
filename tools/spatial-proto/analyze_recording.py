#!/usr/bin/env python3
"""Analyze phone recordings of the measurement playlist or the sync probe."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import soundfile as sf
from scipy.signal import correlate, find_peaks, welch

from dsp.common import band_power, one_third_octave_centers
from render import PROTO_VERSION, load_config
from signals import _sync_chirp


def _fractional_peak(values: np.ndarray, index: int) -> float:
    if index <= 0 or index >= len(values) - 1:
        return float(index)
    left, center, right = abs(values[index - 1]), abs(values[index]), abs(values[index + 1])
    denominator = left - 2.0 * center + right
    if abs(denominator) < 1e-15:
        return float(index)
    offset = 0.5 * (left - right) / denominator
    return float(index + np.clip(offset, -1.0, 1.0))


def _matched_start(audio: np.ndarray, template: np.ndarray, expected: float, radius: int, minimum_strength: float = 0.0) -> tuple[float, float] | None:
    expected_int = int(round(expected))
    start = max(0, expected_int - radius)
    end = min(len(audio), expected_int + radius + len(template))
    if end - start < len(template):
        return None
    local = correlate(audio[start:end], template, mode="valid", method="fft")
    index = int(np.argmax(np.abs(local)))
    strength = float(abs(local[index]))
    if strength < minimum_strength:
        return None
    return start + _fractional_peak(local, index), strength


def _find_first_pair(audio: np.ndarray, template: np.ndarray, sample_rate: int) -> tuple[float, float, float, float]:
    # The user may start the recorder several seconds before pressing Play.
    scan = audio[: min(len(audio), sample_rate * 30)]
    corr = correlate(scan, template, mode="valid", method="fft")
    magnitude = np.abs(corr)
    if not len(magnitude) or float(np.max(magnitude)) <= 1e-12:
        raise ValueError("Чирпы не найдены в начале записи")
    peaks, _ = find_peaks(magnitude, distance=int(0.045 * sample_rate), prominence=float(np.max(magnitude)) * 0.015)
    if not len(peaks):
        peaks = np.array([int(np.argmax(magnitude))])
    candidates: list[tuple[float, int, int]] = []
    min_offset, max_offset = 0.065 * sample_rate, 0.14 * sample_rate
    for left_pos, peak in enumerate(peaks):
        deltas = peaks[left_pos + 1 :] - peak
        matches = np.flatnonzero((deltas >= min_offset) & (deltas <= max_offset))
        for match in matches:
            right_peak = int(peaks[left_pos + 1 + match])
            score = float(magnitude[peak] + magnitude[right_peak])
            # The L event repeats close to one second after this pair.
            later = peaks[(peaks > right_peak + int(0.6 * sample_rate)) & (peaks < right_peak + int(1.5 * sample_rate))]
            if len(later):
                score += 0.5 * float(magnitude[later[0]])
            candidates.append((score, int(peak), right_peak))
    if not candidates:
        raise ValueError("Не удалось найти пару L→R с интервалом около 100 мс")
    strongest = max(float(magnitude[left]) for _, left, _ in candidates)
    plausible = [item for item in candidates if magnitude[item[1]] >= strongest * 0.15 and magnitude[item[2]] >= strongest * 0.15]
    _, left_index, right_index = min(plausible, key=lambda item: item[1])
    return _fractional_peak(corr, left_index), _fractional_peak(corr, right_index), float(magnitude[left_index]), float(magnitude[right_index])


def analyze_sync(recording_path: str | Path, output_dir: str | Path | None = None, config: dict | None = None) -> dict:
    config = config or load_config()
    recording, sample_rate = sf.read(str(recording_path), dtype="float32", always_2d=True)
    if not len(recording):
        raise ValueError("Запись пустая")
    # Use the least noisy microphone channel instead of summing stereo mics, which
    # can partially cancel a high-frequency chirp through their own phase offset.
    mono = recording[:, int(np.argmax(np.std(recording, axis=0)))]
    probe = config["blind_test"]
    template = _sync_chirp(sample_rate, float(probe["sync_chirp_ms"]) / 1000.0)
    left0, right0, left_strength, right_strength = _find_first_pair(mono, template, sample_rate)
    lr_offset = float(probe["sync_lr_offset_ms"]) / 1000.0
    nominal_interval = float(probe["sync_pair_interval_seconds"])
    radius = int(round(0.04 * sample_rate))
    estimated_ppm = 0.0
    left_positions = [left0]
    right_positions = [right0]
    l_indices = [0]
    r_indices = [0]
    minimum_strength = 0.16 * min(left_strength, right_strength)
    max_pairs = int(np.ceil(len(mono) / (sample_rate * nominal_interval))) + 1

    for pair in range(1, min(max_pairs, 16)):
        expected = left0 + pair * nominal_interval * sample_rate
        found = _matched_start(mono, template, expected, radius, minimum_strength)
        if found is None:
            break
        found_position, _ = found
        left_positions.append(found_position)
        l_indices.append(pair)
        right_expected = found_position + lr_offset * sample_rate
        right_found = _matched_start(mono, template, right_expected, radius, minimum_strength)
        if right_found is not None:
            right_positions.append(right_found[0])
            r_indices.append(pair)
    if len(left_positions) < 4:
        raise ValueError(f"Найдено только {len(left_positions)} чирпов L; нужна более длинная или чище записанная проба")
    slope, _ = np.polyfit(np.asarray(l_indices, dtype=float), np.asarray(left_positions), 1)
    estimated_ppm = (slope / (sample_rate * nominal_interval) - 1.0) * 1_000_000.0

    last_left = left_positions[-1]
    for pair in range(len(left_positions), max_pairs):
        scale = 1.0 + estimated_ppm * 1e-6
        expected = left0 + pair * nominal_interval * sample_rate * scale
        found = _matched_start(mono, template, expected, radius, minimum_strength)
        if found is None:
            if expected >= len(mono) - len(template):
                break
            continue
        found_position, _ = found
        left_positions.append(found_position)
        l_indices.append(pair)
        expected_right = found_position + lr_offset * sample_rate * scale
        right_found = _matched_start(mono, template, expected_right, radius, minimum_strength)
        if right_found is not None:
            right_positions.append(right_found[0])
            r_indices.append(pair)
        if len(left_positions) % 30 == 0:
            fit_count = min(120, len(left_positions))
            fit_slope, _ = np.polyfit(np.asarray(l_indices[-fit_count:], dtype=float), np.asarray(left_positions[-fit_count:]), 1)
            estimated_ppm = (fit_slope / (sample_rate * nominal_interval) - 1.0) * 1_000_000.0

    right_by_index = dict(zip(r_indices, right_positions))
    matched = [(index, left, right_by_index[index]) for index, left in zip(l_indices, left_positions) if index in right_by_index]
    if len(matched) < 3:
        raise ValueError(f"Найдено только {len(matched)} полных пар L/R")
    pair_indices = np.asarray([item[0] for item in matched], dtype=float)
    left_samples = np.asarray([item[1] for item in matched])
    right_samples = np.asarray([item[2] for item in matched])
    # Final robust clock estimate from the long L→L sequence.
    clock_slope, clock_intercept = np.polyfit(pair_indices, left_samples, 1)
    estimated_ppm = (clock_slope / (sample_rate * nominal_interval) - 1.0) * 1_000_000.0
    scale = 1.0 + estimated_ppm * 1e-6
    intervals = (right_samples - left_samples) / sample_rate / scale
    median = float(np.median(intervals))
    spread_us = (intervals - median) * 1_000_000.0
    result = {
        "PROTO_VERSION": PROTO_VERSION,
        "recording": str(recording_path),
        "sample_rate_hz": sample_rate,
        "pair_count": int(len(intervals)),
        "estimated_recorder_clock_ppm": float(estimated_ppm),
        "median_lr_interval_ms_including_geometry": median * 1000.0,
        "median_lr_offset_from_nominal_ms": (median - lr_offset) * 1000.0,
        "jitter_sigma_us": float(np.std(spread_us)),
        "jitter_p95_abs_us": float(np.percentile(np.abs(spread_us), 95)),
        "jitter_max_abs_us": float(np.max(np.abs(spread_us))),
        "times_seconds": (pair_indices * nominal_interval).tolist(),
        "spread_us": spread_us.tolist(),
        "goal_p95_us": 50.0,
    }
    output = Path(output_dir) if output_dir else Path(recording_path).resolve().parent
    output.mkdir(parents=True, exist_ok=True)
    with (output / "report_sync.json").open("w", encoding="utf-8") as file:
        json.dump(result, file, ensure_ascii=False, indent=2)
    fig, axis = plt.subplots(figsize=(10, 4.5), constrained_layout=True)
    axis.plot(result["times_seconds"], spread_us, linewidth=0.8)
    axis.axhline(0, color="black", linewidth=0.8)
    axis.axhline(50, color="red", linestyle="--", linewidth=0.8)
    axis.axhline(-50, color="red", linestyle="--", linewidth=0.8)
    axis.set_xlabel("Время записи, с")
    axis.set_ylabel("Интервал L→R относительно медианы, мкс")
    axis.set_title("Разброс акустической синхронизации (геометрический сдвиг вычтен медианой)")
    axis.grid(True, alpha=0.25)
    fig.savefig(output / "report_sync.png", dpi=140)
    plt.close(fig)
    (output / "report_sync.md").write_text(
        "\n".join(
            [
                "# Синхронизация L/R по акустической записи",
                "",
                f"Записано пар чирпов: {len(intervals)}. Оценка часов телефона записи: {estimated_ppm:+.1f} ppm.",
                f"Медианный интервал L→R: {median * 1000:.4f} мс. Он включает постоянную геометрию расстановки.",
                f"Разброс относительно медианы: σ {result['jitter_sigma_us']:.2f} мкс; p95 {result['jitter_p95_abs_us']:.2f} мкс; max {result['jitter_max_abs_us']:.2f} мкс.",
                "Цель по стабильности: p95 ≤ 50 мкс. Геометрическая разница исключена из разброса вычитанием медианы.",
                "",
                "![Разброс во времени](report_sync.png)",
                "",
            ]
        ),
        encoding="utf-8",
    )
    return result


def _active_regions(audio: np.ndarray, sample_rate: int) -> list[tuple[int, int]]:
    block = max(1, int(round(0.05 * sample_rate)))
    usable = len(audio) // block * block
    if not usable:
        return []
    frames = audio[:usable].reshape(-1, block, audio.shape[1])
    level = np.sqrt(np.mean(frames * frames, axis=(1, 2)))
    baseline = float(np.percentile(level, 15))
    threshold = max(baseline * 3.5, float(np.max(level)) * 0.004, 1e-5)
    active = level > threshold
    # Ignore short dips inside a music segment and short isolated noises in a pause.
    gap_blocks = max(1, int(round(0.7 / (block / sample_rate))))
    regions = []
    indices = np.flatnonzero(active)
    if not len(indices):
        return []
    start = previous = int(indices[0])
    for index in indices[1:]:
        index = int(index)
        if index - previous > gap_blocks:
            if previous - start >= int(1.0 * sample_rate / block):
                regions.append((start * block, min(usable, (previous + 1) * block)))
            start = index
        previous = index
    if previous - start >= int(1.0 * sample_rate / block):
        regions.append((start * block, min(usable, (previous + 1) * block)))
    return regions


def _recording_band_power(audio: np.ndarray, sample_rate: int, centers: np.ndarray):
    channels = audio.shape[1]
    if channels == 1:
        mono = audio[:, 0]
        frequencies, power = welch(mono, sample_rate, nperseg=min(32768, len(mono)))
        return frequencies, power
    mono_power = (audio[:, 0] + audio[:, 1]) * 0.5
    frequencies, power = welch(mono_power, sample_rate, nperseg=min(32768, len(mono_power)))
    f, left = welch(audio[:, 0], sample_rate, nperseg=min(32768, len(audio)))
    _, right = welch(audio[:, 1], sample_rate, nperseg=min(32768, len(audio)))
    # Welch of a complex channel mixture is not a valid cross-spectrum; estimate coherence directly.
    from scipy.signal import csd

    _, cross = csd(audio[:, 0], audio[:, 1], sample_rate, nperseg=min(32768, len(audio)))
    coherence = np.clip(np.abs(cross) / np.sqrt(left * right + 1e-30), 0.0, 1.0)
    return frequencies, power, (f, coherence)


def analyze_measurement(recording_path: str | Path, measure_key_path: str | Path, output_dir: str | Path | None = None) -> dict:
    audio, sample_rate = sf.read(str(recording_path), dtype="float32", always_2d=True)
    with Path(measure_key_path).open("r", encoding="utf-8") as file:
        playlist = json.load(file)
    if int(playlist["sample_rate"]) != sample_rate:
        raise ValueError(f"Частота записи {sample_rate} Гц, плейлиста {playlist['sample_rate']} Гц")
    regions = _active_regions(audio, sample_rate)
    tracks = playlist["tracks"]
    if not regions:
        raise ValueError("Не найдены активные сегменты; проверьте запись и уровень фона")
    if len(regions) != len(tracks):
        print(f"Предупреждение: найдено активных отрезков {len(regions)}, в ключе {len(tracks)}; будут сопоставлены первые общие.", file=sys.stderr)
    matched_count = min(len(regions), len(tracks))
    centers = one_third_octave_centers(100.0, 12_000.0)
    recorded: dict[str, dict[str, dict]] = {"pink_center": {}, "pink_stereo": {}}
    for index in range(matched_count):
        track = tracks[index]
        start, end = regions[index]
        margin = int(round(0.2 * sample_rate))
        section = audio[min(end, start + margin) : max(start, end - margin)]
        if len(section) < sample_rate:
            continue
        result = _recording_band_power(section, sample_rate, centers)
        frequencies, power = result[0], result[1]
        bands = [band_power(frequencies, power, float(center)) for center in centers]
        coherence_values = None
        if len(section.shape) == 2 and section.shape[1] == 2:
            f, coh = result[2]
            coherence_values = [band_power(f, coh, float(center)) for center in centers]
        recorded[track["signal"]][track["variant"]] = {
            "blind_code": track["blind_code"],
            "start_seconds": start / sample_rate,
            "end_seconds": end / sample_rate,
            "spectrum_power": bands,
            "intermicrophone_coherence": coherence_values,
        }
    deviations: dict[str, dict[str, list[float]]] = {}
    for signal_name, variants in recorded.items():
        if "REF" not in variants:
            continue
        reference = np.asarray(variants["REF"]["spectrum_power"])
        deviations[signal_name] = {}
        for variant, item in variants.items():
            power = np.asarray(item["spectrum_power"])
            deviations[signal_name][variant] = (10.0 * np.log10(np.maximum(power, 1e-30) / np.maximum(reference, 1e-30))).tolist()
    result = {
        "PROTO_VERSION": PROTO_VERSION,
        "recording": str(recording_path),
        "sample_rate": sample_rate,
        "stereo_recording": audio.shape[1] == 2,
        "detected_regions": len(regions),
        "matched_segments": matched_count,
        "one_third_octave_centers_hz": centers.tolist(),
        "segments": recorded,
        "response_deviation_db_vs_ref": deviations,
        "coherence_note": "Межмикрофонная когерентность — грубый ориентир ширины, не измерительный микрофон." if audio.shape[1] == 2 else None,
    }
    output = Path(output_dir) if output_dir else Path(recording_path).resolve().parent
    output.mkdir(parents=True, exist_ok=True)
    with (output / "report_recording.json").open("w", encoding="utf-8") as file:
        json.dump(result, file, ensure_ascii=False, indent=2)
    fig, axis = plt.subplots(figsize=(9, 4.5), constrained_layout=True)
    for signal_name, variants in deviations.items():
        for variant, values in variants.items():
            axis.plot(centers, values, marker="o", markersize=3, label=f"{signal_name} · {variant}")
    axis.axhline(0, color="black", linewidth=0.8)
    axis.set_xscale("log")
    axis.set_xticks([100, 200, 500, 1000, 2000, 5000, 10000])
    axis.get_xaxis().set_major_formatter(plt.ScalarFormatter())
    axis.set_xlabel("Частота, Гц")
    axis.set_ylabel("Отклонение относительно REF, дБ")
    axis.set_title("АЧХ по записи в точке прослушивания")
    axis.grid(True, which="both", alpha=0.25)
    axis.legend(ncol=3, fontsize=7)
    fig.savefig(output / "report_recording.png", dpi=140)
    plt.close(fig)
    lines = [
        "# Отчёт по записи в точке прослушивания",
        "",
        f"Стереозапись: {'да' if audio.shape[1] == 2 else 'нет'}. Найдено сегментов: {len(regions)} из {len(tracks)}.",
        "АЧХ показывает изменение каждого варианта относительно записанного REF, поэтому учитывает реальные динамики и комнату.",
    ]
    if audio.shape[1] == 2:
        lines.append("Межмикрофонная когерентность приведена как грубый ориентир ширины; телефонный микрофон не является измерительным.")
        lines.extend(["", "## Межмикрофонная когерентность (ориентировочно)", "", "| Сигнал / вариант | Средняя когерентность 100 Гц–12 кГц |", "|---|---:|"])
        for signal_name, variants in recorded.items():
            for variant, item in variants.items():
                values = item.get("intermicrophone_coherence")
                if values:
                    in_range = [value for center, value in zip(centers, values) if 100 <= center <= 12_000 and np.isfinite(value)]
                    if in_range:
                        lines.append(f"| {signal_name} / {variant} | {np.mean(in_range):.3f} |")
    lines.extend(["", "![АЧХ записи](report_recording.png)", ""])
    (output / "report_recording.md").write_text("\n".join(lines), encoding="utf-8")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Метрики записи телефона и стабильности синхронизации L/R")
    parser.add_argument("recording", type=Path, help="WAV-запись с телефона")
    parser.add_argument("--sync", action="store_true", help="анализировать десятиминутную chirp-пробу")
    parser.add_argument("--measure-key", type=Path, help="measure/key.json для pink_center/pink_stereo плейлиста")
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--config", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.sync:
            result = analyze_sync(args.recording, args.out_dir, load_config(args.config))
            print(
                f"Пары: {result['pair_count']}; часы: {result['estimated_recorder_clock_ppm']:+.1f} ppm; "
                f"σ {result['jitter_sigma_us']:.2f} мкс, p95 {result['jitter_p95_abs_us']:.2f} мкс"
            )
        elif args.measure_key:
            result = analyze_measurement(args.recording, args.measure_key, args.out_dir)
            print(f"Найдено сегментов: {result['detected_regions']}; отчёт: {(args.out_dir or args.recording.parent) / 'report_recording.md'}")
        else:
            parser.error("Укажите --sync или --measure-key")
    except (OSError, ValueError, KeyError) as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
