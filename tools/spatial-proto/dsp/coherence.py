from __future__ import annotations

import numpy as np
from scipy.ndimage import uniform_filter1d

from .common import stft


def analyze_coherence(
    audio: np.ndarray,
    sample_rate: int,
    settings: dict,
    n_fft: int = 2048,
    hop: int = 512,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Return smoothed coherence, expansion weight, and transient flux per STFT frame."""
    spectrum = stft(audio, n_fft, hop)
    left, right = spectrum[:, :, 0], spectrum[:, :, 1]
    frames = spectrum.shape[1]
    alpha = np.exp(-(hop / sample_rate) / float(settings.get("coherence_time_seconds", 0.08)))
    p_left = np.abs(left) ** 2
    p_right = np.abs(right) ** 2
    p_cross = left * np.conj(right)
    smooth_left = np.empty_like(p_left)
    smooth_right = np.empty_like(p_right)
    smooth_cross = np.empty_like(p_cross)
    smooth_left[:, 0] = p_left[:, 0]
    smooth_right[:, 0] = p_right[:, 0]
    smooth_cross[:, 0] = p_cross[:, 0]
    for frame in range(1, frames):
        smooth_left[:, frame] = alpha * smooth_left[:, frame - 1] + (1.0 - alpha) * p_left[:, frame]
        smooth_right[:, frame] = alpha * smooth_right[:, frame - 1] + (1.0 - alpha) * p_right[:, frame]
        smooth_cross[:, frame] = alpha * smooth_cross[:, frame - 1] + (1.0 - alpha) * p_cross[:, frame]

    raw = np.abs(smooth_cross) / np.sqrt(smooth_left * smooth_right + 1e-30)
    frequencies = np.fft.rfftfreq(n_fft, 1.0 / sample_rate)
    smoothed = np.empty_like(raw)
    ild_db = np.empty_like(smooth_left)
    fraction = float(settings.get("coherence_band_fraction_octave", 1.0 / 6.0))
    half_ratio = 2.0 ** (fraction / 2.0)
    for index, frequency in enumerate(frequencies):
        if frequency <= 0.0:
            smoothed[index] = raw[index]
            ild_db[index] = 10.0 * np.log10((smooth_left[index] + 1e-30) / (smooth_right[index] + 1e-30))
            continue
        low, high = frequency / half_ratio, frequency * half_ratio
        first = max(1, int(np.searchsorted(frequencies, low, side="left")))
        last = min(len(frequencies), int(np.searchsorted(frequencies, high, side="right")))
        cross = np.mean(smooth_cross[first:last], axis=0) if last > first else smooth_cross[index]
        left_power = np.mean(smooth_left[first:last], axis=0) if last > first else smooth_left[index]
        right_power = np.mean(smooth_right[first:last], axis=0) if last > first else smooth_right[index]
        smoothed[index] = np.abs(cross) / np.sqrt(left_power * right_power + 1e-30)
        ild_db[index] = 10.0 * np.log10((left_power + 1e-30) / (right_power + 1e-30))
    smoothed = np.clip(smoothed, 0.0, 1.0)

    magnitude = np.mean(np.abs(spectrum), axis=2)
    positive_flux = np.maximum(0.0, magnitude[:, 1:] - magnitude[:, :-1])
    flux = np.zeros(frames, dtype=np.float64)
    if frames > 1:
        flux[1:] = np.sum(positive_flux, axis=0) / (np.sum(magnitude[:, :-1], axis=0) + 1e-12)
    threshold = float(settings.get("transient_flux_threshold", 0.25))
    median = float(np.median(flux))
    mad = float(np.median(np.abs(flux - median)))
    threshold = max(threshold, median + 4.0 * mad)
    attacks = flux > threshold
    suppression = float(settings.get("transient_suppression", 0.3))
    recovery = float(settings.get("transient_recovery_seconds", 0.03))
    decay = np.exp(-(hop / sample_rate) / recovery)
    transient_gain = np.ones(frames, dtype=np.float64)
    active_gain = 1.0
    for frame in range(frames):
        if attacks[frame]:
            active_gain = suppression
        else:
            active_gain = 1.0 - (1.0 - active_gain) * decay
        transient_gain[frame] = active_gain

    floor = float(settings.get("coherence_floor", 0.3))
    weight = np.clip((1.0 - smoothed - floor) / (1.0 - floor), 0.0, 1.0)
    direct_ild_db = float(settings.get("ild_direct_db", 12.0))
    ild_ramp_db = max(float(settings.get("ild_ramp_db", 6.0)), 1e-6)
    ild_guard = np.clip((direct_ild_db - np.abs(ild_db)) / ild_ramp_db, 0.0, 1.0)
    weight *= ild_guard
    frequencies_below = frequencies < float(settings.get("low_frequency_guard_hz", 300.0))
    weight[frequencies_below] = 0.0
    weight *= transient_gain[None, :]
    # Keep the ambient mask at least 50 ms smooth as required by the prototype spec.
    smoothing_frames = max(1, int(np.ceil(0.05 * sample_rate / hop)))
    if smoothing_frames > 1:
        weight = uniform_filter1d(weight, size=smoothing_frames, axis=1, mode="nearest")
    return smoothed, weight, flux
