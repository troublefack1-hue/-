"""The phone's link queue (relay/netproxy.py Traffic): Claude first, every other app gets what is left while Claude is
busy, and the «Кто ест трафик» table (traffic_get on /ws/phone) shows who ate what, who asks for more than it gets,
and what waits at the PC."""
import asyncio, json, struct, sys, tempfile, time
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "relay"))
import aiohttp
from aiohttp import web
import relay
import netproxy

T = "testsecret-0123456789abcdef"; PORT = 8797; U = f"http://127.0.0.1:{PORT}"
OPEN, DATA, CLOSE, OPENED = 1, 2, 3, 4
LINK = 50_000   # the phone's link as the agent would measure it, bytes/s
results = []
def report(name, ok, detail=""):
    results.append((name, ok)); print(("PASS " if ok else "FAIL ") + name + (": " + detail if detail else ""))


async def firehose(reader, writer):
    """A server that sends as long as it is read (a download of an endless file)."""
    try:
        chunk = b"x" * 16384
        while True:
            writer.write(chunk)
            await writer.drain()
    except (ConnectionError, OSError):
        pass


def learn(traffic, name, ip):
    q = b"".join(bytes([len(p)]) + p.encode() for p in name.split(".")) + b"\x00"
    pkt = struct.pack("!HHHHHH", 1, 0x0100, 1, 0, 0, 0) + q + struct.pack("!HH", 1, 1)
    qn = netproxy.dns_qname(pkt)
    traffic.learn(name, netproxy.dns_answer(pkt, qn[2], 1, ip))


