#!/usr/bin/env python3
"""NOVRA VIDEO local server with persistent project storage."""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, quote
from urllib.request import Request, build_opener, HTTPRedirectHandler
from pathlib import Path
import ipaddress
import socket
import json
import mimetypes
import os
import re
import uuid
import tempfile

BASE = Path(__file__).resolve().parent
STATIC = BASE / "static"
DATA = BASE / "data"
VIDEOS = DATA / "videos"
LIBRARY = DATA / "library.json"
PORT = 8765
MAX_URL_LENGTH = 4096
MAX_METADATA_SIZE = 2 * 1024 * 1024

DATA.mkdir(exist_ok=True)
VIDEOS.mkdir(parents=True, exist_ok=True)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_id(value):
    try:
        return str(uuid.UUID(value))
    except (ValueError, TypeError, AttributeError):
        raise ValueError("معرّف الفيديو غير صالح.")


def read_library():
    if not LIBRARY.exists():
        return []
    try:
        data = json.loads(LIBRARY.read_text(encoding="utf-8"))
        return data if isinstance(data, list) else []
    except (OSError, json.JSONDecodeError):
        raise RuntimeError("ملف المكتبة تالف أو غير قابل للقراءة.")


def save_library(items):
    temp = LIBRARY.with_suffix(".json.tmp")
    temp.write_text(
        json.dumps(items, ensure_ascii=False, indent=2),
        encoding="utf-8"
    )
    os.replace(temp, LIBRARY)


