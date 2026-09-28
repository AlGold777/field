#!/usr/bin/env python3
"""Local browser UI that calls the spatial prototype's existing Python pipeline."""

from __future__ import annotations

import argparse
import csv
import copy
import io
import json
import mimetypes
import re
import socket
import subprocess
import threading
import uuid
import webbrowser
import zipfile
from datetime import date
from email.parser import BytesParser
from email.policy import default as email_policy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, quote, unquote, urlencode, urlsplit

import yaml

from analyze_files import analyze_rendered_files
from analyze_recording import analyze_sync
from render import PROTO_VERSION, _listener_order, load_config, render_music, render_sync
from score import SCORE_COLUMNS, summarize


ROOT = Path(__file__).resolve().parent
HTML_PATH = ROOT / "ui" / "index.html"
CONFIG_PATH = ROOT / "config.yaml"
DEFAULT_CONFIG = load_config(CONFIG_PATH)
OUTPUT_ROOT = ROOT / "out" / "ui"
SYNC_ROOT = ROOT / "out" / "ui-sync"
MAX_UPLOAD_BYTES = 350_000_000
MUSIC_EXTENSIONS = {".wav", ".flac"}
REQUIRED_SCORE_FIELDS = ("width", "depth", "center", "coloration", "naturalness", "preference")

RUNS: dict[str, dict[str, Any]] = {}
SYNC_JOBS: dict[str, dict[str, Any]] = {}
STATE_LOCK = threading.RLock()
CONFIG_LOCK = threading.RLock()
SERVER_PORT = 0
LAN_IP = "127.0.0.1"


def _copy_json(value: Any) -> Any:
    return json.loads(json.dumps(value, ensure_ascii=False))


def _local_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("192.0.2.1", 80))
        address = sock.getsockname()[0]
        return address if not address.startswith("127.") else "127.0.0.1"
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def _base_url(host: str = "127.0.0.1") -> str:
    return f"http://{host}:{SERVER_PORT}"


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(path)


def _write_json(path: Path, data: dict) -> None:
    _atomic_write(path, json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False).encode("utf-8"))


def _run_path(run_id: str) -> Path:
    if not re.fullmatch(r"[0-9a-f]{32}", run_id):
        raise ValueError("Некорректный идентификатор запуска")
    path = OUTPUT_ROOT / run_id
    if not path.is_dir():
        raise FileNotFoundError("Запуск не найден")
    return path


def _sync_path(sync_id: str) -> Path:
    if not re.fullmatch(r"[0-9a-f]{32}", sync_id):
        raise ValueError("Некорректный идентификатор замера")
    path = SYNC_ROOT / sync_id
    if not path.is_dir():
        raise FileNotFoundError("Замер не найден")
    return path


def _reload_saved_runs() -> None:
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    SYNC_ROOT.mkdir(parents=True, exist_ok=True)
    for output in OUTPUT_ROOT.iterdir():
        key_path = output / "key.json"
        if output.is_dir() and re.fullmatch(r"[0-9a-f]{32}", output.name) and key_path.is_file():
            RUNS[output.name] = {
                "run_id": output.name,
                "status": "complete",
                "progress": 100,
                "message": "Набор готов",
                "output_dir": output,
                "tracks_total": len(json.loads(key_path.read_text(encoding="utf-8")).get("tracks", {})),
            }
    for output in SYNC_ROOT.iterdir():
        if output.is_dir() and re.fullmatch(r"[0-9a-f]{32}", output.name):
            report = output / "report_sync.json"
            SYNC_JOBS[output.name] = {
                "sync_id": output.name,
                "status": "complete" if report.exists() else "probe_ready" if (output / "sync_probe.wav").exists() else "error",
                "progress": 100 if report.exists() else 10,
                "message": "Отчёт готов" if report.exists() else "Проба готова" if (output / "sync_probe.wav").exists() else "",
                "output_dir": output,
            }


def _safe_listener(value: str) -> str:
    safe = re.sub(r"[^A-Za-zА-Яа-яЁё0-9_-]+", "_", value.strip()).strip("_")
    return safe[:64] or "listener"


def _score_file(output_dir: Path, listener: str) -> Path:
    return output_dir / f"scores_{_safe_listener(listener)}.csv"


def _saved_score_files(output_dir: Path) -> list[Path]:
    files = []
    for path in sorted(output_dir.glob("scores_*.csv")):
        try:
            with path.open("r", newline="", encoding="utf-8-sig") as file:
                if next(csv.DictReader(file), None) is not None:
                    files.append(path)
        except (OSError, csv.Error):
            continue
    return files


