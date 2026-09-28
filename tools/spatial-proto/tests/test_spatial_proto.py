from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tests"))

from analyze_recording import analyze_sync
from dsp.ambient import apply_w3
from dsp.coherence import analyze_coherence
from dsp.common import istft, split_lr4_bands, stft
from dsp.limiter import limit, true_peak
from dsp.msband import apply_w2
from render import VARIANT_CODES, _loudness_match_and_limit, load_config, process, render_music
from simulate_sync_recording import simulate


def relative_error_db(reference: np.ndarray, actual: np.ndarray) -> float:
    error = np.linalg.norm(actual - reference)
    if error == 0:
        return float("-inf")
    return float(20.0 * np.log10(error / max(np.linalg.norm(reference), 1e-30)))


class DspTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.config = load_config()
        cls.sample_rate = 48_000
        rng = np.random.default_rng(991)
        cls.audio = rng.normal(0.0, 0.08, (24_000, 2))

    def test_reference_is_unchanged(self) -> None:
        result = process(self.audio, {"kind": "reference"}, self.config)
        self.assertTrue(np.array_equal(result, self.audio))

    def test_w2_zero_gain_is_transparent(self) -> None:
        _, weight, _ = analyze_coherence(self.audio, self.sample_rate, self.config["stft"])
        result = apply_w2(self.audio, {"low_hz": 300, "high_hz": 5000, "mid_db": 0, "high_db": 0}, weight, self.sample_rate)
        self.assertLess(relative_error_db(self.audio, result), -90.0)

    def test_linkwitz_riley_band_sum_is_flat(self) -> None:
        low, mid, high = split_lr4_bands(self.audio[:, :1], 300, 5000, self.sample_rate)
        reconstructed = low + mid + high
        self.assertLess(float(np.max(np.abs(reconstructed - self.audio[:, :1]))), 1e-10)
        self.assertLess(relative_error_db(self.audio[:, :1], reconstructed), -90.0)

    def test_sqrt_hann_stft_reconstructs(self) -> None:
        result = istft(stft(self.audio, 2048, 512), len(self.audio), 2048, 512)
        self.assertLess(relative_error_db(self.audio, result), -80.0)

    def test_coherent_mono_stays_mono_through_w2_w3(self) -> None:
        mono = self.audio[:, 0]
        stereo = np.column_stack((mono, mono))
        _, weight, _ = analyze_coherence(stereo, self.sample_rate, self.config["stft"])
        w2 = apply_w2(stereo, {"low_hz": 300, "high_hz": 5000, "mid_db": 6, "high_db": 4}, weight, self.sample_rate)
        w3, _ = apply_w3(w2, weight, self.config["ambient"], self.sample_rate)
        self.assertLess(relative_error_db(w3[:, :1], w3[:, 1:]), -60.0)

    def test_limiter_respects_true_peak_ceiling(self) -> None:
        t = np.arange(self.sample_rate) / self.sample_rate
        source = np.column_stack((1.8 * np.sin(2 * np.pi * 997 * t), 1.8 * np.sin(2 * np.pi * 997 * t + 0.02)))
        result, report = limit(source, self.sample_rate, self.config["limiter"])
        self.assertLessEqual(20 * np.log10(true_peak(result, 4)), -1.0 + 1e-3)
        self.assertGreater(report["triggered_samples"], 0)

    def test_variants_loudness_within_target(self) -> None:
        t = np.arange(self.sample_rate * 2) / self.sample_rate
        source = np.column_stack((0.12 * np.sin(2 * np.pi * 440 * t), 0.09 * np.sin(2 * np.pi * 440 * t + 0.2)))
        levels = []
        for variant in VARIANT_CODES:
            processed = process(source, self.config["variants"][variant], self.config)
            _, report = _loudness_match_and_limit(processed, self.config)
            levels.append(report["lufs_after_limiter"])
        self.assertLess(max(levels) - min(levels), 0.3)


class IntegrationTests(unittest.TestCase):
    def test_same_seed_creates_same_codes_and_pcm_files(self) -> None:
        config = load_config()
        config["fragment_seconds"] = 1.0
        config["automatic_selection"] = {"short_term_seconds": 0.5, "step_seconds": 0.25}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_dir = root / "jazz"
            source_dir.mkdir()
            rng = np.random.default_rng(23)
            for index in range(3):
                signal = rng.normal(0.0, 0.05, (2 * 48_000, 2))
                sf.write(str(source_dir / f"track{index}.wav"), signal, 48_000, subtype="PCM_24")
            first = root / "first"
            second = root / "second"
            first.mkdir()
            second.mkdir()
            key_a = render_music(source_dir, first, config, 123, ["A", "B"])
            key_b = render_music(source_dir, second, config, 123, ["A", "B"])
            self.assertEqual(list(key_a["tracks"]), list(key_b["tracks"]))
            for filename in sorted(path.name for path in (first / "tracks").glob("*.wav")):
                audio_a, _ = sf.read(str(first / "tracks" / filename), dtype="float64")
                audio_b, _ = sf.read(str(second / "tracks" / filename), dtype="float64")
                self.assertTrue(np.array_equal(audio_a, audio_b), filename)
            self.assertEqual(key_a["listener_orders"], key_b["listener_orders"])

    def test_sync_analyzer_recovers_known_jitter(self) -> None:
        config = load_config()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for jitter in (0.0, 20.0, 100.0):
                path = simulate(root / f"sync-{jitter:g}.wav", jitter, duration_seconds=24, seed=35)
                report = analyze_sync(path, root / f"report-{jitter:g}", config)
                self.assertLessEqual(abs(report["jitter_sigma_us"] - jitter), 5.0, f"target={jitter}, report={report['jitter_sigma_us']}")


if __name__ == "__main__":
    unittest.main()
