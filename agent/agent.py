#!/usr/bin/env python3
"""
pc-remote agent (Windows).

Runs on the PC in the user's session. Connects OUT to the relay, streams
the screen as JPEG frames (and optionally the PC's sound) while someone is
watching, applies mouse/keyboard events coming from the phone and executes
commands (reboot, shutdown, lock, sleep).

Binary frames: first byte is the type.
  0x01 + JPEG                          video frame
  0x02 + rate(uint16 LE) + PCM16 mono  audio chunk (0x0A + packet when the phone decodes Opus, see opus.py)

Config: config.json next to this file (see config.example.json).
"""
import array
import asyncio
import struct
import collections
import ctypes
import hashlib
import io
import json
import logging
import logging.handlers
import os
import subprocess
import threading
import sys
import time
from ctypes import wintypes
from pathlib import Path

import aiohttp
import mss
from PIL import Image

import audio_out
import capture
import extras
import notify_watch
import opus
import video

HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "config.json"
FRAME_VIDEO, FRAME_AUDIO, FRAME_ZONE = b"\x01", b"\x02", b"\x05"
log = logging.getLogger("agent")

# Quality profiles the phone can switch between. "idle" = app in background.
PROFILES = {
    # the page calls these traffic ceilings: tiny "до 15 КБ/с", eco "до 100 КБ/с", normal "до 1 МБ/с", hq "без
    # ограничений" (CEILING). fps is only the most the grab loop tries: the network decides the real rate.
    "tiny":   {"fps": 12, "max_width": 480,  "quality": 22},   # Opus 16 kbit/s
    "eco":    {"fps": 12, "max_width": 960,  "quality": 30},
    "normal": {"fps": 12, "max_width": 1280, "quality": 55},
    "hq":     {"fps": 20, "max_width": 1920, "quality": 75},
    "idle":   {"fps": 0,  "max_width": 640,  "quality": 30},
}

# Adaptive ladder for the video path: (max_width, fps, bitrate). Rung 0 = the profile as chosen;
# the agent walks down as fast as the link degrades (measured throughput, late acks) and climbs
# back one rung at a time when there is headroom. Bottom rung is "potato": 320 px, 2 fps, 24 kbit/s,
# which still keeps the cursor and clicks responsive on a near-dead link.
LADDER = [
    (None, None, None),        # 0: profile's own settings
    (1280, 12, "1200k"),
    (960, 10, "600k"),
    (720, 8, "300k"),
    (480, 6, "150k"),
    (480, 6, "64k"),           # the bottom favours frames per second over pixels: movement and feedback matter
    (320, 6, "40k"),           # more than detail on a thin link, and detail comes from zooming into a region
    (320, 4, "24k"),
]
TINY_RUNG = 5                   # the "10 KB/s" profile never goes above this
ECO_RUNG = 2                    # "до 100 КБ/с": 960 px at 600 kbit/s at most
# bytes/s the owner allows per profile (the page's "Трафик" buttons); None = whatever the network gives
CEILING = {"tiny": 15_000, "eco": 100_000, "normal": 1_000_000, "hq": None}
# the page's "Кадр" buttons: how much better (+) or lighter (-) than the profile's quality, in crf/cq steps
PQ_BOOST = {"smooth": -4, "normal": 0, "sharp": 3, "max": 6}


def _kbit(br: str) -> int:
    return int(br.rstrip("k"))


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
try:  # physical pixels everywhere: mss captures them, so mouse coordinates must use them too
    ctypes.WinDLL("shcore").SetProcessDpiAwareness(2)
except Exception:  # noqa: BLE001
    pass

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


class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]


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
    "BrowserBack": 0xA6, "BrowserForward": 0xA7, "BrowserHome": 0xAC,
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

    def _refresh_metrics(self):
        self.vx = user32.GetSystemMetrics(SM_XVIRTUALSCREEN)
        self.vy = user32.GetSystemMetrics(SM_YVIRTUALSCREEN)
        self.vw = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN)
        self.vh = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN)

    def move(self, fx: float, fy: float):
        self._refresh_metrics()
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


_CLIP_PRIVATE_FMTS: list = []


def _clipboard_private() -> bool:
    """Password managers mark secrets so clipboard tools leave them alone; honour that.
    Must be called with the clipboard open."""
    global _CLIP_PRIVATE_FMTS
    if not _CLIP_PRIVATE_FMTS:
        _CLIP_PRIVATE_FMTS = [user32.RegisterClipboardFormatW("ExcludeClipboardContentFromMonitorProcessing"),
                              user32.RegisterClipboardFormatW("CanIncludeInClipboardHistory"),
                              user32.RegisterClipboardFormatW("CanUploadToCloudClipboard")]
    try:
        if user32.IsClipboardFormatAvailable(_CLIP_PRIVATE_FMTS[0]):
            return True
        for fmt in _CLIP_PRIVATE_FMTS[1:]:          # present with value 0 = "do not keep / do not sync"
            if user32.IsClipboardFormatAvailable(fmt):
                h = user32.GetClipboardData(fmt)
                if h:
                    p = kernel32.GlobalLock(h)
                    try:
                        if p and ctypes.cast(p, ctypes.POINTER(wintypes.DWORD)).contents.value == 0:
                            return True
                    finally:
                        kernel32.GlobalUnlock(h)
    except Exception:  # noqa: BLE001
        return False
    return False


def get_clipboard() -> str | None:
    """Current clipboard text (≤ 10 KB) or None; None also for content marked private."""
    if not user32.OpenClipboard(None):
        return None
    try:
        if _clipboard_private():
            return None
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

# ------------------------------------------------------------ windows ---
# The phone's "□" button shows the open windows like Android's recents.

_EnumProc = (ctypes.WINFUNCTYPE if hasattr(ctypes, "WINFUNCTYPE") else ctypes.CFUNCTYPE)(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
_DWM = None


def _cloaked(hwnd) -> bool:
    """UWP windows that are suspended/hidden stay 'visible' but cloaked."""
    global _DWM
    try:
        if _DWM is None:
            _DWM = ctypes.WinDLL("dwmapi")
        v = ctypes.c_int(0)
        _DWM.DwmGetWindowAttribute(ctypes.c_void_p(hwnd), 14, ctypes.byref(v), ctypes.sizeof(v))
        return bool(v.value)
    except Exception:  # noqa: BLE001
        return False


def list_windows() -> list:
    out = []
    fg = user32.GetForegroundWindow()
    me = os.getpid()
    try:
        import psutil
    except ImportError:
        psutil = None

    def cb(hwnd, _):
        if not user32.IsWindowVisible(hwnd):
            return True
        ex = user32.GetWindowLongW(hwnd, -20)
        if ex & 0x80 and not ex & 0x40000:  # tool window without app-window flag
            return True
        n = user32.GetWindowTextLengthW(hwnd)
        if n == 0:
            return True
        buf = ctypes.create_unicode_buffer(n + 1)
        user32.GetWindowTextW(hwnd, buf, n + 1)
        title = buf.value.strip()
        if not title or title in ("Program Manager", "Windows Input Experience") or _cloaked(hwnd):
            return True
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value == me:
            return True
        proc = ""
        if psutil:
            try:
                proc = psutil.Process(pid.value).name().rsplit(".", 1)[0]
            except Exception:  # noqa: BLE001
                proc = ""
        out.append({"hwnd": int(hwnd), "title": title[:80], "proc": proc, "active": hwnd == fg,
                    "min": bool(user32.IsIconic(hwnd))})
        return True
    user32.EnumWindows(_EnumProc(cb), 0)
    return out[:40]


def window_action(op: str, hwnd: int) -> str:
    hwnd = int(hwnd)
    if not user32.IsWindow(hwnd):
        return "нет окна"
    if op == "focus":
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, 9)  # SW_RESTORE
        # Windows refuses SetForegroundWindow from a background process unless a key was just pressed
        user32.keybd_event(0x12, 0, 0, 0); user32.keybd_event(0x12, 0, 2, 0)  # tap Alt
        user32.SetForegroundWindow(hwnd)
        user32.BringWindowToTop(hwnd)
    elif op == "close":
        user32.PostMessageW(hwnd, 0x0010, 0, 0)  # WM_CLOSE
    elif op == "min":
        user32.ShowWindow(hwnd, 6)
    elif op == "max":
        user32.ShowWindow(hwnd, 3)
    else:
        return "unknown"
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
        if name in ("shutdown", "reboot") and os.name == "nt":
            # a pending timer (shutdown /t N) makes a second shutdown call fail with "already scheduled"
            subprocess.run(["shutdown", "/a"], creationflags=subprocess.CREATE_NO_WINDOW, capture_output=True)
        if name == "sleep" and os.name == "nt":
            # rundll32 passes its argument as a string pointer, so SetSuspendState sees "hibernate"
            # as true whenever hibernation is enabled; the direct call sleeps as asked
            def _sleep():
                ctypes.WinDLL("powrprof").SetSuspendState(0, 1, 0)
            threading.Thread(target=_sleep, daemon=True).start()
            return "ok"
        subprocess.Popen(cmds[name], creationflags=subprocess.CREATE_NO_WINDOW)
        return "ok"
    except Exception as e:  # noqa: BLE001
        return str(e)


# ------------------------------------------------------------ terminal ---

