#!/usr/bin/env python3
"""Summarize listener scores against the blind-test key and file metrics."""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np

from render import PROTO_VERSION


SCORE_COLUMNS = ["fragment", "code", "width", "depth", "center", "coloration", "naturalness", "preference", "shifted_center", "comment"]
ATTRIBUTES = ("width", "depth", "center", "coloration", "naturalness", "preference", "shifted_center")


def write_template(key_path: str | Path, output_path: str | Path) -> Path:
    with Path(key_path).open("r", encoding="utf-8") as file:
        key = json.load(file)
    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    has_xtc = any(item.get("kind") == "xtc" for item in key["tracks"].values())
    columns = ["fragment", "code", "distance_m", *SCORE_COLUMNS[2:-1], "outside_phone", "comment"] if has_xtc else SCORE_COLUMNS
    with output.open("w", newline="", encoding="utf-8-sig") as file:
        writer = csv.DictWriter(file, fieldnames=columns)
        writer.writeheader()
        for code, item in key["tracks"].items():
            if item.get("kind") in ("music", "xtc"):
                row = {"fragment": item["fragment_id"], "code": code}
                if has_xtc:
                    row["distance_m"] = item.get("distance_m", "")
                writer.writerow(row)
    return output


def _condition(item: dict) -> str:
    distance = item.get("distance_m")
    return f"{item['variant']} · {float(distance):g} м" if distance is not None else item["variant"]


def _read_scores(path: Path, key: dict) -> list[dict]:
    with path.open("r", newline="", encoding="utf-8-sig") as file:
        reader = csv.DictReader(file)
        if reader.fieldnames is None or not {"fragment", "code"}.issubset(reader.fieldnames):
            raise ValueError(f"В {path.name} нужны колонки fragment и code")
        rows = []
        for line_number, row in enumerate(reader, start=2):
            code = (row.get("code") or "").strip()
            if not code:
                continue
            if code not in key["tracks"] or key["tracks"][code].get("kind") not in ("music", "xtc"):
                raise ValueError(f"{path.name}:{line_number}: код {code!r} не найден в ключе")
            expected_fragment = key["tracks"][code]["fragment_id"]
            fragment = (row.get("fragment") or "").strip()
            if fragment and fragment != expected_fragment:
                raise ValueError(f"{path.name}:{line_number}: код {code} относится к {expected_fragment}, а не к {fragment}")
            for attr in ATTRIBUTES:
                text = (row.get(attr) or "").strip()
                if not text:
                    row[attr] = None
                    continue
                value = float(text.replace(",", "."))
                if not 0 <= value <= 100:
                    raise ValueError(f"{path.name}:{line_number}: {attr} должен быть 0–100")
                row[attr] = value
            item = key["tracks"][code]
            outside_phone = None
            if (row.get("outside_phone") or "").strip():
                outside_value = (row["outside_phone"] or "").strip().lower()
                if outside_value not in {"yes", "да", "1", "true", "no", "нет", "0", "false"}:
                    raise ValueError(f"{path.name}:{line_number}: outside_phone должен быть да/нет")
                outside_phone = outside_value in {"yes", "да", "1", "true"}
            rows.append(
                {
                    "listener": path.stem,
                    "fragment": expected_fragment,
                    "code": code,
                    "variant": item["variant"],
                    "condition": _condition(item),
                    "kind": item.get("kind", "music"),
                    "distance_m": item.get("distance_m"),
                    "outside_phone": outside_phone,
                    **{attr: row.get(attr) for attr in ATTRIBUTES},
                    "comment": row.get("comment", ""),
                }
            )
    return rows


def _excluded_listeners(rows: list[dict]) -> dict[str, str]:
    grouped: dict[str, list[dict]] = {}
    for row in rows:
        grouped.setdefault(row["listener"], []).append(row)
    excluded = {}
    for listener, listener_rows in grouped.items():
        refs = [row for row in listener_rows if row["variant"] == "REF" and row["naturalness"] is not None]
        if not refs:
            continue
        reference_conditions = {(row["fragment"], row["distance_m"]) for row in refs}
        low_count = len({(row["fragment"], row["distance_m"]) for row in refs if row["naturalness"] < 70})
        if reference_conditions and low_count > len(reference_conditions) / 2:
            excluded[listener] = f"оценка естественности REF ниже 70 в {low_count}/{len(reference_conditions)} контрольных условиях"
    return excluded


