#!/usr/bin/env python3
"""Create a noisy acoustic-style sync recording with known R-channel jitter."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from signals import _sync_chirp


def simulate(output_path: str | Path, jitter_us: float, duration_seconds: float = 60.0, sample_rate: int = 48_000, seed: int = 7) -> Path:
    rng = np.random.default_rng(seed)
    start_offset = int(round(0.5 * sample_rate))
    count = int(round(duration_seconds * sample_rate)) + start_offset + sample_rate
    audio = rng.normal(0.0, 0.002, count).astype(np.float64)
    template = _sync_chirp(sample_rate, 0.020)
    pair_count = int(duration_seconds)
    jitter = rng.normal(size=pair_count)
    if pair_count:
        jitter -= np.mean(jitter)
        deviation = float(np.std(jitter))
        jitter = jitter * (jitter_us / max(deviation, 1e-12))

    def add_event(position: float) -> None:
        start = int(np.floor(position))
        fraction = position - start
        indices = np.arange(len(template) + 1, dtype=np.float64) - fraction
        shifted = np.interp(indices, np.arange(len(template)), template, left=0.0, right=0.0)
        audio[start : start + len(shifted)] += shifted

    for pair in range(pair_count):
        left = start_offset + pair * sample_rate
        right = left + 0.1 * sample_rate + jitter[pair] * sample_rate / 1_000_000.0
        add_event(left)
        add_event(right)
    target = Path(output_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(target), audio, sample_rate, subtype="PCM_24", format="WAV")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--jitter-us", type=float, choices=(0.0, 20.0, 100.0), required=True)
    parser.add_argument("--duration", type=float, default=60.0)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    simulate(args.output, args.jitter_us, args.duration, seed=args.seed)
    print(f"Created {args.output}: target σ {args.jitter_us:g} µs, {args.duration:g} seconds")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
