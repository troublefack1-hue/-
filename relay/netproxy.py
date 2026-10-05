"""
The phone's internet through the PC («Интернет через ПК»).

The second phone app puts an Android VPN interface up and hands every TCP connection and
UDP datagram to us over one WebSocket (/ws/net, owner secret only). We open the real
sockets here, on the PC, by its default route: with a VPN running on the PC that is the
VPN, so the phone comes out of the same exit without running a VPN client of its own.
DNS (UDP port 53) is answered here too: block lists in hosts format drop ads/trackers,
everything else goes to the upstream resolvers.

Wire protocol, binary WebSocket frames, all integers big-endian:
  phone -> PC   0x01 OPEN   id(4) port(2) host(utf8)       open a TCP connection
                0x02 DATA   id(4) bytes
                0x03 CLOSE  id(4)
                0x05 UDP    id(4) port(2) hlen(1) host payload   datagram from flow id
  PC -> phone   0x04 OPENED id(4)                             connected
                0x02 DATA / 0x03 CLOSE / 0x05 UDP             same layout, other direction
A flow id is chosen by the phone (one per SOCKS UDP association); its PC-side socket is
dropped after 60 s of silence.
"""
import asyncio
import json
import logging
import socket
import struct
import time
from pathlib import Path

from aiohttp import WSMsgType, web

log = logging.getLogger("relay.net")

OPEN, DATA, CLOSE, OPENED, UDP = 1, 2, 3, 4, 5
MAX_STREAMS = 512
UDP_IDLE = 60.0
CHUNK = 32 * 1024
DEFAULT_LISTS = ["https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"]
LIST_MAX_AGE = 7 * 24 * 3600


class BlockList:
    """Hosts-format lists (0.0.0.0 ads.example.com) → a set; a name is blocked if it or a parent is listed."""

    def __init__(self, folder: Path, urls: list, enabled: bool = True):
        self.folder, self.urls, self.enabled = folder, urls, enabled
        self.names: set = set()
        self.custom: dict = {}      # name -> ip answered locally (home devices by name)
        self.loaded_at = 0.0
        self.load_cached()

    def load_cached(self):
        names = set()
        for i, _ in enumerate(self.urls):
            f = self.folder / f"list{i}.txt"
            if f.exists():
                names |= self.parse(f.read_text("utf-8", "replace"))
                self.loaded_at = max(self.loaded_at, f.stat().st_mtime)
        self.names = names
        if names:
            log.info("block list: %d names", len(names))

    @staticmethod
    def parse(text: str) -> set:
        out = set()
        for line in text.splitlines():
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            parts = line.split()
            host = parts[1] if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1", "::", "::1") else (parts[0] if len(parts) == 1 else "")
            host = host.lower().rstrip(".")
            if host and host not in ("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback") and "." in host:
                out.add(host)
        return out

    async def refresh(self, force: bool = False):
        """Download the lists once a week (or when missing); keeps the old set on failure."""
        if not force and time.time() - self.loaded_at < LIST_MAX_AGE and self.names:
            return
        import aiohttp
        self.folder.mkdir(parents=True, exist_ok=True)
        names = set()
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=60)) as s:
            for i, url in enumerate(self.urls):
                try:
                    async with s.get(url) as r:
                        text = await r.text()
                    if r.status == 200 and len(text) > 1000:
                        (self.folder / f"list{i}.txt").write_text(text, "utf-8")
                        names |= self.parse(text)
                except Exception as e:  # noqa: BLE001
                    log.info("block list %s: %s", url, e)
        if names:
            self.names = names
            self.loaded_at = time.time()
            log.info("block list refreshed: %d names", len(names))

    def blocked(self, name: str) -> bool:
        if not self.enabled or not self.names:
            return False
        name = name.lower().rstrip(".")
        while name:
            if name in self.names:
                return True
            name = name.partition(".")[2]
        return False


