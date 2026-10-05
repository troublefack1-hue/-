#!/usr/bin/env python3
"""
pc-remote relay.

Runs on the VPS. Connects the PC agent (outgoing connection from home,
so a grey IP is fine) with the phone (a browser page).

  /            -> phone web client (static files from ../web)
  /ws/pc       -> agent connects here (binary frames = JPEG, text = JSON)
  /ws/phone    -> phone connects here
  /api/wake    -> POST: publish "wake" to the ntfy channel (optional)

Auth: a single shared secret (config.json -> "secret"). Both the agent and
the phone send it as the "token" query parameter. Compared in constant time.
"""
import asyncio
import hmac
import json
import logging
import os
import ssl
import sys
import time
from pathlib import Path

from aiohttp import ClientSession, WSMsgType, web

HERE = Path(__file__).resolve().parent
WEB_DIR = HERE.parent / "web"
CONFIG_PATH = Path(os.environ.get("PC_REMOTE_CONFIG", HERE / "config.json"))

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
    # optional second listener with our own certificate (direct mode)
    cfg.setdefault("tls_host", "0.0.0.0")
    cfg.setdefault("tls_port", 0)
    cfg.setdefault("tls_cert", "")
    cfg.setdefault("tls_key", "")
    cfg.setdefault("ca_cert", "")
    return cfg


class Hub:
    """Holds the one PC socket and any number of phone sockets."""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.pc: web.WebSocketResponse | None = None
        self.pc_since: float = 0
        self.phones: set[web.WebSocketResponse] = set()
        self.last_frame: bytes | None = None
        self.failed_auth: dict[str, list[float]] = {}

    # --- auth -----------------------------------------------------------
    def check_token(self, request: web.Request) -> bool:
        ip = request.remote or "?"
        now = time.time()
        attempts = [t for t in self.failed_auth.get(ip, []) if now - t < 600]
        if len(attempts) >= 10:
            self.failed_auth[ip] = attempts
            return False
        token = request.query.get("token", "")
        ok = hmac.compare_digest(token.encode(), self.cfg["secret"].encode())
        if not ok:
            attempts.append(now)
            self.failed_auth[ip] = attempts
            log.warning("bad token from %s", ip)
        return ok

    # --- status broadcast ----------------------------------------------
    def status(self) -> dict:
        return {
            "t": "status",
            "pc_online": self.pc is not None,
            "pc_since": self.pc_since,
            "phones": len(self.phones),
        }

    async def broadcast_phones(self, data, binary=False):
        dead = []
        for ws in self.phones:
            try:
                if binary:
                    await ws.send_bytes(data)
                else:
                    await ws.send_str(data)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.phones.discard(ws)

    async def send_pc(self, text: str):
        if self.pc is not None:
            try:
                await self.pc.send_str(text)
            except Exception:
                pass

    # --- /ws/pc -----------------------------------------------------------
    async def pc_handler(self, request: web.Request):
        if not self.check_token(request):
            raise web.HTTPForbidden()
        ws = web.WebSocketResponse(heartbeat=20, max_msg_size=16 * 1024 * 1024)
        await ws.prepare(request)
        old, self.pc = self.pc, ws
        if old is not None:
            await old.close(code=4000, message=b"replaced")
        self.pc_since = time.time()
        log.info("pc connected from %s", request.remote)
        await self.broadcast_phones(json.dumps(self.status()))
        # tell the agent whether anyone is watching right now
        await ws.send_str(json.dumps({"t": "viewers", "n": len(self.phones)}))
        try:
            async for msg in ws:
                if msg.type == WSMsgType.BINARY:
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
            log.info("pc disconnected")
        return ws

    # --- /ws/phone ------------------------------------------------------
    async def phone_handler(self, request: web.Request):
        if not self.check_token(request):
            raise web.HTTPForbidden()
        ws = web.WebSocketResponse(heartbeat=20)
        await ws.prepare(request)
        self.phones.add(ws)
        log.info("phone connected from %s (%d)", request.remote, len(self.phones))
        await ws.send_str(json.dumps(self.status()))
        await self.send_pc(json.dumps({"t": "viewers", "n": len(self.phones)}))
        if self.last_frame:
            await ws.send_bytes(self.last_frame)
        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    # everything from the phone goes straight to the agent
                    await self.send_pc(msg.data)
                elif msg.type == WSMsgType.ERROR:
                    break
        finally:
            self.phones.discard(ws)
            await self.send_pc(json.dumps({"t": "viewers", "n": len(self.phones)}))
            log.info("phone disconnected (%d left)", len(self.phones))
        return ws

    # --- /api/wake ------------------------------------------------------
    async def wake_handler(self, request: web.Request):
        if not self.check_token(request):
            raise web.HTTPForbidden()
        url = self.cfg.get("ntfy_wake_url")
        if not url:
            return web.json_response({"ok": False, "error": "ntfy_wake_url not set"})
        try:
            async with ClientSession() as s:
                async with s.post(url, data=b"wake", timeout=15) as r:
                    ok = r.status == 200
        except Exception as e:  # noqa: BLE001
            return web.json_response({"ok": False, "error": str(e)}, headers=CORS)
        return web.json_response({"ok": ok}, headers=CORS)

    async def status_handler(self, request: web.Request):
        # CORS: the launcher page lives on another origin (local mode)
        if not self.check_token(request):
            raise web.HTTPForbidden(headers=CORS)
        return web.json_response(self.status(), headers=CORS)


CORS = {"Access-Control-Allow-Origin": "*"}


async def index(_request):
    return web.FileResponse(WEB_DIR / "index.html")


def make_app(cfg: dict) -> web.Application:
    hub = Hub(cfg)
    app = web.Application()
    app.router.add_get("/", index)
    app.router.add_get("/ws/pc", hub.pc_handler)
    app.router.add_get("/ws/phone", hub.phone_handler)
    app.router.add_post("/api/wake", hub.wake_handler)
    app.router.add_get("/api/status", hub.status_handler)
    app.router.add_static("/static", WEB_DIR)
    if cfg["ca_cert"]:
        # the phone downloads and installs this once, then trusts the relay
        async def ca(_request):
            return web.FileResponse(cfg["ca_cert"], headers={
                "Content-Type": "application/x-x509-ca-cert",
                "Content-Disposition": 'attachment; filename="pc-remote-ca.crt"'})
        app.router.add_get("/ca.crt", ca)
    return app


async def serve(cfg: dict):
    runner = web.AppRunner(make_app(cfg), access_log=None)  # URLs carry the token: no access log
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
