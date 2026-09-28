from __future__ import annotations

import numpy as np

from .common import db_to_gain, istft, split_lr4_bands, stft


def apply_w2(
    audio: np.ndarray,
    params: dict,
    coherence_weight: np.ndarray,
    sample_rate: int,
    n_fft: int = 2048,
    hop: int = 512,
) -> np.ndarray:
    """Apply frequency-dependent M/S expansion, preserving M and the protected bass."""
    mid = (audio[:, 0] + audio[:, 1]) * 0.5
    side = (audio[:, 0] - audio[:, 1]) * 0.5
    low_hz = float(params.get("low_hz", 300.0))
    high_hz = float(params.get("high_hz", 5000.0))
    low, middle, high = split_lr4_bands(side[:, None], low_hz, high_hz, sample_rate)
    frequencies = np.fft.rfftfreq(n_fft, 1.0 / sample_rate)

    def scaled_band(band: np.ndarray, db: float, selected: np.ndarray) -> np.ndarray:
        band_spec = stft(band, n_fft, hop)
        weight = coherence_weight.copy()
        weight[~selected] = 0.0
        gain = 1.0 + (db_to_gain(db) - 1.0) * weight
        return istft(band_spec * gain[:, :, None], len(audio), n_fft, hop)[:, 0]

    mid_selected = (frequencies >= low_hz) & (frequencies < high_hz)
    high_selected = frequencies >= high_hz
    side_out = low[:, 0] + scaled_band(middle, float(params.get("mid_db", 0.0)), mid_selected)
    side_out += scaled_band(high, float(params.get("high_db", 0.0)), high_selected)
    return np.column_stack((mid + side_out, mid - side_out))
