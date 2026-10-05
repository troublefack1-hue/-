#!/usr/bin/env python3
"""
pc-remote relay.

Connects the PC agent (an outgoing connection from home, so a grey IP is
fine) with the phone. Runs on a VPS, or on the PC itself (direct/local modes,
and inside the PC Remote app).

  /            -> phone web client (static files from ../web)
  /ws/pc       -> agent; first text message must be {"t":"auth","token":...}
  /ws/phone    -> phone; same first-message auth
  /api/status  -> GET, Authorization: Bearer <secret>
  /api/wake    -> POST, same header: publish "wake" to the ntfy channel
  /api/pair    -> GET ?code=NNNNNN: one-time exchange of the pairing code
                  for the secret (code is set by the PC app, valid 5 min)
  /ca.crt      -> the relay's own CA certificate (direct mode)

Binary frames from the agent: first byte is the type, 0x01 = JPEG video
frame, 0x02 = audio chunk. Text frames are JSON events, forwarded as-is.

The secret never appears in a URL: it travels in a header or inside the
WebSocket, so it cannot leak through browser history or access logs.
"""
import asyncio
import hmac
import ipaddress
import json
import logging
import os
import secrets
import ssl
import sys
import time
from pathlib import Path

from aiohttp import ClientSession, WSMsgType, web

HERE = Path(__file__).resolve().parent
WEB_DIR = HERE.parent / "web"
CONFIG_PATH = Path(os.environ.get("PC_REMOTE_CONFIG", HERE / "config.json"))

FRAME_VIDEO, FRAME_AUDIO, FRAME_CAST, FRAME_CAST_AUDIO = 0x01, 0x02, 0x03, 0x04
MAX_PHONES = 4                      # simultaneous viewers
MAX_FRAME = 4 * 1024 * 1024         # bytes per binary frame from the agent
MAX_EVENT = 64 * 1024               # bytes per text event from a phone
MAX_CAST = 2 * 1024 * 1024          # bytes per screen-cast frame from a phone
AUTH_TIMEOUT = 5                    # seconds to send the auth message
LOCKOUT_ATTEMPTS, LOCKOUT_WINDOW = 10, 600
CORS = {"Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Headers": "Authorization",
        "Access-Control-Allow-Methods": "GET, POST"}

log = logging.getLogger("relay")


def load_config() -> dict:
    if not CONFIG_PATH.exists():
        sys.exit(f"config not found: {CONFIG_PATH} (copy config.example.json)")
    cfg = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    if len(cfg.get("secret", "")) < 16:
        sys.exit("config: 'secret' must be at least 16 characters")
    cfg.setdefault("host", "127.0.0.1")
    cfg.setdefault("port", 8787)
    cfg.setdefault("ntfy_wake_url", "")
    cfg.setdefault("tls_host", "0.0.0.0")
    cfg.setdefault("tls_port", 0)
    cfg.setdefault("tls_cert", "")
    cfg.setdefault("tls_key", "")
    cfg.setdefault("ca_cert", "")
    # file transfer: phone uploads land in upload_dir; share_dirs can be browsed/downloaded
    cfg.setdefault("upload_dir", "")
    cfg.setdefault("share_dirs", [])
    return cfg


def client_ip(request: web.Request) -> str:
    """Real client address. X-Forwarded-For is honoured only when the direct
    peer is a loopback proxy (Caddy on the VPS), never from the internet."""
    peer = request.remote or "?"
    try:
        if ipaddress.ip_address(peer).is_loopback:
            fwd = request.headers.get("X-Forwarded-For", "")
            if fwd:
                return fwd.split(",")[0].strip()
    except ValueError:
        pass
    return peer


class Lockout:
    """Per-IP counter of failed auth attempts with a bounded memory footprint."""

    def __init__(self):
        self.failed: dict[str, list[float]] = {}

    def _prune(self, now: float):
        for ip in [ip for ip, ts in self.failed.items() if not ts or now - ts[-1] > LOCKOUT_WINDOW]:
            del self.failed[ip]
        if len(self.failed) > 10000:  # someone is spraying from many addresses
            for ip in list(self.failed)[: len(self.failed) - 5000]:
                del self.failed[ip]

    def blocked(self, ip: str) -> bool:
        now = time.time()
        self._prune(now)
        ts = [t for t in self.failed.get(ip, []) if now - t < LOCKOUT_WINDOW]
        self.failed[ip] = ts
        return len(ts) >= LOCKOUT_ATTEMPTS

    def fail(self, ip: str):
        self.failed.setdefault(ip, []).append(time.time())

    def ok(self, ip: str):
        self.failed.pop(ip, None)


