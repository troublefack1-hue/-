#!/usr/bin/env python3
"""
pc-remote agent (Windows).

Runs on the PC in the user's session. Connects OUT to the relay, streams
the screen as JPEG frames (and optionally the PC's sound) while someone is
watching, applies mouse/keyboard events coming from the phone and executes
commands (reboot, shutdown, lock, sleep).

Binary frames: first byte is the type.
  0x01 + JPEG                          video frame
  0x02 + rate(uint16 LE) + PCM16 mono  audio chunk

Config: config.json next to this file (see config.example.json).
"""
import array
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
FRAME_VIDEO, FRAME_AUDIO = b"\x01", b"\x02"
log = logging.getLogger("agent")

# Quality profiles the phone can switch between. "idle" = app in background.
PROFILES = {
    "eco":    {"fps": 2,  "max_width": 640,  "quality": 30},
    "normal": {"fps": 12, "max_width": 1280, "quality": 55},
    "hq":     {"fps": 20, "max_width": 1920, "quality": 75},
    "idle":   {"fps": 0,  "max_width": 640,  "quality": 30},
}

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
    cfg.setdefault("projects", [])   # folders offered in the terminal panel
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
    "VolumeMute": 0xAD, "VolumeDown": 0xAE, "VolumeUp": 0xAF,
    "MediaNext": 0xB0, "MediaPrev": 0xB1, "MediaStop": 0xB2, "MediaPlayPause": 0xB3,
}
EXTENDED = {"Delete", "Home", "End", "PageUp", "PageDown", "ArrowLeft", "ArrowUp",
            "ArrowRight", "ArrowDown", "Insert", "Meta", "PrintScreen"}


class Input:
    """Translates phone events into SendInput calls."""

    def __init__(self, monitor: dict):
        self.mon = monitor
        self.vx = user32.GetSystemMetrics(SM_XVIRTUALSCREEN)
        self.vy = user32.GetSystemMetrics(SM_YVIRTUALSCREEN)
        self.vw = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN)
        self.vh = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN)

    def move(self, fx: float, fy: float):
        fx, fy = min(1.0, max(0.0, fx)), min(1.0, max(0.0, fy))
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
        dy, dx = max(-1200, min(1200, dy)), max(-1200, min(1200, dx))
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
        for code in memoryview(s[:2000].encode("utf-16-le")).cast("H"):
            _send(_key(scan=code, flags=KEYEVENTF_UNICODE),
                  _key(scan=code, flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))

    def combo(self, keys: list[str]):
        keys = keys[:6]
        for k in keys:
            self.key(k, True)
        for k in reversed(keys):
            self.key(k, False)


# ------------------------------------------------------------- commands ---

kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
CF_UNICODETEXT, GMEM_MOVEABLE = 13, 0x0002
user32.GetClipboardData.restype = ctypes.c_void_p
kernel32.GlobalLock.restype = ctypes.c_void_p
kernel32.GlobalAlloc.restype = ctypes.c_void_p


def set_clipboard(text: str) -> bool:
    """Put text on the Windows clipboard (for 'paste from phone')."""
    text = text[:100_000]
    data = text.encode("utf-16-le") + b"\x00\x00"
    if not user32.OpenClipboard(None):
        return False
    try:
        user32.EmptyClipboard()
        h = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(data))
        p = kernel32.GlobalLock(h)
        ctypes.memmove(p, data, len(data))
        kernel32.GlobalUnlock(h)
        user32.SetClipboardData(CF_UNICODETEXT, h)
        return True
    finally:
        user32.CloseClipboard()


def get_clipboard() -> str | None:
    """Current clipboard text (≤ 10 KB) or None."""
    if not user32.OpenClipboard(None):
        return None
    try:
        h = user32.GetClipboardData(CF_UNICODETEXT)
        if not h:
            return None
        p = kernel32.GlobalLock(h)
        try:
            return ctypes.wstring_at(p)[:10_000]
        finally:
            kernel32.GlobalUnlock(h)
    finally:
        user32.CloseClipboard()


def open_url(url: str) -> str:
    """Open a web link on the PC in the default browser. http(s) only."""
    url = url.strip()[:2000]
    if not (url.startswith("http://") or url.startswith("https://")) or any(c in url for c in " \r\n\"'"):
        return "only http(s) links"
    os.startfile(url)  # noqa: S606 - validated scheme
    return "ok"

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