def summarize(key_path: str | Path, score_paths: list[str | Path], metrics_path: str | Path | None = None, output_path: str | Path | None = None) -> dict:
    with Path(key_path).open("r", encoding="utf-8") as file:
        key = json.load(file)
    rows = []
    for path in score_paths:
        rows.extend(_read_scores(Path(path), key))
    excluded = _excluded_listeners(rows)
    included = [row for row in rows if row["listener"] not in excluded]
    if not included:
        raise ValueError("После проверки REF не осталось оценок слушателей")
    variants = sorted({row["condition"] for row in included}, key=lambda x: (not x.startswith("REF"), x))
    by_variant = {}
    for variant in variants:
        variant_rows = [row for row in included if row["condition"] == variant]
        yes_values = [row["outside_phone"] for row in variant_rows if row["outside_phone"] is not None]
        by_variant[variant] = {
            "count": len(variant_rows),
            "attributes": {},
            "outside_phone_yes_percent": float(100.0 * np.mean(yes_values)) if yes_values else None,
        }
        for attribute in ATTRIBUTES:
            values = [row[attribute] for row in variant_rows if row[attribute] is not None]
            if values:
                q25, median, q75 = np.percentile(values, [25, 50, 75])
                by_variant[variant]["attributes"][attribute] = {"median": float(median), "q25": float(q25), "q75": float(q75)}
    if not any(variant.startswith("REF") for variant in by_variant):
        raise ValueError("В оценках нет варианта REF")

    file_metrics = None
    if metrics_path:
        with Path(metrics_path).open("r", encoding="utf-8") as file:
            file_metrics = json.load(file)
    technical = file_metrics.get("max_midband_mono_sum_shape_deviation_db", {}) if file_metrics else {}
    highres_technical = file_metrics.get("max_midband_mono_sum_welch_ripple_pp_db", {}) if file_metrics else {}
    verdicts = {}
    for variant in variants:
        if variant.startswith("REF"):
            continue
        attrs = by_variant[variant]["attributes"]
        distance_suffix = variant.partition(" · ")[1] + variant.partition(" · ")[2] if " · " in variant else ""
        reference_variant = f"REF{distance_suffix}"
        ref_attrs = by_variant.get(reference_variant, {}).get("attributes", by_variant.get("REF", {}).get("attributes", {}))
        failures = []
        for attribute, label in (("center", "центр"), ("coloration", "окраска")):
            if attribute in attrs and attribute in ref_attrs and attrs[attribute]["median"] < ref_attrs[attribute]["median"] - 10:
                failures.append(f"медиана «{label}» ниже REF больше чем на 10")
        actual_variant = next(row["variant"] for row in included if row["condition"] == variant)
        is_music = all(row["kind"] == "music" for row in included if row["condition"] == variant)
        mono_deviation = technical.get(actual_variant) if is_music else None
        if mono_deviation is not None and float(mono_deviation) > 2.0:
            failures.append(f"форма моно-суммы отличается от REF на {float(mono_deviation):.2f} дБ")
        success_attributes = []
        for attribute, label in (("width", "ширина"), ("depth", "глубина")):
            if attribute in attrs and attribute in ref_attrs and attrs[attribute]["median"] >= ref_attrs[attribute]["median"] + 15:
                success_attributes.append(label)
        if failures:
            verdict = "ПРОВАЛ"
        elif success_attributes:
            verdict = "УСПЕХ: " + " и/или ".join(success_attributes)
        else:
            verdict = "НЕТ ВЫИГРЫША ≥15"
        verdicts[variant] = {
            "verdict": verdict,
            "failures": failures,
            "successful_attributes": success_attributes,
            "mono_sum_max_shape_deviation_db": mono_deviation,
        }

    result = {
        "PROTO_VERSION": key.get("PROTO_VERSION", PROTO_VERSION),
        "included_listeners": sorted({row["listener"] for row in included}),
        "excluded_listeners": excluded,
        "variants": by_variant,
        "verdicts": verdicts,
    }
    output = Path(output_path) if output_path else Path(key_path).parent / "score_report.md"
    output.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# Сводка слепого слухового теста", "", "Медианы и межквартильный размах (Q1–Q3), шкала 0–100.", ""]
    has_outside = any(row["outside_phone"] is not None for row in included)
    extra_header = " | За пределами телефона: да, %" if has_outside else ""
    lines.append("| Вариант / расстояние | N | Ширина | Глубина | Центр | Окраска | Естественность | Предпочтение | Центр ±10 см" + extra_header + " |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|" + ("---:|" if has_outside else ""))
    for variant, summary in by_variant.items():
        rendered = []
        for attribute in ATTRIBUTES:
            stats = summary["attributes"].get(attribute)
            rendered.append(f"{stats['median']:.0f} ({stats['q25']:.0f}–{stats['q75']:.0f})" if stats else "—")
        extra_cell = f" {summary['outside_phone_yes_percent']:.0f}% |" if has_outside and summary["outside_phone_yes_percent"] is not None else (" — |" if has_outside else "|")
        lines.append(f"| {variant} | {summary['count']} | " + " | ".join(rendered) + " |" + extra_cell)
    lines.extend(["", "## Вердикты", ""])
    for variant, verdict in verdicts.items():
        extra = "; ".join(verdict["failures"])
        lines.append(f"- **{variant}: {verdict['verdict']}**" + (f" — {extra}" if extra else ""))
    lines.extend(["", "## Исключённые слушатели", ""])
    if excluded:
        lines.extend(f"- {listener}: {reason}" for listener, reason in excluded.items())
    else:
        lines.append("Нет.")
    lines.extend(["", "Слушатель исключается, если REF получил естественность ниже 70 более чем в половине контрольных условий.", ""])
    lines.extend(["Технический критерий: форму моно-суммы оценивают после вычитания среднего отклонения по 1/3-октавным полосам 200 Гц–5 кГц; провал — остаток больше 2 дБ в любой полосе. Высокое разрешение по Велчу (8192 точки, 300 Гц–5 кГц) — дополнительный ориентир не более 1 дБ пик-пик.", ""])
    if highres_technical:
        lines.extend(["| Вариант | Welch ripple p-p, 300 Гц–5 кГц |", "|---|---:|"])
        lines.extend(f"| {variant} | {float(value):.2f} дБ |" for variant, value in highres_technical.items())
        lines.append("")
    output.write_text("\n".join(lines), encoding="utf-8")
    result_path = output.with_suffix(".json")
    with result_path.open("w", encoding="utf-8") as file:
        json.dump(result, file, ensure_ascii=False, indent=2)
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Раскрыть коды и свести оценки слушателей")
    parser.add_argument("--key", type=Path, required=True, help="key.json, держать отдельно от слушателей")
    parser.add_argument("--scores", type=Path, nargs="+", help="один или несколько заполненных scores_*.csv")
    parser.add_argument("--metrics", type=Path, help="metrics.json из analyze_files.py")
    parser.add_argument("--output", type=Path, help="путь итогового Markdown-отчёта")
    parser.add_argument("--template", action="store_true", help="создать пустой CSV-шаблон вместо подсчёта")
    parser.add_argument("--listener", default="listener", help="имя в имени CSV для --template")
    args = parser.parse_args(argv)
    try:
        if args.template:
            out = args.output or args.key.parent / f"scores_{args.listener}.csv"
            print(f"Шаблон: {write_template(args.key, out)}")
            return 0
        if not args.scores:
            parser.error("Укажите --scores или --template")
        result = summarize(args.key, args.scores, args.metrics, args.output)
        print(f"Исключено слушателей: {len(result['excluded_listeners'])}; отчёт: {args.output or args.key.parent / 'score_report.md'}")
        for variant, verdict in result["verdicts"].items():
            print(f"{variant}: {verdict['verdict']}")
    except (OSError, ValueError, KeyError) as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