async def main():
    tmp = tempfile.mkdtemp()
    cfg = {"secret": T, "host": "127.0.0.1", "port": PORT, "ntfy_wake_url": "", "tls_host": "0.0.0.0", "tls_port": 0,
           "tls_cert": "", "tls_key": "", "ca_cert": "", "share_dirs": [], "upload_dir": str(Path(tmp) / "up"),
           "net_dir": str(Path(tmp) / "net"), "net_block_ads": False, "net_block_lists": ["http://127.0.0.1:1/none"],
           "dns_upstream": ["127.0.0.1:1"]}
    app = relay.make_app(cfg); hub = app["hub"]
    hub.netproxy.is_local = staticmethod(lambda h: False)   # the test's servers live on loopback
    hub.phone_bw = LINK
    runner = web.AppRunner(app, access_log=None); await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", PORT).start()
    s1 = await asyncio.start_server(firehose, "127.0.0.1", 8781)
    s2 = await asyncio.start_server(firehose, "127.0.0.2", 8782)
    tr = hub.netproxy.traffic
    learn(tr, "api.anthropic.com", "127.0.0.2")
    report("an address learnt from DNS is Claude", tr.label("127.0.0.2") == "Claude", tr.label("127.0.0.2"))
    report("a Telegram address is Telegram without any name", tr.label("149.154.167.51") == "Telegram")
    report("a name is grouped by its site", tr.label("rr3---sn-abc.googlevideo.com") == "YouTube" and tr.label("cdn.example.co.uk") == "example.co.uk",
           tr.label("cdn.example.co.uk"))
    # a connection names itself: TLS ClientHello's server name (made by Python's own TLS) and HTTP Host
    import ssl
    ctx = ssl.create_default_context(); inc, out = ssl.MemoryBIO(), ssl.MemoryBIO()
    obj = ctx.wrap_bio(inc, out, server_hostname="claude.ai")
    try:
        obj.do_handshake()
    except ssl.SSLWantReadError:
        pass
    hello = out.read()
    report("TLS ClientHello names its site (SNI)", netproxy.first_name(hello) == "claude.ai", repr(netproxy.first_name(hello)))
    crlf = bytes([13, 10])
    report("HTTP request names its site (Host)",
           netproxy.first_name(b"GET / HTTP/1.1" + crlf + b"Host: Example.org:80" + crlf + crlf) == "example.org")
    report("other bytes name nothing", netproxy.first_name(bytes([0, 1]) + b"binary") is None)
    got = {7: 0, 8: 0}
    async with aiohttp.ClientSession() as s:
        ws = await s.ws_connect(U + "/ws/net", max_msg_size=0)
        await ws.send_str(json.dumps({"t": "auth", "token": T})); await ws.receive()   # net_hello

        async def reader():
            async for m in ws:
                if m.type == aiohttp.WSMsgType.BINARY and m.data[0] == DATA:
                    sid = struct.unpack("!I", m.data[1:5])[0]
                    got[sid] = got.get(sid, 0) + len(m.data) - 5
        rt = asyncio.ensure_future(reader())

        # an other app alone: nobody with priority is busy -> no limit
        await ws.send_bytes(bytes([OPEN]) + struct.pack("!IH", 7, 8781) + b"127.0.0.1")
        await asyncio.sleep(1.5)
        a0 = got[7]; await asyncio.sleep(1.0); alone = got[7] - a0
        report("an other app alone is not held back", alone > 2 * LINK, f"{alone} B/s")

        # Claude starts: the other app gets what is left (here: almost nothing, Claude takes the whole link)
        await ws.send_bytes(bytes([OPEN]) + struct.pack("!IH", 8, 8782) + b"127.0.0.2")
        await asyncio.sleep(2.0)   # Claude's meter needs a second to see it
        b0, c0 = got[7], got[8]; await asyncio.sleep(2.0)
        other, claude = (got[7] - b0) / 2, (got[8] - c0) / 2
        report("Claude goes first, unlimited", claude > 2 * LINK, f"{claude:.0f} B/s")
        report("the other app gets what is left (here ~1 KB/s)", other < 4000, f"{other:.0f} B/s")
        budget = tr.budget()
        report("the budget for the others follows the link", budget is not None and budget <= 1000.5, str(budget))

        # the table, as «Мой ПК» asks for it
        ph = await s.ws_connect(U + "/ws/phone")
        await ph.send_str(json.dumps({"t": "auth", "token": T}))
        await ph.send_str(json.dumps({"t": "traffic_get"}))
        table = None
        for _ in range(10):
            m = await asyncio.wait_for(ph.receive(), 3)
            if m.type == aiohttp.WSMsgType.TEXT and '"traffic"' in m.data[:20]:
                table = json.loads(m.data); break
        rows = {r["name"]: r for r in (table or {}).get("rows", [])}
        report("the table lists Claude, the nameless app and the screen", {"Claude", "Без имени (только адрес)", "Мой ПК — экран и звук"} <= set(rows),
               ", ".join(rows))
        o = rows.get("Без имени (только адрес)", {})
        report("the held-back app asks for more than it gets, and waits at the PC", o.get("queued", 0) > 0 and o.get("wants", 0) >= o.get("rate", 0),
               f"wants {o.get('wants')} gets {o.get('rate')} queued {o.get('queued')}")
        report("the table says what the others may take", table is not None and table.get("others") is not None and table.get("bw") == LINK,
               json.dumps({k: table.get(k) for k in ("bw", "others", "claude")}) if table else "no table")
        report("the queue at the PC stays within its bounds", tr.queued_total <= netproxy.QUEUE_TOTAL + netproxy.CHUNK * 4, str(tr.queued_total))

        # a nameless connection says "claude.ai" in its first bytes: from then on it is Claude, first in the queue
        await ws.send_bytes(bytes([OPEN]) + struct.pack("!IH", 9, 8781) + b"127.0.0.1")
        await asyncio.sleep(0.3)
        await ws.send_bytes(bytes([DATA]) + struct.pack("!I", 9) + hello)
        await asyncio.sleep(0.3)
        sess = list(hub.netproxy.sessions)[0]
        report("a nameless connection becomes Claude by its SNI", sess.streams.get(9, [None, None, "?"])[2] == "Claude",
               str(sess.streams.get(9, [None, None, "?"])[2]))
        await ws.send_bytes(bytes([CLOSE]) + struct.pack("!I", 9))

        # Claude stops: the other app is free again
        await ws.send_bytes(bytes([CLOSE]) + struct.pack("!I", 8))
        await asyncio.sleep(3.5)   # Claude's meter forgets it after 2.5 s
        d0 = got[7]; await asyncio.sleep(1.0); after = got[7] - d0
        report("Claude done: the other app is free again", after > 2 * LINK, f"{after} B/s")
        await ph.close(); await ws.close(); rt.cancel()
    s1.close(); s2.close()
    await runner.cleanup()
    print(f"{sum(ok for _, ok in results)}/{len(results)} passed")
    sys.exit(0 if all(ok for _, ok in results) else 1)

asyncio.run(main())
