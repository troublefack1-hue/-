#!/usr/bin/env python3
"""
pc-remote agent (Windows).

Runs on the PC in the user's session. Connects OUT to the relay, streams
the screen as JPEG frames while someone is watching, applies mouse/keyboard
events coming from the phone and executes commands (reboot, shutdown, lock).

Config: config.json next to this file (see config.example.json).
"""
import asyncio
import ctypes
import hashlib
import io
import json
import logging
import os
import subprocess
import sys
import time
from ctypes import wintypes
from pathlib import Path

import aiohttp
import mss
from PIL import Image

HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "config.json"
log = logging.getLogger("agent")

# ---------------------------------------------------------------- config ---

def load_config() -> dict:
    if not CONFIG_PATH.exists():
        sys.exit(f"config not found: {CONFIG_PATH} (copy config.example.json)")
    cfg = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    for k in ("relay_url", "secret"):
        if not cfg.get(k):
            sys.exit(f"config: '{k}' is required")
    cfg.setdefault("max_width", 1280)
    cfg.setdefault("quality", 55)
    cfg.setdefault("fps", 12)
    cfg.setdefault("monitor", 1)
    return cfg


# ---------------------------------------------------------- win32 input ---

user32 = ctypes.WinDLL("user32", use_last_error=True)

INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x0002, 0x0004
MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP = 0x0008, 0x0010
MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP = 0x0020, 0x0040
MOUSEEVENTF_WHEEL, MOUSEEVENTF_HWHEEL = 0x0800, 0x1000
MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_VIRTUALDESK = 0x8000, 0x4000
KEYEVENTF_KEYUP, KEYEVENTF_UNICODE, KEYEVENTF_EXTENDEDKEY = 0x0002, 0x0004, 0x0001
SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN = 76, 77, 78, 79

ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]


def _send(*inputs: INPUT):
    arr = (INPUT * len(inputs))(*inputs)
    user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))


def _mouse(flags, dx=0, dy=0, data=0) -> INPUT:
    i = INPUT(type=INPUT_MOUSE)
    i.u.mi = MOUSEINPUT(dx, dy, data, flags, 0, 0)
    return i


def _key(vk=0, scan=0, flags=0) -> INPUT:
    i = INPUT(type=INPUT_KEYBOARD)
    i.u.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
    return i


# Names the web client sends for special keys -> virtual-key codes.
VK = {
    "Enter": 0x0D, "Backspace": 0x08, "Tab": 0x09, "Escape": 0x1B, "Space": 0x20,
    "Delete": 0x2E, "Home": 0x24, "End": 0x23, "PageUp": 0x21, "PageDown": 0x22,
    "ArrowLeft": 0x25, "ArrowUp": 0x26, "ArrowRight": 0x27, "ArrowDown": 0x28,
    "Shift": 0x10, "Control": 0x11, "Alt": 0x12, "Meta": 0x5B, "CapsLock": 0x14,
    "F1": 0x70, "F2": 0x71, "F3": 0x72, "F4": 0x73, "F5": 0x74, "F6": 0x75,
    "F7": 0x76, "F8": 0x77, "F9": 0x78, "F10": 0x79, "F11": 0x7A, "F12": 0x7B,
    "PrintScreen": 0x2C, "Insert": 0x2D,
}
EXTENDED = {"Delete", "Home", "End", "PageUp", "PageDown", "ArrowLeft", "ArrowUp",
            "ArrowRight", "ArrowDown", "Insert", "Meta", "PrintScreen"}