def _load_key(output_dir: Path) -> dict:
    key_path = output_dir / "key.json"
    if not key_path.is_file():
        raise FileNotFoundError("Треки ещё не готовы")
    return json.loads(key_path.read_text(encoding="utf-8"))


def _read_saved_scores(path: Path) -> dict[str, dict]:
    if not path.is_file():
        return {}
    with path.open("r", newline="", encoding="utf-8-sig") as file:
        return {row.get("code", ""): row for row in csv.DictReader(file) if row.get("code")}


def _validate_config(candidate: Any, reference: Any, path: str = "config") -> Any:
    if isinstance(reference, dict):
        if not reference:
            if not isinstance(candidate, dict):
                raise ValueError(f"Ожидается объект настроек: {path}")
            result = {}
            for key, value in candidate.items():
                if not isinstance(key, str) or isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
                    raise ValueError(f"Значения {path} должны быть неотрицательными числами")
                result[key] = float(value)
            return result
        if not isinstance(candidate, dict) or set(candidate) != set(reference):
            raise ValueError(f"Структура настроек не совпадает с config.yaml: {path}")
        return {key: _validate_config(candidate[key], child, f"{path}.{key}") for key, child in reference.items()}
    if isinstance(reference, list):
        if not isinstance(candidate, list):
            raise ValueError(f"Ожидается список: {path}")
        if reference and len(candidate) != len(reference):
            raise ValueError(f"Нельзя менять число элементов списка: {path}")
        if not reference:
            return candidate
        return [_validate_config(item, reference[min(index, len(reference) - 1)], f"{path}[{index}]") for index, item in enumerate(candidate)]
    if isinstance(reference, bool):
        if not isinstance(candidate, bool):
            raise ValueError(f"Ожидается переключатель true/false: {path}")
        return candidate
    if isinstance(reference, int):
        if isinstance(candidate, bool) or not isinstance(candidate, (int, float)) or int(candidate) != candidate:
            raise ValueError(f"Ожидается целое число: {path}")
        return int(candidate)
    if isinstance(reference, float):
        if isinstance(candidate, bool) or not isinstance(candidate, (int, float)):
            raise ValueError(f"Ожидается число: {path}")
        return float(candidate)
    if isinstance(reference, str):
        if not isinstance(candidate, str):
            raise ValueError(f"Ожидается текст: {path}")
        return candidate
    return candidate


def _save_config(candidate: Any) -> dict:
    validated = _validate_config(candidate, DEFAULT_CONFIG)
    if int(validated.get("sample_rate", 0)) != 48_000:
        raise ValueError("Частота прототипа должна оставаться 48 кГц")
    with CONFIG_LOCK:
        rendered = yaml.safe_dump(validated, allow_unicode=True, sort_keys=False, width=110)
        _atomic_write(CONFIG_PATH, rendered.encode("utf-8"))
        load_config(CONFIG_PATH)
    return validated


def _request_multipart(handler: BaseHTTPRequestHandler) -> list[tuple[str, str, bytes]]:
    length = int(handler.headers.get("Content-Length", "0"))
    if length <= 0:
        raise ValueError("Пустая загрузка")
    if length > MAX_UPLOAD_BYTES:
        raise ValueError("Размер загрузки превышает 350 МБ")
    content_type = handler.headers.get("Content-Type", "")
    if not content_type.lower().startswith("multipart/form-data;"):
        raise ValueError("Ожидается multipart/form-data")
    body = handler.rfile.read(length)
    message = BytesParser(policy=email_policy).parsebytes(
        b"MIME-Version: 1.0\r\nContent-Type: " + content_type.encode("ascii", "replace") + b"\r\n\r\n" + body
    )
    parts: list[tuple[str, str, bytes]] = []
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition") or ""
        filename = part.get_filename() or ""
        parts.append((str(name), str(filename), part.get_payload(decode=True) or b""))
    return parts


def _mime(path: Path) -> str:
    if path.suffix.lower() == ".wav":
        return "audio/wav"
    return mimetypes.guess_type(path.name)[0] or "application/octet-stream"


