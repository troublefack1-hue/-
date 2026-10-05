"""The phone side of «Интернет через ПК» on a desktop JVM: NetMux + Socks5Server (android-net) against the
real relay over TLS. curl speaks SOCKS5 to the Java server; a DNS query goes through the UDP ASSOCIATE path."""
import asyncio, json, os, shutil, socket, struct, subprocess, sys, tempfile, time
from pathlib import Path
HERE = Path(__file__).resolve().parent; ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "relay"))
from aiohttp import web
import relay, certs, netproxy

T = "testsecret-0123456789abcdef"; PORT = 8792; TLS = 8791
results = []
def report(name, ok, detail=""):
    results.append((name, ok)); print(("PASS " if ok else "FAIL ") + name + (": " + detail if detail else ""))

def dns_query(name, qid=0x4242):
    q = b"".join(bytes([len(l)]) + l.encode() for l in name.split(".")) + b"\x00"
    return struct.pack("!HHHHHH", qid, 0x0100, 1, 0, 0, 0) + q + struct.pack("!HH", 1, 1)

def socks_udp_dns(port, name):
    """SOCKS5 UDP ASSOCIATE by hand: returns the DNS answer bytes."""
    c = socket.create_connection(("127.0.0.1", port), timeout=5)
    c.sendall(b"\x05\x01\x00"); assert c.recv(2) == b"\x05\x00"
    c.sendall(b"\x05\x03\x00\x01\x00\x00\x00\x00\x00\x00")
    r = c.recv(10); assert r[1] == 0, r
    relay_port = struct.unpack("!H", r[8:10])[0]
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.settimeout(5)
    pkt = b"\x00\x00\x00\x01" + bytes([10, 8, 0, 1]) + struct.pack("!H", 53) + dns_query(name)
    u.sendto(pkt, ("127.0.0.1", relay_port))
    data, _ = u.recvfrom(4096)
    c.close(); u.close()
    return data[10:]   # strip the SOCKS UDP header (IPv4 form)

class FakeDns(asyncio.DatagramProtocol):
    def connection_made(self, t): self.t = t
    def datagram_received(self, d, a):
        q = netproxy.dns_qname(d); self.t.sendto(netproxy.dns_answer(d, q[2], q[1], "203.0.113.7"), a)

