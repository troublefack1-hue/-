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

FRAME_VIDEO, FRAME_AUDIO, FRAME_CAST, FRAME_CAST_AUDIO, FRAME_PFS_DATA, FRAME_PFS_WRITE = 0x01, 0x02, 0x03, 0x04, 0x06, 0x07
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
    # Windows: send replies through the physical LAN adapter even when a VPN
    # owns the default route, so the phone's connection survives VPN on/off.
    cfg.setdefault("pin_interface", True)
    # push channel to nudge phones when the PC side changed (derived from the wake channel)
    cfg.setdefault("ntfy_phone_url", (cfg["ntfy_wake_url"] + "-phone") if cfg["ntfy_wake_url"] else "")
    # a second phone with limited rights: see, wake, power — nothing else
    cfg.setdefault("guest_secret", "")
    return cfg


GUEST_ALLOW = {"ping", "ack", "profile", "cmd", "hello_phone", "monitor", "sys_get"}
# agent -> phone message types a guest must NOT receive (terminal, clipboard, notifications,
# window list, downloads). A guest sees the screen and power results, nothing private.
GUEST_RECV_BLOCK = {"term_out", "term_exit", "term_open", "pc_clip", "pc_notify", "attention",
                    "sys", "procs", "timers", "downloads", "dl", "powerplans", "windows"}


LAN_IP = {"ip": None}


def lan_interface_index() -> int | None:
    """Index of the adapter that carries the real 0.0.0.0/0 route (WireGuard-style
    VPNs add 0.0.0.0/1 + 128.0.0.0/1 instead, so this stays the physical NIC).
    Also remembers that adapter's IP in LAN_IP for the self-test."""
    if sys.platform != "win32":
        return None
    try:
        import ctypes
        import re
        import subprocess
        out = subprocess.run(["route", "print", "-4", "0.0.0.0"], capture_output=True, text=True,
                             creationflags=0x08000000).stdout
        best = None
        for m in re.finditer(r"^\s*0\.0\.0\.0\s+0\.0\.0\.0\s+(\d+\.\d+\.\d+\.\d+)\s+(\d+\.\d+\.\d+\.\d+)\s+(\d+)", out, re.M):
            gw, ifip, metric = m.group(1), m.group(2), int(m.group(3))
            if best is None or metric < best[1]:
                best = (gw, metric, ifip)
        if not best:
            return None
        LAN_IP["ip"] = best[2]
        import socket
        idx = ctypes.c_ulong()
        gw_n = ctypes.c_ulong(int.from_bytes(socket.inet_aton(best[0]), "little"))
        if ctypes.windll.iphlpapi.GetBestInterface(gw_n, ctypes.byref(idx)) == 0:
            return int(idx.value)
    except Exception as e:  # noqa: BLE001
        log.info("lan interface detection failed: %s", e)
    return None


def internet_interface_index() -> int | None:
    """Adapter Windows would use for the internet right now (VPN when it is on)."""
    if sys.platform != "win32":
        return None
    try:
        import ctypes
        import socket
        idx = ctypes.c_ulong()
        dest = ctypes.c_ulong(int.from_bytes(socket.inet_aton("8.8.8.8"), "little"))
        if ctypes.windll.iphlpapi.GetBestInterface(dest, ctypes.byref(idx)) == 0:
            return int(idx.value)
    except Exception:  # noqa: BLE001
        pass
    return None


class NetWatcher:
    """Polls the routing table every 2 s; reports VPN on/off and LAN adapter changes."""

    def __init__(self, on_change):
        self.on_change = on_change
        self.lan = lan_interface_index()
        self.vpn = self._vpn_now(self.lan)
        self.changed_at = 0.0

    @staticmethod
    def _vpn_now(lan):
        inet = internet_interface_index()
        return bool(lan and inet and inet != lan)

    async def run(self):
        if sys.platform != "win32":
            return
        loop = asyncio.get_running_loop()
        while True:
            await asyncio.sleep(2)
            try:
                lan = await loop.run_in_executor(None, lan_interface_index)
                vpn = await loop.run_in_executor(None, self._vpn_now, lan)
            except Exception:  # noqa: BLE001
                continue
            if lan != self.lan or vpn != self.vpn:
                old_lan, self.lan, self.vpn, self.changed_at = self.lan, lan, vpn, time.time()
                log.info("network change: vpn=%s lan_if=%s (was %s)", vpn, lan, old_lan)
                try:
                    await self.on_change(vpn, lan, old_lan)
                except Exception as e:  # noqa: BLE001
                    log.warning("net change handler: %s", e)