class Hub:
    """Holds the one PC socket and the phone sockets."""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.pc: web.WebSocketResponse | None = None
        self.pc_since: float = 0
        self.phones: set[web.WebSocketResponse] = set()
        self.last_frame: bytes | None = None
        self.lockout = Lockout()
        # pairing: the PC app sets a 6-digit code that is valid for 5 minutes;
        # the phone app exchanges it once for the secret
        self.pair_code: str | None = None
        self.pair_until: float = 0
        self.on_paired = None  # callback(remote_ip) for the PC app's UI
        self.on_cast = None    # callback(kind, data): phone screen (0x03 jpeg) / sound (0x04 pcm) -> PC app; (0, None) = stopped
        self.on_phones = None  # callback(count, names) for the PC app's UI
        self.phone_names: dict = {}
        self.events: list = []  # last 200 events: (time, text)

    def log_event(self, text: str):
        self.events.append((time.time(), text))
        del self.events[:-200]

    # --- auth -----------------------------------------------------------
    def token_ok(self, token: str) -> bool:
        return hmac.compare_digest(token.encode(), self.cfg["secret"].encode())

    def check_header(self, request: web.Request) -> bool:
        ip = client_ip(request)
        if self.lockout.blocked(ip):
            return False
        auth = request.headers.get("Authorization", "")
        token = auth[7:] if auth.startswith("Bearer ") else ""
        if self.token_ok(token):
            self.lockout.ok(ip)
            return True
        self.lockout.fail(ip)
        log.warning("bad token from %s", ip)
        return False

    async def ws_auth(self, ws: web.WebSocketResponse, ip: str) -> bool:
        """First message must be {"t":"auth","token":...} within AUTH_TIMEOUT."""
        if self.lockout.blocked(ip):
            await ws.close(code=4003, message=b"locked")
            return False
        try:
            msg = await asyncio.wait_for(ws.receive(), AUTH_TIMEOUT)
            ev = json.loads(msg.data) if msg.type == WSMsgType.TEXT else {}
            if ev.get("t") == "auth" and self.token_ok(str(ev.get("token", ""))):
                self.lockout.ok(ip)
                return True
        except (asyncio.TimeoutError, ValueError, TypeError, AttributeError):
            pass
        self.lockout.fail(ip)
        log.warning("bad ws auth from %s", ip)
        await ws.close(code=4003, message=b"auth")
        return False

    # --- pairing ----------------------------------------------------------
    def start_pairing(self) -> str:
        self.pair_code = f"{secrets.randbelow(10**6):06d}"
        self.pair_until = time.time() + 300
        return self.pair_code

    async def pair_handler(self, request: web.Request):
        ip = client_ip(request)
        if self.lockout.blocked(ip):
            raise web.HTTPForbidden()
        code = request.query.get("code", "")
        ok = (self.pair_code is not None and time.time() < self.pair_until
              and hmac.compare_digest(code, self.pair_code))
        if not ok:
            self.lockout.fail(ip)
            log.warning("bad pairing code from %s", ip)
            raise web.HTTPForbidden()
        self.pair_code = None  # single use
        log.info("phone paired from %s", ip)
        self.log_event(f"телефон привязан ({ip})")
        if self.on_paired:
            self.on_paired(ip)
        return web.json_response({"secret": self.cfg["secret"]})

    # --- status broadcast ----------------------------------------------
    def status(self) -> dict:
        return {"t": "status", "pc_online": self.pc is not None,
                "pc_since": self.pc_since, "phones": len(self.phones)}

    async def broadcast_phones(self, data, binary=False):
        dead = []
        for ws in self.phones:
            try:
                if binary:
                    await ws.send_bytes(data)
                else:
                    await ws.send_str(data)
            except Exception:  # noqa: BLE001
                dead.append(ws)
        for ws in dead:
            self.phones.discard(ws)

    async def send_pc(self, text: str):
        if self.pc is not None:
            try:
                await self.pc.send_str(text)
            except Exception:  # noqa: BLE001
                pass

    def phones_changed(self):
        if self.on_phones:
            names = [self.phone_names.get(w, "") for w in self.phones]
            self.on_phones(len(self.phones), [n for n in names if n])

    async def tell_pc_viewers(self):
        await self.send_pc(json.dumps({"t": "viewers", "n": len(self.phones)}))

    # --- /ws/pc -----------------------------------------------------------
    async def pc_handler(self, request: web.Request):
        ws = web.WebSocketResponse(heartbeat=20, max_msg_size=MAX_FRAME)
        await ws.prepare(request)
        ip = client_ip(request)
        if not await self.ws_auth(ws, ip):
            return ws
        old, self.pc = self.pc, ws
        if old is not None:
            await old.close(code=4000, message=b"replaced")
        self.pc_since = time.time()
        log.info("pc connected from %s", ip)
        self.log_event("ПК подключился")
        await self.broadcast_phones(json.dumps(self.status()))
        await self.tell_pc_viewers()
        try:
            async for msg in ws:
                if msg.type == WSMsgType.BINARY:
                    if msg.data and msg.data[0] == FRAME_VIDEO:
                        self.last_frame = msg.data
                    await self.broadcast_phones(msg.data, binary=True)
                elif msg.type == WSMsgType.TEXT:
                    await self.broadcast_phones(msg.data)
                elif msg.type == WSMsgType.ERROR:
                    break
        finally:
            if self.pc is ws:
                self.pc = None
                self.last_frame = None
                await self.broadcast_phones(json.dumps(self.status()))
                self.log_event("ПК отключился")
            log.info("pc disconnected")
        return ws

    # --- /ws/phone ------------------------------------------------------
    async def phone_handler(self, request: web.Request):
        ws = web.WebSocketResponse(heartbeat=20, max_msg_size=MAX_CAST)
        await ws.prepare(request)
        ip = client_ip(request)
        if not await self.ws_auth(ws, ip):
            return ws
        if len(self.phones) >= MAX_PHONES:
            await ws.close(code=4004, message=b"too many viewers")
            return ws
        self.phones.add(ws)
        log.info("phone connected from %s (%d)", ip, len(self.phones))
        self.log_event(f"телефон подключился ({ip})")
        self.phones_changed()
        await ws.send_str(json.dumps(self.status()))
        await self.tell_pc_viewers()
        if self.last_frame:
            await ws.send_bytes(self.last_frame)
        casting = False
        try:
            async for msg in ws:
                if msg.type == WSMsgType.BINARY:
                    if msg.data and msg.data[0] in (FRAME_CAST, FRAME_CAST_AUDIO) and self.on_cast:
                        casting = True
                        self.on_cast(msg.data[0], msg.data[1:])
                    continue
                if msg.type != WSMsgType.TEXT:
                    if msg.type == WSMsgType.ERROR:
                        break
                    continue
                if len(msg.data) > MAX_EVENT:
                    break
                if msg.data.startswith('{"t":"hello_phone"'):
                    try:
                        self.phone_names[ws] = str(json.loads(msg.data).get("model", ""))[:40]
                    except ValueError:
                        pass
                    self.phones_changed()
                    continue
                if msg.data.startswith('{"t":"cmd"') or msg.data.startswith('{"t":"term_open"'):
                    try:
                        ev = json.loads(msg.data)
                        self.log_event(f"{ip}: {ev.get('t')} {ev.get('cmd') or ev.get('kind') or ''}")
                    except ValueError:
                        pass
                if msg.data.startswith('{"t":"cast_stop"'):
                    casting = False
                    if self.on_cast:
                        self.on_cast(0, None)
                    continue
                if msg.data.startswith('{"t":"ping"') or msg.data.startswith('{"t": "ping"'):
                    await ws.send_str(json.dumps({"t": "pong", "ts": time.time(), "pc_online": self.pc is not None}))
                    continue
                await self.send_pc(msg.data)  # everything else goes to the agent
        finally:
            self.phones.discard(ws)
            self.phone_names.pop(ws, None)
            self.log_event(f"телефон отключился ({ip})")
            if casting and self.on_cast:
                self.on_cast(0, None)
            self.phones_changed()
            await self.tell_pc_viewers()
            log.info("phone disconnected (%d left)", len(self.phones))
        return ws

    async def ring_phones(self):
        self.log_event("найти телефон")
        """PC app -> every connected phone: make noise (find my phone)."""
        await self.broadcast_phones(json.dumps({"t": "ring"}))

    # --- HTTP API ----------------------------------------------------------
    async def wake_handler(self, request: web.Request):
        if not self.check_header(request):
            raise web.HTTPForbidden(headers=CORS)
        url = self.cfg.get("ntfy_wake_url")
        if not url:
            return web.json_response({"ok": False, "error": "ntfy_wake_url not set"}, headers=CORS)
        try:
            async with ClientSession() as s:
                async with s.post(url, data=b"wake", timeout=15) as r:
                    ok = r.status == 200
        except Exception as e:  # noqa: BLE001
            return web.json_response({"ok": False, "error": str(e)}, headers=CORS)
        return web.json_response({"ok": ok}, headers=CORS)

    async def status_handler(self, request: web.Request):
        if not self.check_header(request):
            raise web.HTTPForbidden(headers=CORS)
        return web.json_response(self.status(), headers=CORS)

    async def options_handler(self, _request):
        return web.Response(headers=CORS)

    async def events_handler(self, request: web.Request):
        if not self.check_header(request):
            raise web.HTTPForbidden(headers=CORS)
        return web.json_response([{"ts": t, "text": x} for t, x in reversed(self.events)], headers=CORS)

    # --- file transfer (only when the relay runs on the PC: upload_dir / share_dirs set)
    def _safe_path(self, raw: str) -> Path | None:
        """Resolve a user path and make sure it stays inside one of share_dirs."""
        roots = [Path(d).resolve() for d in self.cfg["share_dirs"] if d]
        try:
            p = Path(raw).resolve()
        except (OSError, ValueError):
            return None
        for r in roots:
            if p == r or r in p.parents:
                return p
        return None

    async def upload_handler(self, request: web.Request):
        if not self.check_header(request):
            raise web.HTTPForbidden(headers=CORS)
        if not self.cfg["upload_dir"]:
            return web.json_response({"ok": False, "error": "upload_dir not set"}, headers=CORS)
        name = Path(request.headers.get("X-Filename", "file")).name or "file"
        name = "".join(c for c in name if c not in '<>:"/\\|?*')[:120] or "file"
        dest_dir = Path(self.cfg["upload_dir"])
        # optional: drop the file into the folder currently open on the phone
        want = request.headers.get("X-Dir", "")
        if want:
            d = self._safe_path(want)
            if d is not None and d.is_dir():
                dest_dir = d
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest = dest_dir / name
        n = 1
        while dest.exists():
            dest = dest_dir / f"{Path(name).stem} ({n}){Path(name).suffix}"
            n += 1
        size = 0
        with open(dest, "wb") as f:
            async for chunk in request.content.iter_chunked(1 << 16):
                size += len(chunk)
                if size > 2 * 1024 ** 3:
                    f.close()
                    dest.unlink(missing_ok=True)
                    return web.json_response({"ok": False, "error": "file too large"}, headers=CORS)
                f.write(chunk)
        self.log_event(f"файл с телефона: {dest.name} ({size // 1024} КБ)")
        return web.json_response({"ok": True, "name": dest.name, "size": size}, headers=CORS)

    async def files_handler(self, request: web.Request):
        if not self.check_header(request):
            raise web.HTTPForbidden(headers=CORS)
        raw = request.query.get("path", "")
        if not raw:
            roots = [{"name": Path(d).name or d, "path": d, "dir": True} for d in self.cfg["share_dirs"] if d]
            return web.json_response({"path": "", "items": roots}, headers=CORS)
        p = self._safe_path(raw)
        if p is None or not p.is_dir():
            raise web.HTTPForbidden(headers=CORS)
        items = []
        try:
            for child in sorted(p.iterdir(), key=lambda c: (not c.is_dir(), c.name.lower()))[:500]:
                try:
                    st = child.stat()
                except OSError:
                    continue
                items.append({"name": child.name, "path": str(child), "dir": child.is_dir(),
                              "size": st.st_size, "mtime": st.st_mtime})
        except OSError:
            raise web.HTTPForbidden(headers=CORS)
        return web.json_response({"path": str(p), "items": items}, headers=CORS)

    async def thumb_handler(self, request: web.Request):
        """Small JPEG preview of an image for the files grid (needs Pillow)."""
        if not self.check_header(request):
            raise web.HTTPForbidden(headers=CORS)
        p = self._safe_path(request.query.get("path", ""))
        if p is None or not p.is_file():
            raise web.HTTPForbidden(headers=CORS)
        try:
            from PIL import Image, ImageOps
            import io
            loop = asyncio.get_running_loop()

            def make():
                with Image.open(p) as im:
                    im = ImageOps.exif_transpose(im)
                    im.thumbnail((240, 240))
                    buf = io.BytesIO()
                    im.convert("RGB").save(buf, "JPEG", quality=70)
                    return buf.getvalue()
            data = await loop.run_in_executor(None, make)
        except Exception:  # noqa: BLE001
            raise web.HTTPNotFound(headers=CORS)
        return web.Response(body=data, content_type="image/jpeg", headers={**CORS, "Cache-Control": "private, max-age=3600"})

    async def file_handler(self, request: web.Request):
        if not self.check_header(request):
            raise web.HTTPForbidden(headers=CORS)
        p = self._safe_path(request.query.get("path", ""))
        if p is None or not p.is_file():
            raise web.HTTPForbidden(headers=CORS)
        self.log_event(f"файл на телефон: {p.name}")
        disp = "inline" if request.query.get("inline") else "attachment"
        return web.FileResponse(p, headers={**CORS, "Content-Disposition": f'{disp}; filename="{p.name}"'})