# ------------------------------------------------------------ terminal ---

class Term:
    """One console on the PC (PowerShell or the Claude Code CLI), streamed to the phone."""

    SHELLS = {
        "shell": "powershell.exe -NoLogo",
        "cmd": "cmd.exe",
        "claude": "cmd.exe /c claude",          # Claude Code CLI must be on PATH
    }

    @staticmethod
    def available() -> bool:
        try:
            import winpty  # noqa: F401
            return True
        except ImportError:
            return False

    def __init__(self, kind: str, cwd: str | None, cols: int, rows: int):
        import winpty
        cmd = self.SHELLS.get(kind, self.SHELLS["shell"])
        if cwd and not os.path.isdir(cwd):
            cwd = None
        self.proc = winpty.PtyProcess.spawn(cmd, cwd=cwd or os.path.expanduser("~"),
                                            dimensions=(max(5, min(rows, 200)), max(20, min(cols, 400))))

    def write(self, data: str):
        self.proc.write(data[:4096])

    def resize(self, cols: int, rows: int):
        self.proc.setwinsize(max(5, min(rows, 200)), max(20, min(cols, 400)))

    def read(self) -> str | None:
        """Blocking read of one chunk; None when the process has exited."""
        try:
            return self.proc.read(4096)
        except EOFError:
            return None

    def alive(self) -> bool:
        return self.proc.isalive()

    def close(self):
        try:
            self.proc.terminate(force=True)
        except Exception:  # noqa: BLE001
            pass


# ----------------------------------------------------------- streaming ---

class Screen:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.sct = mss.mss()
        self.mon_index = min(cfg["monitor"], len(self.sct.monitors) - 1)
        self.mon = self.sct.monitors[self.mon_index]
        self.profile = dict(PROFILES["normal"], fps=cfg["fps"], max_width=cfg["max_width"], quality=cfg["quality"])
        self.quality = self.profile["quality"]
        self.set_width(self.profile["max_width"])
        self._last_hash = b""

    def set_monitor(self, index: int):
        """0 = all monitors as one picture, 1..n = a single monitor."""
        index = max(0, min(int(index), len(self.sct.monitors) - 1))
        self.mon_index = index
        self.mon = self.sct.monitors[index]
        self.set_width(self.profile["max_width"])

    def set_profile(self, name: str):
        p = PROFILES.get(name)
        if not p:
            return
        if name == "normal":
            p = dict(p, fps=self.cfg["fps"], max_width=self.cfg["max_width"], quality=self.cfg["quality"])
        self.profile = dict(p)
        self.quality = p["quality"]
        self.set_width(p["max_width"])
        log.info("profile %s: %s", name, p)

    def set_width(self, max_width: int):
        w, h = self.mon["width"], self.mon["height"]
        scale = min(1.0, max_width / w)
        self.size = (max(2, int(w * scale)), max(2, int(h * scale)))
        self._last_hash = b""

    def adapt(self, rtt: float):
        """Trade picture quality for speed when the link is slow, and back."""
        top_w, top_q = self.profile["max_width"], self.profile["quality"]
        if rtt > 0.6 and self.quality > 25:
            self.quality = max(25, self.quality - 10)
        elif rtt > 0.6 and self.size[0] > 480:
            self.set_width(int(self.size[0] * 0.8))
        elif rtt < 0.15:
            if self.size[0] < min(top_w, self.mon["width"]):
                self.set_width(min(top_w, int(self.size[0] * 1.25)))
            elif self.quality < top_q:
                self.quality = min(top_q, self.quality + 5)

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


