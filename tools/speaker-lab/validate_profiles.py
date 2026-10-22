#!/usr/bin/env python3
"""Валидатор профилей устройств A2 (см. docs/a2-profile-spec.md).

Использование:
    python3 -I tools/speaker-lab/validate_profiles.py profiles/devices.json

Код выхода: 0 — файл валиден (печатает OK); 1 — есть ошибки или файл не читается.
Только стандартная библиотека Python 3.
"""
import json
import math
import re
import sys

VALIDATOR_VERSION = "0.1.0"

SECTION_TYPES = ("highpass", "lowshelf", "highshelf", "peak")
GAIN_TYPES = ("peak", "lowshelf", "highshelf")
MAX_SECTIONS = 16
MAX_ALLPASS = 4
REQUIRED_CLASS = "generic_phone_speaker"

KEY_RE = re.compile(r"^[a-z0-9_]+$")
SEMVER_RE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")


def _is_num(value):
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
    )


def _range_field(errors, path, obj, key, lo, hi):
    """Проверяет обязательное числовое поле в диапазоне. Возвращает значение или None."""
    if key not in obj:
        errors.append(f"{path}: отсутствует поле {key}")
        return None
    value = obj[key]
    if not _is_num(value):
        errors.append(f"{path}.{key}: ожидается число, получено {value!r}")
        return None
    if not lo <= value <= hi:
        errors.append(f"{path}.{key}: {value} вне диапазона [{lo}, {hi}]")
        return None
    return value


def _validate_section(errors, path, sec):
    if not isinstance(sec, dict):
        errors.append(f"{path}: секция должна быть объектом")
        return
    sec_type = sec.get("type")
    if sec_type not in SECTION_TYPES:
        errors.append(
            f"{path}.type: допустимо {', '.join(SECTION_TYPES)}, получено {sec_type!r}"
        )
    _range_field(errors, path, sec, "f0_hz", 20, 20000)
    _range_field(errors, path, sec, "q", 0.1, 6)
    if sec_type in GAIN_TYPES:
        gain = _range_field(errors, path, sec, "gain_db", -15, 6)
        f0 = sec.get("f0_hz")
        if gain is not None and _is_num(f0) and f0 > 8000 and gain > 3:
            errors.append(
                f"{path}.gain_db: {gain} выше +3 дБ при f0_hz {f0} (> 8000 Гц)"
            )


def _validate_sections(errors, path, sections):
    if not isinstance(sections, list):
        errors.append(f"{path}: sections должен быть списком")
        return
    if len(sections) > MAX_SECTIONS:
        errors.append(
            f"{path}: секций {len(sections)}, максимум {MAX_SECTIONS}"
        )
    for i, sec in enumerate(sections):
        _validate_section(errors, f"{path}[{i}]", sec)


def _validate_profile(errors, path, prof, model_keys):
    """Общая часть для записи в classes и в models."""
    if not isinstance(prof, dict):
        errors.append(f"{path}: профиль должен быть объектом")
        return

    if "synthetic" in prof and not isinstance(prof["synthetic"], bool):
        errors.append(f"{path}.synthetic: ожидается true или false")

    # preamp_db: только верхняя граница (<= 0), нижней спецификация не задаёт.
    if "preamp_db" not in prof:
        errors.append(f"{path}: отсутствует поле preamp_db")
    elif not _is_num(prof["preamp_db"]):
        errors.append(
            f"{path}.preamp_db: ожидается число, получено {prof['preamp_db']!r}"
        )
    elif prof["preamp_db"] > 0:
        errors.append(f"{path}.preamp_db: {prof['preamp_db']} больше 0")

    _range_field(errors, path, prof, "f_low_hz", 60, 600)

    if "sections" not in prof:
        errors.append(f"{path}: отсутствует поле sections")
    else:
        _validate_sections(errors, f"{path}.sections", prof["sections"])

    allpass = prof.get("allpass_vs_partner", {})
    if not isinstance(allpass, dict):
        errors.append(f"{path}.allpass_vs_partner: ожидается объект")
        return

    for partner, sections in allpass.items():
        ap_path = f"{path}.allpass_vs_partner[{partner}]"
        if partner not in model_keys:
            errors.append(f"{ap_path}: партнёр {partner!r} не найден среди models")
        if not isinstance(sections, list):
            errors.append(f"{ap_path}: ожидается список секций")
            continue
        if len(sections) > MAX_ALLPASS:
            errors.append(f"{ap_path}: allpass-секций {len(sections)}, максимум {MAX_ALLPASS}")
        for i, ap in enumerate(sections):
            if not isinstance(ap, dict):
                errors.append(f"{ap_path}[{i}]: секция должна быть объектом")
                continue
            _range_field(errors, f"{ap_path}[{i}]", ap, "f0_hz", 20, 20000)
            _range_field(errors, f"{ap_path}[{i}]", ap, "q", 0.1, 6)


def validate(doc):
    """Возвращает список ошибок схемы профилей. Пустой список означает, что файл валиден."""
    errors = []
    if not isinstance(doc, dict):
        return ["корень документа должен быть объектом"]

    version = doc.get("profiles_version")
    if not isinstance(version, str) or not SEMVER_RE.match(version):
        errors.append(f"profiles_version: ожидается semver, получено {version!r}")

    schema = doc.get("schema")
    if not isinstance(schema, int) or isinstance(schema, bool) or schema != 1:
        errors.append(f"schema: ожидается целое 1, получено {schema!r}")

    models = doc.get("models")
    if not isinstance(models, list):
        errors.append("models: ожидается список")
        models = []

    model_keys = {
        m["key"] for m in models
        if isinstance(m, dict) and isinstance(m.get("key"), str)
    }

    classes = doc.get("classes")
    if not isinstance(classes, dict):
        errors.append("classes: ожидается объект")
    else:
        if REQUIRED_CLASS not in classes:
            errors.append(f"classes: обязателен класс {REQUIRED_CLASS}")
        for name, prof in classes.items():
            _validate_profile(errors, f"classes.{name}", prof, model_keys)

    seen_keys = set()
    seen_match = {}  # нормализованная строка match -> key модели
    for i, model in enumerate(models):
        path = f"models[{i}]"
        if not isinstance(model, dict):
            errors.append(f"{path}: модель должна быть объектом")
            continue

        key = model.get("key")
        if not isinstance(key, str) or not KEY_RE.match(key):
            errors.append(f"{path}.key: допустимы только [a-z0-9_], получено {key!r}")
        elif key in seen_keys:
            errors.append(f"{path}.key: дубликат {key!r}")
        else:
            seen_keys.add(key)

        match = model.get("match")
        if (
            not isinstance(match, list)
            or not match
            or not all(isinstance(s, str) and s.strip() for s in match)
        ):
            errors.append(f"{path}.match: ожидается непустой список непустых строк")
        else:
            for s in match:
                norm = s.lower()
                other = seen_match.get(norm)
                if other is not None and other != key:
                    errors.append(
                        f"{path}.match: строка {s!r} уже задана в модели {other!r}"
                    )
                else:
                    seen_match[norm] = key

        _validate_profile(errors, path, model, model_keys)

    return errors


def main(argv):
    if len(argv) != 2:
        print("usage: validate_profiles.py <devices.json>", file=sys.stderr)
        return 2
    path = argv[1]
    try:
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
    except (OSError, ValueError) as exc:
        print(f"ОШИБКА: не удалось прочитать {path}: {exc}")
        return 1

    errors = validate(doc)
    if errors:
        for err in errors:
            print(f"ОШИБКА: {err}")
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
