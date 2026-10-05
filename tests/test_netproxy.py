"""The phone's internet through the PC (relay/netproxy.py): TCP mux, UDP flows, DNS with block list, guest refused."""
import asyncio, json, os, struct, sys, tempfile
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "relay"))
import aiohttp
from aiohttp import web
import relay
import netproxy

T = "testsecret-0123456789abcdef"; G = "guestsecret-0123456789abcdef"; PORT = 8796; U = f"http://127.0.0.1:{PORT}"
OPEN, DATA, CLOSE, OPENED, UDP = 1, 2, 3, 4, 5
results = []
def report(name, ok, detail=""):
    results.append((name, ok)); print(("PASS " if ok else "FAIL ") + name + (": " + detail if detail else ""))

def dns_query(name, qtype=1, qid=0x1234):
    q = b"".join(bytes([len(l)]) + l.encode() for l in name.split(".")) + b"\x00"
    return struct.pack("!HHHHHH", qid, 0x0100, 1, 0, 0, 0) + q + struct.pack("!HH", qtype, 1)

class Echo(asyncio.DatagramProtocol):
    def connection_made(self, t): self.t = t
    def datagram_received(self, d, a): self.t.sendto(b"echo:" + d, a)

class FakeDns(asyncio.DatagramProtocol):
    def connection_made(self, t): self.t = t
    def datagram_received(self, d, a):
        q = netproxy.dns_qname(d); self.t.sendto(netproxy.dns_answer(d, q[2], q[1], "93.184.216.34"), a)

async def ws_net(session, token):
    ws = await session.ws_connect(U + "/ws/net")
    await ws.send_str(json.dumps({"t": "auth", "token": token}))
    return ws

async def recv_bin(ws, timeout=5):
    while True:
        m = await asyncio.wait_for(ws.receive(), timeout)
        if m.type == aiohttp.WSMsgType.BINARY: return m.data
        if m.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR): return None

