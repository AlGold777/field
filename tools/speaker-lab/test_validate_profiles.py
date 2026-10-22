"""Тесты валидатора профилей A2. Запуск:
    python3 -I -m unittest discover -s tools/speaker-lab -p 'test_*.py'
"""
import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest

from validate_profiles import validate

HERE = os.path.dirname(os.path.abspath(__file__))
DEVICES = os.path.normpath(os.path.join(HERE, "..", "..", "profiles", "devices.json"))


def _load_raw():
    with open(DEVICES, encoding="utf-8") as fh:
        return json.load(fh)


def _load_valid():
    return _load_raw()


class ValidFileTest(unittest.TestCase):
    def test_fixture_is_valid(self):
        self.assertEqual(validate(_load_valid()), [])

    def test_devices_json_is_valid(self):
        self.assertEqual(validate(_load_raw()), [])

    def test_minimal_valid_doc(self):
        doc = {
            "profiles_version": "0.1.0",
            "schema": 1,
            "classes": {
                "generic_phone_speaker": {
                    "preamp_db": 0.0,
                    "f_low_hz": 250.0,
                    "sections": [{"type": "highpass", "f0_hz": 250.0, "q": 0.5}],
                }
            },
            "models": [],
        }
        self.assertEqual(validate(doc), [])


class ValidatorTestBase(unittest.TestCase):
    def setUp(self):
        self.doc = _load_valid()
        self.model = self.doc["models"][0]
        self.generic = self.doc["classes"]["generic_phone_speaker"]

    def assertHasError(self, errors, needle):
        self.assertTrue(
            any(needle in e for e in errors),
            f"ожидалась ошибка с {needle!r}, получено: {errors}",
        )


class TopLevelTest(ValidatorTestBase):
    def test_root_not_object(self):
        self.assertTrue(validate([]))

    def test_bad_profiles_version(self):
        self.doc["profiles_version"] = "0.1"
        self.assertHasError(validate(self.doc), "profiles_version")

    def test_schema_must_be_1(self):
        self.doc["schema"] = 2
        self.assertHasError(validate(self.doc), "schema")

    def test_schema_true_rejected(self):
        self.doc["schema"] = True
        self.assertTrue(validate(self.doc))

    def test_generic_class_required(self):
        del self.doc["classes"]["generic_phone_speaker"]
        self.assertHasError(validate(self.doc), "generic_phone_speaker")

    def test_models_must_be_list(self):
        self.doc["models"] = {}
        self.assertHasError(validate(self.doc), "models")


class KeyTest(ValidatorTestBase):
    def test_duplicate_key(self):
        self.doc["models"].append(copy.deepcopy(self.model))
        self.doc["models"][1]["match"] = ["OTHER/X/y"]
        self.assertHasError(validate(self.doc), "дубликат")

    def test_key_uppercase_rejected(self):
        self.model["key"] = "Synthetic_A"
        self.assertHasError(validate(self.doc), "key")

    def test_key_hyphen_rejected(self):
        self.model["key"] = "synthetic-a"
        self.assertHasError(validate(self.doc), "key")


class MatchTest(ValidatorTestBase):
    def test_duplicate_match_across_models(self):
        other = copy.deepcopy(self.model)
        other["key"] = "other_model"
        self.doc["models"].append(other)
        self.assertHasError(validate(self.doc), "уже задана")

    def test_duplicate_match_case_insensitive(self):
        other = copy.deepcopy(self.model)
        other["key"] = "other_model"
        other["match"] = ["test/synth_a/SYNTH_A"]
        self.doc["models"].append(other)
        self.assertHasError(validate(self.doc), "уже задана")

    def test_empty_match_rejected(self):
        self.model["match"] = []
        self.assertHasError(validate(self.doc), "match")

    def test_match_not_strings_rejected(self):
        self.model["match"] = [42]
        self.assertHasError(validate(self.doc), "match")


class SectionRangeTest(ValidatorTestBase):
    def test_f0_too_high(self):
        self.model["sections"][0]["f0_hz"] = 25000
        self.assertHasError(validate(self.doc), "f0_hz")

    def test_f0_too_low(self):
        self.model["sections"][0]["f0_hz"] = 10
        self.assertHasError(validate(self.doc), "f0_hz")

    def test_q_too_low(self):
        self.model["sections"][0]["q"] = 0.05
        self.assertHasError(validate(self.doc), "q")

    def test_q_too_high(self):
        self.model["sections"][0]["q"] = 6.5
        self.assertHasError(validate(self.doc), "q")

    def test_gain_too_low(self):
        self.model["sections"][1]["gain_db"] = -16
        self.assertHasError(validate(self.doc), "gain_db")

    def test_gain_too_high(self):
        self.model["sections"][1]["gain_db"] = 7
        self.assertHasError(validate(self.doc), "gain_db")

    def test_gain_missing_for_peak(self):
        del self.model["sections"][1]["gain_db"]
        self.assertHasError(validate(self.doc), "отсутствует поле gain_db")

    def test_gain_missing_for_shelf(self):
        del self.model["sections"][2]["gain_db"]
        self.assertHasError(validate(self.doc), "отсутствует поле gain_db")

    def test_bool_is_not_number(self):
        self.model["sections"][0]["q"] = True
        self.assertHasError(validate(self.doc), "ожидается число")

    def test_nan_rejected(self):
        self.model["sections"][0]["q"] = float("nan")
        self.assertTrue(validate(self.doc))

    def test_highshelf_above_8k_gain_over_3(self):
        self.model["sections"][2]["gain_db"] = 4.0  # f0 = 9000
        self.assertHasError(validate(self.doc), "выше +3 дБ")

    def test_peak_above_8k_gain_over_3(self):
        self.model["sections"][1]["f0_hz"] = 12000
        self.model["sections"][1]["gain_db"] = 3.5
        self.assertHasError(validate(self.doc), "выше +3 дБ")

    def test_gain_at_8k_boundary_allowed(self):
        self.model["sections"][1]["f0_hz"] = 8000
        self.model["sections"][1]["gain_db"] = 4.0
        self.assertEqual(validate(self.doc), [])

    def test_gain_3_above_8k_allowed(self):
        self.model["sections"][2]["gain_db"] = 3.0
        self.assertEqual(validate(self.doc), [])


