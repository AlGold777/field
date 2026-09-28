from __future__ import annotations

import numpy as np
from scipy.signal import butter, lfilter

from .common import db_to_gain, lowpass_lr4, safe_sosfiltfilt, stft, istft


def _allpass_chain(signal: np.ndarray, sections: list[dict]) -> np.ndarray:
    result = np.asarray(signal, dtype=np.float64)
    for section in sections:
        delay = max(1, int(section["delay_samples"]))
        coefficient = float(section["coefficient"])
        numerator = np.zeros(delay + 1, dtype=np.float64)
        denominator = np.zeros(delay + 1, dtype=np.float64)
        numerator[0], numerator[-1] = coefficient, 1.0
        denominator[0], denominator[-1] = 1.0, coefficient
        result = lfilter(numerator, denominator, result)
    return result


def _high_shelf(audio: np.ndarray, sample_rate: int, frequency: float, gain_db: float) -> np.ndarray:
    """RBJ high-shelf biquad with a gentle, approximately 1-octave transition."""
    amplitude = 10.0 ** (gain_db / 40.0)
    omega = 2.0 * np.pi * frequency / sample_rate
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
    sos = np.array([[b0 / a0, b1 / a0, b2 / a0, 1.0, a1 / a0, a2 / a0]])
    return safe_sosfiltfilt(sos, audio)


def apply_w3(
    audio: np.ndarray,
    coherence_weight: np.ndarray,
    params: dict,
    sample_rate: int,
    n_fft: int = 2048,
    hop: int = 512,
) -> tuple[np.ndarray, np.ndarray]:
    """Separate direct and diffuse energy, decorrelate only the diffuse part."""
    source_spectrum = stft(audio, n_fft, hop)
    mask = coherence_weight[:, :, None]
    diffuse = istft(source_spectrum * mask, len(audio), n_fft, hop)
    direct = istft(source_spectrum * (1.0 - mask), len(audio), n_fft, hop)

    diffuse[:, 0] = _allpass_chain(diffuse[:, 0], params.get("allpass_left", []))
    diffuse[:, 1] = _allpass_chain(diffuse[:, 1], params.get("allpass_right", []))

    mid = (diffuse[:, 0] + diffuse[:, 1]) * 0.5
    side = (diffuse[:, 0] - diffuse[:, 1]) * 0.5
    low_hz = float(params.get("decorrelation_highpass_hz", 300.0))
    side_low = lowpass_lr4(side[:, None], low_hz, sample_rate)[:, 0]
    side_high = side - side_low
    side = side_low + side_high * db_to_gain(float(params.get("side_gain_db", 3.0)))
    diffuse = np.column_stack((mid + side, mid - side))

    if params.get("d3_enabled", True):
        diffuse = _high_shelf(
            diffuse,
            sample_rate,
            float(params.get("d3_corner_hz", 8000.0)),
            float(params.get("d3_gain_db", -2.0)),
        )
    diffuse *= db_to_gain(float(params.get("level_db", -2.0)))
    return direct + diffuse, diffuse
