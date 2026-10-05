"""Trackpad mode of the phone page in a real browser with real touch events (CDP): what reaches the PC.
Slow finger = fine steps, fast finger = big steps; tap + touch-and-move = drag (button held); two-finger tap =
right click. A fake PC records the mouse commands the relay hands it."""
import asyncio, json, os, sys
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "relay"))
import aiohttp
from aiohttp import web
import relay
from playwright.async_api import async_playwright

T = "testsecret-0123456789abcdef"; PORT = 8794; U = f"http://127.0.0.1:{PORT}"
EXE = os.environ.get("PCR_CHROMIUM")
results = []


def report(name, ok, detail=""):
    results.append(ok); print(("PASS " if ok else "FAIL ") + name + (": " + detail if detail else ""))


async def main():
    cfg = {"secret": T, "host": "127.0.0.1", "port": PORT, "ntfy_wake_url": "", "tls_host": "0.0.0.0", "tls_port": 0,
           "tls_cert": "", "tls_key": "", "ca_cert": "", "share_dirs": [str(HERE)], "upload_dir": str(HERE)}
    runner = web.AppRunner(relay.make_app(cfg), access_log=None); await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", PORT).start()
    got = []
    async with aiohttp.ClientSession() as s, async_playwright() as p:
        b = await p.chromium.launch(**({"executable_path": EXE} if EXE else {}))
        page = await b.new_page(viewport={"width": 412, "height": 900}, has_touch=True, is_mobile=True)
        await page.add_init_script("localStorage.setItem('pcr_prefs', JSON.stringify({hintsSeen:true})); localStorage.setItem('pcr_mouse','trackpad')")
        await page.goto(U + "/#" + T); await page.wait_for_selector("#app:not([hidden])", timeout=8000)
        pc = await s.ws_connect(U + "/ws/pc"); await pc.send_str(json.dumps({"t": "auth", "token": T})); await pc.receive_str()
        await pc.send_str(json.dumps({"t": "hello", "w": 480, "h": 270, "mw": 1920, "mh": 1080, "host": "PC", "video": False}))

        async def collect():
            async for m in pc:
                if m.type == aiohttp.WSMsgType.TEXT:
                    ev = json.loads(m.data)
                    if ev.get("t") in ("move", "btn", "click"):
                        got.append(ev)
        task = asyncio.create_task(collect())
        await page.wait_for_function("document.getElementById('state').textContent.includes('в сети')", timeout=8000)
        await page.wait_for_function("document.getElementById('splash').hidden", timeout=8000)   # the splash takes touches first
        await page.wait_for_timeout(400)
        cdp = await page.context.new_cdp_session(page)

        async def touch(kind, pts):
            await cdp.send("Input.dispatchTouchEvent", {"type": kind, "touchPoints": [{"x": x, "y": y, "id": i} for i, (x, y) in enumerate(pts)]})

        async def swipe(dx, steps, ms):
            await touch("touchStart", [(200, 450)])
            for i in range(1, steps + 1):
                await touch("touchMove", [(200 + dx * i / steps, 450)]); await asyncio.sleep(ms / 1000)
            await touch("touchEnd", []); await page.wait_for_timeout(250)

        def moved_px(since):
            xs = [e["x"] for e in got[since:] if e["t"] == "move"]
            return round((xs[-1] - xs[0]) * 1920) if len(xs) > 1 else 0

        n = len(got); await swipe(100, 25, 40)          # 100 points in 1 s: slow
        slow = moved_px(n)
        n = len(got); await swipe(100, 5, 0)            # 100 points as fast as CDP delivers: fast
        fast = moved_px(n)
        report("slow swipe: about 1 PC pixel per point", 80 <= slow <= 160, f"{slow} px for 100 points")
        report("fast swipe goes much further", fast >= 2 * max(slow, 1), f"{fast} px for 100 points")

        n = len(got)
        await touch("touchStart", [(200, 450)]); await touch("touchEnd", []); await asyncio.sleep(0.12)   # tap
        await touch("touchStart", [(200, 450)])
        for i in range(1, 8):
            await touch("touchMove", [(200 + 8 * i, 450)]); await asyncio.sleep(0.03)
        await touch("touchEnd", []); await page.wait_for_timeout(300)
        seq = [e["t"] + ("_down" if e.get("down") else "_up" if e["t"] == "btn" else "") for e in got[n:]]
        downs = [i for i, x in enumerate(seq) if x == "btn_down"]; ups = [i for i, x in enumerate(seq) if x == "btn_up"]
        report("tap then touch-and-move drags: button down, moves, button up",
               bool(downs and ups and downs[0] < ups[-1] and "move" in seq[downs[0]:ups[-1]]), " ".join(seq[:12]))

        n = len(got)
        await touch("touchStart", [(180, 450), (260, 450)]); await asyncio.sleep(0.08); await touch("touchEnd", [])
        await page.wait_for_timeout(300)
        rc = [e for e in got[n:] if e["t"] == "click" and e.get("b") == "right"]
        report("two-finger tap = right click", len(rc) == 1, str(got[n:])[:120])

        n = len(got)
        await touch("touchStart", [(180, 450), (260, 450)])
        for i in range(1, 8):
            await touch("touchMove", [(180, 450 - 10 * i), (260, 450 - 10 * i)]); await asyncio.sleep(0.03)
        await touch("touchEnd", []); await page.wait_for_timeout(300)
        report("two-finger scroll is not a right click", not [e for e in got[n:] if e["t"] == "click"], str(got[n:])[:80])
        task.cancel(); await b.close()
    await runner.cleanup()
    print(f"\n{sum(results)}/{len(results)} passed")
    sys.exit(0 if all(results) else 1)

asyncio.run(main())
