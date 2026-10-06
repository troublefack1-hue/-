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
import collections
import ipaddress
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
        self.on_reply = None   # (name, reply) -> None: NetProxy hooks Traffic.learn here
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
                if self.on_reply is not None:
                    self.on_reply(key[0], pkt[:2] + c[1])
                return pkt[:2] + c[1]
        reply = await self.race(pkt)
        if reply is not None and key is not None and self.on_reply is not None:
            self.on_reply(key[0], reply)   # which app is behind an address (Traffic.learn)
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


def dns_addresses(pkt: bytes) -> list:
    """Every A/AAAA address in a DNS reply (CNAME chains included): the IPs the asking app will connect to."""
    out = []
    try:
        qd, an = struct.unpack("!HH", pkt[4:8])
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
        for _ in range(an):
            i = skip_name(i)
            rtype, _c, _ttl, rdlen = struct.unpack("!HHIH", pkt[i:i + 10])
            i += 10
            if rtype == 1 and rdlen == 4:
                out.append(socket.inet_ntoa(pkt[i:i + 4]))
            elif rtype == 28 and rdlen == 16:
                out.append(socket.inet_ntop(socket.AF_INET6, pkt[i:i + 16]))
            i += rdlen
    except (IndexError, struct.error, OSError):
        pass
    return out


def first_name(data: bytes) -> str | None:
    """The host a connection's first bytes name: TLS ClientHello server_name, or an HTTP request's Host header."""
    try:
        if len(data) > 43 and data[0] == 0x16 and data[5] == 0x01:            # TLS handshake record, ClientHello
            i = 9 + 2 + 32                                                     # record+handshake headers, version, random
            i += 1 + data[i]                                                   # session id
            i += 2 + int.from_bytes(data[i:i + 2], "big")                      # cipher suites
            i += 1 + data[i]                                                   # compression methods
            end = i + 2 + int.from_bytes(data[i:i + 2], "big")
            i += 2
            while i + 4 <= min(end, len(data)):
                etype, elen = int.from_bytes(data[i:i + 2], "big"), int.from_bytes(data[i + 2:i + 4], "big")
                if etype == 0 and elen > 5:                                    # server_name: list(2) type(1) len(2) name
                    n = int.from_bytes(data[i + 7:i + 9], "big")
                    name = data[i + 9:i + 9 + n].decode("ascii")
                    return name.lower() if name else None
                i += 4 + elen
            return None
        head = data[:2048]
        if head[:4] in (b"GET ", b"POST", b"PUT ", b"HEAD", b"OPTI", b"DELE", b"PATC", b"CONN"):
            for line in head.split(b"\r\n")[1:]:
                if line[:5].lower() == b"host:":
                    return line[5:].strip().split(b":")[0].decode("ascii").lower() or None
    except (IndexError, UnicodeDecodeError):
        return None
    return None


STREAM_QUEUE = 256 * 1024       # read ahead per connection at the PC
QUEUE_TOTAL = 16 * 1024 * 1024  # and for all of them together (memory)

# Which app a connection belongs to, by the name it asked for (the proxy only sees names and addresses, not apps)
CLAUDE = ("anthropic.com", "claude.ai", "claude.com", "claudeusercontent.com")
APPS = [
    (CLAUDE, "Claude"),
    (("telegram.org", "t.me", "telegram.me", "tdesktop.com", "telesco.pe", "cdn-telegram.org"), "Telegram"),
    (("googlevideo.com", "youtube.com", "ytimg.com", "youtubei.googleapis.com", "ggpht.com", "youtube-nocookie.com"), "YouTube"),
    (("whatsapp.net", "whatsapp.com"), "WhatsApp"),
    (("cdninstagram.com", "instagram.com", "fbcdn.net", "facebook.com", "fbsbx.com"), "Instagram / Facebook"),
    (("vk.com", "vk.ru", "userapi.com", "vkuser.net", "vk-cdn.net", "vkuseraudio.net", "vkuservideo.net", "mycdn.me", "ok.ru"), "VK / OK"),
    (("tiktokcdn.com", "tiktok.com", "tiktokv.com", "byteoversea.com", "ibytedtos.com", "tiktokcdn-eu.com"), "TikTok"),
    (("play.googleapis.com", "android.clients.google.com", "gvt1.com", "play-fe.googleapis.com", "play-lh.googleusercontent.com"), "Google Play"),
    (("googleapis.com", "gstatic.com", "google.com", "googleusercontent.com", "gvt2.com", "1e100.net", "google.ru"), "Google"),
    (("yandex.ru", "yandex.net", "yandex.com", "yastatic.net", "ya.ru", "yandex.st"), "Яндекс"),
    (("miui.com", "xiaomi.com", "xiaomi.net", "mi.com", "mi-img.com"), "Xiaomi (система)"),
    (("discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media"), "Discord"),
    (("spotify.com", "scdn.co", "spotifycdn.com"), "Spotify"),
    (("twitch.tv", "ttvnw.net", "jtvnw.net"), "Twitch"),
    (("openai.com", "chatgpt.com", "oaiusercontent.com"), "ChatGPT"),
    (("avito.ru", "avito.st"), "Авито"),
]
TELEGRAM_NETS = [ipaddress.ip_network(n) for n in ("149.154.160.0/20", "91.108.4.0/22", "91.108.8.0/22", "91.108.12.0/22",
                                                    "91.108.16.0/22", "91.108.20.0/22", "91.108.56.0/22", "95.161.64.0/20",
                                                    "185.76.151.0/24", "2001:b28:f23d::/48", "2001:b28:f23f::/48", "2001:67c:4e8::/48")]