# ---------------------------------------------------------------- DNS ---

def dns_qname(pkt: bytes):
    """(name, qtype, end_offset) of the first question, or None."""
    try:
        if len(pkt) < 12 or struct.unpack("!H", pkt[4:6])[0] < 1:
            return None
        i, labels = 12, []
        while True:
            n = pkt[i]
            if n == 0:
                i += 1
                break
            if n & 0xC0:
                return None
            labels.append(pkt[i + 1:i + 1 + n].decode("ascii", "replace"))
            i += 1 + n
        qtype = struct.unpack("!H", pkt[i:i + 2])[0]
        return ".".join(labels), qtype, i + 4
    except (IndexError, struct.error):
        return None


def dns_answer(pkt: bytes, qend: int, qtype: int, ip: str | None, ttl: int = 60) -> bytes:
    """Same question back with an A/AAAA answer (ip) or an empty NOERROR answer."""
    flags = 0x8180
    header = pkt[:2] + struct.pack("!HHHHH", flags, 1, 1 if ip and qtype in (1, 28) else 0, 0, 0)
    body = pkt[12:qend]
    if ip and qtype == 1:
        body += b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, ttl, 4) + socket.inet_aton(ip)
    elif ip and qtype == 28:
        body += b"\xc0\x0c" + struct.pack("!HHIH", 28, 1, ttl, 16) + socket.inet_pton(socket.AF_INET6, "::" if ip == "0.0.0.0" else ip)
    return header + body


def dns_min_ttl(pkt: bytes) -> int | None:
    """Smallest TTL over every resource record in a reply; None if it has no records (negative answer)."""
    try:
        qd, an, ns, ar = struct.unpack("!HHHH", pkt[4:12])
        i = 12

        def skip_name(i):
            while True:
                n = pkt[i]
                if n == 0:
                    return i + 1
                if n & 0xC0 == 0xC0:
                    return i + 2
                i += 1 + n
        for _ in range(qd):
            i = skip_name(i) + 4
        ttl = None
        for _ in range(an + ns + ar):
            i = skip_name(i)
            _t, _c, rttl, rdlen = struct.unpack("!HHIH", pkt[i:i + 10])
            i += 10 + rdlen
            ttl = rttl if ttl is None else min(ttl, rttl)
        return ttl
    except (IndexError, struct.error):
        return None


