from __future__ import annotations

import numpy as np
from scipy.signal import fftconvolve
from scipy.signal.windows import tukey


def _crossfeed_model(frequency: float, speaker_spacing: float, distance: float, head_radius: float, sound_speed: float) -> np.ndarray:
    speakers = [(-speaker_spacing / 2.0, 0.0), (speaker_spacing / 2.0, 0.0)]
    ears = [(-head_radius, distance), (head_radius, distance)]
    matrix = np.empty((2, 2), dtype=np.complex128)
    for ear_index, (ear_x, ear_y) in enumerate(ears):
        for speaker_index, (speaker_x, speaker_y) in enumerate(speakers):
            dx, dy = ear_x - speaker_x, ear_y - speaker_y
            path = np.hypot(dx, dy)
            transfer = np.exp(-2j * np.pi * frequency * path / sound_speed) / max(path, 1e-6)
            opposite_side = (ear_index == 0 and speaker_index == 1) or (ear_index == 1 and speaker_index == 0)
            if opposite_side:
                # Simple spherical-head shadow approximation for the far ear.
                transfer *= 1.0 / (1.0 + 1j * frequency / 2200.0)
            matrix[ear_index, speaker_index] = transfer
    direct_scale = np.mean(np.abs(np.diag(matrix)))
    return matrix / max(direct_scale, 1e-12)


def design_xtc_filters(
    sample_rate: int,
    speaker_spacing_m: float,
    listener_distance_m: float,
    head_radius_m: float,
    sound_speed_m_s: float,
    low_hz: float,
    high_hz: float,
    regularization: float,
    fft_size: int = 32768,
    causal_delay_ms: float = 30.0,
) -> tuple[np.ndarray, int]:
    """Create a regularized 2x2 XTC FIR matrix from a compact point-source/head model."""
    frequencies = np.fft.rfftfreq(fft_size, 1.0 / sample_rate)
    response = np.zeros((2, 2, len(frequencies)), dtype=np.complex128)
    response[0, 0] = 1.0
    response[1, 1] = 1.0
    edge_low, edge_high = low_hz / (2.0 ** (1.0 / 12.0)), high_hz * (2.0 ** (1.0 / 12.0))
    for index, frequency in enumerate(frequencies):
        if edge_low <= frequency <= edge_high:
            transfer = _crossfeed_model(frequency, speaker_spacing_m, listener_distance_m, head_radius_m, sound_speed_m_s)
            inverse = transfer.conj().T @ np.linalg.inv(transfer @ transfer.conj().T + regularization**2 * np.eye(2))
            if frequency < low_hz:
                amount = 0.5 - 0.5 * np.cos(np.pi * (frequency - edge_low) / (low_hz - edge_low))
            elif frequency > high_hz:
                amount = 0.5 + 0.5 * np.cos(np.pi * (frequency - high_hz) / (edge_high - high_hz))
            else:
                amount = 1.0
            target = np.eye(2, dtype=np.complex128) * (1.0 - amount) + inverse * amount
            response[:, :, index] = target

    delay_samples = max(1, int(round(causal_delay_ms * sample_rate / 1000.0)))
    response *= np.exp(-2j * np.pi * frequencies * delay_samples / sample_rate)[None, None, :]
    impulse = np.fft.irfft(response, n=fft_size, axis=-1)
    post_samples = min(8192, fft_size // 2)
    indices = (np.arange(delay_samples + post_samples) - delay_samples) % fft_size
    filters = impulse[:, :, indices]
    filters *= tukey(filters.shape[-1], alpha=0.15)[None, None, :]
    return filters, delay_samples


def apply_xtc(
    audio: np.ndarray,
    sample_rate: int,
    params: dict,
    listener_distance_m: float,
    regularization: float,
) -> tuple[np.ndarray, dict]:
    filters, delay_samples = design_xtc_filters(
        sample_rate=sample_rate,
        speaker_spacing_m=float(params["speaker_spacing_m"]),
        listener_distance_m=float(listener_distance_m),
        head_radius_m=float(params.get("head_radius_m", 0.0875)),
        sound_speed_m_s=float(params.get("sound_speed_m_s", 343.0)),
        low_hz=float(params.get("low_hz", 500.0)),
        high_hz=float(params.get("high_hz", 6000.0)),
        regularization=float(regularization),
        fft_size=int(params.get("fft_size", 32768)),
        causal_delay_ms=float(params.get("causal_delay_ms", 30.0)),
    )
    channels = []
    for output_channel in range(2):
        mixed = np.zeros(len(audio) + filters.shape[-1] - 1, dtype=np.float64)
        for input_channel in range(2):
            mixed += fftconvolve(audio[:, input_channel], filters[output_channel, input_channel], mode="full")
        channels.append(mixed)
    rendered = np.column_stack(channels)
    aligned = rendered[delay_samples : delay_samples + len(audio)]
    if len(aligned) < len(audio):
        aligned = np.pad(aligned, ((0, len(audio) - len(aligned)), (0, 0)))
    return aligned, {"delay_samples": delay_samples, "delay_compensated": True, "filter_taps": int(filters.shape[-1])}