class Input:
    """Translates phone events into SendInput calls."""

    def __init__(self, monitor: dict):
        # the monitor we stream; phone coords are 0..1 within it
        self.mon = monitor
        self.vx = user32.GetSystemMetrics(SM_XVIRTUALSCREEN)
        self.vy = user32.GetSystemMetrics(SM_YVIRTUALSCREEN)
        self.vw = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN)
        self.vh = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN)

    def move(self, fx: float, fy: float):
        px = self.mon["left"] + fx * self.mon["width"]
        py = self.mon["top"] + fy * self.mon["height"]
        ax = int((px - self.vx) * 65535 / max(self.vw - 1, 1))
        ay = int((py - self.vy) * 65535 / max(self.vh - 1, 1))
        _send(_mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, ax, ay))

    def button(self, which: str, down: bool):
        flags = {
            "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
            "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
            "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
        }[which]
        _send(_mouse(flags[0] if down else flags[1]))

    def wheel(self, dy: int, dx: int = 0):
        if dy:
            _send(_mouse(MOUSEEVENTF_WHEEL, data=ctypes.c_uint32(dy & 0xFFFFFFFF).value))
        if dx:
            _send(_mouse(MOUSEEVENTF_HWHEEL, data=ctypes.c_uint32(dx & 0xFFFFFFFF).value))

    def key(self, name: str, down: bool):
        vk = VK.get(name)
        if vk is None and len(name) == 1:
            vk = user32.VkKeyScanW(ord(name)) & 0xFF
        if vk is None or vk == 0xFF:
            return
        flags = (0 if down else KEYEVENTF_KEYUP) | (KEYEVENTF_EXTENDEDKEY if name in EXTENDED else 0)
        _send(_key(vk=vk, flags=flags))

    def text(self, s: str):
        # type arbitrary unicode, independent of keyboard layout
        for code in memoryview(s.encode("utf-16-le")).cast("H"):
            _send(_key(scan=code, flags=KEYEVENTF_UNICODE),
                  _key(scan=code, flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))

    def combo(self, keys: list[str]):
        for k in keys:
            self.key(k, True)
        for k in reversed(keys):
            self.key(k, False)


# ------------------------------------------------------------- commands ---

def run_command(name: str) -> str:
    cmds = {
        "reboot": ["shutdown", "/r", "/t", "3", "/f"],
        "shutdown": ["shutdown", "/s", "/t", "3", "/f"],
        "cancel": ["shutdown", "/a"],
        "lock": ["rundll32.exe", "user32.dll,LockWorkStation"],
        "sleep": ["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"],
    }
    if name not in cmds:
        return f"unknown command: {name}"
    try:
        subprocess.Popen(cmds[name], creationflags=subprocess.CREATE_NO_WINDOW)
        return "ok"
    except Exception as e:  # noqa: BLE001
        return str(e)


# ----------------------------------------------------------- streaming ---

class Screen:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.sct = mss.mss()
        self.mon = self.sct.monitors[min(cfg["monitor"], len(self.sct.monitors) - 1)]
        self.quality = cfg["quality"]
        self.set_width(cfg["max_width"])
        self._last_hash = b""

    def set_width(self, max_width: int):
        w, h = self.mon["width"], self.mon["height"]
        scale = min(1.0, max_width / w)
        self.size = (max(2, int(w * scale)), max(2, int(h * scale)))
        self._last_hash = b""

    def adapt(self, rtt: float):
        """Trade picture quality for speed when the link is slow, and back."""
        cfg = self.cfg
        if rtt > 0.6 and self.quality > 25:
            self.quality = max(25, self.quality - 10)
        elif rtt > 0.6 and self.size[0] > 640:
            self.set_width(int(self.size[0] * 0.8))
        elif rtt < 0.15:
            if self.size[0] < min(cfg["max_width"], self.mon["width"]):
                self.set_width(min(cfg["max_width"], int(self.size[0] * 1.25)))
            elif self.quality < cfg["quality"]:
                self.quality = min(cfg["quality"], self.quality + 5)

    def grab(self) -> bytes | None:
        """Return a JPEG, or None if the screen hasn't changed."""
        shot = self.sct.grab(self.mon)
        digest = hashlib.blake2b(shot.raw, digest_size=8).digest()
        if digest == self._last_hash:
            return None
        self._last_hash = digest
        img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
        if self.size != shot.size:
            img = img.resize(self.size, Image.BILINEAR)
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=self.quality, optimize=False)
        return buf.getvalue()


