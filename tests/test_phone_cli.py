"""End to end: relay in-process + a fake Android phone (tests/fake_phone.py) + the `phone` CLI (tools/phone.py)."""
import asyncio, json, os, subprocess, sys, tempfile, shutil
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "relay"))
from aiohttp import web
import relay

T = "testsecret-0123456789abcdef"; PORT = 8797; U = f"http://127.0.0.1:{PORT}"
results = []
def report(name, ok, detail=""):
    results.append((name, ok)); print(("PASS " if ok else "FAIL ") + name + (": " + detail if detail else ""))

async def main():
    tmp = Path(tempfile.mkdtemp())
    root = tmp / "phone"; (root / "DCIM" / "Camera").mkdir(parents=True); (root / "Documents").mkdir()
    (root / "DCIM" / "Camera" / "IMG_1.jpg").write_bytes(os.urandom(1_500_000)); (root / "Documents" / "notes.txt").write_text("привет", "utf-8")
    data = tmp / "pcdata"; data.mkdir(); (data / "config.json").write_text(json.dumps({"secret": T}))
    cfg = {"secret": T, "host": "127.0.0.1", "port": PORT, "ntfy_wake_url": "", "tls_host": "0.0.0.0", "tls_port": 0,
           "tls_cert": "", "tls_key": "", "ca_cert": "", "share_dirs": [str(tmp)], "upload_dir": str(tmp / "up")}
    app = relay.make_app(cfg); runner = web.AppRunner(app, access_log=None); await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", PORT).start()
    phone = subprocess.Popen([sys.executable, str(HERE / "fake_phone.py"), str(root), U])
    env = {**os.environ, "PC_REMOTE_DATA": str(data), "PC_REMOTE_URL": U}
    def cli(*args, check=True):
        r = subprocess.run([sys.executable, str(HERE.parent / "tools" / "phone.py"), *args], capture_output=True, text=True, env=env, timeout=60)
        return r.returncode, r.stdout, r.stderr
    try:
        for _ in range(50):
            if app["hub"].fs_phone is not None: break
            await asyncio.sleep(0.1)
        report("phone announced itself as file server", app["hub"].fs_phone is not None)
        loop = asyncio.get_running_loop()
        rc, out, err = await loop.run_in_executor(None, cli, "ls", "/sdcard/DCIM/Camera"); report("ls", rc == 0 and "IMG_1.jpg" in out, err.strip())
        rc, out, err = await loop.run_in_executor(None, cli, "find", "/sdcard", "*.txt"); report("find", rc == 0 and "notes.txt" in out, err.strip())
        rc, out, err = await loop.run_in_executor(None, cli, "cat", "/sdcard/Documents/notes.txt"); report("cat", rc == 0 and "привет" in out, err.strip())
        got = tmp / "got.jpg"
        rc, out, err = await loop.run_in_executor(None, cli, "get", "/sdcard/DCIM/Camera/IMG_1.jpg", str(got))
        report("get 1.5 MB identical", rc == 0 and got.read_bytes() == (root / "DCIM" / "Camera" / "IMG_1.jpg").read_bytes(), err.strip())
        up = tmp / "up.bin"; up.write_bytes(os.urandom(700_000))
        rc, out, err = await loop.run_in_executor(None, cli, "put", str(up), "/sdcard/Download/")
        report("put identical", rc == 0 and (root / "Download" / "up.bin").read_bytes() == up.read_bytes(), err.strip())
        rc, out, err = await loop.run_in_executor(None, cli, "pull", "/sdcard/DCIM/Camera")
        report("pull mirrors folder", rc == 0 and (data / "phone" / "sdcard" / "DCIM" / "Camera" / "IMG_1.jpg").exists(), err.strip())
        rc, out, err = await loop.run_in_executor(None, cli, "pull", "/sdcard/DCIM/Camera"); report("pull again downloads nothing", rc == 0 and "0)" in out)
        rc, out, err = await loop.run_in_executor(None, cli, "cat", "/sdcard/../../etc/passwd"); report("ATTACK: path escape refused", rc != 0 and "outside" in err)
        rc, out, err = await loop.run_in_executor(None, cli, "rm", "/sdcard/Download/up.bin"); report("rm", rc == 0 and not (root / "Download" / "up.bin").exists())
        # two uploads at once must not cross their data (explicit per-request ids)
        a = tmp / "a.bin"; a.write_bytes(b"A" * 400_000); c = tmp / "c.bin"; c.write_bytes(b"C" * 650_000)
        r1 = loop.run_in_executor(None, cli, "put", str(a), "/sdcard/Download/a.bin")
        r2 = loop.run_in_executor(None, cli, "put", str(c), "/sdcard/Download/c.bin")
        await asyncio.gather(r1, r2)
        ok_a = (root / "Download" / "a.bin").read_bytes() == a.read_bytes()
        ok_c = (root / "Download" / "c.bin").read_bytes() == c.read_bytes()
        report("concurrent uploads stay separate", ok_a and ok_c)
        import aiohttp
        async with aiohttp.ClientSession() as s:
            r = await s.post(U + "/api/phone", json={"op": "list", "path": "/sdcard"}); report("no token -> 403", r.status == 403)
            r = await s.post(U + "/api/phone", json={"op": "list", "path": "/sdcard"}, headers={"Authorization": "Bearer " + T}); report("list via HTTP", r.status == 200)
        phone.terminate(); phone.wait(5); await asyncio.sleep(0.5)
        rc, out, err = await loop.run_in_executor(None, cli, "ls", "/sdcard"); report("phone gone -> clear error", rc != 0 and "телефон" in err)
    finally:
        if phone.poll() is None: phone.kill()
        await runner.cleanup(); shutil.rmtree(tmp, ignore_errors=True)
    print(f"\n{sum(ok for _, ok in results)}/{len(results)} passed")
    sys.exit(0 if all(ok for _, ok in results) else 1)

asyncio.run(main())