class PreampFlowTest(ValidatorTestBase):
    def test_preamp_positive_rejected(self):
        self.model["preamp_db"] = 1.0
        self.assertHasError(validate(self.doc), "больше 0")

    def test_preamp_zero_allowed(self):
        self.model["preamp_db"] = 0.0
        self.assertEqual(validate(self.doc), [])

    def test_f_low_too_low(self):
        self.model["f_low_hz"] = 50
        self.assertHasError(validate(self.doc), "f_low_hz")

    def test_f_low_too_high(self):
        self.model["f_low_hz"] = 700
        self.assertHasError(validate(self.doc), "f_low_hz")


class SectionCountTest(ValidatorTestBase):
    def test_sixteen_sections_allowed(self):
        self.model["sections"] = [
            {"type": "peak", "f0_hz": 1000 + i, "q": 1.0, "gain_db": 0.0}
            for i in range(16)
        ]
        self.assertEqual(validate(self.doc), [])

    def test_seventeen_sections_rejected(self):
        self.model["sections"] = [
            {"type": "peak", "f0_hz": 1000 + i, "q": 1.0, "gain_db": 0.0}
            for i in range(17)
        ]
        self.assertHasError(validate(self.doc), "максимум 16")


class AllpassTest(ValidatorTestBase):
    def test_five_allpass_rejected(self):
        self.model["allpass_vs_partner"] = {
            "synthetic_test_b": [{"f0_hz": 1000 + i, "q": 0.6} for i in range(5)]
        }
        self.assertHasError(validate(self.doc), "максимум 4")

    def test_four_allpass_allowed(self):
        self.model["allpass_vs_partner"] = {
            "synthetic_test_b": [{"f0_hz": 1000 + i, "q": 0.6} for i in range(4)]
        }
        self.assertEqual(validate(self.doc), [])

    def test_unknown_partner_rejected(self):
        self.model["allpass_vs_partner"] = {
            "no_such_model": [{"f0_hz": 1800.0, "q": 0.6}]
        }
        self.assertHasError(validate(self.doc), "не найден среди models")

    def test_allpass_f0_out_of_range(self):
        self.model["allpass_vs_partner"]["synthetic_test_b"] = [
            {"f0_hz": 5, "q": 0.6}
        ]
        self.assertHasError(validate(self.doc), "f0_hz")

    def test_allpass_q_out_of_range(self):
        self.model["allpass_vs_partner"]["synthetic_test_b"] = [
            {"f0_hz": 1800.0, "q": 7}
        ]
        self.assertHasError(validate(self.doc), "q")


class SectionTypeTest(ValidatorTestBase):
    def test_unknown_type_rejected(self):
        self.model["sections"][0]["type"] = "notch"
        self.assertHasError(validate(self.doc), "type")

    def test_section_not_object(self):
        self.model["sections"][0] = "highpass"
        self.assertHasError(validate(self.doc), "секция должна быть объектом")

    def test_sections_not_list(self):
        self.model["sections"] = {"type": "highpass"}
        self.assertHasError(validate(self.doc), "sections должен быть списком")

    def test_synthetic_must_be_bool(self):
        self.model["synthetic"] = "yes"
        self.assertHasError(validate(self.doc), "synthetic")


class CliTest(unittest.TestCase):
    VALIDATOR = os.path.join(HERE, "validate_profiles.py")

    def _run(self, path):
        return subprocess.run(
            [sys.executable, "-I", self.VALIDATOR, path],
            capture_output=True,
            text=True,
        )

    def _write_tmp(self, tmp, doc):
        path = os.path.join(tmp, "devices.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(doc, fh)
        return path

    def test_cli_valid_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._run(self._write_tmp(tmp, _load_valid()))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.strip(), "OK")

    def test_cli_invalid_file(self):
        doc = _load_valid()
        doc["models"][0]["sections"][0]["q"] = 99
        with tempfile.TemporaryDirectory() as tmp:
            result = self._run(self._write_tmp(tmp, doc))
        self.assertEqual(result.returncode, 1)
        self.assertIn("q", result.stdout)

    def test_cli_real_devices_json(self):
        result = self._run(DEVICES)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_cli_unreadable_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "broken.json")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write("{not json")
            result = self._run(path)
        self.assertEqual(result.returncode, 1)


if __name__ == "__main__":
    unittest.main()
