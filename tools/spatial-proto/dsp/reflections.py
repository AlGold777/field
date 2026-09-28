from __future__ import annotations

import numpy as np
from scipy.signal import butter

from .common import db_to_gain, safe_sosfiltfilt


def apply_reflections(
    audio: np.ndarray,
    diffuse: np.ndarray,
    params: dict,
    sample_rate: int,
) -> np.ndarray:
    """Add short, asymmetric cross-channel taps sourced only from diffuse energy."""
    if not params.get("enabled", True):
        return audio.copy()
    low = float(params.get("low_hz", 500.0))
    high = float(params.get("high_hz", 6000.0))
    sos = butter(2, [low, high], btype="bandpass", fs=sample_rate, output="sos")
    filtered = safe_sosfiltfilt(sos, diffuse)
    result = audio.copy()
    for tap in params.get("taps", []):
        gain = db_to_gain(float(tap["gain_db"]))
        left_delay = max(1, int(round(float(tap["left_delay_ms"]) * sample_rate / 1000.0)))
        right_delay = max(1, int(round(float(tap["right_delay_ms"]) * sample_rate / 1000.0)))
        result[left_delay:, 0] += filtered[:-left_delay, 1] * gain
        result[right_delay:, 1] += filtered[:-right_delay, 0] * gain
    return result