def _render_worker(run_id: str, listener: str, seed: int) -> None:
    output_dir = OUTPUT_ROOT / run_id
    source_dir = output_dir / "source"
    try:
        config = load_config(CONFIG_PATH)

        def report_progress(done: int, total: int, label: str) -> None:
            value = 8 + round(78 * done / max(total, 1))
            with STATE_LOCK:
                RUNS[run_id].update(progress=value, message=f"Рендер: {label}")

        with STATE_LOCK:
            RUNS[run_id].update(status="running", progress=5, message="Готовлю слепые треки")
        key = render_music(source_dir, output_dir, config, seed, None, progress_callback=report_progress)
        key["PROTO_VERSION"] = PROTO_VERSION
        key["seed"] = seed
        key["date"] = date.today().isoformat()
        if listener.strip():
            _listener_order(key, listener.strip(), seed)
        _write_json(output_dir / "key.json", key)
        with STATE_LOCK:
            RUNS[run_id].update(status="analyzing", progress=90, message="Считаю метрики и строю отчёт")
        analyze_rendered_files(output_dir, config)
        with STATE_LOCK:
            RUNS[run_id].update(
                status="complete",
                progress=100,
                message="Треки и отчёт готовы",
                tracks_total=len(key["tracks"]),
            )
    except Exception as error:  # surfaced in the UI as a job error
        with STATE_LOCK:
            RUNS[run_id].update(status="error", progress=100, message=str(error))


def _sync_worker(sync_id: str, recording_path: Path) -> None:
    output_dir = SYNC_ROOT / sync_id
    try:
        with STATE_LOCK:
            SYNC_JOBS[sync_id].update(status="analyzing", progress=20, message="Анализирую запись")
        result = analyze_sync(recording_path, output_dir, load_config(CONFIG_PATH))
        with STATE_LOCK:
            SYNC_JOBS[sync_id].update(status="complete", progress=100, message="Отчёт готов", result=result)
    except Exception as error:
        with STATE_LOCK:
            SYNC_JOBS[sync_id].update(status="error", progress=100, message=str(error))


