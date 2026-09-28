#!/usr/bin/env python3
"""Create a noisy room recording with known jitter and recorder-clock drift."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import butter, fftconvolve, sosfilt

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from signals import _sync_chirp


def _room_response(sample_rate: int, rt60_seconds: float, rng: np.random.Generator) -> np.ndarray:
    count = max(2, int(round(rt60_seconds * sample_rate)))
    time = np.arange(count) / sample_rate
    response = rng.normal(size=count) * (0.06 * 10.0 ** (-3.0 * time / rt60_seconds))
    response[0] += 1.0
    for delay_ms, gain in ((3.7, 0.24), (9.1, 0.15), (18.6, 0.10), (34.0, 0.06)):
        index = int(round(delay_ms * sample_rate / 1000.0))
        if index < count:
            response[index] += gain
    return response


def _event_response(template: np.ndarray, sample_rate: int, channel: int, rt60: float, rng: np.random.Generator) -> np.ndarray:
    if channel == 0:
        sections = butter(2, 5200.0, btype="lowpass", fs=sample_rate, output="sos")
    else:
        sections = butter(2, 900.0, btype="highpass", fs=sample_rate, output="sos")
    speaker = sosfilt(sections, template)
    speaker *= np.sqrt(np.mean(template * template)) / max(float(np.sqrt(np.mean(speaker * speaker))), 1e-12)
    return fftconvolve(speaker, _room_response(sample_rate, rt60, rng))


def simulate(
    output_path: str | Path,
    jitter_us: float,
    duration_seconds: float = 60.0,
    sample_rate: int = 48_000,
    seed: int = 7,
    recorder_drift_ppm: float = 0.0,
    room_rt60_seconds: float = 0.4,
) -> Path:
    rng = np.random.default_rng(seed)
    template = _sync_chirp(sample_rate, 0.020)
    interval = 1.0
    lr_offset = 0.1
    drift_scale = 1.0 + recorder_drift_ppm * 1e-6
    pair_count = int(duration_seconds / interval)
    jitter = rng.normal(size=pair_count)
    if pair_count:
        jitter -= np.mean(jitter)
        jitter *= jitter_us / max(float(np.std(jitter)), 1e-12)

    # Apply distinct speaker frequency responses and static room paths for L/R.
    responses = [
        _event_response(template, sample_rate, channel, room_rt60_seconds, rng)
        for channel in (0, 1)
    ]
    start_offset = int(round(0.5 * sample_rate))
    final_event = start_offset + int(np.ceil(duration_seconds * sample_rate * drift_scale))
    count = final_event + int(round((room_rt60_seconds + 0.5) * sample_rate))
    chirp_rms = float(np.sqrt(np.mean(template * template)))
    audio = rng.normal(0.0, chirp_rms * 0.01, count).astype(np.float64)  # -40 dB re chirp

    def add_event(position: float, response: np.ndarray) -> None:
        start = int(np.floor(position))
        fraction = position - start
        shifted = np.interp(
            np.arange(len(response) + 1, dtype=np.float64) - fraction,
            np.arange(len(response), dtype=np.float64),
            response,
            left=0.0,
            right=0.0,
        )
        end = min(count, start + len(shifted))
        if start < count:
            audio[start:end] += shifted[: end - start]

    for pair in range(pair_count):
        left = start_offset + pair * interval * sample_rate * drift_scale
        right = left + (lr_offset + jitter[pair] / 1_000_000.0) * sample_rate * drift_scale
        add_event(left, responses[0])
        add_event(right, responses[1])

    target = Path(output_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(target), audio, sample_rate, subtype="PCM_24", format="WAV")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--jitter-us", type=float, choices=(0.0, 20.0, 100.0), required=True)
    parser.add_argument("--drift-ppm", type=float, choices=(-20.0, 20.0), default=20.0)
    parser.add_argument("--duration", type=float, default=60.0)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    simulate(args.output, args.jitter_us, args.duration, seed=args.seed, recorder_drift_ppm=args.drift_ppm)
    print(
        f"Created {args.output}: target σ {args.jitter_us:g} µs, "
        f"recorder drift {args.drift_ppm:+g} ppm, {args.duration:g} seconds"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