class Dns:
    """Blocked → answered here. Known → from the cache (TTL honoured). Else all upstreams are asked at
    once and the first reply wins; their latencies are measured so the stats show who is fast."""

    CACHE_MAX = 5000
    TTL_MIN, TTL_MAX, TTL_NEG = 30, 3600, 60

    def __init__(self, upstream: list, blocklist: BlockList):
        self.upstream = upstream or ["1.1.1.1", "8.8.8.8"]
        self.block = blocklist
        self.queries = 0
        self.blocked = 0
        self.cached = 0
        self.journal: list = []     # last 200 (ts, name, blocked)
        self.cache: dict = {}       # (name, qtype) -> (expires, reply without the id)
        self.latency: dict = {}     # upstream -> smoothed ms
        self.wins: dict = {}        # upstream -> how often it answered first

    async def resolve(self, pkt: bytes) -> bytes | None:
        q = dns_qname(pkt)
        self.queries += 1
        key = None
        if q:
            name, qtype, qend = q
            hit = self.block.blocked(name)
            self.journal.append((time.time(), name, hit))
            del self.journal[:-200]
            if hit:
                self.blocked += 1
                return dns_answer(pkt, qend, qtype, "0.0.0.0" if qtype in (1, 28) else None, ttl=300)
            custom = self.block.custom.get(name.lower())
            if custom:
                return dns_answer(pkt, qend, qtype, custom if qtype == 1 else None)
            key = (name.lower(), qtype)
            c = self.cache.get(key)
            if c and c[0] > time.monotonic():
                self.cached += 1
                return pkt[:2] + c[1]
        reply = await self.race(pkt)
        if reply is not None and key is not None and len(reply) >= 12 and (reply[3] & 0x0F) in (0, 3):   # NOERROR / NXDOMAIN
            ttl = dns_min_ttl(reply)
            ttl = self.TTL_NEG if ttl is None else max(self.TTL_MIN, min(self.TTL_MAX, ttl))
            if len(self.cache) >= self.CACHE_MAX:
                now = time.monotonic()
                for k in [k for k, v in self.cache.items() if v[0] <= now][:1000] or list(self.cache)[:500]:
                    self.cache.pop(k, None)
            self.cache[key] = (time.monotonic() + ttl, reply[2:])
        return reply

    async def race(self, pkt: bytes) -> bytes | None:
        """Ask every upstream at once, take the first answer, remember who was fast."""
        loop = asyncio.get_running_loop()
        tasks = {}
        for server in self.upstream:
            tasks[asyncio.ensure_future(self._ask(loop, server, pkt))] = server
        if not tasks:
            return None
        pending = set(tasks)
        try:
            while pending:
                done, pending = await asyncio.wait(pending, timeout=3.0, return_when=asyncio.FIRST_COMPLETED)
                if not done:
                    break
                for t in done:
                    r = t.result()
                    if r is not None:
                        self.wins[tasks[t]] = self.wins.get(tasks[t], 0) + 1
                        return r
        finally:
            for t in pending:
                t.cancel()
        return None

    async def _ask(self, loop, server: str, pkt: bytes) -> bytes | None:
        host, _, port = server.rpartition(":") if ":" in server and server.count(":") == 1 else (server, "", "")
        t0 = time.monotonic()
        try:
            fut = loop.create_future()
            tr, _ = await loop.create_datagram_endpoint(lambda: _OneShot(fut), remote_addr=(host, int(port or 53)))
            try:
                tr.sendto(pkt)
                r = await asyncio.wait_for(fut, 3.0)
            finally:
                tr.close()
        except (asyncio.TimeoutError, OSError) as e:
            log.debug("dns %s: %s", server, e)
            self.latency[server] = min(3000.0, self.latency.get(server, 3000.0) * 0.7 + 3000.0 * 0.3)
            return None
        ms = (time.monotonic() - t0) * 1000
        self.latency[server] = ms if server not in self.latency else self.latency[server] * 0.7 + ms * 0.3
        return r

    def stats(self) -> dict:
        return {"dns_queries": self.queries, "dns_blocked": self.blocked, "dns_cached": self.cached, "dns_cache_size": len(self.cache),
                "dns_upstreams": [{"server": u, "ms": round(self.latency[u]) if u in self.latency else None, "wins": self.wins.get(u, 0)} for u in self.upstream]}


class _OneShot(asyncio.DatagramProtocol):
    def __init__(self, fut):
        self.fut = fut

    def datagram_received(self, data, addr):
        if not self.fut.done():
            self.fut.set_result(data)

    def error_received(self, exc):
        if not self.fut.done():
            self.fut.set_exception(exc)


# -------------------------------------------------------------- proxy ---

class _UdpFlow(asyncio.DatagramProtocol):
    def __init__(self, session, fid: int):
        self.session, self.fid = session, fid
        self.transport = None
        self.last = time.monotonic()

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        self.last = time.monotonic()
        self.session.udp_bytes += len(data)
        asyncio.ensure_future(self.session.send_udp(self.fid, addr[0], addr[1], data))

    def error_received(self, exc):
        log.debug("udp flow %d: %s", self.fid, exc)


