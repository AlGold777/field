from __future__ import annotations

from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import butter, resample_poly, sosfilt, sosfiltfilt


def read_stereo(path: str | Path, sample_rate: int = 48_000) -> np.ndarray:
    audio, source_rate = sf.read(str(path), dtype="float64", always_2d=True)
    if audio.shape[1] != 2:
        raise ValueError(f"Ожидается стереофайл (2 канала): {path}; каналов: {audio.shape[1]}")
    if source_rate != sample_rate:
        divisor = np.gcd(source_rate, sample_rate)
        audio = resample_poly(audio, sample_rate // divisor, source_rate // divisor, axis=0)
    if not np.all(np.isfinite(audio)):
        raise ValueError(f"В файле есть NaN или бесконечные значения: {path}")
    return np.asarray(audio, dtype=np.float64)


def write_pcm24(path: str | Path, audio: np.ndarray, sample_rate: int = 48_000) -> None:
    sf.write(str(path), np.asarray(audio, dtype=np.float64), sample_rate, subtype="PCM_24", format="WAV")


def sqrt_hann(n_fft: int) -> np.ndarray:
    return np.sqrt(np.hanning(n_fft + 1)[:-1])


def stft(audio: np.ndarray, n_fft: int = 2048, hop: int = 512) -> np.ndarray:
    """Centered sqrt-Hann STFT, returned as (frequency, frame, channel)."""
    audio = np.asarray(audio, dtype=np.float64)
    if audio.ndim == 1:
        audio = audio[:, None]
    pad = n_fft // 2
    frame_count = int(np.ceil(len(audio) / hop)) + 1
    padded = np.pad(audio, ((pad, pad + hop), (0, 0)))
    frames = np.lib.stride_tricks.sliding_window_view(padded, n_fft, axis=0)[::hop][:frame_count]
    # sliding_window_view puts the channel axis before the window axis.
    frames = frames.transpose(0, 2, 1) * sqrt_hann(n_fft)[None, :, None]
    return np.fft.rfft(frames, axis=1).transpose(1, 0, 2)


def istft(spectrum: np.ndarray, length: int, n_fft: int = 2048, hop: int = 512) -> np.ndarray:
    """Inverse of stft(), with overlap-add normalization and original length."""
    spectrum = np.asarray(spectrum)
    frames = np.fft.irfft(spectrum.transpose(1, 0, 2), n=n_fft, axis=1)
    window = sqrt_hann(n_fft)
    frame_count, _, channels = frames.shape
    output = np.zeros((max(0, (frame_count - 1) * hop + n_fft), channels), dtype=np.float64)
    norm = np.zeros(len(output), dtype=np.float64)
    weighted = frames * window[None, :, None]
    for index in range(frame_count):
        start = index * hop
        output[start : start + n_fft] += weighted[index]
        norm[start : start + n_fft] += window * window
    valid = norm > 1e-12
    output[valid] /= norm[valid, None]
    start = n_fft // 2
    result = output[start : start + length]
    if len(result) < length:
        result = np.pad(result, ((0, length - len(result)), (0, 0)))
    return result


def db_to_gain(db: float) -> float:
    return 10.0 ** (float(db) / 20.0)


def gain_to_db(gain: float, floor: float = 1e-15) -> float:
    return 20.0 * np.log10(max(float(gain), floor))


def safe_sosfiltfilt(sos: np.ndarray, audio: np.ndarray) -> np.ndarray:
    padlen = 3 * (2 * len(sos) + 1)
    if len(audio) <= padlen:
        return sosfilt(sos, audio, axis=0)
    return sosfiltfilt(sos, audio, axis=0)


def lowpass_lr4(audio: np.ndarray, cutoff_hz: float, sample_rate: int) -> np.ndarray:
    """Four-pole Linkwitz-Riley magnitude, formed from a forward/reverse Butterworth-2."""
    sos = butter(2, cutoff_hz, btype="lowpass", fs=sample_rate, output="sos")
    return safe_sosfiltfilt(sos, audio)


def split_lr4_bands(audio: np.ndarray, low_hz: float, high_hz: float, sample_rate: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Complementary low/mid/high bands. Their samplewise sum equals the input."""
    low = lowpass_lr4(audio, low_hz, sample_rate)
    above_low = audio - low
    mid = lowpass_lr4(above_low, high_hz, sample_rate)
    high = above_low - mid
    return low, mid, high


def one_third_octave_centers(low_hz: float = 100.0, high_hz: float = 12_000.0) -> np.ndarray:
    centers = []
    step = 2.0 ** (1.0 / 3.0)
    exponent = int(np.floor(3.0 * np.log2(low_hz / 1000.0)))
    center = 1000.0 * 2.0 ** (exponent / 3.0)
    # Keep the nearest standard 1/3-octave center at the lower edge (99.2 Hz ≈ 100 Hz).
    while center < low_hz * 0.99:
        exponent += 1
        center = 1000.0 * 2.0 ** (exponent / 3.0)
    while center <= high_hz:
        centers.append(center)
        center *= step
    return np.asarray(centers)


def band_power(frequencies: np.ndarray, power: np.ndarray, center_hz: float) -> float:
    ratio = 2.0 ** (1.0 / 6.0)
    mask = (frequencies >= center_hz / ratio) & (frequencies < center_hz * ratio)
    if not np.any(mask):
        return float("nan")
    return float(np.mean(power[mask]))