async def main():
    tmp = Path(tempfile.mkdtemp()); S = Path(os.environ.get("PCR_SCRATCH", tmp))
    sdk_jar = os.environ.get("PCR_ANDROID_JAR", "")
    certs.ensure_certs(tmp, ["127.0.0.1"])
    (tmp / "net").mkdir(); (tmp / "net" / "list0.txt").write_text("0.0.0.0 ads.example.com\n")
    loop = asyncio.get_running_loop()
    async def http(reader, writer):
        try: await reader.readuntil(b"\r\n\r\n")
        except asyncio.IncompleteReadError: writer.close(); return
        body = b"hello via socks"; writer.write(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(body) + body); await writer.drain(); writer.close()
    srv = await asyncio.start_server(http, "127.0.0.1", 8790)
    await loop.create_datagram_endpoint(FakeDns, local_addr=("127.0.0.1", 8789))
    cfg = {"secret": T, "host": "127.0.0.1", "port": PORT, "ntfy_wake_url": "", "tls_host": "127.0.0.1", "tls_port": TLS,
           "tls_cert": str(tmp / "server.crt"), "tls_key": str(tmp / "server.key"), "ca_cert": str(tmp / "ca.crt"),
           "share_dirs": [], "upload_dir": str(tmp / "up"), "net_dir": str(tmp / "net"), "net_block_lists": [], "dns_upstream": ["127.0.0.1:8789"], "extra_ports": [8793]}
    app = relay.make_app(cfg)
    app["hub"].netproxy.is_local = staticmethod(lambda h: False)   # test servers live on loopback
    serve = asyncio.ensure_future(relay.serve(cfg, app)); await asyncio.sleep(1.0)

    # compile the phone classes (no Android in them) + the harness
    out = tmp / "classes"; out.mkdir()
    src = [ROOT / "android" / "src" / "ru" / "pcremote" / n for n in ("Pinned.java", "Pairing.java", "WsClient.java", "Paths.java")]
    src += [ROOT / "android-net" / "src" / "ru" / "pcremote" / "net" / n for n in ("NetMux.java", "Socks5Server.java")]
    src += [ROOT / "android-net" / "test" / "SocksTest.java"]
    r = subprocess.run(["javac", "-encoding", "UTF-8", "-nowarn", "-d", str(out), *map(str, src)], capture_output=True, text=True, env={**os.environ, "JAVA_TOOL_OPTIONS": ""})
    report("phone classes compile on the desktop JVM", r.returncode == 0, r.stderr[-400:])
    if r.returncode != 0: return False
    errf = open(tmp / "java.err", "w")
    java = subprocess.Popen(["java", "-cp", str(out), "SocksTest", "127.0.0.1", str(TLS), T, "25000"], stdout=subprocess.PIPE, stderr=errf, text=True,
                            env={**os.environ, "JAVA_TOOL_OPTIONS": ""})
    line = (await loop.run_in_executor(None, java.stdout.readline)).strip()   # never block the loop: the relay serves in it
    report("NetMux connected over TLS (trust on first use), SOCKS5 listening", line.startswith("SOCKS "), line or (tmp / "java.err").read_text()[-600:])
    if not line.startswith("SOCKS "): return False
    sport = int(line.split()[1])
    await asyncio.sleep(0.3)
    report("relay sees one net session", len(app["hub"].netproxy.sessions) == 1)
    # TCP through curl --socks5-hostname
    NOPROXY = {**os.environ, "HTTPS_PROXY": "", "HTTP_PROXY": "", "ALL_PROXY": "", "NO_PROXY": "*"}
    def curl(url): return subprocess.run(["curl", "-sS", "-m", "10", "--socks5-hostname", f"127.0.0.1:{sport}", url], capture_output=True, text=True, env=NOPROXY)
    c = await loop.run_in_executor(None, curl, "http://127.0.0.1:8790/")
    report("curl over SOCKS5 -> relay -> local HTTP server", c.stdout == "hello via socks", (c.stdout or c.stderr)[:200])
    c = await loop.run_in_executor(None, curl, "http://127.0.0.1:1/")
    report("refused target reported to the SOCKS client", c.returncode != 0 and not c.stdout, c.stderr[:120])
    # several parallel transfers
    outs = [c.stdout for c in await asyncio.gather(*[loop.run_in_executor(None, curl, "http://127.0.0.1:8790/") for _ in range(8)])]
    report("8 parallel streams", all(o == "hello via socks" for o in outs), str([o[:10] for o in outs]))
    await asyncio.sleep(0.3)
    report("streams closed on both sides", not list(app["hub"].netproxy.sessions)[0].streams)
    # UDP ASSOCIATE -> DNS at the PC
    ans = await loop.run_in_executor(None, socks_udp_dns, sport, "example.com")
    report("DNS over SOCKS5 UDP ASSOCIATE answered by the PC", ans[-4:] == bytes([203, 0, 113, 7]) and ans[:2] == b"\x42\x42", ans.hex())
    ans = await loop.run_in_executor(None, socks_udp_dns, sport, "ads.example.com")
    report("blocked name -> 0.0.0.0 through the phone path", ans[-4:] == b"\x00\x00\x00\x00", ans.hex())
    # extra TLS port + /api/ping (what the phone's port probe uses)
    def ping(port): return subprocess.run(["curl", "-sS", "-m", "10", "-k", "-o", "/dev/null", "-w", "%{http_code} %{size_download}", "-H", "Authorization: Bearer " + T, f"https://127.0.0.1:{port}/api/ping?n=262144"], capture_output=True, text=True, env=NOPROXY)
    r1, r2 = await asyncio.gather(loop.run_in_executor(None, ping, TLS), loop.run_in_executor(None, ping, 8793))
    report("/api/ping 256 KB on the main and the extra TLS port", r1.stdout == "200 262144" and r2.stdout == "200 262144", f"{r1.stdout} / {r2.stdout} {r2.stderr[:80]}")
    r3 = await loop.run_in_executor(None, lambda: subprocess.run(["curl", "-sS", "-m", "10", "-k", "-o", "/dev/null", "-w", "%{http_code}", f"https://127.0.0.1:8793/api/ping?n=10"], capture_output=True, text=True, env=NOPROXY))
    report("/api/ping needs a token", r3.stdout == "403", r3.stdout)
    st = await loop.run_in_executor(None, lambda: subprocess.run(["curl", "-sS", "-m", "10", "-k", "-H", "Authorization: Bearer " + T, f"https://127.0.0.1:{TLS}/api/status"], capture_output=True, text=True, env=NOPROXY))
    report("status lists the ports", json.loads(st.stdout).get("ports") == [TLS, 8793], st.stdout[:100])
    # path ordering (Paths.java) on the desktop JVM
    r = subprocess.run(["javac", "-encoding", "UTF-8", "-nowarn", "-d", str(out), str(ROOT / "android" / "src" / "ru" / "pcremote" / "Paths.java"), str(ROOT / "android" / "test" / "PathsTest.java")], capture_output=True, text=True, env={**os.environ, "JAVA_TOOL_OPTIONS": ""})
    pt = await loop.run_in_executor(None, lambda: subprocess.run(["java", "-cp", str(out), "PathsTest"], capture_output=True, text=True, env={**os.environ, "JAVA_TOOL_OPTIONS": ""}))
    print(pt.stdout.strip())
    report("Paths: USB/hotspot/LAN before public", r.returncode == 0 and pt.returncode == 0, (r.stderr or pt.stderr)[-300:])
    java.terminate(); await loop.run_in_executor(None, java.wait, 5)
    await asyncio.sleep(0.3)
    report("session gone after the phone disconnects", not app["hub"].netproxy.sessions)
    srv.close(); serve.cancel()
    bad = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(bad)}/{len(results)} passed" + (f", FAILED: {bad}" if bad else ""))
    return not bad

if __name__ == "__main__":
    sys.exit(0 if asyncio.run(main()) else 1)