def validate_remote_url(url):
    if not url or len(url) > MAX_URL_LENGTH:
        raise ValueError("الرابط غير صالح أو طويل جدًا.")
    p = urlparse(url)
    if p.scheme not in ("http", "https") or not p.hostname:
        raise ValueError("استخدم رابط HTTP أو HTTPS مباشر لفيديو.")
    if p.username or p.password:
        raise ValueError("الروابط التي تحتوي على بيانات تسجيل دخول غير مدعومة.")
    host = p.hostname.lower()
    if host in ("localhost", "localhost.localdomain"):
        raise ValueError("لا يمكن استيراد روابط من الجهاز نفسه.")
    try:
        addresses = {x[4][0] for x in socket.getaddrinfo(host, None)}
        for addr in addresses:
            ip = ipaddress.ip_address(addr.split("%")[0])
            if not ip.is_global:
                raise ValueError("الرابط يشير إلى عنوان شبكة خاص وغير مسموح.")
    except socket.gaierror:
        raise ValueError("تعذر العثور على عنوان الموقع.")
    return p


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(STATIC), **kwargs)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        super().end_headers()

    def send_json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > MAX_METADATA_SIZE:
            raise ValueError("حجم البيانات غير صالح.")
        raw = self.rfile.read(length)
        return json.loads(raw.decode("utf-8"))

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/proxy":
            self.handle_proxy(parse_qs(parsed.query).get("url", [""])[0])
            return
        if parsed.path == "/api/videos":
            self.send_json(read_library())
            return
        if parsed.path.startswith("/api/videos/") and parsed.path.endswith("/file"):
            video_id = parsed.path.split("/")[-2]
            self.handle_video_file(video_id)
            return
        if parsed.path == "/":
            self.path = "/index.html"
        return super().do_GET()

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/page-download":
            self.handle_page_download()
            return
        if parsed.path == "/api/videos":
            self.handle_upload(parse_qs(parsed.query))
            return
        self.send_error(404)

    def do_PUT(self):
        parsed = urlparse(self.path)
        parts = parsed.path.strip("/").split("/")
        if len(parts) == 4 and parts[:2] == ["api", "videos"] and parts[3] == "metadata":
            self.handle_update_metadata(parts[2])
            return
        self.send_error(404)

    def do_DELETE(self):
        parsed = urlparse(self.path)
        parts = parsed.path.strip("/").split("/")
        if len(parts) == 3 and parts[:2] == ["api", "videos"]:
            self.handle_delete(parts[2])
            return
        self.send_error(404)

    def handle_page_download(self):
        try:
            payload = self.read_json()
            if not isinstance(payload, dict):
                raise ValueError("بيانات الطلب غير صالحة.")

            url = payload.get("url", "")
            parsed = validate_remote_url(url)
            host = (parsed.hostname or "").lower().rstrip(".")

            blocked_hosts = (
                "youtube.com", "youtu.be", "youtube-nocookie.com",
                "xnxx.com"
            )
            if any(host == domain or host.endswith("." + domain)
                   for domain in blocked_hosts):
                raise ValueError("هذا الرابط غير متاح للتنزيل من NOVRA VIDEO.")

            import yt_dlp

            with tempfile.TemporaryDirectory(
                prefix="novra-page-", dir=str(DATA)
            ) as temp_dir:
                options = {
                    "format": "best",
                    "outtmpl": str(Path(temp_dir) / "%(id)s.%(ext)s"),
                    "noplaylist": True,
                    "quiet": True,
                    "no_warnings": True,
                    "restrictfilenames": True,
                    "socket_timeout": 25,
                    "retries": 1,
                    "fragment_retries": 1,
                    "cachedir": False,
                    "overwrites": True,
                }

                with yt_dlp.YoutubeDL(options) as downloader:
                    info = downloader.extract_info(url, download=True)

                files = [
                    item for item in Path(temp_dir).iterdir()
                    if item.is_file()
                    and not item.name.endswith((".part", ".ytdl"))
                ]

                if len(files) != 1:
                    raise ValueError(
                        "لم ينتج الموقع ملف فيديو واحدًا قابلًا للتنزيل."
                    )

                media_file = files[0]
                size = media_file.stat().st_size
                if size <= 0:
                    raise ValueError("الملف الذي تم تنزيله فارغ.")

                title = str((info or {}).get("title") or "NOVRA VIDEO")
                title = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", title)
                title = title.strip(" ._")[:100] or "video"

                filename = title + media_file.suffix.lower()
                content_type = (
                    mimetypes.guess_type(filename)[0]
                    or "application/octet-stream"
                )

                self.send_response(200)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(size))
                self.send_header(
                    "Content-Disposition",
                    "attachment; filename*=UTF-8''" + quote(filename, safe="")
                )
                self.end_headers()

                try:
                    with media_file.open("rb") as source:
                        while True:
                            chunk = source.read(256 * 1024)
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        except Exception as exc:
            self.send_json({"error": str(exc)[:300]}, 400)

    def handle_upload(self, query):
        filename = None
        try:
            video_id = validate_id(query.get("id", [""])[0])
            name = query.get("name", ["فيديو"])[0][:120]
            content_type = self.headers.get("Content-Type", "video/mp4").split(";")[0]
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0:
                raise ValueError("ملف الفيديو فارغ أو لم يصل حجمه.")
            filename = f"{video_id}.media"
            target = VIDEOS / filename
            if target.exists():
                raise ValueError("هذا الفيديو موجود بالفعل.")

            remaining = length
            with target.open("xb") as output:
                while remaining:
                    chunk = self.rfile.read(min(256 * 1024, remaining))
                    if not chunk:
                        raise ConnectionError("انقطع رفع الفيديو قبل اكتماله.")
                    output.write(chunk)
                    remaining -= len(chunk)

            item = {
                "id": video_id,
                "name": name,
                "filename": filename,
                "size": target.stat().st_size,
                "type": content_type,
                "duration": 0,
                "thumb": "",
                "created": __import__("time").time_ns() // 1_000_000,
                "lastPlayed": 0,
                "position": 0,
                "favorite": False
            }
            items = read_library()
            items.append(item)
            save_library(items)
            self.send_json(item, 201)
        except Exception as exc:
            if filename:
                try:
                    (VIDEOS / filename).unlink(missing_ok=True)
                except OSError:
                    pass
            self.send_json({"error": str(exc)[:300]}, 400)

    def handle_update_metadata(self, raw_id):
        try:
            video_id = validate_id(raw_id)
            updates = self.read_json()
            if not isinstance(updates, dict):
                raise ValueError("بيانات التحديث غير صالحة.")
            allowed = {"name", "duration", "thumb", "customThumb", "lastPlayed", "position", "favorite"}
            items = read_library()
            item = next((v for v in items if v.get("id") == video_id), None)
            if not item:
                self.send_json({"error": "الفيديو غير موجود."}, 404)
                return
            for key in allowed:
                if key in updates:
                    item[key] = updates[key]
            save_library(items)
            self.send_json(item)
        except Exception as exc:
            self.send_json({"error": str(exc)[:300]}, 400)

    def handle_delete(self, raw_id):
        try:
            video_id = validate_id(raw_id)
            items = read_library()
            item = next((v for v in items if v.get("id") == video_id), None)
            if not item:
                self.send_json({"error": "الفيديو غير موجود."}, 404)
                return
            filename = item.get("filename", "")
            if not re.fullmatch(r"[0-9a-f-]{36}\.media", filename):
                raise ValueError("اسم ملف الفيديو غير آمن.")
            (VIDEOS / filename).unlink(missing_ok=True)
            save_library([v for v in items if v.get("id") != video_id])
            self.send_json({"deleted": True})
        except Exception as exc:
            self.send_json({"error": str(exc)[:300]}, 400)

    def handle_video_file(self, raw_id):
        try:
            video_id = validate_id(raw_id)
            items = read_library()
            item = next((v for v in items if v.get("id") == video_id), None)
            if not item:
                self.send_error(404, "الفيديو غير موجود.")
                return
            filename = item.get("filename", "")
            if not re.fullmatch(r"[0-9a-f-]{36}\.media", filename):
                self.send_error(400, "اسم الملف غير صالح.")
                return
            path = VIDEOS / filename
            if not path.is_file():
                self.send_error(404, "ملف الفيديو غير موجود.")
                return

            size = path.stat().st_size
            start, end = 0, size - 1
            range_header = self.headers.get("Range")
            status = 200
            if range_header:
                match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header.strip())
                if not match:
                    self.send_error(416)
                    return
                a, b = match.groups()
                if not a and not b:
                    self.send_error(416)
                    return
                if not a:
                    count = int(b)
                    start = max(0, size - count)
                else:
                    start = int(a)
                    if b:
                        end = min(int(b), size - 1)
                if start >= size or end < start:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.end_headers()
                    return
                status = 206

            length = end - start + 1
            self.send_response(status)
            self.send_header("Content-Type", item.get("type") or "application/octet-stream")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(length))
            if status == 206:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            with path.open("rb") as f:
                f.seek(start)
                remaining = length
                while remaining:
                    chunk = f.read(min(256 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as exc:
            self.send_error(400, str(exc)[:200])

    def handle_proxy(self, url):
        try:
            validate_remote_url(url)
            req = Request(url, headers={
                "User-Agent": "NOVRA-VIDEO/1.0",
                "Accept": "video/*,audio/*,application/octet-stream;q=0.9,*/*;q=0.1"
            })
            response = build_opener(NoRedirect).open(req, timeout=25)
            ctype = response.headers.get("Content-Type", "application/octet-stream").split(";")[0].lower()
            if not (ctype.startswith(("video/", "audio/")) or ctype in ("application/octet-stream", "binary/octet-stream")):
                response.close()
                self.send_error(415, "الرابط لا يبدو أنه ملف وسائط مباشر مدعوم.")
                return
            length = response.headers.get("Content-Length")
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            if length:
                self.send_header("Content-Length", length)
            self.end_headers()
            while True:
                chunk = response.read(256 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)
            response.close()
        except Exception as exc:
            body = str(exc).replace("\n", " ")[:240].encode("utf-8")
            self.send_response(400)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def log_message(self, fmt, *args):
        print("[NOVRA]", fmt % args)


if __name__ == "__main__":
    print("\nNOVRA VIDEO is running")
    print(f"Open on this phone: http://127.0.0.1:{PORT}")
    print(f"Persistent videos: {VIDEOS}")
    print(f"Library metadata: {LIBRARY}")
    print("Keep this Termux session running. Press Ctrl+C to stop.\n")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
