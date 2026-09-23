#!/usr/bin/env python3
"""Local grok-build sidecar for the ZombiePig Paper plugin.

Binds 127.0.0.1 only. Uses wilder0p's grok.com OAuth session so inference
counts against grok-build weekly credits. When remaining weekly credits
are below the floor, /chat returns source=canned_gate and the plugin
uses canned lines.

Does not print tokens or player text.
"""
from __future__ import annotations

import fcntl
import json
import os
import ssl
import struct
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BIND = os.environ.get("ZOMBIEPIG_BIND", "127.0.0.1")
PORT = int(os.environ.get("ZOMBIEPIG_PORT", "18787"))
AUTH_JSON = Path(os.environ.get("ZOMBIEPIG_AUTH_JSON", os.path.expanduser("~/.grok/auth.json")))
MODEL = os.environ.get("ZOMBIEPIG_MODEL", "grok-4-fast-non-reasoning")
MIN_REMAINING = float(os.environ.get("ZOMBIEPIG_MIN_REMAINING", "25"))
TOKEN_URL = "https://auth.x.ai/oauth2/token"
API_BASE = "https://api.x.ai/v1"
CREDITS_URL = "https://grok.com/grok_api_v2.GrokBuildBilling/GetGrokCreditsConfig"

CTX = ssl.create_default_context()
LOCK = threading.Lock()
CACHE = {"credits": None, "credits_at": 0.0}


def log(msg: str) -> None:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    print(f"[zombiepigd] {ts} {msg}", flush=True)


def _http(method: str, url: str, headers: dict, data: bytes | None = None, timeout: int = 20):
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, context=CTX, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def _decode_varint(buf: bytes, i: int) -> tuple[int, int]:
    shift = 0
    n = 0
    while True:
        if i >= len(buf):
            raise ValueError("eof")
        x = buf[i]
        i += 1
        n |= (x & 0x7F) << shift
        if not (x & 0x80):
            return n, i
        shift += 7
        if shift > 70:
            raise ValueError("varint")