class Session:
    """One phone's /ws/net connection: its TCP streams and UDP flows."""

    def __init__(self, proxy: "NetProxy", ws: web.WebSocketResponse):
        self.proxy, self.ws = proxy, ws
        self.streams: dict = {}      # id -> (writer, task)
        self.udp: dict = {}          # id -> _UdpFlow
        self.tcp_bytes = 0
        self.udp_bytes = 0
        self.lock = asyncio.Lock()

    async def send(self, data: bytes):
        async with self.lock:
            if not self.ws.closed:
                await self.ws.send_bytes(data)

    async def send_udp(self, fid: int, host: str, port: int, payload: bytes):
        h = host.encode()
        await self.send(bytes([UDP]) + struct.pack("!IHB", fid, port, len(h)) + h + payload)

    async def handle(self, frame: bytes):
        if len(frame) < 5:
            return
        kind, sid = frame[0], struct.unpack("!I", frame[1:5])[0]
        if kind == OPEN:
            if len(frame) < 7:
                return
            port = struct.unpack("!H", frame[5:7])[0]
            host = frame[7:].decode("utf-8", "replace")[:253]
            if len(self.streams) >= MAX_STREAMS:
                await self.send(bytes([CLOSE]) + frame[1:5])
                return
            task = asyncio.ensure_future(self.open_stream(sid, host, port))
            self.streams[sid] = [None, task]
        elif kind == DATA:
            st = self.streams.get(sid)
            if st and st[0] is not None:
                try:
                    st[0].write(frame[5:])
                    self.tcp_bytes += len(frame) - 5
                    await st[0].drain()
                except (ConnectionError, OSError):
                    await self.close_stream(sid, tell=True)
        elif kind == CLOSE:
            await self.close_stream(sid, tell=False)
        elif kind == UDP:
            if len(frame) < 8:
                return
            port, hlen = struct.unpack("!HB", frame[5:8])
            host = frame[8:8 + hlen].decode("utf-8", "replace")
            payload = frame[8 + hlen:]
            await self.udp_out(sid, host, port, payload)

    async def open_stream(self, sid: int, host: str, port: int):
        try:
            if self.proxy.is_local(host):
                raise OSError("refused: local address")
            reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), 15)
        except Exception as e:  # noqa: BLE001
            log.debug("open %s:%d: %s", host, port, e)
            self.streams.pop(sid, None)
            await self.send(bytes([CLOSE]) + struct.pack("!I", sid))
            return
        st = self.streams.get(sid)
        if st is None:          # closed by the phone while we were connecting
            writer.close()
            return
        st[0] = writer
        await self.send(bytes([OPENED]) + struct.pack("!I", sid))
        try:
            while True:
                data = await reader.read(CHUNK)
                if not data:
                    break
                self.tcp_bytes += len(data)
                await self.send(bytes([DATA]) + struct.pack("!I", sid) + data)
        except (ConnectionError, OSError, asyncio.CancelledError):
            pass
        finally:
            if self.streams.pop(sid, None) is not None:
                writer.close()
                await self.send(bytes([CLOSE]) + struct.pack("!I", sid))

    async def close_stream(self, sid: int, tell: bool):
        st = self.streams.pop(sid, None)
        if not st:
            return
        writer, task = st
        if writer is not None:
            writer.close()
        elif task is not None and not task.done():
            task.cancel()
        if tell:
            await self.send(bytes([CLOSE]) + struct.pack("!I", sid))

    async def udp_out(self, fid: int, host: str, port: int, payload: bytes):
        if port == 53:                                  # DNS is ours
            reply = await self.proxy.dns.resolve(payload)
            if reply is not None:
                await self.send_udp(fid, host, port, reply)
            return
        if self.proxy.is_local(host):
            return
        flow = self.udp.get(fid)
        if flow is None:
            if len(self.udp) >= MAX_STREAMS:
                return
            loop = asyncio.get_running_loop()
            flow = _UdpFlow(self, fid)
            try:
                await loop.create_datagram_endpoint(lambda: flow, family=socket.AF_INET, local_addr=("0.0.0.0", 0))
            except OSError as e:
                log.debug("udp socket: %s", e)
                return
            self.udp[fid] = flow
        flow.last = time.monotonic()
        self.udp_bytes += len(payload)
        try:
            flow.transport.sendto(payload, (host, port))
        except (OSError, ValueError):
            pass

    async def close_all(self):
        for sid in list(self.streams):
            await self.close_stream(sid, tell=False)
        for flow in self.udp.values():
            flow.transport and flow.transport.close()
        self.udp.clear()

    def reap_udp(self):
        now = time.monotonic()
        for fid, flow in list(self.udp.items()):
            if now - flow.last > UDP_IDLE:
                flow.transport and flow.transport.close()
                del self.udp[fid]