def pinned_socket(host: str, port: int, if_index: int):
    """Listening socket whose accepted connections reply via the given interface."""
    import socket
    IP_UNICAST_IF = 31
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    # the option takes the index in network byte order
    s.setsockopt(socket.IPPROTO_IP, IP_UNICAST_IF, socket.htonl(if_index).to_bytes(4, "little"))
    s.bind((host, port))
    s.listen(100)
    s.setblocking(False)
    return s


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
        self.pair_guest = False
        self.guests: set = set()
        self.on_paired = None  # callback(remote_ip) for the PC app's UI
        self.on_cast = None    # callback(kind, data): phone screen (0x03 jpeg) / sound (0x04 pcm) -> PC app; (0, None) = stopped
        self.on_phones = None  # callback(count, names) for the PC app's UI
        self.phone_names: dict = {}
        self.events: list = []  # last 200 events: (time, text)
        self.net = {"vpn": False, "lan": None, "pinned": False}
        self.on_net = None      # callback(dict) for the PC app's UI
        # phone files for the PC: the Android service announces hello_phone{"fs":true}; the local
        # `phone` CLI asks /api/phone, we forward to that phone and wait for its answer
        self.fs_phone = None
        self.pfs_pending: dict = {}   # id -> {"fut": Future, "chunks": asyncio.Queue | None}
        self.pfs_seq = 0

    def log_event(self, text: str):
        self.events.append((time.time(), text))
        del self.events[:-200]

    # --- auth -----------------------------------------------------------
    def token_ok(self, token: str) -> bool:
        return hmac.compare_digest(token.encode(), self.cfg["secret"].encode()) or self.is_guest_token(token)

    def is_guest_token(self, token: str) -> bool:
        g = self.cfg.get("guest_secret") or ""
        return bool(g) and hmac.compare_digest(token.encode(), g.encode())

    def check_header(self, request: web.Request, owner_only: bool = True) -> bool:
        ip = client_ip(request)
        if self.lockout.blocked(ip):
            return False
        auth = request.headers.get("Authorization", "")
        token = auth[7:] if auth.startswith("Bearer ") else ""
        if self.token_ok(token):
            self.lockout.ok(ip)
            return not (owner_only and self.is_guest_token(token))
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
                if self.is_guest_token(str(ev.get("token", ""))):
                    self.guests.add(ws)
                return True
        except (asyncio.TimeoutError, ValueError, TypeError, AttributeError):
            pass
        self.lockout.fail(ip)
        log.warning("bad ws auth from %s", ip)
        await ws.close(code=4003, message=b"auth")
        return False

    # --- pairing ----------------------------------------------------------
    def start_pairing(self, guest: bool = False) -> str:
        self.pair_code = f"{secrets.randbelow(10**6):06d}"
        self.pair_until = time.time() + 300
        self.pair_guest = guest
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
        secret = self.cfg["guest_secret"] if (self.pair_guest and self.cfg.get("guest_secret")) else self.cfg["secret"]
        return web.json_response({"secret": secret, "ntfy": self.cfg.get("ntfy_phone_url", ""), "wake": self.cfg.get("ntfy_wake_url", "")})

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

    async def broadcast_agent_text(self, data: str):
        """Like broadcast_phones for text, but keeps private message types away from guests."""
        if self.guests:
            try:
                t = json.loads(data).get("t")
            except (ValueError, TypeError):
                t = None
            if t in GUEST_RECV_BLOCK:
                dead = []
                for ws in self.phones:
                    if ws in self.guests:
                        continue
                    try:
                        await ws.send_str(data)
                    except Exception:  # noqa: BLE001
                        dead.append(ws)
                for ws in dead:
                    self.phones.discard(ws)
                return
        await self.broadcast_phones(data)

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
                    await self.broadcast_agent_text(msg.data)
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
        ws = web.WebSocketResponse(heartbeat=8, max_msg_size=MAX_CAST)  # dead phones noticed in ~16 s
        await ws.prepare(request)
        ip = client_ip(request)
        if not await self.ws_auth(ws, ip):
            return ws
        if len(self.phones) >= MAX_PHONES:
            await ws.close(code=4004, message=b"too many viewers")
            return ws
        self.phones.add(ws)
        self.had_phone = True
        log.info("phone connected from %s (%d)", ip, len(self.phones))
        self.log_event(f"телефон подключился ({ip})")
        self.phones_changed()
        await ws.send_str(json.dumps(self.status()))
        if ws in self.guests:
            await ws.send_str(json.dumps({"t": "role", "guest": True}))
        await self.tell_pc_viewers()
        if self.last_frame:
            await ws.send_bytes(self.last_frame)
        casting = False
        try:
            async for msg in ws:
                if msg.type == WSMsgType.BINARY:
                    if len(msg.data) > MAX_FRAME:      # a phone must not push giant frames into the PC
                        break
                    if ws in self.guests:              # a guest only watches: no casting, no files
                        continue
                    if msg.data and msg.data[0] in (FRAME_CAST, FRAME_CAST_AUDIO) and self.on_cast:
                        casting = True
                        self.on_cast(msg.data[0], msg.data[1:])
                    elif msg.data and msg.data[0] == FRAME_PFS_DATA and ws is self.fs_phone:
                        self.pfs_chunk(msg.data)
                    continue
                if msg.type != WSMsgType.TEXT:
                    if msg.type == WSMsgType.ERROR:
                        break
                    continue
                if len(msg.data) > MAX_EVENT:
                    break
                if msg.data.startswith('{"t":"hello_phone"') or msg.data.startswith('{"t": "hello_phone"'):
                    try:
                        hp = json.loads(msg.data)
                        self.phone_names[ws] = str(hp.get("model", ""))[:40]
                        if hp.get("fs") and ws not in self.guests:
                            self.fs_phone = ws
                    except ValueError:
                        pass
                    self.phones_changed()
                    continue
                if msg.data.startswith('{"t":"pfs_r"') or msg.data.startswith('{"t": "pfs_r"'):
                    if ws is self.fs_phone:
                        self.pfs_reply(msg.data)
                    continue
                if msg.data.startswith('{"t":"cmd"') or msg.data.startswith('{"t":"term_open"'):
                    try:
                        ev = json.loads(msg.data)
                        what = str(ev.get('cmd') or ev.get('kind') or '')[:32]
                        what = "".join(c for c in what if c.isalnum() or c in "_-")   # phone-controlled: keep it plain
                        self.log_event(f"{ip}: {ev.get('t')} {what}")
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
                if ws in self.guests:
                    try:
                        if json.loads(msg.data).get("t") not in GUEST_ALLOW:
                            continue
                    except ValueError:
                        continue
                await self.send_pc(msg.data)  # everything else goes to the agent
        finally:
            self.phones.discard(ws)
            self.guests.discard(ws)
            if ws is self.fs_phone:
                self.fs_phone = None
                for p in list(self.pfs_pending.values()):
                    if not p["fut"].done():
                        p["fut"].set_exception(ConnectionError("телефон отключился"))
            self.phone_names.pop(ws, None)
            self.log_event(f"телефон отключился ({ip})")
            if self.pc is not None:
                asyncio.create_task(self.nudge_phones("phone-lost"))  # PC takes the first step
            if casting and self.on_cast:
                self.on_cast(0, None)
            self.phones_changed()
            await self.tell_pc_viewers()
            log.info("phone disconnected (%d left)", len(self.phones))
        return ws

    async def net_changed(self, vpn: bool, lan, old_lan):
        """VPN toggled or the LAN adapter changed: tell the phones right away."""
        self.net.update(vpn=vpn, lan=lan)
        self.log_event("VPN включён" if vpn else "VPN выключен")
        await self.broadcast_phones(json.dumps({"t": "net", "vpn": vpn}))
        if self.on_net:
            self.on_net(dict(self.net))
        await self.nudge_phones("net")

    _last_nudge = 0.0
    had_phone = False  # a phone has connected at least once -> worth calling it back

    async def keep_calling(self):
        """Long outage: while the PC is up and no phone is on the link, keep
        nudging every 60 s, forever. The phone's background service listens
        for exactly this while its own connection is down."""
        # backoff: every 60 s for 10 min, then every 5 min for an hour, then every 15 min —
        # keeps the free ntfy.sh daily quota safe during a long outage (~100 posts/day, not 1440)
        waiting = 0
        while True:
            await asyncio.sleep(60)
            if self.pc is not None and not self.phones and self.had_phone:
                waiting += 1
                step = 1 if waiting <= 10 else 5 if waiting <= 70 else 15
                if waiting % step == 0:
                    await self.nudge_phones("still-waiting")
            else:
                waiting = 0

    async def nudge_phones(self, reason: str):
        """PC-initiated reconnect: a push the phone's background service listens
        for while its own link is down. Rate-limited to one per 10 s."""
        url = self.cfg.get("ntfy_phone_url")
        if not url or (time.time() - self._last_nudge < 10 and not reason.startswith("ip=")):
            return
        self._last_nudge = time.time()
        try:
            async with ClientSession() as s:
                await s.post(url, data=f"reconnect:{reason}".encode(), timeout=10)
            log.info("nudged phones via ntfy (%s)", reason)
        except Exception as e:  # noqa: BLE001
            log.info("nudge failed: %s", e)

    async def ring_phones(self):
        self.log_event("найти телефон")
        """PC app -> every connected phone: make noise (find my phone)."""
        await self.broadcast_phones(json.dumps({"t": "ring"}))

    # --- HTTP API ----------------------------------------------------------
    async def wake_handler(self, request: web.Request):
        if not self.check_header(request, owner_only=False):
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
        if not self.check_header(request, owner_only=False):
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

    # --- phone files (for the `phone` CLI and Claude on the PC) -----------
    def pfs_reply(self, text: str):
        try:
            ev = json.loads(text)
        except ValueError:
            return
        p = self.pfs_pending.get(str(ev.get("id")))
        if not p:
            return
        if ev.get("begin"):
            p["item"] = ev.get("item")
            p["started"].set()
            return
        if ev.get("done") and p["chunks"] is not None:
            p["chunks"].put_nowait(None)
        if not p["fut"].done():
            p["fut"].set_result(ev)

    def pfs_chunk(self, data: bytes):
        rid = data[1:9].decode("ascii", "replace").strip()
        p = self.pfs_pending.get(rid)
        if p and p["chunks"] is not None:
            p["chunks"].put_nowait(data[9:])

    def _new_rid(self) -> str:
        self.pfs_seq += 1
        return f"{self.pfs_seq:x}"[-8:]

    async def pfs_call(self, req: dict, timeout: float = 30.0, stream: bool = False) -> dict:
        if self.fs_phone is None:
            raise ConnectionError("телефон не подключён или доступ к файлам не включён")
        rid = self._new_rid()
        p = {"fut": asyncio.get_running_loop().create_future(), "chunks": asyncio.Queue() if stream else None,
             "started": asyncio.Event(), "item": None}
        self.pfs_pending[rid] = p
        try:
            await self.fs_phone.send_str(json.dumps({"t": "pfs", "id": rid, **req}))
            if stream:
                waiter = asyncio.ensure_future(p["started"].wait())
                try:
                    await asyncio.wait_for(asyncio.wait([waiter, p["fut"]], return_when=asyncio.FIRST_COMPLETED), timeout)
                finally:
                    waiter.cancel()
                if p["fut"].done() and not p["started"].is_set():   # an error came instead of "begin"
                    res = p["fut"].result()
                    raise ConnectionError(res.get("error", "ошибка"))
                if not p["started"].is_set():
                    raise asyncio.TimeoutError()
                return p
            res = await asyncio.wait_for(p["fut"], timeout)
            if not res.get("ok"):
                raise ConnectionError(res.get("error", "ошибка"))
            return res
        finally:
            if not stream:
                self.pfs_pending.pop(rid, None)

    async def phone_files_handler(self, request: web.Request):
        """Local API for the `phone` CLI: JSON ops, file bytes for read, raw body for write."""
        if not self.check_header(request):
            raise web.HTTPForbidden(headers=CORS)
        op = request.query.get("op") or ""
        try:
            if request.method == "GET" and op == "read":
                p = await self.pfs_call({"op": "read", "path": request.query.get("path", "")}, stream=True)
                rid = next(k for k, v in self.pfs_pending.items() if v is p)
                try:
                    resp = web.StreamResponse(headers={**CORS, "Content-Type": "application/octet-stream",
                                                       "X-Item": json.dumps(p["item"] or {}, ensure_ascii=True)})
                    if p["item"] and p["item"].get("size") is not None:
                        resp.content_length = int(p["item"]["size"])
                    await resp.prepare(request)
                    while True:
                        chunk = await asyncio.wait_for(p["chunks"].get(), 60)
                        if chunk is None:
                            break
                        await resp.write(chunk)
                    await resp.write_eof()
                    return resp
                finally:
                    self.pfs_pending.pop(rid, None)
            if request.method == "POST" and op == "write":
                if self.fs_phone is None:
                    raise ConnectionError("телефон не подключён или доступ к файлам не включён")
                path = request.query.get("path", "")
                # one explicit id ties write_begin, the data frames and write_end to the same open file
                rid = self._new_rid()
                loop = asyncio.get_running_loop()
                p = {"fut": loop.create_future(), "chunks": None, "started": asyncio.Event(), "item": None}
                self.pfs_pending[rid] = p
                try:
                    await self.fs_phone.send_str(json.dumps({"t": "pfs", "id": rid, "op": "write_begin", "path": path}))
                    res = await asyncio.wait_for(p["fut"], 30)
                    if not res.get("ok"):
                        raise ConnectionError(res.get("error", "не удалось открыть файл"))
                    p["fut"] = loop.create_future()   # reuse the entry for write_end's answer
                    hdr = bytes([FRAME_PFS_WRITE]) + rid.encode().ljust(8)
                    total = 0
                    async for chunk in request.content.iter_chunked(256 * 1024):
                        await self.fs_phone.send_bytes(hdr + chunk)
                        total += len(chunk)
                    await self.fs_phone.send_str(json.dumps({"t": "pfs", "id": rid, "op": "write_end"}))
                    res = await asyncio.wait_for(p["fut"], 60)
                finally:
                    self.pfs_pending.pop(rid, None)
                if not res.get("ok"):
                    raise ConnectionError(res.get("error", "ошибка записи"))
                self.log_event(f"файл на телефон: {path} ({total} байт)")
                return web.json_response(res, headers=CORS)
            body = await request.json() if request.method == "POST" else dict(request.query)
            op = body.get("op", op)
            if op not in ("roots", "list", "stat", "find", "delete", "mkdir", "move"):
                raise web.HTTPBadRequest(text="bad op", headers=CORS)
            res = await self.pfs_call({k: v for k, v in body.items() if k in ("op", "path", "to", "q", "limit")})
            if op in ("delete", "move", "mkdir"):
                self.log_event(f"телефон: {op} {body.get('path', '')}")
            return web.json_response(res, headers=CORS)
        except (ConnectionError, asyncio.TimeoutError) as e:
            return web.json_response({"ok": False, "error": str(e) or "нет ответа от телефона"}, status=502, headers=CORS)

    async def fs_handler(self, request: web.Request):
        """Explorer-like operations: mkdir, rename, delete, copy, move. All paths inside share_dirs."""
        if not self.check_header(request):
            raise web.HTTPForbidden(headers=CORS)
        try:
            body = await request.json()
        except ValueError:
            raise web.HTTPBadRequest(headers=CORS)
        op = str(body.get("op", ""))
        src = self._safe_path(str(body.get("path", "")))
        if src is None:
            return web.json_response({"ok": False, "error": "путь вне разрешённых папок"}, headers=CORS)
        import shutil
        loop = asyncio.get_running_loop()
        try:
            if op == "mkdir":
                name = "".join(c for c in str(body.get("name", "")) if c not in '<>:"/\\|?*').strip()
                if not name:
                    raise ValueError("пустое имя")
                (src / name).mkdir(exist_ok=False)
                self.log_event(f"новая папка: {src / name}")
            elif op == "rename":
                name = "".join(c for c in str(body.get("name", "")) if c not in '<>:"/\\|?*').strip()
                if not name:
                    raise ValueError("пустое имя")
                src.rename(src.parent / name)
                self.log_event(f"переименовано: {src.name} → {name}")
            elif op == "delete":
                try:
                    from send2trash import send2trash
                    await loop.run_in_executor(None, send2trash, str(src))
                except ImportError:
                    if src.is_dir():
                        await loop.run_in_executor(None, shutil.rmtree, src)
                    else:
                        src.unlink()
                self.log_event(f"удалено: {src}")
            elif op in ("copy", "move"):
                dst = self._safe_path(str(body.get("to", "")))
                if dst is None or not dst.is_dir():
                    raise ValueError("папка назначения недоступна")
                target = dst / src.name
                if target.exists():
                    raise ValueError("там уже есть такой файл")
                if op == "move":
                    await loop.run_in_executor(None, shutil.move, str(src), str(target))
                elif src.is_dir():
                    await loop.run_in_executor(None, shutil.copytree, src, target)
                else:
                    await loop.run_in_executor(None, shutil.copy2, src, target)
                self.log_event(f"{'перемещено' if op == 'move' else 'скопировано'}: {src.name} → {dst}")
            else:
                raise ValueError("неизвестная операция")
        except Exception as e:  # noqa: BLE001
            return web.json_response({"ok": False, "error": str(e)}, headers=CORS)
        return web.json_response({"ok": True}, headers=CORS)

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
        # inline only for media that cannot run code; everything else downloads. An .html from
        # the PC rendered inside the app's origin could read the secret from localStorage.
        import mimetypes
        mime = mimetypes.guess_type(p.name)[0] or "application/octet-stream"
        safe_inline = mime.startswith(("image/", "video/", "audio/")) or mime in ("application/pdf", "text/plain")
        if mime == "image/svg+xml":
            safe_inline = False
        disp = "inline" if request.query.get("inline") and safe_inline else "attachment"
        headers = {**CORS, "Content-Disposition": f'{disp}; filename="{p.name}"', "X-Content-Type-Options": "nosniff",
                   "Content-Security-Policy": "sandbox; default-src 'none'; img-src 'self'; media-src 'self'"}
        return web.FileResponse(p, headers=headers)


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
    app.router.add_post("/api/fs", hub.fs_handler)
    app.router.add_get("/api/phone", hub.phone_files_handler)
    app.router.add_post("/api/phone", hub.phone_files_handler)
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
        hub = (app or runner.app)["hub"]
        site = {"obj": None}

        async def bind_tls(if_index):
            if site["obj"] is not None:
                await site["obj"].stop()
                site["obj"] = None
            if if_index and cfg.get("pin_interface"):
                try:
                    sock = pinned_socket(cfg["tls_host"], cfg["tls_port"], if_index)
                    site["obj"] = web.SockSite(runner, sock, ssl_context=ctx)
                    await site["obj"].start()
                    hub.net.update(pinned=True, lan=if_index)
                    log.info("listening on https://%s:%s (pinned to LAN adapter %d, VPN-proof)", cfg["tls_host"], cfg["tls_port"], if_index)
                    return
                except Exception as e:  # noqa: BLE001
                    log.warning("interface pinning failed (%s), listening normally", e)
            site["obj"] = web.TCPSite(runner, cfg["tls_host"], cfg["tls_port"], ssl_context=ctx)
            await site["obj"].start()
            hub.net.update(pinned=False)
            log.info("listening on https://%s:%s", cfg["tls_host"], cfg["tls_port"])

        async def self_test() -> bool:
            """Connect to our own public port via the LAN address (not loopback)."""
            ip = LAN_IP.get("ip")
            if not ip:
                return True
            try:
                cctx = ssl.create_default_context()
                cctx.check_hostname = False
                cctx.verify_mode = ssl.CERT_NONE
                r, w = await asyncio.wait_for(asyncio.open_connection(ip, cfg["tls_port"], ssl=cctx), 3)
                w.close()
                return True
            except Exception as e:  # noqa: BLE001
                log.warning("self-test failed via %s: %s", ip, e)
                return False

        async def on_net(vpn, lan, old_lan):
            if lan != old_lan and lan:
                await bind_tls(lan)   # adapter changed (cable <-> Wi-Fi): re-pin
            if not await self_test():
                # repair: try the other binding mode once
                was_pinned = hub.net.get("pinned")
                await bind_tls(None if was_pinned else lan)
                hub.log_event("слушатель перезапущен после проверки связи")
            await hub.net_changed(vpn, lan, old_lan)

        watcher = NetWatcher(on_net)
        hub.net.update(vpn=watcher.vpn, lan=watcher.lan)
        await bind_tls(watcher.lan)
        asyncio.create_task(watcher.run())
    asyncio.create_task((app or runner.app)["hub"].keep_calling())
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