def _walk(buf: bytes) -> dict[int, list]:
    i = 0
    out: dict[int, list] = {}
    while i < len(buf):
        key, i = _decode_varint(buf, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            val, i = _decode_varint(buf, i)
            out.setdefault(field, []).append(("varint", val))
        elif wire == 1:
            if i + 8 > len(buf):
                break
            val = struct.unpack_from("<d", buf, i)[0]
            i += 8
            out.setdefault(field, []).append(("double", val))
        elif wire == 2:
            ln, i = _decode_varint(buf, i)
            chunk = buf[i : i + ln]
            i += ln
            out.setdefault(field, []).append(("bytes", chunk))
        elif wire == 5:
            if i + 4 > len(buf):
                break
            val = struct.unpack_from("<f", buf, i)[0]
            i += 4
            out.setdefault(field, []).append(("float", val))
        else:
            break
    return out


def _timestamp(chunk: bytes) -> datetime | None:
    fields = _walk(chunk)
    sec = 0
    nano = 0
    if 1 in fields and fields[1][0][0] == "varint":
        sec = fields[1][0][1]
    if 2 in fields and fields[2][0][0] == "varint":
        nano = fields[2][0][1]
    if sec <= 0:
        return None
    return datetime.fromtimestamp(sec + nano / 1e9, tz=timezone.utc)


def parse_credits_grpc(raw: bytes) -> dict:
    i = 0
    used = None
    period_end = None
    period_start = None
    while i + 5 <= len(raw):
        flag = raw[i]
        i += 1
        ln = int.from_bytes(raw[i : i + 4], "big")
        i += 4
        payload = raw[i : i + ln]
        i += ln
        if flag & 0x80:
            continue
        top = _walk(payload)
        if 1 not in top or top[1][0][0] != "bytes":
            continue
        cfg = _walk(top[1][0][1])
        if 1 in cfg and cfg[1][0][0] == "float":
            used = float(cfg[1][0][1])
        if 4 in cfg and cfg[4][0][0] == "bytes":
            period_start = _timestamp(cfg[4][0][1])
        if 5 in cfg and cfg[5][0][0] == "bytes":
            period_end = _timestamp(cfg[5][0][1])
    if used is None:
        raise ValueError("no usage percent in credits payload")
    remaining = max(0.0, min(100.0, 100.0 - used))
    return {
        "used_percent": round(used, 2),
        "remaining_percent": round(remaining, 2),
        "period_start": period_start.isoformat() if period_start else None,
        "period_end": period_end.isoformat() if period_end else None,
    }


def _load_auth() -> dict:
    data = json.loads(AUTH_JSON.read_text())
    if not data:
        raise RuntimeError("empty auth.json")
    return next(iter(data.values()))


def _write_auth(entry: dict) -> None:
    raw = AUTH_JSON.read_text()
    data = json.loads(raw)
    key = next(iter(data.keys()))
    data[key] = entry
    tmp = AUTH_JSON.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n")
    os.chmod(tmp, 0o600)
    tmp.replace(AUTH_JSON)


def _expired(entry: dict, skew: int = 45) -> bool:
    exp = entry.get("expires_at") or ""
    try:
        dt = datetime.fromisoformat(exp.replace("Z", "+00:00"))
    except Exception:
        return True
    return dt.timestamp() < time.time() + skew


def access_token() -> str:
    with LOCK:
        with AUTH_JSON.open("r+") as fh:
            fcntl.flock(fh, fcntl.LOCK_EX)
            fh.seek(0)
            data = json.loads(fh.read())
            key = next(iter(data.keys()))
            entry = data[key]
            if not _expired(entry):
                return entry["key"]
            log("refreshing grok oauth token")
            body = urllib.parse.urlencode(
                {
                    "grant_type": "refresh_token",
                    "refresh_token": entry["refresh_token"],
                    "client_id": entry["oidc_client_id"],
                }
            ).encode()
            status, _hdrs, raw = _http(
                "POST",
                TOKEN_URL,
                {"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
                body,
            )
            if status != 200:
                log(f"token refresh failed status={status}")
                if entry.get("key"):
                    return entry["key"]
                raise RuntimeError("token refresh failed")
            tok = json.loads(raw.decode())
            entry["key"] = tok["access_token"]
            if tok.get("refresh_token"):
                entry["refresh_token"] = tok["refresh_token"]
            expires_in = int(tok.get("expires_in") or 3600)
            entry["expires_at"] = datetime.fromtimestamp(
                time.time() + expires_in, tz=timezone.utc
            ).isoformat()
            data[key] = entry
            fh.seek(0)
            fh.truncate()
            fh.write(json.dumps(data, indent=2) + "\n")
            fh.flush()
            os.fsync(fh.fileno())
            return entry["key"]


def cli_headers(token: str, extra: dict | None = None) -> dict:
    h = {
        "Authorization": f"Bearer {token}",
        "X-XAI-Token-Auth": "xai-grok-cli",
        "User-Agent": "xai-grok-cli",
        "Accept": "application/json",
    }
    if extra:
        h.update(extra)
    return h


def fetch_credits(force: bool = False) -> dict:
    now = time.time()
    cached = CACHE["credits"]
    if not force and cached and now - CACHE["credits_at"] < 60:
        return cached
    token = access_token()
    status, _hdrs, raw = _http(
        "POST",
        CREDITS_URL,
        cli_headers(
            token,
            {
                "Accept": "application/grpc-web+proto",
                "Content-Type": "application/grpc-web+proto",
                "X-Grpc-Web": "1",
                "Origin": "https://grok.com",
                "Referer": "https://grok.com/?_s=usage",
            },
        ),
        b"\x00\x00\x00\x00\x00",
        timeout=15,
    )
    if status != 200:
        raise RuntimeError(f"credits status {status}")
    info = parse_credits_grpc(raw)
    info["canned"] = info["remaining_percent"] < MIN_REMAINING
    CACHE["credits"] = info
    CACHE["credits_at"] = now
    return info


def chat_completion(system: str, messages: list[dict], max_tokens: int) -> str:
    token = access_token()
    payload = {
        "model": MODEL,
        "messages": [{"role": "system", "content": system}] + messages,
        "max_tokens": max(16, min(max_tokens, 180)),
        "temperature": 0.7,
    }
    status, _hdrs, raw = _http(
        "POST",
        f"{API_BASE}/chat/completions",
        cli_headers(token, {"Content-Type": "application/json"}),
        json.dumps(payload).encode(),
        timeout=20,
    )
    if status != 200:
        raise RuntimeError(f"chat status {status}")
    data = json.loads(raw.decode())
    text = data["choices"][0]["message"]["content"]
    if not isinstance(text, str) or not text.strip():
        raise RuntimeError("empty model text")
    return text.strip()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        log(fmt % args)

    def _json(self, code: int, obj: dict) -> None:
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path in ("/health", "/"):
            self._json(200, {"ok": True, "model": MODEL, "min_remaining_percent": MIN_REMAINING})
            return
        if self.path == "/credits":
            try:
                info = fetch_credits(force=True)
                self._json(200, {"ok": True, **info})
            except Exception as e:
                log(f"credits error: {type(e).__name__}")
                self._json(503, {"ok": False, "error": "credits_unavailable", "canned": True})
            return
        self._json(404, {"ok": False, "error": "not_found"})

    def do_POST(self) -> None:
        if self.path != "/chat":
            self._json(404, {"ok": False, "error": "not_found"})
            return
        try:
            n = int(self.headers.get("Content-Length") or "0")
        except ValueError:
            n = 0
        raw = self.rfile.read(max(0, min(n, 80_000)))
        try:
            body = json.loads(raw.decode() or "{}")
        except json.JSONDecodeError:
            self._json(400, {"ok": False, "error": "bad_json"})
            return
        try:
            credits = fetch_credits()
        except Exception as e:
            log(f"credits error before chat: {type(e).__name__}")
            self._json(
                200,
                {
                    "ok": True,
                    "source": "canned_gate",
                    "reason": "credits_unavailable",
                    "text": "",
                    "canned": True,
                },
            )
            return
        if credits.get("canned"):
            self._json(
                200,
                {
                    "ok": True,
                    "source": "canned_gate",
                    "reason": "credits_low",
                    "text": "",
                    **credits,
                },
            )
            return
        system = body.get("system") or ""
        messages = body.get("messages") or []
        if not system or not isinstance(messages, list) or not messages:
            self._json(400, {"ok": False, "error": "missing_messages"})
            return
        clean = []
        for m in messages[-12:]:
            if not isinstance(m, dict):
                continue
            role = m.get("role")
            content = m.get("content")
            if role in ("user", "assistant") and isinstance(content, str) and content.strip():
                clean.append({"role": role, "content": content[:1500]})
        if not clean:
            self._json(400, {"ok": False, "error": "missing_messages"})
            return
        max_tokens = int(body.get("max_tokens") or 120)
        try:
            text = chat_completion(system, clean, max_tokens)
        except Exception as e:
            log(f"chat error: {type(e).__name__}")
            self._json(
                200,
                {
                    "ok": True,
                    "source": "canned_gate",
                    "reason": "chat_failed",
                    "text": "",
                    **credits,
                },
            )
            return
        try:
            credits = fetch_credits(force=True)
        except Exception:
            pass
        self._json(
            200,
            {
                "ok": True,
                "source": "grok",
                "text": text,
                **credits,
            },
        )


def main() -> None:
    if not AUTH_JSON.is_file():
        raise SystemExit(f"missing {AUTH_JSON}")
    httpd = ThreadingHTTPServer((BIND, PORT), Handler)
    log(f"listening on {BIND}:{PORT} model={MODEL} min_remaining={MIN_REMAINING}")
    try:
        info = fetch_credits(force=True)
        log(
            f"credits remaining={info['remaining_percent']}% used={info['used_percent']}% canned={info['canned']}"
        )
    except Exception as e:
        log(f"startup credits check failed: {type(e).__name__} (will retry on demand)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    httpd.server_close()


if __name__ == "__main__":
    main()
