"""«Проводник» ↔ PC: PcClient/PcLoc/Ops against the real relay over TLS, plus ZipLoc, search filters and batch rename."""
import asyncio, os, subprocess, sys, tempfile
from pathlib import Path
HERE = Path(__file__).resolve().parent; ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "relay"))
from aiohttp import web
import relay, certs

T = "testsecret-0123456789abcdef"; PORT = 8782; TLS = 8781

async def main():
    tmp = Path(tempfile.mkdtemp()); certs.ensure_certs(tmp, ["127.0.0.1"])
    share = tmp / "share"; (share / "docs" / "inner").mkdir(parents=True)
    (share / "hello.txt").write_bytes("привет".encode()); (share / "photo.jpg").write_bytes(b"\xff\xd8x"); (share / "docs" / "inner" / "deep.md").write_text("# deep")
    cfg = {"secret": T, "host": "127.0.0.1", "port": PORT, "ntfy_wake_url": "", "tls_host": "127.0.0.1", "tls_port": TLS,
           "tls_cert": str(tmp / "server.crt"), "tls_key": str(tmp / "server.key"), "ca_cert": str(tmp / "ca.crt"),
           "share_dirs": [str(share)], "upload_dir": str(tmp / "up"), "net_block_lists": []}
    app = relay.make_app(cfg)
    serve = asyncio.ensure_future(relay.serve(cfg, app)); await asyncio.sleep(1.0)
    loop = asyncio.get_running_loop()
    out = tmp / "classes"; out.mkdir()
    env = {**os.environ, "JAVA_TOOL_OPTIONS": ""}
    src = [str(p) for p in (ROOT / "android-files" / "src" / "ru" / "pcremote" / "files").glob("*.java") if p.name not in ("MainActivity.java", "ViewerActivity.java", "ZoomImageView.java", "Thumbs.java", "FileProvider.java", "EditorActivity.java")]
    src += [str(ROOT / "android" / "src" / "ru" / "pcremote" / n) for n in ("Pinned.java", "Pairing.java", "Paths.java")] + [str(ROOT / "android-files" / "test" / "PcTest.java")]
    r = subprocess.run(["javac", "-encoding", "UTF-8", "-nowarn", "-d", str(out), *src], capture_output=True, text=True, env=env)
    if r.returncode: print(r.stderr[-1500:]); return False
    r = await loop.run_in_executor(None, lambda: subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", str(out), "PcTest", "127.0.0.1", str(TLS), T, str(share)], capture_output=True, text=True, env=env, timeout=120))
    print(r.stdout.strip()); 
    if r.returncode: print(r.stderr[-1500:])
    serve.cancel()
    return r.returncode == 0

if __name__ == "__main__":
    sys.exit(0 if asyncio.run(main()) else 1)