class Audio:
    """PC sound via WASAPI loopback (pyaudiowpatch), downmixed to 16 kHz mono."""

    RATE = 16000

    def __init__(self):
        self.stream = None
        self.pa = None
        self.src_rate = 48000
        self.channels = 2

    def available(self) -> bool:
        try:
            import pyaudiowpatch  # noqa: F401
            return True
        except ImportError:
            return False

    def start(self):
        import pyaudiowpatch as pyaudio
        self.pa = pyaudio.PyAudio()
        wasapi = self.pa.get_host_api_info_by_type(pyaudio.paWASAPI)
        dev = self.pa.get_device_info_by_index(wasapi["defaultOutputDevice"])
        if not dev.get("isLoopbackDevice"):
            for d in self.pa.get_loopback_device_info_generator():
                if dev["name"] in d["name"]:
                    dev = d
                    break
        self.src_rate = int(dev["defaultSampleRate"])
        self.channels = int(dev["maxInputChannels"])
        self.stream = self.pa.open(format=pyaudio.paInt16, channels=self.channels, rate=self.src_rate,
                                   input=True, input_device_index=dev["index"],
                                   frames_per_buffer=self.src_rate // 20)
        log.info("audio: %s @ %d Hz x%d", dev["name"], self.src_rate, self.channels)

    def stop(self):
        if self.stream:
            self.stream.stop_stream()
            self.stream.close()
            self.stream = None
        if self.pa:
            self.pa.terminate()
            self.pa = None

    def read(self) -> bytes:
        """One ~50 ms chunk as 16 kHz mono PCM16, framed for the relay."""
        raw = self.stream.read(self.src_rate // 20, exception_on_overflow=False)
        s = array.array("h", raw)
        ch, step = self.channels, max(1, round(self.src_rate / self.RATE))
        out = array.array("h")
        n = len(s) // ch
        for i in range(0, n, step):
            base = i * ch
            out.append(sum(s[base:base + ch]) // ch)
        return FRAME_AUDIO + self.RATE.to_bytes(2, "little") + out.tobytes()


class Agent:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.screen = Screen(cfg)
        self.input = Input(self.screen.mon)
        self.audio = Audio()
        self.audio_on = False
        self.viewers = 0
        self.ack = asyncio.Event()
        self.ack.set()
        self.sent_at = 0.0
        self.rtt = 0.0  # smoothed send->ack time, drives quality adaptation
        self.terms: dict[str, Term] = {}

    def ws_url(self) -> str:
        base = self.cfg["relay_url"].rstrip("/")
        base = base.replace("https://", "wss://").replace("http://", "ws://")
        return f"{base}/ws/pc"

    async def run(self):
        delay = 2
        while True:
            try:
                async with aiohttp.ClientSession() as s:
                    async with s.ws_connect(self.ws_url(), heartbeat=20, max_msg_size=64 * 1024) as ws:
                        await ws.send_str(json.dumps({"t": "auth", "token": self.cfg["secret"]}))
                        log.info("connected to relay")
                        delay = 2
                        await ws.send_str(json.dumps({
                            "t": "hello", "w": self.screen.size[0], "h": self.screen.size[1],
                            "host": os.environ.get("COMPUTERNAME", ""), "audio": self.audio.available(),
                            "monitors": len(self.screen.sct.monitors) - 1, "monitor": self.screen.mon_index,
                            "term": Term.available(), "projects": self.cfg.get("projects", [])}))
                        tasks = [asyncio.create_task(self.stream(ws)), asyncio.create_task(self.stream_audio(ws)),
                                 asyncio.create_task(self.watch_clipboard(ws))]
                        try:
                            await self.receive(ws)
                        finally:
                            for t in tasks:
                                t.cancel()
                            for term in self.terms.values():
                                term.close()
                            self.terms.clear()
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001
                log.warning("relay connection lost: %s", e)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 60)

    async def stream(self, ws):
        last_sent = 0.0
        loop = asyncio.get_running_loop()
        while True:
            fps = self.screen.profile["fps"]
            if self.viewers == 0 or fps == 0:
                await asyncio.sleep(0.5)
                continue
            interval = 1 / fps
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
            await ws.send_bytes(FRAME_VIDEO + jpeg)
            last_sent = self.sent_at
            if int(last_sent) % 3 == 0:
                self.screen.adapt(self.rtt)
            await asyncio.sleep(max(0, interval - (time.monotonic() - t0)))

    async def stream_audio(self, ws):
        loop = asyncio.get_running_loop()
        while True:
            if not (self.audio_on and self.viewers and self.screen.profile["fps"]):
                if self.audio.stream:
                    self.audio.stop()
                await asyncio.sleep(0.3)
                continue
            if not self.audio.stream:
                try:
                    await loop.run_in_executor(None, self.audio.start)
                except Exception as e:  # noqa: BLE001
                    log.warning("audio unavailable: %s", e)
                    self.audio_on = False
                    await ws.send_str(json.dumps({"t": "audio", "on": False, "error": str(e)}))
                    continue
            chunk = await loop.run_in_executor(None, self.audio.read)
            await ws.send_bytes(chunk)

    async def watch_clipboard(self, ws):
        """PC clipboard -> phone, whenever it changes while someone is watching."""
        loop = asyncio.get_running_loop()
        last_seq = user32.GetClipboardSequenceNumber()
        while True:
            await asyncio.sleep(1.0)
            if not self.viewers:
                continue
            seq = user32.GetClipboardSequenceNumber()
            if seq == last_seq:
                continue
            last_seq = seq
            text = await loop.run_in_executor(None, get_clipboard)
            if text and text.strip():
                await ws.send_str(json.dumps({"t": "pc_clip", "s": text}))

    async def term_pump(self, ws, tid: str, term: Term):
        """Reads console output in a thread and ships it to the phone."""
        loop = asyncio.get_running_loop()
        try:
            while True:
                chunk = await loop.run_in_executor(None, term.read)
                if chunk is None:
                    break
                await ws.send_str(json.dumps({"t": "term_out", "id": tid, "data": chunk}))
        except Exception as e:  # noqa: BLE001
            log.info("term %s ended: %s", tid, e)
        finally:
            self.terms.pop(tid, None)
            term.close()
            try:
                await ws.send_str(json.dumps({"t": "term_exit", "id": tid}))
            except Exception:  # noqa: BLE001
                pass

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
                elif t == "profile":
                    self.screen.set_profile(str(ev.get("name", "normal")))
                elif t == "audio":
                    self.audio_on = bool(ev.get("on"))
                elif t == "move":
                    self.input.move(float(ev["x"]), float(ev["y"]))
                elif t == "btn":
                    self.input.button(ev.get("b", "left"), bool(ev["down"]))
                elif t == "click":
                    self.input.move(float(ev["x"]), float(ev["y"]))
                    b = ev.get("b", "left")
                    for _ in range(max(1, min(3, int(ev.get("n", 1))))):
                        self.input.button(b, True)
                        self.input.button(b, False)
                elif t == "wheel":
                    self.input.wheel(int(ev.get("dy", 0)), int(ev.get("dx", 0)))
                elif t == "key":
                    self.input.key(str(ev["k"]), bool(ev["down"]))
                elif t == "text":
                    self.input.text(str(ev["s"]))
                elif t == "clip":  # long text from the phone: clipboard + Ctrl+V
                    if set_clipboard(str(ev["s"])):
                        self.input.combo(["Control", "v"])
                elif t == "open_url":
                    res = open_url(str(ev.get("url", "")))
                    await ws.send_str(json.dumps({"t": "cmd_result", "cmd": "open_url", "result": res}))
                elif t == "monitor":
                    self.screen.set_monitor(int(ev.get("n", 1)))
                elif t == "term_open":
                    tid = str(ev.get("id", "t1"))[:16]
                    if tid in self.terms or len(self.terms) >= 4:
                        continue
                    if not Term.available():
                        await ws.send_str(json.dumps({"t": "term_out", "id": tid,
                                                      "data": "\r\nНа ПК не установлен pywinpty (pip install pywinpty)\r\n"}))
                        await ws.send_str(json.dumps({"t": "term_exit", "id": tid}))
                        continue
                    term = Term(str(ev.get("kind", "shell")), ev.get("cwd"), int(ev.get("cols", 80)), int(ev.get("rows", 24)))
                    self.terms[tid] = term
                    asyncio.create_task(self.term_pump(ws, tid, term))
                elif t == "term_in":
                    term = self.terms.get(str(ev.get("id", "")))
                    if term:
                        term.write(str(ev.get("data", "")))
                elif t == "term_resize":
                    term = self.terms.get(str(ev.get("id", "")))
                    if term:
                        term.resize(int(ev.get("cols", 80)), int(ev.get("rows", 24)))
                elif t == "term_close":
                    term = self.terms.pop(str(ev.get("id", "")), None)
                    if term:
                        term.close()
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