class SpatialHandler(BaseHTTPRequestHandler):
    server_version = f"SpatialProtoUI/{PROTO_VERSION}"

    def log_message(self, format: str, *args: Any) -> None:
        print(f"[{self.log_date_time_string()}] {format % args}")

    def _send(self, status: int, body: bytes, content_type: str = "application/json; charset=utf-8", headers: dict[str, str] | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, payload: Any) -> None:
        self._send(status, json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8"))

    def _body_json(self) -> Any:
        length = int(self.headers.get("Content-Length", "0"))
        if length > 5_000_000:
            raise ValueError("Слишком большой запрос")
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_GET(self) -> None:
        parsed = urlsplit(self.path)
        parts = [unquote(piece) for piece in parsed.path.split("/") if piece]
        try:
            if parsed.path in ("/", "/index.html") or (len(parts) == 2 and parts[0] == "mobile"):
                self._send(200, HTML_PATH.read_bytes(), "text/html; charset=utf-8")
                return
            if parsed.path == "/api/config":
                with CONFIG_LOCK:
                    config = load_config(CONFIG_PATH)
                self._json(200, {"config": config, "defaults": DEFAULT_CONFIG, "proto_version": PROTO_VERSION})
                return
            if len(parts) == 3 and parts[:2] == ["api", "runs"]:
                self._json(200, self._run_status(parts[2]))
                return
            if len(parts) == 3 and parts[:2] == ["api", "mobile"]:
                self._json(200, self._mobile_data(parts[2], parse_qs(parsed.query)))
                return
            if len(parts) == 3 and parts[:2] == ["api", "qr"]:
                self._send_qr(parts[2], parse_qs(parsed.query))
                return
            if len(parts) == 3 and parts[0] == "download":
                self._download_track(parts[1], parts[2])
                return
            if len(parts) == 2 and parts[0] == "download-sync":
                path = _sync_path(parts[1]) / "sync_probe.wav"
                self._send_download(path)
                return
            if len(parts) == 3 and parts[:2] == ["api", "sync-status"]:
                with STATE_LOCK:
                    state = SYNC_JOBS.get(parts[2])
                    if not state:
                        raise FileNotFoundError("Замер не найден")
                    response = {key: value for key, value in state.items() if key not in {"output_dir"}}
                self._json(200, response)
                return
            if len(parts) == 3 and parts[0] == "sync-report":
                path = _sync_path(parts[1]) / parts[2]
                if path.name not in {"report_sync.md", "report_sync.json", "report_sync.png"} or not path.is_file():
                    raise FileNotFoundError("Файл отчёта не найден")
                content_type = "text/markdown; charset=utf-8" if path.suffix == ".md" else _mime(path)
                self._send(200, path.read_bytes(), content_type)
                return
            if len(parts) == 3 and parts[0] == "run-report":
                path = _run_path(parts[1]) / parts[2]
                if path.name not in {"score_report.md", "score_report.json"} or not path.is_file():
                    raise FileNotFoundError("Файл отчёта ещё не создан")
                content_type = "text/markdown; charset=utf-8" if path.suffix == ".md" else "application/json; charset=utf-8"
                self._send(200, path.read_bytes(), content_type)
                return
            self._json(404, {"error": "Маршрут не найден"})
        except FileNotFoundError as error:
            self._json(404, {"error": str(error)})
        except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
            self._json(400, {"error": str(error)})

    def do_POST(self) -> None:
        parsed = urlsplit(self.path)
        parts = [piece for piece in parsed.path.split("/") if piece]
        try:
            if parsed.path == "/api/render":
                self._start_render()
                return
            if parsed.path == "/api/config":
                payload = self._body_json()
                config = _save_config(payload.get("config"))
                self._json(200, {"config": config, "message": "Настройки сохранены в config.yaml"})
                return
            if parsed.path == "/api/config/reset":
                config = _save_config(copy.deepcopy(DEFAULT_CONFIG))
                self._json(200, {"config": config, "message": "Восстановлены значения config.yaml по умолчанию"})
                return
            if len(parts) == 3 and parts[:2] == ["api", "scores"]:
                self._save_scores(parts[2], self._body_json())
                return
            if len(parts) == 3 and parts[:2] == ["api", "reveal"]:
                self._reveal(parts[2])
                return
            if len(parts) == 3 and parts[:2] == ["api", "open"]:
                output = _run_path(parts[2])
                if not (output / "tracks").is_dir():
                    raise FileNotFoundError("Папка треков ещё не готова")
                subprocess.Popen(["open", str(output / "tracks")])
                self._json(200, {"message": "Папка tracks открыта в Finder"})
                return
            if parsed.path == "/api/sync/create":
                self._create_sync()
                return
            if parsed.path == "/api/sync/upload":
                self._upload_sync_recording()
                return
            self._json(404, {"error": "Маршрут не найден"})
        except FileNotFoundError as error:
            self._json(404, {"error": str(error)})
        except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
            self._json(400, {"error": str(error)})

    def _run_status(self, run_id: str) -> dict:
        output_dir = _run_path(run_id)
        with STATE_LOCK:
            state = RUNS.get(run_id)
            if state:
                response = {key: value for key, value in state.items() if key != "output_dir"}
            else:
                response = {"run_id": run_id, "status": "complete", "progress": 100, "message": "Набор готов"}
        score_files = _saved_score_files(output_dir)
        response["scores_saved"] = bool(score_files)
        response["score_file_count"] = len(score_files)
        if (output_dir / "key.json").is_file():
            key = _load_key(output_dir)
            response["tracks_total"] = len(key.get("tracks", {}))
            listener = response.get("listener") or next(iter(key.get("listener_orders", {})), "listener")
            response["mobile_url"] = f"{_base_url(LAN_IP)}/mobile/{run_id}?{urlencode({'listener': listener})}"
        return response

    def _mobile_data(self, run_id: str, query: dict[str, list[str]]) -> dict:
        output_dir = _run_path(run_id)
        key = _load_key(output_dir)
        listener = (query.get("listener") or ["listener"])[0].strip() or "listener"
        safe_listener = _safe_listener(listener)
        orders = _listener_order(key, listener, int(key.get("seed", 0)))
        order_by_fragment = {entry["fragment"]: entry["codes"] for entry in orders}
        tracks_by_code = key.get("tracks", {})
        tracks = []
        for fragment in sorted(order_by_fragment):
            for code in order_by_fragment[fragment]:
                item = tracks_by_code.get(code, {})
                tracks.append(
                    {
                        "fragment": fragment,
                        "code": code,
                        "filename": item.get("filename", ""),
                        "download_url": f"/download/{run_id}/{quote(item.get('filename', ''), safe='')}",
                    }
                )
        saved = _read_saved_scores(output_dir / f"scores_{safe_listener}.csv")
        return {
            "run_id": run_id,
            "listener": listener,
            "score_filename": f"scores_{safe_listener}.csv",
            "tracks": tracks,
            "saved_scores": saved,
            "proto_version": key.get("PROTO_VERSION", PROTO_VERSION),
        }

    def _send_qr(self, run_id: str, query: dict[str, list[str]]) -> None:
        _run_path(run_id)
        listener = (query.get("listener") or ["listener"])[0]
        url = f"{_base_url(LAN_IP)}/mobile/{run_id}?{urlencode({'listener': listener})}"
        try:
            import qrcode
        except ImportError as error:
            raise ValueError("Для QR-кода установите зависимости: python -m pip install -r requirements.txt") from error
        image = qrcode.make(url, box_size=7, border=3)
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        self._send(200, buffer.getvalue(), "image/png")

    def _send_download(self, path: Path) -> None:
        if not path.is_file():
            raise FileNotFoundError("Файл ещё не готов")
        filename = path.name.replace('"', "")
        self.send_response(200)
        self.send_header("Content-Type", _mime(path))
        self.send_header("Content-Length", str(path.stat().st_size))
        self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        with path.open("rb") as file:
            while chunk := file.read(1024 * 1024):
                self.wfile.write(chunk)

    def _download_track(self, run_id: str, filename: str) -> None:
        output_dir = _run_path(run_id)
        key = _load_key(output_dir)
        allowed = {item.get("filename") for item in key.get("tracks", {}).values()}
        if filename not in allowed or Path(filename).name != filename:
            raise FileNotFoundError("Трек не найден")
        self._send_download(output_dir / "tracks" / filename)

    def _start_render(self) -> None:
        parts = _request_multipart(self)
        values: dict[str, str] = {}
        uploads: list[tuple[str, bytes]] = []
        for name, filename, data in parts:
            if filename:
                path_name = filename.replace("\\", "/").rsplit("/", 1)[-1]
                if Path(path_name).suffix.lower() in MUSIC_EXTENSIONS:
                    uploads.append((path_name, data))
            elif name:
                values[name] = data.decode("utf-8", "replace")
        if not 3 <= len(uploads) <= 5:
            raise ValueError(f"Выберите папку с 3–5 WAV/FLAC файлами; найдено: {len(uploads)}")
        if values.get("hardpan_confirmed") != "true":
            raise ValueError("Подтвердите наличие джазового трека с жёсткой панорамой")
        listener = values.get("listener", "listener").strip() or "listener"
        seed = int(load_config(CONFIG_PATH).get("random_seed", 0))
        run_id = uuid.uuid4().hex
        output_dir = OUTPUT_ROOT / run_id
        source_dir = output_dir / "source"
        source_dir.mkdir(parents=True, exist_ok=True)
        for index, (filename, data) in enumerate(uploads, start=1):
            clean = re.sub(r"[^A-Za-zА-Яа-яЁё0-9._-]+", "_", filename).strip("._") or f"track{index}.wav"
            (source_dir / f"{index:02d}_{clean}").write_bytes(data)
        state = {
            "run_id": run_id,
            "status": "queued",
            "progress": 5,
            "message": "Исходники загружены, готовлю рендер",
            "listener": listener,
            "output_dir": output_dir,
            "tracks_total": 0,
        }
        with STATE_LOCK:
            RUNS[run_id] = state
        thread = threading.Thread(target=_render_worker, args=(run_id, listener, seed), daemon=True)
        thread.start()
        mobile_url = f"{_base_url(LAN_IP)}/mobile/{run_id}?{urlencode({'listener': listener})}"
        self._json(202, {"run_id": run_id, "mobile_url": mobile_url})

    def _save_scores(self, run_id: str, payload: dict) -> None:
        output_dir = _run_path(run_id)
        key = _load_key(output_dir)
        listener = str(payload.get("listener", "")).strip()
        if not listener:
            raise ValueError("Введите имя слушателя")
        rows = payload.get("scores")
        if not isinstance(rows, list):
            raise ValueError("Нет оценок для сохранения")
        music_tracks = {code: item for code, item in key.get("tracks", {}).items() if item.get("kind") == "music"}
        by_code: dict[str, dict] = {}
        for row in rows:
            if not isinstance(row, dict):
                raise ValueError("Некорректная строка оценок")
            code = str(row.get("code", ""))
            if code not in music_tracks or code in by_code:
                raise ValueError(f"Неизвестный или повторяющийся код: {code}")
            clean = {"fragment": music_tracks[code]["fragment_id"], "code": code}
            for field in REQUIRED_SCORE_FIELDS:
                value = row.get(field)
                if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= float(value) <= 100:
                    raise ValueError(f"Оценка {field} для {code} должна быть от 0 до 100")
                clean[field] = round(float(value))
            shift = row.get("shifted_center")
            if shift in (None, ""):
                clean["shifted_center"] = ""
            else:
                if isinstance(shift, bool) or not isinstance(shift, (int, float)) or not 0 <= float(shift) <= 100:
                    raise ValueError(f"Оценка shifted_center для {code} должна быть от 0 до 100")
                clean["shifted_center"] = str(round(float(shift)))
            clean["comment"] = str(row.get("comment", ""))[:500]
            by_code[code] = clean
        if set(by_code) != set(music_tracks):
            raise ValueError(f"Нужно оценить все треки: сохранено {len(by_code)} из {len(music_tracks)}")
        target = _score_file(output_dir, listener)
        with target.open("w", newline="", encoding="utf-8-sig") as file:
            writer = csv.DictWriter(file, fieldnames=SCORE_COLUMNS)
            writer.writeheader()
            for code in music_tracks:
                writer.writerow(by_code[code])
        self._json(200, {"message": f"Оценки сохранены в {target.name}", "filename": target.name})

    def _reveal(self, run_id: str) -> None:
        output_dir = _run_path(run_id)
        score_files = _saved_score_files(output_dir)
        if not score_files:
            raise ValueError("Сначала сохраните оценки со страницы слушателя")
        key_path = output_dir / "key.json"
        metrics_path = output_dir / "metrics.json"
        if not metrics_path.is_file():
            raise FileNotFoundError("Метрики ещё не готовы")
        result = summarize(
            key_path,
            score_files,
            metrics_path,
            output_dir / "score_report.md",
        )
        report = (output_dir / "score_report.md").read_text(encoding="utf-8")
        self._json(200, {"summary": result, "report": report, "score_files": [path.name for path in score_files]})

    def _create_sync(self) -> None:
        sync_id = uuid.uuid4().hex
        output_dir = SYNC_ROOT / sync_id
        output_dir.mkdir(parents=True, exist_ok=True)
        path = render_sync(output_dir, load_config(CONFIG_PATH))
        with STATE_LOCK:
            SYNC_JOBS[sync_id] = {
                "sync_id": sync_id,
                "status": "probe_ready",
                "progress": 10,
                "message": "Файл пробы готов к скачиванию",
                "output_dir": output_dir,
            }
        self._json(201, {"sync_id": sync_id, "filename": path.name, "download_url": f"/download-sync/{sync_id}"})

    def _upload_sync_recording(self) -> None:
        parts = _request_multipart(self)
        recording = next(((filename, data) for _, filename, data in parts if filename), None)
        if recording is None:
            raise ValueError("Выберите WAV или FLAC запись")
        filename, data = recording
        extension = Path(filename.replace("\\", "/")).suffix.lower()
        if extension not in MUSIC_EXTENSIONS:
            raise ValueError("Запись должна быть WAV или FLAC")
        sync_id = uuid.uuid4().hex
        output_dir = SYNC_ROOT / sync_id
        output_dir.mkdir(parents=True, exist_ok=True)
        recording_path = output_dir / f"recording{extension}"
        recording_path.write_bytes(data)
        with STATE_LOCK:
            SYNC_JOBS[sync_id] = {
                "sync_id": sync_id,
                "status": "queued",
                "progress": 5,
                "message": "Запись загружена",
                "output_dir": output_dir,
            }
        thread = threading.Thread(target=_sync_worker, args=(sync_id, recording_path), daemon=True)
        thread.start()
        self._json(202, {"sync_id": sync_id})


def main() -> int:
    parser = argparse.ArgumentParser(description="Локальный браузерный интерфейс пространственного аудиопрототипа")
    parser.add_argument("--port", type=int, default=0, help="порт интерфейса, по умолчанию выбирается автоматически")
    parser.add_argument("--no-browser", action="store_true", help="не открывать браузер автоматически")
    args = parser.parse_args()
    if not HTML_PATH.is_file():
        parser.error(f"Не найден интерфейс: {HTML_PATH}")
    if args.port and not 1024 <= args.port <= 65535:
        parser.error("Порт должен быть в диапазоне 1024–65535")
    _reload_saved_runs()
    global SERVER_PORT, LAN_IP
    LAN_IP = _local_ip()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), SpatialHandler)
    SERVER_PORT = int(server.server_address[1])
    local_url = _base_url("127.0.0.1")
    lan_url = _base_url(LAN_IP)
    print(f"Локальный интерфейс spatial-proto {PROTO_VERSION}: {local_url}")
    print(f"Ссылка для телефона в этой сети: {lan_url}")
    print("Для остановки нажмите Ctrl+C.")
    if not args.no_browser:
        webbrowser.open(local_url)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        print("\nОстанавливаю локальный интерфейс.")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
