from __future__ import annotations

from collections import deque

import numpy as np
from scipy.signal import resample_poly

from .common import gain_to_db


def true_peak(audio: np.ndarray, oversample: int = 4) -> float:
    if not len(audio):
        return 0.0
    if oversample > 1:
        measured = resample_poly(audio, oversample, 1, axis=0)
    else:
        measured = audio
    return float(np.max(np.abs(measured)))


def _forward_max(values: np.ndarray, lookahead: int) -> np.ndarray:
    result = np.empty_like(values)
    queue: deque[int] = deque()
    end = min(len(values), lookahead + 1)
    for index in range(end):
        while queue and values[queue[-1]] <= values[index]:
            queue.pop()
        queue.append(index)
    for index in range(len(values)):
        while queue and queue[0] < index:
            queue.popleft()
        result[index] = values[queue[0]] if queue else 0.0
        next_index = index + lookahead + 1
        if next_index < len(values):
            while queue and values[queue[-1]] <= values[next_index]:
                queue.pop()
            queue.append(next_index)
    return result


def limit(
    audio: np.ndarray,
    sample_rate: int,
    settings: dict,
) -> tuple[np.ndarray, dict]:
    """Look-ahead peak limiter with 4x oversampled peak detection."""
    audio = np.asarray(audio, dtype=np.float64)
    oversample = max(1, int(settings.get("oversample", 4)))
    ceiling = 10.0 ** (float(settings.get("ceiling_dbtp", -1.0)) / 20.0)
    before = true_peak(audio, oversample)
    if before <= ceiling or not len(audio):
        return audio.copy(), {
            "triggered_samples": 0,
            "trigger_count": 0,
            "peak_before_dbtp": gain_to_db(before),
            "peak_after_dbtp": gain_to_db(before),
            "max_reduction_db": 0.0,
            "delay_samples": 0,
        }

    over = np.abs(resample_poly(audio, oversample, 1, axis=0))
    samples = np.max(over, axis=1)
    if len(samples) % oversample:
        samples = np.pad(samples, (0, oversample - len(samples) % oversample), mode="edge")
    sample_peaks = samples.reshape(-1, oversample).max(axis=1)[: len(audio)]
    lookahead = max(1, int(round(float(settings.get("lookahead_ms", 5.0)) * sample_rate / 1000.0)))
    future_peak = _forward_max(sample_peaks, lookahead)
    required = np.minimum(1.0, ceiling / np.maximum(future_peak, 1e-15))
    release_seconds = max(0.001, float(settings.get("release_ms", 80.0)) / 1000.0)
    release = np.exp(-1.0 / (release_seconds * sample_rate))
    envelope = np.ones(len(audio), dtype=np.float64)
    previous = 1.0
    for index, target in enumerate(required):
        if target < previous:
            previous = target
        else:
            previous = 1.0 - (1.0 - previous) * release
            previous = min(previous, target)
        envelope[index] = previous
    result = audio * envelope[:, None]
    after = true_peak(result, oversample)
    # Compensate for the oversampling filter's boundary response and numerical error.
    if after > ceiling:
        result *= ceiling / after
        after = true_peak(result, oversample)
    active = envelope < 0.999999
    trigger_count = int(np.count_nonzero(active & ~np.r_[False, active[:-1]]))
    return result, {
        "triggered_samples": int(np.count_nonzero(active)),
        "trigger_count": trigger_count,
        "peak_before_dbtp": gain_to_db(before),
        "peak_after_dbtp": gain_to_db(after),
        "max_reduction_db": -gain_to_db(float(np.min(envelope))),
        "delay_samples": 0,
    }