def find_git_bash() -> str | None:
    """bash.exe from Git for Windows, if installed."""
    for base in (os.environ.get("ProgramFiles"), os.environ.get("ProgramFiles(x86)"),
                 os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs")):
        if base:
            p = os.path.join(base, "Git", "bin", "bash.exe")
            if os.path.isfile(p):
                return p
    return None


import re
ATTENTION_RE = re.compile(r"Do you want to (proceed|make this edit|run this command|create)|Would you like to|\(esc\)|Yes, and don't ask again|Esc to cancel")
ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07|[\r\x07]")


class Term:
    """One console on the PC (Git Bash, PowerShell, cmd or the Claude Code CLI), streamed to the phone."""

    BIN = ""   # folder with the `phone` CLI (set by the PC app); goes on PATH of every terminal

    @staticmethod
    def shells() -> dict:
        bash = find_git_bash()
        d = {}
        # Claude Code learns about the phone from PHONE.md (claude-phone.sh appends it to the system prompt)
        launcher = os.path.join(Term.BIN, "claude-phone.sh").replace("\\", "/") if Term.BIN else ""
        # invoke via `sh` so it runs even though copyfile did not set the execute bit
        claude = f"sh '{launcher}'" if launcher and os.path.isfile(launcher) else "claude"
        # argv lists, not strings: pywinpty splits a string with shlex(posix=False), which keeps the quotes, so
        # '"C:\Program Files\Git\bin\bash.exe" …' was "not found" and the Git Bash / Claude tabs never opened
        if bash:
            d["bash"] = [bash, "--login", "-i"]
            d["claude"] = [bash, "--login", "-i", "-c", claude]   # Claude Code inside Git Bash
        d["shell"] = ["powershell.exe", "-NoLogo"]
        d["cmd"] = ["cmd.exe"]
        d.setdefault("claude", ["cmd.exe", "/c", "claude"])      # Claude Code CLI must be on PATH
        return d

    @staticmethod
    def available() -> bool:
        try:
            import winpty  # noqa: F401
            return True
        except ImportError:
            return False

    def __init__(self, kind: str, cwd: str | None, cols: int, rows: int):
        import winpty
        shells = self.shells()
        cmd = shells.get(kind) or shells.get("bash") or shells["shell"]
        if cwd and not os.path.isdir(cwd):
            cwd = None
        env = dict(os.environ)
        if Term.BIN:
            env["PATH"] = Term.BIN + os.pathsep + env.get("PATH", "")
        self.proc = winpty.PtyProcess.spawn(cmd, cwd=cwd or os.path.expanduser("~"), env=env,
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
        # kill the whole tree: bash -> claude/node would otherwise live on as orphans after the tab closes
        try:
            pid = int(getattr(self.proc, "pid", 0) or 0)
            if pid and os.name == "nt":
                subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)], capture_output=True, timeout=10,
                               creationflags=subprocess.CREATE_NO_WINDOW)
        except Exception:  # noqa: BLE001
            pass
        try:
            self.proc.terminate(force=True)
        except Exception:  # noqa: BLE001
            pass


# -------------------------------------------------------------- volume ---

class Volume:
    """Master volume via Windows Core Audio (pycaw); falls back to media keys."""

    def __init__(self):
        self.ep = None
        try:
            from ctypes import POINTER, cast
            from comtypes import CLSCTX_ALL
            from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
            dev = AudioUtilities.GetSpeakers()
            if hasattr(dev, "EndpointVolume"):   # pycaw 2025+: a wrapper with the interface ready ("no attribute 'Activate'")
                self.ep = dev.EndpointVolume
            else:
                iface = dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
                self.ep = cast(iface, POINTER(IAudioEndpointVolume))
        except Exception as e:  # noqa: BLE001
            log.info("pycaw unavailable (%s): volume via media keys only", e)

    def get(self) -> dict:
        if not self.ep:
            return {"level": None, "mute": None}
        try:
            return {"level": int(round(self.ep.GetMasterVolumeLevelScalar() * 100)), "mute": bool(self.ep.GetMute())}
        except Exception:  # noqa: BLE001
            return {"level": None, "mute": None}

    def set(self, level: int | None = None, mute: bool | None = None, inp: "Input | None" = None):
        if self.ep:
            try:
                if level is not None:
                    self.ep.SetMasterVolumeLevelScalar(max(0, min(100, int(level))) / 100, None)
                if mute is not None:
                    self.ep.SetMute(bool(mute), None)
                return
            except Exception as e:  # noqa: BLE001
                log.warning("volume: %s", e)
        if inp is not None:  # fallback: media keys
            if mute is not None:
                inp.key("VolumeMute", True); inp.key("VolumeMute", False)
            elif level is not None:
                for _ in range(3):
                    inp.key("VolumeUp" if level >= 50 else "VolumeDown", True)
                    inp.key("VolumeUp" if level >= 50 else "VolumeDown", False)


# ----------------------------------------------------------- streaming ---

