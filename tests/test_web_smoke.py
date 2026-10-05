"""Smoke test of the phone web client in a real browser: load, connect a fake PC, exercise menus,
settings, the Back handler and the keyboard; any page error fails the test."""
import asyncio, json, os, sys
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "relay"))
import aiohttp
from aiohttp import web
import relay
from playwright.async_api import async_playwright

T = "testsecret-0123456789abcdef"; PORT = 8796; U = f"http://127.0.0.1:{PORT}"
EXE = os.environ.get("PCR_CHROMIUM")  # optional explicit chromium path

async def main():
    cfg = {"secret": T, "host": "127.0.0.1", "port": PORT, "ntfy_wake_url": "", "tls_host": "0.0.0.0", "tls_port": 0,
           "tls_cert": "", "tls_key": "", "ca_cert": "", "share_dirs": [str(HERE)], "upload_dir": "/tmp/pcr-up"}
    app = relay.make_app(cfg); runner = web.AppRunner(app, access_log=None); await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", PORT).start()
    errs = []
    async with aiohttp.ClientSession() as s, async_playwright() as p:
        b = await p.chromium.launch(**({"executable_path": EXE} if EXE else {}))
        page = await b.new_page(viewport={"width": 412, "height": 900}, has_touch=True)
        page.on("pageerror", lambda e: errs.append(str(e)))
        await page.add_init_script("localStorage.setItem('pcr_prefs', JSON.stringify({hintsSeen:true}))")
        await page.goto(U + "/#" + T); await page.wait_for_selector("#app:not([hidden])", timeout=8000)
        pc = await s.ws_connect(U + "/ws/pc"); await pc.send_str(json.dumps({"t": "auth", "token": T})); await pc.receive_str()
        await pc.send_str(json.dumps({"t": "hello", "w": 640, "h": 360, "host": "CI-PC", "audio": True, "term": True, "video": False, "volume": {"level": 30, "mute": False}}))
        await page.wait_for_timeout(400)
        for sel in ["#menuBtn", "#settingsBtn"]: await page.click(sel); await page.wait_for_timeout(200)
        await page.click("#mouseMode [data-mouse=trackpad]"); await page.click("#settingsPage [data-close]")
        await page.click("#menuBtn"); await page.click("#sysBtn"); await page.wait_for_timeout(200)
        for tab in ["procs", "timers", "devices", "dl", "log", "diag"]: await page.click(f"#sysTabs [data-tab={tab}]"); await page.wait_for_timeout(120)
        await page.click("#sysPage [data-close]")
        await page.click("#filesBtn"); await page.wait_for_timeout(300); await page.click("#filesPage [data-close]")
        await page.click("#termBtn"); await page.wait_for_timeout(200); await page.click("#termBack")
        await page.click("#kbBtn"); await page.keyboard.type("ok"); await page.click("#kbBtn")
        await page.click("#navbar [data-nav=recent]"); await page.wait_for_timeout(200)
        handled = await page.evaluate("window.pcrBack()"); assert handled is True, "Back should close the windows sheet"
        await page.evaluate("window.pcrVisible(false); window.pcrVisible(true)")
        await pc.send_str(json.dumps({"t": "windows", "items": [{"hwnd": 1, "title": "<b>x</b>", "proc": "p", "active": True, "min": False}]}))
        await pc.send_str(json.dumps({"t": "pc_notify", "app": "Test", "title": "hi", "text": "there"}))
        await pc.send_str(json.dumps({"t": "attention", "term": "t1", "text": "Do you want to proceed?"}))
        await page.wait_for_timeout(400)
        await pc.close(); await page.wait_for_timeout(600)   # disconnect path (clearCanvas)
        await b.close()
    await runner.cleanup()
    print("page errors:", errs)
    sys.exit(1 if errs else 0)

asyncio.run(main())
