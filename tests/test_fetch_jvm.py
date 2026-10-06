"""The phone apps' download with resume («докачка», android/src/ru/pcremote/Fetch.java) on a desktop JVM against the
real relay over TLS: a full download, the rest after 100 KB already came (HTTP Range), a corrupt piece (starts over),
a piece longer than the file (416, starts over)."""
import asyncio, hashlib, json, os, subprocess, sys, tempfile
from pathlib import Path
HERE = Path(__file__).resolve().parent; ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "relay"))
import relay, certs

T = "testsecret-0123456789abcdef"; PORT = 8785; TLS = 8784
results = []
def report(name, ok, detail=""):
    results.append((name, ok)); print(("PASS " if ok else "FAIL ") + name + (": " + detail if detail else ""))


async def main():
    tmp = Path(tempfile.mkdtemp())
    certs.ensure_certs(tmp, ["127.0.0.1"])
    apk_dir = tmp / "apk"; apk_dir.mkdir()
    data = os.urandom(300_000)
    (apk_dir / "pcremote-net.apk").write_bytes(data)
    sha = hashlib.sha256(data).hexdigest()
    (apk_dir / "index.json").write_text(json.dumps({"version": "1.0", "files": {"pcremote-net.apk": {"sha256": sha, "size": len(data), "version": "1.0"}}}))
    cfg = {"secret": T, "host": "127.0.0.1", "port": PORT, "ntfy_wake_url": "", "tls_host": "127.0.0.1", "tls_port": TLS,
           "tls_cert": str(tmp / "server.crt"), "tls_key": str(tmp / "server.key"), "ca_cert": str(tmp / "ca.crt"),
           "share_dirs": [], "upload_dir": str(tmp / "up"), "net_dir": str(tmp / "net"), "net_block_lists": [], "apk_dir": str(apk_dir)}
    app = relay.make_app(cfg)
    serve = asyncio.ensure_future(relay.serve(cfg, app)); await asyncio.sleep(1.0)
    out = tmp / "classes"; out.mkdir()
    src = [ROOT / "android" / "src" / "ru" / "pcremote" / n for n in ("Fetch.java", "Pinned.java", "Paths.java")] + [ROOT / "android" / "test" / "FetchTest.java"]
    env = {**os.environ, "JAVA_TOOL_OPTIONS": ""}
    r = subprocess.run(["javac", "-encoding", "UTF-8", "-nowarn", "-d", str(out), *map(str, src)], capture_output=True, text=True, env=env)
    report("Fetch compiles on the desktop JVM", r.returncode == 0, r.stderr[-500:])
    if r.returncode == 0:
        work = tmp / "dl"; work.mkdir()
        loop = asyncio.get_running_loop()
        p = await loop.run_in_executor(None, lambda: subprocess.run(
            ["java", "-cp", str(out), "FetchTest", "127.0.0.1", str(TLS), T, "/api/apk?name=pcremote-net.apk", sha,
             str(apk_dir / "pcremote-net.apk"), str(work)], capture_output=True, text=True, env=env, timeout=120))
        lines = {l.split()[1]: l for l in p.stdout.splitlines() if l.startswith("CASE ")}
        for case, what in (("full", "a full download, checked"), ("resume", "after 100 KB only the rest comes (Range)"),
                           ("garbage", "a corrupt piece: an error, then a clean start"), ("toolong", "a piece longer than the file: 416, then a clean start")):
            l = lines.get(case, "")
            report(what, " ok " in l + " ", l or p.stderr[-400:])
    serve.cancel()
    print(f"{sum(ok for _, ok in results)}/{len(results)} passed")
    sys.exit(0 if all(ok for _, ok in results) else 1)

asyncio.run(main())
