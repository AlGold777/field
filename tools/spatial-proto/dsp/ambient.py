from __future__ import annotations

import numpy as np
from scipy.signal import butter, freqz, sosfreqz

from .common import db_to_gain, istft, stft


def _high_shelf_magnitude(frequencies: np.ndarray, sample_rate: int, corner_hz: float, gain_db: float) -> np.ndarray:
    """Return an RBJ high-shelf magnitude response for spectral weighting."""
    amplitude = 10.0 ** (gain_db / 40.0)
    omega = 2.0 * np.pi * corner_hz / sample_rate
    cosine, sine = np.cos(omega), np.sin(omega)
    slope = 1.0
    alpha = sine / 2.0 * np.sqrt((amplitude + 1.0 / amplitude) * (1.0 / slope - 1.0) + 2.0)
    beta = 2.0 * np.sqrt(amplitude) * alpha
    b0 = amplitude * ((amplitude + 1) + (amplitude - 1) * cosine + beta)
    b1 = -2 * amplitude * ((amplitude - 1) + (amplitude + 1) * cosine)
    b2 = amplitude * ((amplitude + 1) + (amplitude - 1) * cosine - beta)
    a0 = (amplitude + 1) - (amplitude - 1) * cosine + beta
    a1 = 2 * ((amplitude - 1) - (amplitude + 1) * cosine)
    a2 = (amplitude + 1) - (amplitude - 1) * cosine - beta
    _, response = freqz([b0 / a0, b1 / a0, b2 / a0], [1.0, a1 / a0, a2 / a0], worN=frequencies, fs=sample_rate)
    return np.abs(response)


def _diffuse_side_gain(frequencies: np.ndarray, sample_rate: int, low_hz: float, gain_db: float) -> np.ndarray:
    """Apply the prior LR4 side lift as a zero-phase frequency-domain weight."""
    sections = butter(2, low_hz, btype="lowpass", fs=sample_rate, output="sos")
    _, response = sosfreqz(sections, worN=frequencies, fs=sample_rate)
    low_magnitude = np.abs(response) ** 2  # forward/reverse Butterworth-2 = LR4 magnitude
    high_weight = np.clip(1.0 - low_magnitude, 0.0, 1.0)
    return 1.0 + (db_to_gain(gain_db) - 1.0) * high_weight


def apply_w3(
    audio: np.ndarray,
    coherence_weight: np.ndarray,
    params: dict,
    sample_rate: int,
    n_fft: int = 2048,
    hop: int = 512,
) -> tuple[np.ndarray, np.ndarray]:
    """Mix aligned direct and diffuse STFT paths, with phase-only decorrelation."""
    source_spectrum = stft(audio, n_fft, hop)
    frequencies = np.fft.rfftfreq(n_fft, 1.0 / sample_rate)
    mask = np.clip(coherence_weight, 0.0, 1.0)[:, :, None]
    diffuse = source_spectrum * mask
    direct = source_spectrum - diffuse

    # D4 side gain is applied to diffuse S in the same STFT. The complementary
    # LR4-shaped weight is real and therefore cannot add channel-dependent delay.
    mid = (diffuse[:, :, 0] + diffuse[:, :, 1]) * 0.5
    side = (diffuse[:, :, 0] - diffuse[:, :, 1]) * 0.5
    side_gain = _diffuse_side_gain(
        frequencies,
        sample_rate,
        float(params.get("decorrelation_highpass_hz", 300.0)),
        float(params.get("side_gain_db", 3.0)),
    )
    side *= side_gain[:, None]
    diffuse[:, :, 0] = mid + side
    diffuse[:, :, 1] = mid - side

    # D3 is a spectral shelf, not a separately filtered time-domain path.
    if params.get("d3_enabled", True):
        shelf = _high_shelf_magnitude(
            frequencies,
            sample_rate,
            float(params.get("d3_corner_hz", 8000.0)),
            float(params.get("d3_gain_db", -2.0)),
        )
        diffuse *= shelf[:, None, None]
    diffuse *= db_to_gain(float(params.get("level_db", -2.0)))

    # Smoothly changing phase by log-frequency decorrelates the diffuse field
    # without introducing the independent allpass delays that caused combing.
    theta = np.zeros_like(frequencies)
    valid = frequencies >= float(params.get("decorrelation_highpass_hz", 300.0))
    cycles_per_octave = float(params.get("decorrelation_cycles_per_octave", 1.5))
    if np.any(valid):
        octaves = np.log2(frequencies[valid] / float(params.get("decorrelation_highpass_hz", 300.0)))
        theta[valid] = np.deg2rad(float(params.get("theta_max_deg", 45.0))) * np.sin(
            2.0 * np.pi * cycles_per_octave * octaves
        )
    theta[0] = 0.0
    if n_fft % 2 == 0:
        theta[-1] = 0.0
    diffuse[:, :, 0] *= np.exp(1j * theta[:, None])
    diffuse[:, :, 1] *= np.exp(-1j * theta[:, None])

    combined = istft(direct + diffuse, len(audio), n_fft, hop)
    diffuse_audio = istft(diffuse, len(audio), n_fft, hop)
    return combined, diffuse_audio