async def index(_request):
    return web.FileResponse(WEB_DIR / "index.html")


def make_app(cfg: dict) -> web.Application:
    hub = Hub(cfg)
    app = web.Application(client_max_size=2 * 1024 ** 3)
    app.router.add_get("/", index)
    app.router.add_get("/ws/pc", hub.pc_handler)
    app.router.add_get("/ws/phone", hub.phone_handler)
    app.router.add_post("/api/wake", hub.wake_handler)
    app.router.add_get("/api/status", hub.status_handler)
    app.router.add_get("/api/pair", hub.pair_handler)
    app.router.add_get("/api/events", hub.events_handler)
    app.router.add_post("/api/upload", hub.upload_handler)
    app.router.add_get("/api/files", hub.files_handler)
    app.router.add_get("/api/file", hub.file_handler)
    app.router.add_get("/api/thumb", hub.thumb_handler)
    app.router.add_route("OPTIONS", "/api/{tail:.*}", hub.options_handler)
    app.router.add_static("/static", WEB_DIR)
    if cfg["ca_cert"]:
        # direct mode: the phone's browser downloads and installs this once
        async def ca(_request):
            return web.FileResponse(cfg["ca_cert"], headers={
                "Content-Type": "application/x-x509-ca-cert",
                "Content-Disposition": 'attachment; filename="pc-remote-ca.crt"'})
        app.router.add_get("/ca.crt", ca)
    app["hub"] = hub
    return app


async def serve(cfg: dict, app: web.Application | None = None):
    runner = web.AppRunner(app or make_app(cfg), access_log=None)
    await runner.setup()
    await web.TCPSite(runner, cfg["host"], cfg["port"]).start()
    log.info("listening on http://%s:%s", cfg["host"], cfg["port"])
    if cfg["tls_port"]:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(cfg["tls_cert"], cfg["tls_key"])
        await web.TCPSite(runner, cfg["tls_host"], cfg["tls_port"], ssl_context=ctx).start()
        log.info("listening on https://%s:%s", cfg["tls_host"], cfg["tls_port"])
    while True:
        await asyncio.sleep(3600)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    cfg = load_config()
    try:
        asyncio.run(serve(cfg))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