class NetProxy:
    def __init__(self, hub, cfg: dict, client_ip):
        self.hub = hub
        self.client_ip = client_ip
        self.enabled = bool(cfg.get("net_proxy", True))
        folder = Path(cfg.get("net_dir") or (Path(cfg.get("upload_dir") or ".") / "net"))
        self.block = BlockList(folder, cfg.get("net_block_lists") or DEFAULT_LISTS, bool(cfg.get("net_block_ads", True)))
        self.block.custom = {k.lower(): v for k, v in (cfg.get("net_hosts") or {}).items()}
        self.dns = Dns(cfg.get("dns_upstream") or [], self.block)
        self.sessions: set = set()
        self.started = 0.0

    @staticmethod
    def is_local(host: str) -> bool:
        """The phone must not reach the relay's own loopback services through the tunnel."""
        h = host.lower()
        return h in ("localhost", "ip6-localhost") or h.startswith("127.") or h in ("::1", "0.0.0.0", "::")

    def stats(self) -> dict:
        return {"enabled": self.enabled, "sessions": len(self.sessions), "streams": sum(len(s.streams) for s in self.sessions),
                "tcp_bytes": sum(s.tcp_bytes for s in self.sessions), "udp_bytes": sum(s.udp_bytes for s in self.sessions),
                **self.dns.stats(), "block_names": len(self.block.names), "block_ads": self.block.enabled}

    async def maintenance(self):
        """Weekly block-list refresh and idle UDP sockets."""
        while True:
            try:
                await self.block.refresh()
            except Exception as e:  # noqa: BLE001
                log.info("block list refresh: %s", e)
            for _ in range(360):          # 1 h, reaping every 10 s
                for s in list(self.sessions):
                    s.reap_udp()
                await asyncio.sleep(10)

    async def handler(self, request: web.Request):
        ws = web.WebSocketResponse(heartbeat=15, max_msg_size=4 * 1024 * 1024)
        await ws.prepare(request)
        ip = self.client_ip(request)
        if not await self.hub.ws_auth(ws, ip):
            return ws
        if ws in self.hub.guests or not self.enabled:
            await ws.close(code=4003, message=b"owner only" if ws in self.hub.guests else b"disabled")
            self.hub.guests.discard(ws)
            return ws
        session = Session(self, ws)
        self.sessions.add(session)
        log.info("net session from %s", ip)
        self.hub.log_event(f"интернет через ПК: телефон подключился ({ip})")
        await ws.send_str(json.dumps({"t": "net_hello", "block_ads": self.block.enabled, "block_names": len(self.block.names)}))
        try:
            async for msg in ws:
                if msg.type == WSMsgType.BINARY:
                    await session.handle(msg.data)
                elif msg.type == WSMsgType.TEXT:
                    if msg.data.startswith('{"t":"stats"') or msg.data.startswith('{"t": "stats"'):
                        await ws.send_str(json.dumps({"t": "net_stats", **self.stats(),
                                                      "journal": [{"ts": t, "name": n, "blocked": b} for t, n, b in self.dns.journal[-50:]]}))
                elif msg.type == WSMsgType.ERROR:
                    break
        finally:
            self.sessions.discard(session)
            await session.close_all()
            log.info("net session from %s ended", ip)
        return ws