class Agent:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.screen = Screen(cfg)
        self.input = Input(self.screen.mon)
        self.viewers = 0
        self.ack = asyncio.Event()
        self.ack.set()
        self.sent_at = 0.0
        self.rtt = 0.0  # smoothed send->ack time, drives quality adaptation

    def ws_url(self) -> str:
        base = self.cfg["relay_url"].rstrip("/")
        base = base.replace("https://", "wss://").replace("http://", "ws://")
        return f"{base}/ws/pc?token={self.cfg['secret']}"

    async def run(self):
        delay = 2
        while True:
            try:
                async with aiohttp.ClientSession() as s:
                    async with s.ws_connect(self.ws_url(), heartbeat=20, max_msg_size=0) as ws:
                        log.info("connected to relay")
                        delay = 2
                        await ws.send_str(json.dumps({"t": "hello", "w": self.screen.size[0],
                                                      "h": self.screen.size[1], "host": os.environ.get("COMPUTERNAME", "")}))
                        sender = asyncio.create_task(self.stream(ws))
                        try:
                            await self.receive(ws)
                        finally:
                            sender.cancel()
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001
                log.warning("relay connection lost: %s", e)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 60)

    async def stream(self, ws):
        interval = 1 / self.cfg["fps"]
        last_sent = 0.0
        loop = asyncio.get_running_loop()
        while True:
            if self.viewers == 0:
                await asyncio.sleep(0.5)
                continue
            t0 = time.monotonic()
            # wait until the phone has drawn the previous frame (or 1 s)
            try:
                await asyncio.wait_for(self.ack.wait(), 1.0)
            except asyncio.TimeoutError:
                pass
            jpeg = await loop.run_in_executor(None, self.screen.grab)
            if jpeg is None and time.monotonic() - last_sent < 2.0:
                await asyncio.sleep(interval)
                continue
            if jpeg is None:  # keep-alive frame every 2 s even if static
                self.screen._last_hash = b""
                jpeg = await loop.run_in_executor(None, self.screen.grab)
            self.ack.clear()
            self.sent_at = time.monotonic()
            await ws.send_bytes(jpeg)
            last_sent = self.sent_at
            if int(last_sent) % 3 == 0:
                self.screen.adapt(self.rtt)
            await asyncio.sleep(max(0, interval - (time.monotonic() - t0)))

    async def receive(self, ws):
        async for msg in ws:
            if msg.type != aiohttp.WSMsgType.TEXT:
                if msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.ERROR):
                    break
                continue
            try:
                ev = json.loads(msg.data)
            except ValueError:
                continue
            t = ev.get("t")
            try:
                if t == "ack":
                    if self.sent_at:
                        sample = time.monotonic() - self.sent_at
                        self.rtt = sample if not self.rtt else self.rtt * 0.7 + sample * 0.3
                    self.ack.set()
                elif t == "viewers":
                    self.viewers = int(ev.get("n", 0))
                    if self.viewers:
                        self.screen._last_hash = b""  # force a fresh frame
                elif t == "move":
                    self.input.move(float(ev["x"]), float(ev["y"]))
                elif t == "btn":
                    self.input.button(ev.get("b", "left"), bool(ev["down"]))
                elif t == "click":
                    self.input.move(float(ev["x"]), float(ev["y"]))
                    b = ev.get("b", "left")
                    for _ in range(int(ev.get("n", 1))):
                        self.input.button(b, True)
                        self.input.button(b, False)
                elif t == "wheel":
                    self.input.wheel(int(ev.get("dy", 0)), int(ev.get("dx", 0)))
                elif t == "key":
                    self.input.key(str(ev["k"]), bool(ev["down"]))
                elif t == "text":
                    self.input.text(str(ev["s"]))
                elif t == "combo":
                    self.input.combo([str(k) for k in ev["keys"]])
                elif t == "cmd":
                    res = run_command(str(ev.get("cmd")))
                    log.info("cmd %s -> %s", ev.get("cmd"), res)
                    await ws.send_str(json.dumps({"t": "cmd_result", "cmd": ev.get("cmd"), "result": res}))
            except Exception as e:  # noqa: BLE001
                log.warning("bad event %s: %s", ev, e)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        handlers=[logging.StreamHandler(),
                                  logging.FileHandler(HERE / "agent.log", encoding="utf-8")])
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)  # real pixel coordinates
    except Exception:  # noqa: BLE001
        pass
    cfg = load_config()
    try:
        asyncio.run(Agent(cfg).run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