async def main():
    tmp = Path(tempfile.mkdtemp())
    (tmp / "net").mkdir(); (tmp / "net" / "list0.txt").write_text("# test list\n0.0.0.0 ads.example.com\n0.0.0.0 tracker.net\n")
    loop = asyncio.get_running_loop()
    # local servers the proxy will reach: an HTTP server, a UDP echo, a fake upstream DNS
    async def http(reader, writer):
        try: await reader.readuntil(b"\r\n\r\n")
        except asyncio.IncompleteReadError: writer.close(); return
        body = b"hello from the pc side"
        writer.write(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(body) + body); await writer.drain(); writer.close()
    srv = await asyncio.start_server(http, "127.0.0.1", 8798)
    await loop.create_datagram_endpoint(Echo, local_addr=("127.0.0.1", 8795))
    await loop.create_datagram_endpoint(FakeDns, local_addr=("127.0.0.1", 8794))
    cfg = {"secret": T, "guest_secret": G, "host": "127.0.0.1", "port": PORT, "ntfy_wake_url": "", "tls_host": "0.0.0.0", "tls_port": 0,
           "tls_cert": "", "tls_key": "", "ca_cert": "", "share_dirs": [], "upload_dir": str(tmp / "up"),
           "net_dir": str(tmp / "net"), "net_block_lists": ["http://127.0.0.1:1/none"], "dns_upstream": ["127.0.0.1:8794"],
           "net_hosts": {"nas.home": "192.168.1.50"}}
    app = relay.make_app(cfg); runner = web.AppRunner(app, access_log=None); await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", PORT).start()
    hub = app["hub"]
    report("block list loaded from cache", hub.netproxy.block.blocked("sub.ads.example.com") and not hub.netproxy.block.blocked("example.com"))

    async with aiohttp.ClientSession() as s:
        # guest is refused
        ws = await ws_net(s, G); m = await ws.receive()
        report("guest refused on /ws/net", m.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED) and ws.close_code == 4003, f"{m.type} {ws.close_code}")
        # bad token: locked out / closed
        ws = await ws_net(s, "nope"); m = await ws.receive()
        report("bad token refused", ws.close_code == 4003, str(ws.close_code))

        ws = await ws_net(s, T)
        m = await ws.receive(); hello = json.loads(m.data)
        report("net_hello", hello.get("t") == "net_hello" and hello.get("block_names") == 2, m.data[:80])

        # TCP: OPEN -> OPENED, request/response, remote close -> CLOSE
        host_ip = "127.0.0.1"
        hub.netproxy.is_local = staticmethod(lambda h: False)   # the test's servers live on loopback; production keeps the guard
        await ws.send_bytes(bytes([OPEN]) + struct.pack("!IH", 7, 8798) + host_ip.encode())
        f = await recv_bin(ws)
        report("OPEN -> OPENED", f == bytes([OPENED]) + struct.pack("!I", 7), repr(f))
        await ws.send_bytes(bytes([DATA]) + struct.pack("!I", 7) + b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        got = b""; closed = False
        while True:
            f = await recv_bin(ws)
            if f is None: break
            if f[0] == DATA and struct.unpack("!I", f[1:5])[0] == 7: got += f[5:]
            elif f[0] == CLOSE and struct.unpack("!I", f[1:5])[0] == 7: closed = True; break
        report("TCP data relayed and remote close reported", got.endswith(b"hello from the pc side") and closed, repr(got[-40:]))
        # connection refused -> CLOSE
        await ws.send_bytes(bytes([OPEN]) + struct.pack("!IH", 8, 1) + host_ip.encode())
        f = await recv_bin(ws)
        report("refused target -> CLOSE", f == bytes([CLOSE]) + struct.pack("!I", 8), repr(f))
        # phone closes its own stream: no reply expected, stream gone
        await ws.send_bytes(bytes([OPEN]) + struct.pack("!IH", 9, 8798) + host_ip.encode()); await recv_bin(ws)
        await ws.send_bytes(bytes([CLOSE]) + struct.pack("!I", 9)); await asyncio.sleep(0.2)
        report("phone CLOSE drops the stream", 9 not in list(hub.netproxy.sessions)[0].streams)
        # UDP flow
        h = host_ip.encode()
        await ws.send_bytes(bytes([UDP]) + struct.pack("!IHB", 100, 8795, len(h)) + h + b"ping")
        f = await recv_bin(ws)
        ok = f and f[0] == UDP and struct.unpack("!I", f[1:5])[0] == 100 and f.endswith(b"echo:ping")
        report("UDP datagram relayed back on the same flow", bool(ok), repr(f))
        # DNS: blocked name -> 0.0.0.0, custom name -> local answer, other -> upstream
        async def dns(name, qtype=1):
            pkt = dns_query(name, qtype); d = b"10.8.0.1"
            await ws.send_bytes(bytes([UDP]) + struct.pack("!IHB", 200, 53, len(d)) + d + pkt)
            f = await recv_bin(ws); return f[8 + len(d):]
        a = await dns("ads.example.com"); report("DNS blocked -> 0.0.0.0", a[-4:] == b"\x00\x00\x00\x00" and a[:2] == b"\x12\x34", a.hex())
        a = await dns("nas.home"); report("DNS custom host -> 192.168.1.50", a[-4:] == bytes([192, 168, 1, 50]), a.hex())
        a = await dns("example.com"); report("DNS upstream answer", a[-4:] == bytes([93, 184, 216, 34]), a.hex())
        a = await dns("ads.example.com", 28); report("DNS blocked AAAA -> ::", a[-16:] == bytes(16), a.hex())
        # stats
        await ws.send_str(json.dumps({"t": "stats"})); m = await ws.receive(); st = json.loads(m.data)
        report("stats", st.get("dns_queries") == 4 and st.get("dns_blocked") == 2 and st.get("sessions") == 1, m.data[:120])
        hs = await s.get(U + "/api/status", headers={"Authorization": "Bearer " + T}); j = await hs.json()
        report("/api/status carries net stats", j.get("net", {}).get("dns_blocked") == 2, json.dumps(j.get("net"))[:100])
        # disabled on the PC -> refused
        hub.netproxy.enabled = False
        ws2 = await ws_net(s, T); m = await ws2.receive()
        report("disabled -> refused", ws2.close_code == 4003, str(ws2.close_code))
        hub.netproxy.enabled = True
        await ws.close(); await asyncio.sleep(0.2)
        report("session cleaned up", not hub.netproxy.sessions)
    srv.close()
    bad = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(bad)}/{len(results)} passed" + (f", FAILED: {bad}" if bad else ""))
    return not bad

if __name__ == "__main__":
    sys.exit(0 if asyncio.run(main()) else 1)
