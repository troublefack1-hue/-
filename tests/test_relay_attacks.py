"""Functional checks + attacks against the relay, run in-process so the hub state is visible."""
import asyncio, json, sys, time
import os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "relay"))
import aiohttp
from aiohttp import web
import relay

T = "testsecret-0123456789abcdef"
GUEST = "guestsecret-0123456789abcdef"
cfg = {"secret": T, "guest_secret": GUEST, "host": "127.0.0.1", "port": 8799, "ntfy_wake_url": "", "tls_host": "0.0.0.0", "tls_port": 0,
       "tls_cert": "", "tls_key": "", "ca_cert": ""}
U = "http://127.0.0.1:8799"
results = []

def report(name, ok, detail=""):
    results.append((name, ok)); print(("PASS " if ok else "FAIL ") + name + (": " + detail if detail else ""))

async def ws_auth(s, path, token=T, auth=True):
    ws = await s.ws_connect(U + path, autoclose=False)
    if auth:
        await ws.send_str(json.dumps({"t": "auth", "token": token}))
    return ws

async def main():
    relay.AUTH_TIMEOUT = 1  # speed up the no-auth test
    app = relay.make_app(cfg); hub = app["hub"]
    runner = web.AppRunner(app, access_log=None); await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", 8799).start()
    async with aiohttp.ClientSession() as s:
        # ---------- functional ----------
        r = await s.get(U + "/api/status"); report("status without auth -> 403", r.status == 403)
        r = await s.get(U + "/api/status?token=" + T); report("ATTACK 1: secret in URL no longer accepted", r.status == 403)
        r = await s.get(U + "/api/status", headers={"Authorization": "Bearer " + T}); report("status with Bearer -> 200", r.status == 200)
        ws = await ws_auth(s, "/ws/phone", auth=False); m = await ws.receive(); report("ws without auth closed 4003", m.type == aiohttp.WSMsgType.CLOSE and ws.close_code == 4003)
        ws = await ws_auth(s, "/ws/phone", token="wrong"); m = await ws.receive(); report("ws with wrong token closed 4003", ws.close_code == 4003)
        hub.lockout.failed.clear()
        phone = await ws_auth(s, "/ws/phone"); st = json.loads((await phone.receive()).data); report("phone authed, got status", st["t"] == "status")
        pc = await ws_auth(s, "/ws/pc"); json.loads((await pc.receive()).data); await phone.receive()  # viewers / status online
        await pc.send_bytes(b"\x01JPEG"); m = await phone.receive(); report("video frame forwarded with type byte", m.data == b"\x01JPEG")
        await pc.send_bytes(b"\x02\x80\x3ePCM"); m = await phone.receive(); report("audio frame forwarded", m.data[:1] == b"\x02")
        report("only video is cached for newcomers", hub.last_frame == b"\x01JPEG")
        t0 = time.time(); await phone.send_str('{"t":"ping"}'); m = json.loads((await phone.receive()).data)
        report("ping -> pong with pc_online", m["t"] == "pong" and m["pc_online"] is True)
        await phone.send_str(json.dumps({"t": "profile", "name": "eco"})); m = json.loads((await pc.receive()).data); report("profile forwarded to agent", m == {"t": "profile", "name": "eco"})

        # ---------- ATTACK 2: viewer flood (resource exhaustion / broadcast amplification) ----------
        extra = []
        for i in range(10):
            w = await ws_auth(s, "/ws/phone"); m = await w.receive()
            extra.append((w, m))
        accepted = sum(1 for w, m in extra if m.type == aiohttp.WSMsgType.TEXT)
        report("ATTACK 2: viewer flood capped", accepted == relay.MAX_PHONES - 1 and len(hub.phones) == relay.MAX_PHONES, f"{accepted + 1} of 11 accepted")
        for w, _ in extra: await w.close()

        # ---------- ATTACK 3: lock everyone out via a spoofed X-Forwarded-For (VPS mode) ----------
        for _ in range(12):
            await s.get(U + "/api/status", headers={"Authorization": "Bearer nope", "X-Forwarded-For": "203.0.113.9"})
        r1 = await s.get(U + "/api/status", headers={"Authorization": "Bearer " + T, "X-Forwarded-For": "203.0.113.9"})
        r2 = await s.get(U + "/api/status", headers={"Authorization": "Bearer " + T, "X-Forwarded-For": "198.51.100.7"})
        report("ATTACK 3: lockout is per real client, attacker locked, others not", r1.status == 403 and r2.status == 200)

        # ---------- ATTACK 4: memory exhaustion of the lockout table ----------
        async def spray(i):
            await s.get(U + "/api/status", headers={"Authorization": "Bearer nope", "X-Forwarded-For": f"10.{i >> 16 & 255}.{i >> 8 & 255}.{i & 255}"})
        for chunk in range(0, 12000, 400):
            await asyncio.gather(*(spray(i) for i in range(chunk, chunk + 400)))
        report("ATTACK 4: lockout table bounded", len(hub.lockout.failed) <= 10000, f"{len(hub.lockout.failed)} entries after 12000 IPs")
        hub.lockout.failed.clear()
        if phone.closed:  # the spray starved this client's loop past the server heartbeat: reopen
            phone = await ws_auth(s, "/ws/phone"); await phone.receive()
            while hub.last_frame and False: pass

        # ---------- ATTACK 5: pairing brute force ----------
        code = hub.start_pairing()
        codes = 0
        for i in range(11):
            r = await s.get(U + f"/api/pair?code={i:06d}"); codes += r.status == 200
        r = await s.get(U + f"/api/pair?code={code}")
        report("ATTACK 5: pairing brute force locked out after 10 tries (even the right code)", codes == 0 and r.status == 403)
        hub.lockout.failed.clear()
        r = await s.get(U + f"/api/pair?code={code}"); ok1 = r.status == 200
        r = await s.get(U + f"/api/pair?code={code}"); report("pairing code single-use", ok1 and r.status == 403)

        # ---------- ATTACK 6: oversized / malformed events from a phone ----------
        bad = await ws_auth(s, "/ws/phone"); await bad.receive()
        await bad.send_str("x" * (relay.MAX_EVENT + 1000)); m = await bad.receive()
        while m.type == aiohttp.WSMsgType.BINARY: m = await bad.receive()  # skip the cached frame sent on join
        report("ATTACK 6: oversized event closes that phone only", m.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.ERROR, aiohttp.WSMsgType.CLOSED) and hub.pc is not None, f"msg={m.type} close_code={bad.close_code} pc={hub.pc is not None} phones={len(hub.phones)}")
        await phone.send_str("not json at all"); await phone.send_str('{"t":"ping"}'); m = json.loads((await phone.receive()).data)
        report("malformed JSON does not break the session", m["t"] == "pong")
        big = await ws_auth(s, "/ws/pc"); await big.receive()
        try:
            await big.send_bytes(b"\x01" + b"0" * (relay.MAX_FRAME + 1)); m = await big.receive(); rejected = m.type != aiohttp.WSMsgType.TEXT
        except (ConnectionError, aiohttp.ClientError): rejected = True
        report("oversized frame from a (hijacked) agent is rejected", rejected)
        # ---------- ATTACK 8: a guest must not receive private agent messages ----------
        hub.cfg["guest_secret"] = GUEST
        pc2 = await ws_auth(s, "/ws/pc"); json.loads((await pc2.receive()).data)  # fresh agent link
        guest = await ws_auth(s, "/ws/phone", token=GUEST); gm = await guest.receive()
        owner = await ws_auth(s, "/ws/phone"); await owner.receive()
        async def drain(w, timeout=0.4):
            got = []
            while True:
                try: got.append(await asyncio.wait_for(w.receive(), timeout))
                except asyncio.TimeoutError: break
            return [g.data for g in got if g.type == aiohttp.WSMsgType.TEXT]
        await drain(guest); await drain(owner)
        await pc2.send_str(json.dumps({"t": "term_out", "id": "t1", "data": "секретный вывод"}))
        await pc2.send_str(json.dumps({"t": "pc_clip", "s": "пароль из буфера"}))
        await pc2.send_str(json.dumps({"t": "status", "pc_online": True}))
        g_msgs = await drain(guest); o_msgs = await drain(owner)
        report("ATTACK 8: guest gets no terminal/clipboard", not any("term_out" in m or "pc_clip" in m for m in g_msgs), str(g_msgs)[:80])
        report("owner still receives everything", any("term_out" in m for m in o_msgs) and any("pc_clip" in m for m in o_msgs))
        report("guest still gets public status", any('"t": "status"' in m or '"t":"status"' in m for m in g_msgs))
        # ATTACK 9: a guest tries to cast its screen onto the PC / push a giant frame
        seen = []
        hub.on_cast = lambda kind, data: seen.append(kind)
        await guest.send_bytes(b"\x03JPEG-from-guest"); await asyncio.sleep(0.2)
        report("ATTACK 9: guest cannot cast to the PC", not seen)
        await owner.send_bytes(b"\x03JPEG-from-owner"); await asyncio.sleep(0.2)
        report("owner can cast", seen == [3])
        await owner.send_bytes(b"\x03" + b"\x00" * (relay.MAX_FRAME + 1)); m = await owner.receive()
        report("ATTACK 9b: oversized phone frame closes that phone", m.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSING, aiohttp.WSMsgType.CLOSED))
        hub.on_cast = None
        await guest.close(); await pc2.close()

    await runner.cleanup()
    print(f"\n{sum(ok for _, ok in results)}/{len(results)} passed")
    sys.exit(0 if all(ok for _, ok in results) else 1)

asyncio.run(main())