class Screen:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.sct = mss.mss()
        self.mon_index = min(cfg["monitor"], len(self.sct.monitors) - 1)
        self.mon = self.sct.monitors[self.mon_index]
        self.view_w = 0   # reported by the phone page ({"t":"view"}); 0 = unknown, no cap. Before set_width runs.
        self.uncapped = False   # Wi-Fi with room: the screen's own size, not the phone's width (see video_step)
        self.profile = dict(PROFILES["normal"], fps=cfg["fps"], max_width=cfg["max_width"], quality=cfg["quality"])
        self.quality = self.profile["quality"]
        self.set_width(self.profile["max_width"])
        self._last_hash = b""
        self.zone = None          # {"x","y","w","h"} fractions of the monitor: streamed in full resolution
        self._zone_hash = b""
        self.profile_name = "normal"
        # capture backend: "auto" prefers DXGI and falls back to GDI on failure (retrying later)
        self.capture_mode = str(cfg.get("capture", "auto"))
        self.cap = None
        self.cap_retry_at = 0.0
        self.cap_switches = 0
        # the main stream and the HD zone grab from executor threads while the network loop may swap the backend:
        # without a lock _grab once met self.cap = None mid-swap ('NoneType' has no attribute 'last_ms'), and DXGI
        # is not meant to be driven from two threads at once
        self._cap_lock = threading.RLock()
        self._dxgi_quiet_since = 0.0   # DXGI has said "unchanged" since then
        self._dxgi_checked_at = 0.0    # last GDI cross-check of a silent DXGI
        self._gdi_check_hash = b""     # that check's previous GDI frame
        self._stale_hits = []          # times DXGI was found deaf (twice a minute -> GDI for a while)
        self.grab_ms = 0.0
        self._make_capture()

    def _make_capture(self, prefer_gdi: bool = False):
        with self._cap_lock:
            self._make_capture_locked(prefer_gdi)

    def _make_capture_locked(self, prefer_gdi: bool):
        if self.cap is not None:
            self.cap.close()
            self.cap = None
        want_dxgi = self.capture_mode in ("auto", "dxgi") and not prefer_gdi and self.mon_index >= 1
        if want_dxgi and capture.dxgi_available():
            try:
                self.cap = capture.DxgiCapture(self.mon_index - 1, self.mon)
            except Exception as e:  # noqa: BLE001
                log.info("dxgi capture unavailable (%s): using gdi", e)
                self.cap_retry_at = time.monotonic() + 60
        if self.cap is None:
            self.cap = capture.MssCapture(self.sct, self.mon)
        log.info("capture: %s (monitor %d)", self.cap.name, self.mon_index)

    def set_capture_mode(self, mode: str):
        mode = mode if mode in ("auto", "dxgi", "gdi") else "auto"
        if mode != self.capture_mode:
            self.capture_mode = mode
            self.cap_retry_at = 0.0
            self._make_capture()

    def _grab(self, region=None, force=False):
        """One frame from the current backend; a DXGI failure flips to GDI and schedules a retry."""
        with self._cap_lock:
            return self._grab_locked(region, force)

    def _grab_locked(self, region, force):
        now = time.monotonic()
        if self.cap.name == "gdi" and self.capture_mode in ("auto", "dxgi") and self.cap_retry_at and now > self.cap_retry_at and self.mon_index >= 1:
            self.cap_retry_at = 0.0
            self._make_capture()            # try DXGI again
        try:
            out = self.cap.grab(region, force)
        except Exception as e:  # noqa: BLE001
            if self.cap.name == "dxgi" and self.capture_mode != "dxgi":
                log.warning("dxgi capture failed (%s): gdi for the next minute", e)
                self.cap_switches += 1
                self.cap_retry_at = now + 60
                self._make_capture(prefer_gdi=True)
                out = self.cap.grab(region, force)
            else:
                raise
        if out is None and force and self.cap.name == "dxgi" and (region is not None or getattr(self.cap, "last", None) is None):
            # DXGI hands out changes only: right after a (re)start, with a still or sleeping display, there was no
            # frame at all and the phone stayed black until something moved on the PC. This one frame via GDI.
            try:
                out = capture.MssCapture(self.sct, self.mon).grab(region, True)
                if region is None and out:
                    self.cap.last = out
            except Exception as e:  # noqa: BLE001
                log.info("first frame via gdi failed: %s", e)
        if self.cap.name == "dxgi" and region is None:
            # A DXGI duplication can go deaf (seen 06.10.2026 after the display dropped to 1024x768): it keeps
            # saying "unchanged", and the phone showed a 35-minute-old desktop. While it is silent, check every
            # 2 s with one GDI frame; a different picture means DXGI is stale: hand out the GDI frame, rebuild DXGI.
            if out is not None:
                self._dxgi_quiet_since = 0.0
                self._gdi_check_hash = b""   # a fresh quiet period starts its comparison anew
            else:
                self._dxgi_quiet_since = self._dxgi_quiet_since or now
                if now - self._dxgi_quiet_since > 2 and now - self._dxgi_checked_at > 2:
                    self._dxgi_checked_at = now
                    try:
                        g = capture.MssCapture(self.sct, self.mon).grab(None, True)
                        # compare GDI with the previous GDI check, never with DXGI's frame: the two never match byte
                        # for byte (alpha channel), and that rebuilt DXGI every 2 s until PC Remote fell (06.10 08:03)
                        gh = hashlib.blake2b(g[0], digest_size=8).digest() if g else b""
                        prev, self._gdi_check_hash = self._gdi_check_hash, gh
                        if g and prev and gh != prev:
                            self.cap_switches += 1
                            self._stale_hits = [t for t in self._stale_hits if now - t < 60] + [now]
                            if len(self._stale_hits) >= 2:
                                # rebuilt and still deaf: with the display off or in a fallback mode the desktop
                                # duplication gets no updates at all (06.10.2026, 1024x768) — GDI for 5 minutes
                                log.warning("dxgi deaf again: gdi for the next 5 minutes")
                                self.cap_retry_at = now + 300
                                self._stale_hits = []
                                self._make_capture_locked(True)
                            else:
                                log.warning("dxgi stale (screen changed, dxgi silent): rebuilding capture")
                                self._make_capture_locked(False)
                                if self.cap.name == "dxgi":
                                    self.cap.last = g
                            self._dxgi_quiet_since = 0.0
                            out = g
                    except Exception as e:  # noqa: BLE001
                        log.info("dxgi staleness check failed: %s", e)
        self.grab_ms = self.cap.last_ms if not self.grab_ms else self.grab_ms * 0.8 + self.cap.last_ms * 0.2
        return out

    def grab_raw(self, still: bool = False, region=None):
        """Raw pixels for the video encoder: (bytes, pix_fmt, (w, h)); None if unchanged.
        still=True: the current picture even if unchanged (sharpening a still screen).
        region=(x, y, w, h) in fractions of the monitor: only that part, scaled no wider than the full frame would
        be — a third of the screen then gets three times the detail for the same bytes (zoomed-in phone)."""
        px = None
        if region:
            mw, mh = self.mon["width"], self.mon["height"]
            px = (int(region[0] * mw) // 2 * 2, int(region[1] * mh) // 2 * 2,
                  max(16, int(region[2] * mw) // 2 * 2), max(16, int(region[3] * mh) // 2 * 2))
        got = self._grab(px, force=still or not self._last_hash)
        if got is None:
            return None
        raw, size = got
        digest = hashlib.blake2b(raw, digest_size=8).digest()
        if digest == self._last_hash and not still:
            return None
        self._last_hash = digest
        target = self.size
        if px:
            scale = min(1.0, self.size[0] / size[0])
            target = (max(16, int(size[0] * scale) // 2 * 2), max(16, int(size[1] * scale) // 2 * 2))
        if target == size:
            return raw, "bgra", size
        img = Image.frombytes("RGB", size, raw, "raw", "BGRX").resize(target, Image.BILINEAR)
        return img.tobytes(), "rgb24", target

    def set_zone(self, rect):
        if not rect:
            self.zone = None
            return
        x = min(max(float(rect.get("x", 0)), 0.0), 0.98); y = min(max(float(rect.get("y", 0)), 0.0), 0.98)
        w = min(max(float(rect.get("w", 0)), 0.02), 1.0 - x); h = min(max(float(rect.get("h", 0)), 0.02), 1.0 - y)
        self.zone = {"x": x, "y": y, "w": w, "h": h}
        self._zone_hash = b""

    def grab_zone(self) -> bytes | None:
        """The HD zone (a video player, say) at native resolution and high JPEG quality.
        Frame = 0x05 + 4×uint16 (x, y, w, h as 1/10000 of the monitor) + JPEG."""
        z = self.zone
        if not z:
            return None
        mw, mh = self.mon["width"], self.mon["height"]
        region = (int(z["x"] * mw), int(z["y"] * mh), max(2, int(z["w"] * mw)), max(2, int(z["h"] * mh)))
        got = self._grab(region)
        if got is None:
            return None
        raw, size = got
        digest = hashlib.blake2b(raw, digest_size=8).digest()
        if digest == self._zone_hash:
            return None
        self._zone_hash = digest
        img = Image.frombytes("RGB", size, raw, "raw", "BGRX")
        if img.width > 1920:
            img = img.resize((1920, int(img.height * 1920 / img.width)), Image.BILINEAR)
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=82, optimize=False)
        import struct
        hdr = struct.pack("<4H", *(int(z[k] * 10000) for k in ("x", "y", "w", "h")))
        return hdr + buf.getvalue()

    def detect_zone(self, shots: int = 6, gap: float = 0.12):
        """Find where the picture moves (a playing video) by diffing a few small greyscale shots."""
        from PIL import ImageChops
        prev = None
        acc = None
        for _ in range(shots):
            shot = self.sct.grab(self.mon)
            g = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX").convert("L")
            g = g.resize((320, max(1, int(320 * shot.height / shot.width))), Image.BILINEAR)
            if prev is not None:
                d = ImageChops.difference(g, prev).point(lambda p: 255 if p > 24 else 0)
                acc = d if acc is None else ImageChops.lighter(acc, d)
            prev = g
            time.sleep(gap)
        if acc is None:
            return None
        bbox = acc.getbbox()
        if not bbox:
            return None
        W, H = acc.size
        x0, y0, x1, y1 = bbox
        if (x1 - x0) * (y1 - y0) < 0.02 * W * H:
            return None
        pad = 0.01
        return {"x": max(0.0, x0 / W - pad), "y": max(0.0, y0 / H - pad),
                "w": min(1.0, (x1 - x0) / W + 2 * pad), "h": min(1.0, (y1 - y0) / H + 2 * pad)}

    def set_monitor(self, index: int):
        """0 = all monitors as one picture, 1..n = a single monitor."""
        index = max(0, min(int(index), len(self.sct.monitors) - 1))
        self.mon_index = index
        self.mon = self.sct.monitors[index]
        self.set_width(self.profile["max_width"])
        if self.cap is not None:
            self._make_capture()

    def set_profile(self, name: str):
        p = PROFILES.get(name)
        if not p:
            return
        self.profile_name = name
        if name == "normal":
            p = dict(p, fps=self.cfg["fps"], max_width=self.cfg["max_width"], quality=self.cfg["quality"])
        elif name == "idle":   # keep the frame size: coming back to the foreground then costs a P-frame, not a key frame
            p = dict(p, max_width=self.profile["max_width"])
        self.profile = dict(p)
        self.quality = p["quality"]
        self.set_width(p["max_width"])
        log.info("profile %s: %s", name, p)

    def set_width(self, max_width: int):
        # no wider than the phone shows (view_w = device pixels of the picture's width on its screen): an upright
        # phone shows ~1080 px of a 1920 desktop, the rest was encoded and sent for nothing; 480 px floor.
        # Not on Wi-Fi with room (uncapped): there the owner wants the screen's own pixels for zooming.
        if self.view_w and not self.uncapped:
            max_width = min(max_width, max(480, self.view_w))
        w, h = self.mon["width"], self.mon["height"]
        scale = min(1.0, max_width / w)
        # even sides: H.264 in yuv420p refuses odd ones, and widths now follow the phone's screen (any number)
        self.size = (max(2, int(w * scale) // 2 * 2), max(2, int(h * scale) // 2 * 2))
        self._last_hash = b""

    def adapt(self, rtt: float):
        """Trade picture quality for speed when the link is slow, and back."""
        top_w, top_q = self.profile["max_width"], self.profile["quality"]
        if rtt > 0.6 and self.quality > 25:
            self.quality = max(25, self.quality - 10)
        elif rtt > 0.6 and self.size[0] > 320:
            self.set_width(max(320, int(self.size[0] * 0.8)))
        elif rtt > 0.6 and self.quality > 15:
            self.quality = max(15, self.quality - 5)   # "potato": 320 px at quality 15, but the clicks still land
        elif rtt < 0.15:
            if self.size[0] < min(top_w, self.mon["width"]):
                self.set_width(min(top_w, int(self.size[0] * 1.25)))
            elif self.quality < top_q:
                self.quality = min(top_q, self.quality + 5)

    def displays_changed(self) -> bool:
        """Cheap poll: did the monitor layout or a resolution change since we started capturing?"""
        try:
            with mss.mss() as probe:
                now = [dict(m) for m in probe.monitors]
        except Exception:  # noqa: BLE001
            return False
        return now != [dict(m) for m in self.sct.monitors]

    def reinit(self):
        """Displays changed (monitor unplugged, resolution switch): start over."""
        try:
            self.sct.close()
        except Exception:  # noqa: BLE001
            pass
        self.sct = mss.mss()
        self.set_monitor(self.mon_index)

    def grab(self) -> bytes | None:
        """Return a JPEG, or None if the screen hasn't changed. Raises on capture failure."""
        got = self._grab(force=not self._last_hash)
        if got is None:
            return None
        raw, size = got
        digest = hashlib.blake2b(raw, digest_size=8).digest()
        if digest == self._last_hash:
            return None
        self._last_hash = digest
        img = Image.frombytes("RGB", size, raw, "raw", "BGRX")
        if self.size != size:
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
        self.enc = None            # OpusEncoder when the phone can decode Opus and ffmpeg has libopus
        self.prev_default = ""     # output device to restore after a temporary switch
        self.device_name = ""
        self.only_warn = ""        # why "only on the phone" could not be honoured, for the phone's toast

    def available(self) -> bool:
        try:
            import pyaudiowpatch  # noqa: F401
            return True
        except ImportError:
            return False

    def start(self, source: str = "speakers", only: bool = False, device: str = "", opus_ffmpeg: str | None = None, bitrate: str = "24k"):
        """only: the sound should play on the phone and NOT in the room: move Windows' default output to a
        silent endpoint (device id, or the best guess) for as long as we capture, restore in stop().
        opus_ffmpeg: path to ffmpeg with libopus, or None for raw PCM frames."""
        import pyaudiowpatch as pyaudio
        self.only_warn = ""
        if source != "mic" and only:
            cur = audio_out.default_id()
            target = device or ((audio_out.pick_silent(cur) or {}).get("id", ""))
            if not target:
                self.only_warn = "На ПК только один выход звука: динамики будут играть вместе с телефоном. Добавьте HDMI монитора/S-PDIF или VB-Cable."
            elif cur and cur != target:
                if audio_out.set_default(target):
                    self.prev_default = cur
                    time.sleep(0.4)   # let the audio engine bring the endpoint up before we open loopback on it
                else:
                    self.only_warn = "Windows не дала переключить выход: звук будет и на ПК."
        self.pa = pyaudio.PyAudio()
        wasapi = self.pa.get_host_api_info_by_type(pyaudio.paWASAPI)
        if source == "mic":
            dev = self.pa.get_device_info_by_index(wasapi["defaultInputDevice"])
        else:
            dev = self.pa.get_device_info_by_index(wasapi["defaultOutputDevice"])
            if not dev.get("isLoopbackDevice"):
                for d in self.pa.get_loopback_device_info_generator():
                    if dev["name"] in d["name"]:
                        dev = d
                        break
        self.device_name = dev["name"]
        if opus_ffmpeg:
            try:
                self.enc = opus.OpusEncoder(opus_ffmpeg, self.RATE, bitrate)
            except Exception as e:  # noqa: BLE001
                log.warning("opus encoder: %s (raw PCM instead)", e)
                self.enc = None
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
        if self.enc:
            self.enc.close()
            self.enc = None
        if self.prev_default:
            audio_out.set_default(self.prev_default)   # the PC's speakers come back
            self.prev_default = ""

    def read(self) -> list:
        """One ~50 ms chunk as frames for the relay: Opus packets (0x0A) or one PCM16 chunk (0x02)."""
        raw = self.stream.read(self.src_rate // 20, exception_on_overflow=False)
        s = array.array("h", raw)
        ch, step = self.channels, max(1, round(self.src_rate / self.RATE))
        out = array.array("h")
        n = len(s) // ch
        for i in range(0, n, step):
            base = i * ch
            out.append(sum(s[base:base + ch]) // ch)
        if self.enc is not None and self.enc.alive:
            self.enc.write(out.tobytes())
            return [opus.FRAME_AUDIO_OPUS + p for p in self.enc.packets()]
        return [FRAME_AUDIO + self.RATE.to_bytes(2, "little") + out.tobytes()]


class Agent:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.screen = Screen(cfg)
        self.input = Input(self.screen.mon)
        self.audio = Audio()
        self.audio_on = False
        self.viewers = 0
        self.ack = asyncio.Event()       # set = nothing in flight
        self.ack.set()
        self.sent_at = 0.0
        # frames in flight, oldest first: (sent_at, size, alone). One ack settles one frame. A single timestamp used
        # to make a key frame's ack look like the next P-frame's, poisoning the latency and throughput (06.10.2026)
        self.inflight = collections.deque()
        self._bw_low = 0                      # consecutive throughput samples under half the estimate
        self.link_kind = ""                   # "wifi" / "cellular" / "" — what the page says its network is
        self.phone_maxw = 1920                # the widest H.264 picture the phone's decoder takes (the page probes it)
        self.pq_boost = 0                     # the page's picture quality choice (PQ_BOOST)
        self.claude_bps = 0.0                 # Claude's traffic through «Интернет через ПК» (relay "prio"): it goes first
        self._bw_told_at = 0.0
        self.outbox: list = []                # small messages for the relay, sent by the stream loop
        self.bw_mem: dict = {}                # link kind -> (bytes/s, monotonic time): the last measurement of each
        self.rtt = 0.0  # smoothed send->ack time, drives quality adaptation
        self.terms: dict[str, Term] = {}
        self.volume = Volume()
        self.screen_ok = True
        self.stats = extras.Stats()
        self.timers = extras.Timers(run_command)
        self.downloads = extras.Downloads(os.path.join(os.path.expanduser("~"), "Downloads", "PC Remote"), self._notify)
        self.rules = {"on_connect_monitor": False, "on_disconnect_lock": False, "on_disconnect_monitor_off": False, "notify_phone": True}
        self.notifs = notify_watch.NotifWatcher()
        self.ffmpeg = video.find_ffmpeg()
        self.codecs: list = []      # what the phone can decode: ["avc1", "vp8"]
        self.video_gen = 0          # bump to restart the encoder (new viewer -> key frame)
        self.enc = None
        self.last_input = time.monotonic()   # activity-adaptive frame rate: idle hands -> fewer frames
        self.reconnects = 0
        self.audio_source = "speakers"
        self.audio_device = ""       # output endpoint for "only on the phone" ("" = pick a silent one)
        self.audio_only = False      # sound on the phone only: the room stays quiet while it listens
        self.audio_opus = False      # phone announced an Opus decoder
        self.bw = 0.0                # measured link throughput, bytes/s (from acks of big frames)
        self.epoch = time.monotonic()   # frame timestamps count from here, not from each encoder's start
        self._keyreq_at = 0.0        # last key frame the page asked for
        self.acks_since_view = 0     # acks since the last new viewer: the old link measurement is trusted until a few
        self.refine = 0              # 0 = live; 1..3 = sharpening a still picture step by step
        self.refine_at = 0.0
        self.refine_rung = 0         # the rung a sharpened frame is encoded at (what the measured link affords)
        self.enc_region = None       # (x, y, w, h) fractions: the part of the screen the video shows (zoomed phone)
        self._probe_at = 0.0         # last probe key frame on an unmeasured link
        self.busy_at = 0.0           # last frame that carried real motion
        self.excess = 0.0            # lateness of acks beyond the transfer a frame needs: a queue is building
        self.excess_set = False
        self.excess_ratio = 0.0      # that lateness as a share of the frame's expected transfer time
        self.rtt_min = 0.0           # the link's own latency (a VPN may add 300+ ms); the ladder reacts to time above it
        self.bw_at = 0.0             # when the last throughput sample came in
        self.sent_bytes = 0
        self.adaptive = True         # walk the LADDER with the link (phone setting)
        self.rung = 0
        self.rung_at = 0.0
        self._vid_w = 0              # width last applied for the video path
        self._ws = None

    async def _notify(self, msg: dict):
        if self._ws is not None:
            try:
                await self._ws.send_str(json.dumps(msg))
            except Exception:  # noqa: BLE001
                pass

    def ws_url(self) -> str:
        base = self.cfg["relay_url"].rstrip("/")
        base = base.replace("https://", "wss://").replace("http://", "ws://")
        return f"{base}/ws/pc"

    async def run(self):
        # the terminal readers and the audio reader block a thread each; the default pool
        # (cpu+4) starved the screen grabber once a few tabs were open
        from concurrent.futures import ThreadPoolExecutor
        asyncio.get_running_loop().set_default_executor(ThreadPoolExecutor(max_workers=16, thread_name_prefix="agent"))
        delay = 2
        while True:
            try:
                async with aiohttp.ClientSession() as s:
                    async with s.ws_connect(self.ws_url(), heartbeat=20, max_msg_size=64 * 1024) as ws:
                        await ws.send_str(json.dumps({"t": "auth", "token": self.cfg["secret"]}))
                        self._ws = ws
                        log.info("connected to relay")
                        delay = 2
                        await ws.send_str(json.dumps(self.hello_msg()))
                        tasks = [asyncio.create_task(self.stream(ws)), asyncio.create_task(self.stream_audio(ws)),
                                 asyncio.create_task(self.watch_cursor(ws)),
                                 asyncio.create_task(self.watch_clipboard(ws)), asyncio.create_task(self.stream_zone(ws)),
                                 asyncio.create_task(self.watch_notifications(ws))]
                        try:
                            await self.receive(ws)
                        finally:
                            for t in tasks:
                                t.cancel()
                            self.video_close()
                            try:
                                self.audio.stop()
                            except Exception:  # noqa: BLE001
                                pass
                            for term in self.terms.values():
                                term.close()
                            self.terms.clear()
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001
                log.warning("relay connection lost: %s", e)
            self.reconnects += 1
            await asyncio.sleep(delay)
            delay = min(delay * 2, 60)

    async def stream(self, ws):
        last_sent = 0.0
        last_probe = 0.0
        loop = asyncio.get_running_loop()
        while True:
            fps = self.screen.profile["fps"]
            # nobody touched the phone for 10 s: 5 fps is plenty (a video in the HD zone keeps its own rate)
            if fps > 5 and time.monotonic() - self.last_input > 10 and not self.screen.zone:
                fps = 5
            # the link's rung caps the grab rate too: it used to cap only the encoder's declared rate, so a 12 KB/s
            # link got 10+ tiny frames a second, each with its own ack round trip and ~60 B of framing
            # the rung limits BYTES per second (token bucket in video_step), not frames: a cursor move is a 100-byte
            # frame and may come 12 times a second even on 12 KB/s; a big change pays for itself with a pause
            if self.rung >= 4 and self.ffmpeg and self.codecs:
                fps = min(fps, 12)
            if self.viewers and time.monotonic() - last_probe > 5:
                last_probe = time.monotonic()
                try:
                    if await loop.run_in_executor(None, self.screen.displays_changed):
                        log.info("displays changed: reinit capture")
                        self.video_close()
                        self.screen.reinit()
                        await ws.send_str(json.dumps(self.hello_msg()))   # new size for the phone
                except Exception as e:  # noqa: BLE001
                    log.info("display probe: %s", e)
            if self.viewers == 0:
                self.video_close()   # no viewers: don't keep ffmpeg running
                await asyncio.sleep(0.5)
                continue
            if fps == 0:             # the app is in the background: nothing to send, but the encoder stays warm
                await asyncio.sleep(0.5)
                continue
            interval = 1 / fps
            t0 = time.monotonic()
            codec = video.pick_codec(self.codecs, self.ffmpeg) if (self.ffmpeg and self.codecs) else None
            if codec:
                await self.video_step(ws, codec, fps, interval, t0)
                continue
            self.video_close()
            if self._vid_w:
                self._vid_w = 0
                self.screen.set_width(self.screen.profile["max_width"])   # JPEG path adapts on its own
            if self.adaptive and self.rtt > 0.6:
                interval = 1 / max(2, fps // 2)   # late acks: fewer, smaller frames
            # wait until the phone has drawn the previous frame (or 1 s)
            try:
                await asyncio.wait_for(self.ack.wait(), 1.0)
            except asyncio.TimeoutError:
                pass
            try:
                jpeg = await loop.run_in_executor(None, self.screen.grab)
                if not self.screen_ok:
                    self.screen_ok = True
                    await ws.send_str(json.dumps({"t": "screen", "ok": True}))
            except Exception as e:  # noqa: BLE001
                # monitor unplugged / displays reconfigured: tell the phone, retry every 3 s
                if self.screen_ok:
                    self.screen_ok = False
                    log.warning("screen capture failed: %s", e)
                    await ws.send_str(json.dumps({"t": "screen", "ok": False, "error": str(e)[:120]}))
                await asyncio.sleep(3)
                try:
                    self.screen.reinit()
                except Exception:  # noqa: BLE001
                    pass
                continue
            if jpeg is None:   # nothing changed: nothing to send (the page keeps the last picture)
                await asyncio.sleep(interval)
                continue
            self.mark_sent(len(jpeg))
            await ws.send_bytes(FRAME_VIDEO + jpeg)
            last_sent = self.sent_at
            if int(last_sent) % 3 == 0:
                self.screen.adapt(self.rtt)
            await asyncio.sleep(max(0, interval - (time.monotonic() - t0)))

    def hello_msg(self) -> dict:
        return {"t": "hello", "w": self.screen.size[0], "h": self.screen.size[1], "video": bool(self.ffmpeg),
                "mw": self.screen.mon["width"], "mh": self.screen.mon["height"],   # the monitor itself: trackpad speed
                "host": os.environ.get("COMPUTERNAME", ""), "audio": self.audio.available(),
                "monitors": len(self.screen.sct.monitors) - 1, "monitor": self.screen.mon_index,
                "term": Term.available(), "shells": list(Term.shells().keys()),
                "projects": self.cfg.get("projects", []), "volume": self.volume.get(),
                "opus": opus.available(self.ffmpeg), "audio_devices": audio_out.list_render_devices()}

    async def resend_hello(self):
        """Settings changed on the PC (project folders): tell the phones without reconnecting."""
        if self._ws is not None:
            try:
                await self._ws.send_str(json.dumps(self.hello_msg()))
            except Exception:  # noqa: BLE001
                pass

    def set_view_rect(self, rect):
        """The phone shows only this part of the screen (zoomed in): encode a region around it. The region has a
        margin of a quarter on each side, so a small pan stays inside it; only leaving it, or zooming in to under
        40 % of it, restarts the encoder (a key frame)."""
        if not rect or not self.ffmpeg:
            if self.enc_region is not None:
                self.enc_region = None
                self.video_gen += 1
            return
        try:
            x, y, w, h = (max(0.0, min(1.0, float(rect[k]))) for k in ("x", "y", "w", "h"))
        except (KeyError, TypeError, ValueError):
            return
        if w <= 0 or h <= 0 or (w >= 0.85 and h >= 0.85):   # nearly the whole screen: the full stream is simpler
            self.set_view_rect(None)
            return
        cur = self.enc_region
        inside = cur and x >= cur[0] and y >= cur[1] and x + w <= cur[0] + cur[2] and y + h <= cur[1] + cur[3]
        if inside and w * h >= 0.4 * cur[2] * cur[3]:
            return
        mx, my = w * 0.25, h * 0.25
        nx, ny = max(0.0, x - mx), max(0.0, y - my)
        self.enc_region = (nx, ny, min(1.0 - nx, w + 2 * mx), min(1.0 - ny, h + 2 * my))
        self.video_gen += 1
        log.info("video: region %.2f,%.2f %.2fx%.2f of the screen", *self.enc_region)

    def _gate_closed(self, now: float) -> bool:
        """True while no frame may go out: the previous one is not decoded yet (one frame in flight, or a link-sized
        wait if its ack is late). The frame rate is whatever the network carries (owner, 06.10.2026): at 2 MB/s every
        frame goes, at 10 KB/s what fits, and the frames in between are dropped — the next one grabbed is the newest.
        (A byte budget per rung used to hold frames back on top of that: a 13.8 KB/s link got 8 KB/s.)"""
        if len(self.inflight) >= 2 and now - self.inflight[0][0] > 60.0:
            self.inflight.popleft()   # TCP loses no acks, only whole links (the relay drops those in ~30 s): a 60 s
                                      # safety net; 8 s was less than a 100 KB key frame needs on a 12 KB/s link
        oldest = self.inflight[0] if self.inflight else None
        ceiling = CEILING.get(self.screen.profile_name)
        if self.claude_bps > 300 and self.bw:
            # the owner's order: Claude first — the picture takes what Claude leaves (at least a fifth of the link)
            share = max(self.bw * 0.2, self.bw * 0.9 - self.claude_bps)
            ceiling = min(ceiling, share) if ceiling else share
        if not oldest and ceiling and self.bw > ceiling and self.sent_at:
            # the network is faster than the owner's traffic ceiling: the next frame waits until the last one, spread
            # over the ceiling, would have finished (on a link slower than the ceiling the network paces by itself)
            return now - self.sent_at < self.sent_bytes / ceiling
        if not oldest:
            return False
        expect = self.rtt_min + (oldest[1] / self.bw * 1.2 if self.bw else 1.2) + 0.2   # when its ack is due
        return len(self.inflight) >= 2 or now - oldest[0] < min(8.0, max(0.25, expect))

    def mark_sent(self, size: int):
        now = time.monotonic()
        self.inflight.append((now, size, not self.inflight))
        self.sent_at, self.sent_bytes = now, size
        self.ack.clear()

    def on_ack(self, sample: float, size: int, alone: bool = True):
        """The phone decoded a frame of `size` bytes `sample` seconds after we sent it.

        Throughput is size / (time above the link's base latency), from big frames only. Since the video went
        to constant quality (05.10.2026) a still screen sends 1-3 KB frames; over a VPN with 400 ms of latency
        size/sample read as 5-10 KB/s and the ladder dropped a 300 KB/s link to 320x180 within ten seconds."""
        self.rtt = sample if not self.rtt else self.rtt * 0.7 + sample * 0.3
        if not alone:
            return   # it waited behind another frame: says nothing about the link itself
        # how late beyond what a frame of this size needs on this link: THAT is a queue. rtt minus the base latency
        # was used before, and a key frame's honest 1.6 s transfer on a 12 KB/s link read as a queue (bench 06.10.2026)
        if self.bw:   # before the first measurement a frame's whole transfer would count as lateness
            due = self.rtt_min + size / self.bw
            exc = max(0.0, sample - due)
            self.excess = exc if not self.excess_set else self.excess * 0.7 + exc * 0.3
            self.excess_set = True
            # lateness measured against the frame's own transfer time: a heavy "Максимум" frame 1.3 s late after a
            # 4.7 s transfer is a slightly optimistic estimate, not a queue (it dropped the picture to 320 px)
            ratio = exc / max(0.3, due)
            self.excess_ratio = ratio if not self.excess_ratio else self.excess_ratio * 0.7 + ratio * 0.3
        if size <= 3000:   # a small frame measures the link's latency, not its throughput
            if not self.rtt_min or sample < self.rtt_min:
                self.rtt_min = sample
            else:
                self.rtt_min += (sample - self.rtt_min) * 0.01   # creep up slowly if the route really got longer
        elif not self.rtt_min:
            # the first frame is a big key frame: most of its time is transfer, not latency. Taking it all as
            # latency left nothing to measure the throughput with (bw stayed 0 on Wi-Fi, bench 06.10.2026)
            self.rtt_min = min(sample / 4, 0.3)
        transfer = sample - self.rtt_min
        self.acks_since_view += 1
        # throughput from frames big enough to time: 600-byte frames on a mobile link read anything from 2 to 12 KB/s
        # and the ladder flapped (06.10.2026 16:36)
        if size >= (4000 if self.rung >= 4 else 16000):
            # a frame that crossed faster than we can time (15 ms) still proves a lower bound: without it a fast link
            # after a slow one kept the slow link's 480 px (bench at 2 MB/s, 06.10.2026)
            bw = size / max(transfer, 0.015)
            # down at once, up smoothly: after Wi-Fi -> mobile data the old 400 KB/s took a dozen samples to fade,
            # and every frame until then was sized for Wi-Fi (bench 06.10.2026). But one low sample is mobile
            # jitter (a 7 KB/s reading on a 16 KB/s link cost a rung and a key frame): two in a row mean it
            if self.bw and bw < self.bw * 0.5:
                self._bw_low += 1
            else:
                self._bw_low = 0
            # 5x below the estimate is no jitter but another link (Wi-Fi -> mobile data): taken at once
            sudden = self.bw and bw < self.bw * 0.2
            self.bw = bw if (not self.bw or self._bw_low >= 2 or sudden) else self.bw * 0.7 + bw * 0.3
            self.bw_at = time.monotonic()
            if self.link_kind:
                self.bw_mem[self.link_kind] = (self.bw, self.bw_at)
            if self.bw_at - self._bw_told_at > 2:   # the relay's tunnel queue works with this figure
                self._bw_told_at = self.bw_at
                self.outbox.append(json.dumps({"t": "linkbw", "bw": round(self.bw)}))

    @staticmethod
    def _afford(bw: float) -> int:
        """The highest rung whose bitrate fits in 70 % of a throughput of bw bytes/s."""
        afford = len(LADDER) - 1
        for i in range(1, len(LADDER)):
            if _kbit(LADDER[i][2]) * 1000 <= bw * 8 * 0.7:
                afford = i
                break
        if afford == 1 and bw * 8 * 0.7 >= 2500 * 1000:
            afford = 0
        return afford

    def pick_rung(self) -> int:
        """Where on the LADDER the link puts us right now (see LADDER). Down fast, up slowly."""
        if not self.adaptive:
            self.rung = TINY_RUNG if self.screen.profile_name == "tiny" else 0
            return self.rung
        now = time.monotonic()
        rung = self.rung
        if self.acks_since_view < 1:
            # a new viewer starts where the last measurement (this PC, the last 30 min) puts it, else at 480 px: a start
            # at 320 px and a jump after the first ack was a visible reload of the whole picture (owner, 06.10.2026)
            rung = self._afford(self.bw) if self.bw and now - self.bw_at < 1800 else max(TINY_RUNG, 1)
        # what the measured throughput affords: the highest rung whose bitrate fits in 70 % of it
        # the last measurement is trusted for a long time until a few acks of this viewer came in: a reconnect on a
        # 12 KB/s link used to start at full quality (a 100 KB key frame = 8 s) before measuring again
        # a remembered measurement (up to 2 min, from a previous link) may only lower the rung; climbing needs a
        # fresh one from THIS link — the old Wi-Fi figure once sent a 100 KB key frame down a 12 KB/s link
        fresh = now - self.bw_at < 8 and self.acks_since_view >= 1
        if self.bw and (fresh or (self.acks_since_view < 3 and now - self.bw_at < 120)):
            afford = self._afford(self.bw)
            # down only when the current rung really does not fit (85 % of the link, not the 70 % used to go up): a
            # mobile link reads 10-14 KB/s from one frame to the next, and every rung change is a new key frame —
            # the picture "reloaded" every few seconds (06.10.2026 16:36)
            fits = 0 < rung and _kbit(LADDER[rung][2]) * 1000 <= self.bw * 8 * 0.85
            if afford > rung and not fits:
                rung = afford                                   # down: at once
            elif afford < rung and fresh and self.bw > 150 * 1024 and self.excess < 0.1:
                rung = afford                                   # a plainly fast link (Wi-Fi): straight up
            elif afford < rung and fresh and self.excess < 0.25 and (now - self.rung_at > 15 or self.acks_since_view <= 3):
                rung = afford                                   # up to what the fresh measurement affords (30 % headroom
                                                                # is in `afford`): at once after the tiny start, else per 15 s
        elif 0 < rung < 4 and self.acks_since_view >= 1 and self.excess < 0.15 and now - self.rung_at > 15:
            rung -= 1   # no recent measurement, acks are quick: probe upward — on the wide rungs only. On a thin link
                        # every try is a key frame (41 KB = 3 s of a 12 KB/s link, 10:21 06.10.2026); there the
                        # measuring key frame of the quiet-time probe decides, and the picture stays put meanwhile
        if self.excess > 0.5 and self.excess_ratio > 1.0 and now - self.rung_at > 2:
            rung = min(len(LADDER) - 1, rung + 1)               # a queue builds up (acks late beyond the base latency): step down
        if self.screen.profile_name == "tiny":
            rung = max(rung, TINY_RUNG)
        elif self.screen.profile_name == "eco":
            rung = max(rung, ECO_RUNG)
        if rung != self.rung:
            log.info("adaptive: rung %d -> %d (bw %.0f KB/s, rtt %.0f ms)", self.rung, rung, self.bw / 1024, self.rtt * 1000)
            self.rung, self.rung_at = rung, now
        return rung

    def video_close(self):
        if self.enc:
            self.enc.close()
            self.enc = None

    async def video_step(self, ws, codec: str, fps: int, interval: float, t0: float):
        """One tick of the encoded-video path: grab raw pixels, feed ffmpeg, ship what it produced."""
        loop = asyncio.get_running_loop()
        lw = LADDER[self.pick_rung()][0]
        want_w = min(self.screen.profile["max_width"], lw) if lw else self.screen.profile["max_width"]
        # Wi-Fi with room (owner, 06.10.2026: "на вайфай разрешал бы даже 4К, если тянет"): the screen's own size up to
        # what the phone decodes (the page probes it), not capped by the phone's width — zooming then shows real pixels.
        # 1 MB/s+ for more than 1920 px; the one-frame-in-flight pacing keeps the frame rate to what the network carries.
        wide = (self.rung == 0 and self.link_kind == "wifi" and self.bw > 300 * 1024
                and self.screen.profile_name in ("normal", "hq"))
        if wide:
            want_w = min(self.screen.mon["width"], self.phone_maxw if self.bw > 1024 * 1024 else min(1920, self.phone_maxw))
        if wide != self.screen.uncapped:
            self.screen.uncapped = wide
            self._vid_w = None   # the phone-width cap comes or goes: apply it now even at the same want_w
        if want_w != self._vid_w:
            self._vid_w = want_w
            self.screen.set_width(want_w)
        # May a frame go out now? Checked BEFORE grabbing: a frame grabbed and then held back was lost — grab_raw had
        # already remembered its hash, the next grab said "unchanged", and the last change of an action reached the
        # phone only when something else moved (06.10.2026). Now the grab happens when it can be sent at once.
        if self._gate_closed(time.monotonic()):
            if self.enc is not None and self.enc.alive:
                await self._ship(ws, codec, 0.0)
            await asyncio.sleep(min(interval, 0.02))
            return
        try:
            region = self.enc_region
            raw = await loop.run_in_executor(None, lambda: self.screen.grab_raw(region=region))
            if not self.screen_ok:
                self.screen_ok = True
                await ws.send_str(json.dumps({"t": "screen", "ok": True}))
        except Exception as e:  # noqa: BLE001
            if self.screen_ok:
                self.screen_ok = False
                log.warning("screen capture failed: %s", e)
                await ws.send_str(json.dumps({"t": "screen", "ok": False, "error": str(e)[:120]}))
            self.video_close()
            await asyncio.sleep(3)
            try:
                self.screen.reinit()
            except Exception:  # noqa: BLE001
                pass
            return
        if self.rtt and self.ack.is_set():
            self.rtt *= 0.98   # no ack pending: let a stale "slow" verdict fade so we can try the full tier again
            self.excess *= 0.98
            self.excess_ratio *= 0.98
        if raw is None and self.enc and (self.enc.key[5] != self.video_gen or not self.enc.alive):
            # key[4] is the bitrate (None at full quality), not the generation: comparing it with video_gen closed
            # the encoder on every still frame — 1212 restarts in an evening, a fresh key frame each time
            self.video_close()
            self.screen._last_hash = b""
            await asyncio.sleep(interval)
            return
        # The picture is calm (no frame with real motion for 1.2 s — a blinking cursor or a clock does not count):
        # sharpen it step by step (owner's wish, 06.10.2026) — two ladder rungs up and 3 cq better per step, at most
        # 3 steps (full width the phone shows, cq profile-9). One key frame per step, only after the previous frame
        # was decoded: on a slow link it simply takes longer. Real motion (send loop below) drops back at once.
        # ...and only while the owner just looks (no touch for 3 s) and the link answers quickly: on mobile data a
        # sharpened key frame stood in the queue for seconds and every tap came late (06.10.2026 08:33)
        # One sharpened key frame, not three: on a 40 KB/s link three of them cost 413 KB in a 12 s pause and held
        # the link for ~10 s (bench, 06.10.2026). And never on a slow link: there the owner wants the traffic low.
        # ...and on a slow link once a minute as a probe: small frames cannot measure the link, so this one bigger
        # key frame is what tells the agent that the link got faster (Wi-Fi again) — and sharpens the picture
        # No sharpening of a still picture in steps (a better key frame after a pause): the owner wants every
        # picture final when it arrives — "постепенная прогрузка это зло" (06.10.2026).
        if raw is None:
            # ffmpeg hands a frame out a little after it got it; we used to read only right after writing the next one,
            # so on a still screen the last change sat in the encoder until something else moved — the phone was one
            # frame behind and every pause felt like lag (06.10.2026). Ship whatever is ready on every tick.
            if self.enc is not None and self.enc.alive:
                await self._ship(ws, codec, 0.0)
            await asyncio.sleep(interval)
            return
        data, pix_fmt, size = raw
        # the ladder: resolution, frame rate and bitrate follow the link
        rung = self.refine_rung if self.refine else self.rung
        lw, lfps, lbr = LADDER[rung]
        tier = self.screen.profile_name
        # the encoder runs at the profile's rate; idle seconds simply feed it fewer frames. A rate change used to
        # restart it — a full key frame (100-300 KB) on the first touch after every pause
        prof_fps = self.screen.profile["fps"] or fps
        enc_fps = min(prof_fps, lfps) if lfps else prof_fps
        bitrate = lbr if lbr and _kbit(lbr) < _kbit(video.BITRATE.get(tier, "2500k")) else None
        if self.screen.uncapped and size[0] * size[1] > 1280 * 720:
            # the profile's ceiling is for ~1280x720: a bigger picture gets it in proportion, within 70 % of the link
            # and 30 Mbit/s (constant quality underneath: a still screen still costs almost nothing)
            per_px = _kbit(video.BITRATE.get(tier, "2500k")) * 1000 / (1280 * 720)
            top = 30e6 if tier == "hq" else CEILING.get(tier, 1_000_000) * 8   # "до 1 МБ/с" keeps its word
            bitrate = f"{int(min(per_px * size[0] * size[1], self.bw * 8 * 0.7, top) / 1000)}k"
        if self.refine:
            bitrate = None   # the profile's ceiling: a still frame may take its time
        # One frame in flight, on every rung: the next picture is grabbed only after the phone decoded the previous one
        # (or after a link-sized wait if an ack got lost). A narrow link then gets fewer frames, each of them fresh,
        # instead of a queue of old ones seconds deep — and nothing is spent on frames nobody would see in time.
        # every key change restarts ffmpeg = a full key frame (100-300 KB): the ladder flapping 0->2->1->0 cost one each
        # time. With constant quality and one frame in flight the bitrate ceiling matters on a thin link only.
        thin = rung >= TINY_RUNG - 1 and not self.refine
        final = thin and codec == "h264"   # x264, constant quality, no ceiling (video.Encoder)
        if final:
            # neither the rung's bitrate nor its frame rate matter to a constant-quality encoder (the byte budget
            # spaces the frames): a rung change at the same size is no restart, no key frame, no visible reload
            enc_fps = 6
        key = (codec, size, enc_fps, tier, bitrate if thin and not final else None, self.video_gen, pix_fmt, self.refine, region, final,
               self.pq_boost)
        if self.enc is None or self.enc.key != key or not self.enc.alive:
            self.video_close()
            try:
                # thin link: one frame may hold ~0.3 s of the rung's budget — the frame rate then follows the bytes
                self.enc = video.Encoder(self.ffmpeg, codec, size[0], size[1], enc_fps, tier, pix_fmt, bitrate=bitrate,
                                         cq_boost=3 * self.refine + self.pq_boost, epoch=self.epoch, final=final,
                                         high="avc1h" in self.codecs)
                self.enc.key = key
                log.info("video: %s %dx%d @%d (%s, rung %d, %s%s)", codec, size[0], size[1], enc_fps, tier, rung, self.enc.bitrate,
                         f", sharpening {self.refine}/3" if self.refine else "")
            except Exception as e:  # noqa: BLE001
                log.warning("ffmpeg failed to start: %s", e)
                self.ffmpeg = None   # JPEG from now on
                return
        if not await loop.run_in_executor(None, self.enc.write, data):
            self.video_close()
            return
        self.enc.region = region
        await self._ship(ws, codec, min(0.25, interval))
        await asyncio.sleep(max(0, interval - (time.monotonic() - t0)))

    async def _ship(self, ws, codec: str, wait: float):
        """Send every frame the encoder has finished; wait up to `wait` s for the first one."""
        while self.outbox:   # small notes for the relay (the phone link figure), from code that cannot await
            await ws.send_str(self.outbox.pop(0))
        cid = getattr(self.enc, "cid", 1 if codec == "h264" else 2)
        region = getattr(self.enc, "region", None)
        head = b""
        if region:   # flag bit 1 + the rect as 4 x uint16/65535: the page draws this frame there, not full-screen
            head = struct.pack("<4H", *(min(65535, int(v * 65535)) for v in region))
        while True:
            item = self.enc.get(0.0 if self.enc.out.qsize() else wait)
            wait = 0.0
            if item is None:
                break
            is_key, pts, payload = item
            if is_key:
                log.info("video: key frame %d B%s", len(payload), " (region)" if region else "")
            if not is_key and len(payload) > 6000:      # real motion, not a cursor blink
                self.busy_at = time.monotonic()
                if self.refine and len(payload) > 40000:
                    log.info("video: motion, back to the link's settings")
                    self.refine = 0
            self.mark_sent(len(payload))      # the phone acks decoded frames: that gives us the RTT
            await ws.send_bytes(video.FRAME_VIDEO_CODEC + bytes([(1 if is_key else 0) | (2 if region else 0), cid])
                                + pts.to_bytes(8, "little") + head + payload)
            if not self.enc.out.qsize():
                break

    async def stream_zone(self, ws):
        """The HD zone goes beside the normal picture, at its own (higher) frame rate."""
        loop = asyncio.get_running_loop()
        while True:
            if not (self.screen.zone and self.viewers and self.screen.profile["fps"]):
                await asyncio.sleep(0.3)
                continue
            fps = 24 if self.rtt < 0.15 else 12 if self.rtt < 0.4 else 6
            t0 = time.monotonic()
            try:
                data = await loop.run_in_executor(None, self.screen.grab_zone)
            except Exception as e:  # noqa: BLE001
                log.warning("zone capture failed: %s", e)
                self.screen.set_zone(None)
                await ws.send_str(json.dumps({"t": "zone", "rect": None, "error": str(e)[:80]}))
                continue
            if data:
                await ws.send_bytes(FRAME_ZONE + data)
            await asyncio.sleep(max(0.0, 1 / fps - (time.monotonic() - t0)))

    async def stream_audio(self, ws):
        loop = asyncio.get_running_loop()
        while True:
            if not (self.audio_on and self.viewers):   # sound keeps playing while the app is in the background
                if self.audio.stream:
                    self.audio.stop()
                await asyncio.sleep(0.3)
                continue
            if not self.audio.stream:
                try:
                    bitrate = "16k" if self.screen.profile_name == "tiny" else "24k"
                    await loop.run_in_executor(None, self.audio.start, self.audio_source, self.audio_only, self.audio_device,
                                               self.ffmpeg if (self.audio_opus and opus.available(self.ffmpeg)) else None, bitrate)
                except Exception as e:  # noqa: BLE001
                    log.warning("audio unavailable: %s", e)
                    self.audio_on = False
                    await ws.send_str(json.dumps({"t": "audio", "on": False, "error": str(e)}))
                    continue
                await ws.send_str(json.dumps({"t": "audio", "on": True, "via": self.audio.device_name,
                                              "only": bool(self.audio.prev_default), "warn": self.audio.only_warn,
                                              "codec": "opus" if self.audio.enc else "pcm"}))
            for chunk in await loop.run_in_executor(None, self.audio.read):
                await ws.send_bytes(chunk)

    async def watch_notifications(self, ws):
        """Windows toasts -> the phone, as long as the rule is on."""
        loop = asyncio.get_running_loop()
        while True:
            await asyncio.sleep(4.0)
            if not self.rules.get("notify_phone", True) or not self.notifs.available:
                continue
            try:
                items = await loop.run_in_executor(None, self.notifs.poll)
            except Exception as e:  # noqa: BLE001
                log.debug("notifications: %s", e)
                continue
            for n in items:
                await ws.send_str(json.dumps({"t": "pc_notify", **n}))

    async def watch_cursor(self, ws):
        """The PC cursor as its own tiny channel: {"t":"cur","x","y"} (fractions of the monitor) up to 20 times a
        second while it moves, nothing while it stands — the picture does not have to carry it (the captures do
        not include it anyway), and the page draws it the moment the message lands."""
        last = None
        while True:
            await asyncio.sleep(0.05)
            if not self.viewers or self.screen.profile["fps"] == 0:
                continue
            pt = POINT()
            if not user32.GetCursorPos(ctypes.byref(pt)):
                continue
            mon = self.screen.mon
            x = (pt.x - mon["left"]) / max(1, mon["width"]); y = (pt.y - mon["top"]) / max(1, mon["height"])
            cur = (round(min(1.0, max(0.0, x)), 4), round(min(1.0, max(0.0, y)), 4))
            if cur == last:
                continue
            last = cur
            try:
                await ws.send_str(json.dumps({"t": "cur", "x": cur[0], "y": cur[1]}))
            except Exception:  # noqa: BLE001
                return

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
                await ws.send_str(json.dumps({"t": "pc_clip", "s": text[:2000]}))   # a 50 KB copy is 4 s of a thin link

    async def term_pump(self, ws, tid: str, term: Term):
        """Reads console output in a thread and ships it to the phone."""
        loop = asyncio.get_running_loop()
        tail, last_attn = "", 0.0
        try:
            while True:
                chunk = await loop.run_in_executor(None, term.read)
                if chunk is None:
                    break
                await ws.send_str(json.dumps({"t": "term_out", "id": tid, "data": chunk}))
                # Claude Code is asking something: tell the phone (notification with Yes / No)
                tail = (tail + chunk)[-600:]
                if ATTENTION_RE.search(tail) and time.monotonic() - last_attn > 20:
                    last_attn = time.monotonic()
                    # strip colours and control bytes, keep the last non-empty lines as the question
                    clean = [ln.strip() for ln in ANSI_RE.sub("", tail).splitlines() if ln.strip()]
                    q = " · ".join(clean[-3:])[-160:]
                    await ws.send_str(json.dumps({"t": "attention", "term": tid, "text": q or "Claude ждёт ответа"}))
                    tail = ""
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
                    if self.inflight:
                        sent_at, size, alone = self.inflight.popleft()
                        self.on_ack(time.monotonic() - sent_at, size, alone)
                    if not self.inflight:
                        self.ack.set()
                elif t == "viewers":
                    was = self.viewers
                    self.viewers = int(ev.get("n", 0))
                    if self.viewers > was:
                        self.screen._last_hash = b""  # force a fresh frame
                        self.video_gen += 1           # and a key frame for the newcomer
                        self.acks_since_view = 0
                        # a new link: its latency is unknown, and frames sent to the previous one are nobody's
                        self.inflight.clear(); self.ack.set(); self.rtt = 0.0; self.rtt_min = 0.0; self.excess = 0.0
                        self.excess_ratio = 0.0
                        self.enc_region = None
                        # its link is unknown until the page names it ("video" message): no other link's figure
                        self.bw, self.bw_at, self._bw_low = 0.0, 0.0, 0
                    if not self.viewers:
                        self.codecs = []              # last viewer left: next one re-announces
                    # keep the PC awake while someone is connected (monitor may be off)
                    ES_CONTINUOUS, ES_SYSTEM_REQUIRED = 0x80000000, 0x00000001
                    kernel32.SetThreadExecutionState(ES_CONTINUOUS | (ES_SYSTEM_REQUIRED if self.viewers else 0))
                    if self.viewers and self.rules.get("on_connect_monitor"):
                        extras.monitor_power(True)
                    if not self.viewers:
                        if self.rules.get("on_disconnect_lock"):
                            run_command("lock")
                        if self.rules.get("on_disconnect_monitor_off"):
                            extras.monitor_power(False)
                elif t == "volume":
                    self.volume.set(ev.get("level"), ev.get("mute"), self.input)
                    await ws.send_str(json.dumps({"t": "volume", **self.volume.get()}))
                elif t == "volume_get":
                    await ws.send_str(json.dumps({"t": "volume", **self.volume.get()}))
                elif t == "windows_get":
                    loop = asyncio.get_running_loop()
                    items = await loop.run_in_executor(None, list_windows)
                    await ws.send_str(json.dumps({"t": "windows", "items": items}))
                elif t == "window":
                    res = window_action(str(ev.get("op")), int(ev.get("hwnd", 0)))
                    await ws.send_str(json.dumps({"t": "cmd_result", "cmd": "window_" + str(ev.get("op")), "result": res}))
                    if ev.get("op") in ("close", "min"):
                        await asyncio.sleep(0.3)
                        loop = asyncio.get_running_loop()
                        items = await loop.run_in_executor(None, list_windows)
                        await ws.send_str(json.dumps({"t": "windows", "items": items}))
                elif t == "zone":
                    if ev.get("auto"):
                        loop = asyncio.get_running_loop()
                        rect = await loop.run_in_executor(None, self.screen.detect_zone)
                        self.screen.set_zone(rect)
                        await ws.send_str(json.dumps({"t": "zone", "rect": rect}))
                    elif ev.get("off"):
                        self.screen.set_zone(None)
                        await ws.send_str(json.dumps({"t": "zone", "rect": None}))
                    else:
                        self.screen.set_zone(ev)
                        await ws.send_str(json.dumps({"t": "zone", "rect": self.screen.zone}))
                elif t == "video":
                    if ev.get("off"):
                        self.codecs = []
                    elif isinstance(ev.get("codecs"), list):
                        self.codecs = [c for c in ev["codecs"] if c in ("avc1", "avc1h", "vp8", "hvc1", "av01", "vp09")][:8]
                        log.info("phone decodes: %s", ", ".join(self.codecs) or "nothing")
                        self.audio_opus = "opus" in ev["codecs"]
                    try:
                        self.phone_maxw = max(640, min(4096, int(ev.get("maxw") or 1920)))
                    except (TypeError, ValueError):
                        self.phone_maxw = 1920
                    kind = str(ev.get("link") or "")[:16]
                    self.link_kind = kind
                    if kind and kind in self.bw_mem and not self.acks_since_view:
                        self.bw, self.bw_at = self.bw_mem[kind]   # this kind of network's last figure (30 min, pick_rung)
                    self.video_gen += 1
                elif t == "audio_source":
                    self.audio_source = "mic" if ev.get("src") == "mic" else "speakers"
                    self.audio_device = str(ev.get("device") or "")[:512]
                    only = bool(ev.get("only"))
                    changed = (self.audio_source, self.audio_device, only) != getattr(self, "_audio_cfg", None)
                    self._audio_cfg = (self.audio_source, self.audio_device, only)
                    self.audio_only = only
                    if self.audio.stream and changed:
                        self.audio.stop()  # restarts with the new source/device on the next loop
                elif t == "capture":
                    self.screen.set_capture_mode(str(ev.get("mode", "auto")))
                elif t == "adapt":
                    self.adaptive = bool(ev.get("on", True))
                    self.rung, self.rung_at = 0, time.monotonic()
                elif t == "audio_devices_get":
                    await ws.send_str(json.dumps({"t": "audio_devices", "items": audio_out.list_render_devices()}))
                elif t == "rules":
                    for k in self.rules:
                        if k in ev:
                            self.rules[k] = bool(ev[k])
                elif t == "sys_get":
                    loop = asyncio.get_running_loop()
                    snap = await loop.run_in_executor(None, self.stats.snapshot, bool(ev.get("temps")))
                    await ws.send_str(json.dumps({"t": "sys", **snap}))
                elif t == "procs_get":
                    loop = asyncio.get_running_loop()
                    rows = await loop.run_in_executor(None, self.stats.processes)
                    await ws.send_str(json.dumps({"t": "procs", "items": rows}))
                elif t == "proc_kill":
                    res = self.stats.kill(int(ev.get("pid", 0)))
                    await ws.send_str(json.dumps({"t": "cmd_result", "cmd": "kill", "result": res}))
                elif t == "timer_set":
                    res = await self.timers.set(str(ev.get("action")), int(ev.get("seconds", 0)))
                    await ws.send_str(json.dumps({"t": "timers", "items": self.timers.list(), "result": res}))
                elif t == "timer_cancel":
                    await self.timers.cancel_all()
                    await ws.send_str(json.dumps({"t": "timers", "items": []}))
                elif t == "timers_get":
                    await ws.send_str(json.dumps({"t": "timers", "items": self.timers.list()}))
                elif t == "device":
                    op = ev.get("op")
                    if op == "monitor_off":
                        res = extras.monitor_power(False)
                    elif op == "monitor_on":
                        res = extras.monitor_power(True)
                    elif op == "powerplan":
                        res = extras.set_power_plan(str(ev.get("value", "")))
                    else:
                        res = "unknown"
                    await ws.send_str(json.dumps({"t": "cmd_result", "cmd": op, "result": res}))
                elif t == "powerplans_get":
                    await ws.send_str(json.dumps({"t": "powerplans", "items": extras.power_plans()}))
                elif t == "say":
                    extras.say(str(ev.get("text", "")))
                elif t == "print":
                    res = extras.print_file(str(ev.get("path", "")))
                    await ws.send_str(json.dumps({"t": "cmd_result", "cmd": "print", "result": res}))
                elif t == "download":
                    res = await self.downloads.start(str(ev.get("url", "")), ev.get("dir"))
                    await ws.send_str(json.dumps({"t": "downloads", "items": self.downloads.list(), "result": res}))
                elif t == "downloads_get":
                    await ws.send_str(json.dumps({"t": "downloads", "items": self.downloads.list()}))
                elif t == "download_cancel":
                    self.downloads.cancel(str(ev.get("id", "")))
                    await ws.send_str(json.dumps({"t": "downloads", "items": self.downloads.list()}))
                elif t == "keyreq":
                    if time.monotonic() - self._keyreq_at > 2:
                        self._keyreq_at = time.monotonic()
                        self.video_gen += 1
                elif t == "view":
                    vw = max(0, min(8192, int(ev.get("w") or 0)))   # 0 = unknown: no cap
                    if vw != self.screen.view_w:
                        self.screen.view_w = vw
                        self.screen.set_width(self.screen.profile["max_width"])   # JPEG path
                        self._vid_w = None                                        # video path: re-apply on the next frame
                    self.set_view_rect(ev.get("rect"))
                elif t == "prio":
                    self.claude_bps = float(ev.get("claude") or 0)
                elif t == "pq":
                    self.pq_boost = PQ_BOOST.get(str(ev.get("level")), 0)
                elif t == "profile":
                    was = self.screen.profile_name
                    self.screen.set_profile(str(ev.get("name", "normal")))
                    self._vid_w = None   # set_profile set the profile's width: the rung's cap goes back on next frame
                    if (was == "tiny") != (self.screen.profile_name == "tiny") and self.audio.stream:
                        self.audio.stop()   # Opus bitrate follows the profile
                elif t == "audio":
                    self.audio_on = bool(ev.get("on"))
                if t in ("move", "btn", "click", "wheel", "key", "text", "combo", "clip"):
                    self.last_input = time.monotonic()
                    if self.refine:   # the owner acts: quick frames again, no heavy sharpened ones in the queue
                        self.refine = 0
                if t == "diag_get":
                    await ws.send_str(json.dumps({"t": "diag", "ffmpeg": bool(self.ffmpeg), "codec": self.enc.codec if self.enc else None,
                                                  "encoder": getattr(self.enc, "encoder_name", None) if self.enc else None,
                                                  "enc_fps": self.enc.fps if self.enc else None, "size": list(self.screen.size),
                                                  "profile": self.screen.profile_name, "rtt_ms": int(self.rtt * 1000), "viewers": self.viewers,
                                                  "zone": bool(self.screen.zone), "terms": len(self.terms), "monitor": self.screen.mon_index, "view_w": self.screen.view_w,
                                                  "codecs": self.codecs, "reconnects": self.reconnects, "bw_kbs": round(self.bw / 1024, 1),
                                                  "rung": self.rung, "adaptive": self.adaptive,
                                                  "capture": self.screen.cap.name if self.screen.cap else None, "capture_mode": self.screen.capture_mode,
                                                  "grab_ms": round(self.screen.grab_ms, 1), "enc_ms": round(getattr(self.enc, "enc_ms", 0.0), 1) if self.enc else None, "capture_switches": self.screen.cap_switches, "bitrate": getattr(self.enc, "bitrate", None) if self.enc else None,
                                                  "audio": self.audio.device_name if self.audio.stream else None,
                                                  "audio_codec": ("opus " + self.audio.enc.bitrate) if (self.audio.stream and self.audio.enc) else ("pcm" if self.audio.stream else None),
                                                  "idle_s": int(time.monotonic() - self.last_input)}))
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
                elif t == "open_path":      # open a file/folder on the PC with its default app
                    path = str(ev.get("path", ""))[:4000]
                    if os.path.exists(path):
                        os.startfile(path)  # noqa: S606
                elif t == "reveal":         # show it selected in Windows Explorer
                    path = str(ev.get("path", ""))[:4000]
                    if os.path.exists(path):
                        subprocess.Popen(["explorer.exe", "/select,", path])
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
                    if ev.get("cmd") in ("cancel", "shutdown", "reboot"):
                        self.timers.timers.clear()   # the Windows-scheduled timer is gone either way
                    log.info("cmd %s -> %s", ev.get("cmd"), res)
                    await ws.send_str(json.dumps({"t": "cmd_result", "cmd": ev.get("cmd"), "result": res}))
            except Exception as e:  # noqa: BLE001
                log.warning("bad event %s: %s", ev, e)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        handlers=[logging.StreamHandler(),
                                  logging.handlers.RotatingFileHandler(HERE / "agent.log", maxBytes=2 * 1024 * 1024, backupCount=1, encoding="utf-8")])
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
