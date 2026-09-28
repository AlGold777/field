from __future__ import annotations

from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import chirp
from scipy.signal.windows import tukey


def _pink_noise(seconds: float, sample_rate: int, rng: np.random.Generator) -> np.ndarray:
    count = max(1, int(round(seconds * sample_rate)))
    frequencies = np.fft.rfftfreq(count, 1.0 / sample_rate)
    spectrum = rng.normal(size=len(frequencies)) + 1j * rng.normal(size=len(frequencies))
    spectrum[0] = 0.0
    spectrum /= np.sqrt(np.maximum(frequencies, sample_rate / count))
    if count % 2 == 0:
        spectrum[-1] = spectrum[-1].real
    noise = np.fft.irfft(spectrum, n=count)
    noise /= max(float(np.sqrt(np.mean(noise * noise))), 1e-12)
    return noise * 0.15


def generate_synthetic(seconds: float = 20.0, sample_rate: int = 48_000, seed: int = 0) -> dict[str, np.ndarray]:
    rng = np.random.default_rng(seed)
    center = _pink_noise(seconds, sample_rate, rng)
    pink_center = np.column_stack((center, center))
    pink_stereo = np.column_stack((_pink_noise(seconds, sample_rate, rng), _pink_noise(seconds, sample_rate, rng)))
    click_count = int(round(seconds * 2.0))
    clicks = np.zeros(int(round(seconds * sample_rate)), dtype=np.float64)
    burst_samples = max(16, int(round(0.003 * sample_rate)))
    t = np.arange(burst_samples) / sample_rate
    burst = np.sin(2.0 * np.pi * 3200.0 * t) * np.exp(-t * 1600.0)
    for index in range(click_count):
        start = int(round(index * 0.5 * sample_rate))
        end = min(start + burst_samples, len(clicks))
        clicks[start:end] += burst[: end - start] * (1.0 if index % 2 == 0 else -1.0)
    peak = max(float(np.max(np.abs(clicks))), 1e-12)
    clicks *= 0.45 / peak
    return {
        "pink_center": pink_center,
        "pink_stereo": pink_stereo,
        "clicks_center": np.column_stack((clicks, clicks)),
        "channel_id": generate_channel_id(sample_rate),
    }


def generate_channel_id(sample_rate: int = 48_000) -> np.ndarray:
    seconds = 4.0
    count = int(round(seconds * sample_rate))
    result = np.zeros((count, 2), dtype=np.float64)
    fade_samples = int(round(0.05 * sample_rate))
    for channel, frequency, start_seconds in ((0, 500.0, 0.35), (1, 1000.0, 2.15)):
        start = int(round(start_seconds * sample_rate))
        length = int(round(1.0 * sample_rate))
        phase = np.arange(length) * (2.0 * np.pi * frequency / sample_rate)
        envelope = np.ones(length)
        envelope[:fade_samples] = np.linspace(0.0, 1.0, fade_samples, endpoint=False)
        envelope[-fade_samples:] = np.linspace(1.0, 0.0, fade_samples, endpoint=False)
        result[start : start + length, channel] = 0.2 * np.sin(phase) * envelope
    return result


def _sync_chirp(sample_rate: int, duration_seconds: float) -> np.ndarray:
    count = int(round(duration_seconds * sample_rate))
    time = np.arange(count) / sample_rate
    tone = chirp(time, f0=1000.0, f1=6000.0, t1=duration_seconds, method="linear")
    return tone * tukey(count, alpha=0.25) * 0.3


def write_sync_probe(
    path: str | Path,
    duration_seconds: float = 600.0,
    sample_rate: int = 48_000,
    chirp_ms: float = 20.0,
    pair_interval_seconds: float = 1.0,
    lr_offset_ms: float = 100.0,
) -> None:
    """Stream the ten-minute L/R chirp probe without allocating the whole file."""
    chirp_signal = _sync_chirp(sample_rate, chirp_ms / 1000.0)
    total_samples = int(round(duration_seconds * sample_rate))
    interval = int(round(pair_interval_seconds * sample_rate))
    offset = int(round(lr_offset_ms * sample_rate / 1000.0))
    chunk_size = sample_rate
    with sf.SoundFile(str(path), "w", samplerate=sample_rate, channels=2, subtype="PCM_24", format="WAV") as output:
        for block_start in range(0, total_samples, chunk_size):
            block_end = min(total_samples, block_start + chunk_size)
            block = np.zeros((block_end - block_start, 2), dtype=np.float32)
            first_pair = max(0, int(np.floor(block_start / interval)))
            last_pair = int(np.floor((block_end - 1) / interval))
            for pair in range(first_pair, last_pair + 1):
                base = pair * interval
                for channel, event_start in ((0, base), (1, base + offset)):
                    event_end = event_start + len(chirp_signal)
                    left, right = max(block_start, event_start), min(block_end, event_end)
                    if left < right:
                        block[left - block_start : right - block_start, channel] += chirp_signal[left - event_start : right - event_start]
            output.write(block)