def _is_ip(host: str) -> bool:
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


class Meter:
    __slots__ = ("down", "up", "win", "win_at", "rate")

    def __init__(self):
        self.down = self.up = self.win = 0
        self.win_at = time.monotonic()
        self.rate = 0.0

    def add(self, down: int, up: int):
        now = time.monotonic()
        self.down += down; self.up += up; self.win += down + up
        if now - self.win_at >= 1.0:
            self.rate = self.win / (now - self.win_at)
            self.win, self.win_at = 0, now

    def now_rate(self) -> float:
        return 0.0 if time.monotonic() - self.win_at > 2.5 else self.rate


class Traffic:
    """Names behind addresses, eaten bytes per app, and the queue for the phone's downlink.

    Priority (owner): Claude — always, unshaped; the PC screen — next (the agent yields to Claude, see "prio");
    every other app — what is left of the link while Claude or the screen is busy, else nothing (it waits: its server
    is simply not read, TCP holds it back; UDP datagrams over the budget are dropped, QUIC slows down by itself)."""

    def __init__(self, hub):
        self.hub = hub
        self.names: "collections.OrderedDict[str, str]" = collections.OrderedDict()   # ip -> name the phone asked for
        self.meters: dict = {}            # label -> Meter, since the PC Remote start (memory only, nothing on disk)
        self.asking: dict = {}            # label -> Meter of bytes the app's servers sent us (what it asks for)
        self.queued: dict = {}            # label -> bytes waiting at the PC for the phone's link
        self.queued_total = 0
        self.since = time.time()
        self.tokens = 0.0
        self.tokens_at = time.monotonic()

    def learn(self, name: str, reply: bytes):
        for ip in dns_addresses(reply):
            self.names.pop(ip, None)
            self.names[ip] = name.lower().rstrip(".")
            if len(self.names) > 8192:
                self.names.popitem(last=False)

    def remember(self, ip: str, name: str):
        """A connection to ip said it is name: the next ones to ip (UDP/QUIC too) get the same label."""
        if _is_ip(ip):
            self.names.pop(ip, None)
            self.names[ip] = name
            if len(self.names) > 8192:
                self.names.popitem(last=False)

    def label(self, host: str) -> str:
        h = host.lower().rstrip(".")
        name = self.names.get(h, h) if _is_ip(h) else h
        if _is_ip(name):
            try:
                a = ipaddress.ip_address(name)
                if any(a in n for n in TELEGRAM_NETS):
                    return "Telegram"
            except ValueError:
                pass
            return "Без имени (только адрес)"
        for suffixes, lab in APPS:
            if any(name == s or name.endswith("." + s) for s in suffixes):
                return lab
        parts = name.split(".")
        return ".".join(parts[-3:] if len(parts) >= 3 and len(parts[-2]) <= 3 and parts[-2] in ("com", "co", "org", "net") else parts[-2:])

    def count(self, label: str, down: int, up: int):
        m = self.meters.get(label)
        if m is None:
            m = self.meters[label] = Meter()
        m.add(down, up)

    def wants(self, label: str, n: int, queued_only: bool = False):
        """n bytes came from the app's server into its queue (n < 0: left the queue towards the phone)."""
        self.queued[label] = self.queued.get(label, 0) + n
        self.queued_total += n
        if not queued_only and n > 0:
            m = self.asking.get(label)
            if m is None:
                m = self.asking[label] = Meter()
            m.add(n, 0)

    def claude_rate(self) -> float:
        m = self.meters.get("Claude")
        return m.now_rate() if m else 0.0

    def budget(self):
        """Bytes/s the other apps may take now, or None = unlimited (nobody with priority is busy)."""
        claude = self.claude_rate()
        screen = self.hub.screen_rate() if hasattr(self.hub, "screen_rate") else 0.0
        watching = getattr(self.hub, "screen_watching", lambda: False)()
        if claude < 300 and not watching:
            return None
        bw = getattr(self.hub, "phone_bw", 0.0) or 0.0
        if not bw:
            return 2000.0                                   # link unknown: a trickle, the priority apps first
        return max(1000.0, bw * 0.9 - claude - screen)

    def _refill(self, rate: float):
        now = time.monotonic()
        self.tokens = min(max(4096.0, rate * 0.5), self.tokens + (now - self.tokens_at) * rate)
        self.tokens_at = now

    async def take(self, n: int):
        """Wait until an other-app chunk of n bytes may go to the phone."""
        while True:
            rate = self.budget()
            if rate is None:
                return
            self._refill(rate)
            if self.tokens >= n or self.tokens >= max(4096.0, rate * 0.5):
                self.tokens -= n
                return
            await asyncio.sleep(min(0.25, (n - self.tokens) / rate))

    def may_send_udp(self, n: int) -> bool:
        rate = self.budget()
        if rate is None:
            return True
        self._refill(rate)
        if self.tokens >= n:
            self.tokens -= n
            return True
        return False

    def rows(self) -> list:
        out = [{"name": k, "down": m.down, "up": m.up, "rate": round(m.now_rate()),
                "wants": round(self.asking[k].now_rate()) if k in self.asking else 0, "queued": max(0, self.queued.get(k, 0)),
                "prio": 1 if k == "Claude" else 3} for k, m in self.meters.items()]
        out.sort(key=lambda r: -(r["down"] + r["up"]))
        return out[:40]


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
    label = "?"
    prio = False

    def __init__(self, session, fid: int):
        self.session, self.fid = session, fid
        self.transport = None
        self.last = time.monotonic()

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        tr = self.session.proxy.traffic
        if not self.prio and not tr.may_send_udp(len(data)):
            return   # an other app over its share while Claude or the screen is busy: dropped, QUIC backs off
        tr.count(self.label, len(data), 0)
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
                    if len(st) > 4:
                        tr = self.proxy.traffic
                        if not st[3]:
                            st[3] = True   # the first bytes name the site (TLS SNI / HTTP Host): believe them over DNS
                            name = first_name(frame[5:])
                            if name:
                                st[2] = tr.label(name)
                                tr.remember(st[4], name)
                        tr.count(st[2], 0, len(frame) - 5)
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
        tr = self.proxy.traffic
        st.extend([tr.label(host), False, host])   # the label may be corrected by the first bytes (handle, DATA)
        await self.send(bytes([OPENED]) + struct.pack("!I", sid))
        # The server's bytes are read ahead into a queue here (the PC's own link is fast) and go to the phone in the
        # owner's order: Claude at once, the others from what is left. The queue shows what each app asks for (read
        # rate) against what it gets (sent rate), and how much of it waits at the PC.
        q = collections.deque()
        have, room = asyncio.Event(), asyncio.Event()
        room.set()
        held = [0, False]          # bytes queued for this stream, server finished

        async def pump():
            try:
                while True:
                    await room.wait()
                    while tr.queued_total > QUEUE_TOTAL:
                        await asyncio.sleep(0.05)
                    data = await reader.read(CHUNK)
                    if not data:
                        break
                    q.append(data); held[0] += len(data)
                    tr.wants(st[2], len(data))
                    if held[0] >= STREAM_QUEUE:
                        room.clear()
                    have.set()
            except (ConnectionError, OSError, asyncio.CancelledError):
                pass
            finally:
                held[1] = True
                have.set()
        pumper = asyncio.ensure_future(pump())
        try:
            while True:
                if not q:
                    if held[1]:
                        break
                    have.clear()
                    await have.wait()
                    continue
                data = q.popleft()
                prio = st[2] == "Claude"
                piece = len(data) if prio or tr.budget() is None else 4096   # fine-grained while others wait
                if len(data) > piece:
                    q.appendleft(data[piece:])
                    data = data[:piece]
                if not prio:
                    await tr.take(len(data))
                held[0] -= len(data); tr.wants(st[2], -len(data), queued_only=True)
                if held[0] < STREAM_QUEUE:
                    room.set()
                self.tcp_bytes += len(data)
                tr.count(st[2], len(data), 0)
                await self.send(bytes([DATA]) + struct.pack("!I", sid) + data)
        except (ConnectionError, OSError, asyncio.CancelledError):
            pass
        finally:
            pumper.cancel()
            tr.wants(st[2], -held[0], queued_only=True)
            held[0] = 0
            if self.streams.pop(sid, None) is not None:
                writer.close()
                await self.send(bytes([CLOSE]) + struct.pack("!I", sid))

    async def close_stream(self, sid: int, tell: bool):
        st = self.streams.pop(sid, None)
        if not st:
            return
        writer, task = st[0], st[1]
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
            flow.label = self.proxy.traffic.label(host)
            flow.prio = flow.label == "Claude"
            try:
                await loop.create_datagram_endpoint(lambda: flow, family=socket.AF_INET, local_addr=("0.0.0.0", 0))
            except OSError as e:
                log.debug("udp socket: %s", e)
                return
            self.udp[fid] = flow
        flow.last = time.monotonic()
        self.udp_bytes += len(payload)
        self.proxy.traffic.count(flow.label, 0, len(payload))
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
        self.traffic = Traffic(hub)
        self.dns.on_reply = self.traffic.learn
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
                    if '"pair_answer"' in msg.data[:30]:   # «Интернет через ПК» answered a new phone's request
                        try:
                            ev = json.loads(msg.data)
                            await self.hub.answer_pair(str(ev.get("id", "")), bool(ev.get("ok")), "«Интернет через ПК»")
                        except (ValueError, TypeError):
                            pass
                        continue
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
