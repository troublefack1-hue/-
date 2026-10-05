#!/usr/bin/env python3
"""
PC Remote — the desktop app.

One window, no setup: on first run it creates the secret and certificates,
opens the firewall port, registers itself to start with Windows and runs the
relay (HTTPS on the public port) and the screen agent in the background.

  "Привязать телефон"  shows a 6-digit code the phone app uses once to pair
  "Найти телефон"      makes every connected phone ring (find my phone)
  phone screen cast    opens a full-screen window with the phone's screen
                       and plays its sound on the PC

Build to a single exe with build_exe.ps1, or run: python main.py
"""
import asyncio
import ctypes
import io
import json
import logging
import os
import queue
import secrets
import subprocess
import sys
import threading
import time
import tkinter as tk
import urllib.request
from pathlib import Path
from tkinter import ttk

from PIL import Image, ImageTk

# relay/ and agent/ live next to this file in the repo, or are bundled by PyInstaller
BASE = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
sys.path[:0] = [str(BASE / "relay"), str(BASE / "agent")]
import agent as agent_mod  # noqa: E402
import relay as relay_mod  # noqa: E402
from certs import ensure_certs  # noqa: E402
sys.path.insert(0, str(Path(__file__).resolve().parent))
import deps
import updater  # noqa: E402

APP_NAME = "PC Remote"
DATA = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "pc-remote"
CONFIG = DATA / "config.json"
log = logging.getLogger("pcapp")


# ----------------------------------------------------------------- config ---

def public_ip() -> str:
    for url in ("https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"):
        try:
            with urllib.request.urlopen(url, timeout=6) as r:
                ip = r.read().decode().strip()
                if ip.count(".") == 3:
                    return ip
        except Exception:  # noqa: BLE001
            continue
    return ""


def install_phone_cli() -> Path:
    """Copy the `phone` command (PowerShell + wrappers + PHONE.md for Claude) into the data folder."""
    src = BASE / "pcapp" / "phone_cli"
    dst = DATA / "bin"
    dst.mkdir(parents=True, exist_ok=True)
    import shutil
    for f in ("phone.ps1", "phone.cmd", "phone", "claude-phone.sh", "PHONE.md"):
        if (src / f).exists():
            shutil.copyfile(src / f, dst / f)
    agent_mod.Term.BIN = str(dst)
    return dst


def load_config() -> dict:
    DATA.mkdir(parents=True, exist_ok=True)
    first_run = not CONFIG.exists()
    cfg = json.loads(CONFIG.read_text("utf-8")) if CONFIG.exists() else {}
    changed = first_run
    if not cfg.get("secret"):
        cfg["secret"] = secrets.token_urlsafe(30)
        changed = True
    cfg.setdefault("port", 8443)
    if not cfg.get("guest_secret"):
        cfg["guest_secret"] = secrets.token_urlsafe(30)
        changed = True
    cfg.setdefault("ntfy_wake_url", "")
    cfg.setdefault("max_width", 1280)
    cfg.setdefault("quality", 55)
    cfg.setdefault("fps", 12)
    cfg.setdefault("monitor", 1)
    cfg.setdefault("auto_update", True)
    home = Path.home()
    cfg.setdefault("projects", [])                                   # folders for the terminal panel
    cfg.setdefault("upload_dir", str(home / "Downloads" / "PC Remote"))  # files from the phone
    # the whole PC is browsable from the phone: every drive letter that exists
    drives = [f"{d}:\\" for d in "ABCDEFGHIJKLMNOPQRSTUVWXYZ" if os.path.exists(f"{d}:\\")]
    cfg.setdefault("share_dirs", drives or [str(home)])
    if not cfg.get("public_ip"):
        cfg["public_ip"] = public_ip()
        changed = True
    if changed:
        save_config(cfg)
    cfg["_first_run"] = first_run
    return cfg


def save_config(cfg: dict):
    data = {k: v for k, v in cfg.items() if not k.startswith("_")}
    CONFIG.write_text(json.dumps(data, indent=2, ensure_ascii=False), "utf-8")


# ---------------------------------------------------------------- windows ---

def is_admin() -> bool:
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:  # noqa: BLE001
        return False


def firewall_open(port: int) -> bool:
    """Add an inbound rule once. Needs admin; otherwise asks for elevation."""
    name = f"PC Remote {port}"
    check = subprocess.run(["netsh", "advfirewall", "firewall", "show", "rule", f"name={name}"],
                           capture_output=True, text=True, creationflags=0x08000000)
    if check.returncode == 0 and name in check.stdout:
        return True
    args = f'advfirewall firewall add rule name="{name}" dir=in action=allow protocol=TCP localport={port}'
    if is_admin():
        subprocess.run("netsh " + args, shell=True, creationflags=0x08000000)
        return True
    rc = ctypes.windll.shell32.ShellExecuteW(None, "runas", "netsh", args, None, 0)  # one UAC prompt
    return rc > 32


def autostart(enable: bool):
    import winreg
    exe = sys.executable if getattr(sys, "frozen", False) else f'"{sys.executable}" "{Path(__file__).resolve()}"'
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run",
                        0, winreg.KEY_SET_VALUE) as k:
        if enable:
            winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, f'{exe} --minimized')
        else:
            try:
                winreg.DeleteValue(k, APP_NAME)
            except FileNotFoundError:
                pass


def autostart_enabled() -> bool:
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as k:
            winreg.QueryValueEx(k, APP_NAME)
            return True
    except FileNotFoundError:
        return False


# ---------------------------------------------------------------- backend ---

class AudioOut:
    """Plays PCM16 mono chunks from the phone through the default output device."""

    def __init__(self):
        self.pa = None
        self.stream = None
        self.rate = 0

    def play(self, rate: int, pcm: bytes):
        try:
            import pyaudiowpatch as pyaudio
        except ImportError:
            return
        if self.stream is None or rate != self.rate:
            self.close()
            self.pa = pyaudio.PyAudio()
            self.stream = self.pa.open(format=pyaudio.paInt16, channels=1, rate=rate, output=True)
            self.rate = rate
        self.stream.write(pcm)

    def close(self):
        if self.stream:
            self.stream.stop_stream()
            self.stream.close()
            self.stream = None
        if self.pa:
            self.pa.terminate()
            self.pa = None


class Backend:
    """Relay + agent in one asyncio loop on a background thread."""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.loop = asyncio.new_event_loop()
        self.hub = None
        self.agent = None
        self.error = ""
        self.paired_ip = ""
        self.phones = 0
        self.phone_names: list = []
        self.cast_frames: queue.Queue = queue.Queue(maxsize=3)  # phone screen -> GUI
        self.cast_active = False
        self.audio_out = AudioOut()

    def start(self):
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self._main())
        except Exception as e:  # noqa: BLE001
            self.error = str(e)
            log.exception("backend failed")

    async def _main(self):
        ensure_certs(DATA, [self.cfg["public_ip"] or "127.0.0.1"])
        relay_cfg = {
            "secret": self.cfg["secret"], "host": "127.0.0.1", "port": 8787,
            "ntfy_wake_url": self.cfg["ntfy_wake_url"],
            "tls_host": "0.0.0.0", "tls_port": self.cfg["port"],
            "tls_cert": str(DATA / "server.crt"), "tls_key": str(DATA / "server.key"),
            "ca_cert": str(DATA / "ca.crt"),
            "upload_dir": self.cfg["upload_dir"], "share_dirs": self.cfg["share_dirs"],
            "guest_secret": self.cfg["guest_secret"],
        }
        app = relay_mod.make_app(relay_cfg)
        self.hub = app["hub"]
        self.hub.on_paired = lambda ip: setattr(self, "paired_ip", ip)
        self.hub.on_phones = lambda n, names: (setattr(self, "phones", n), setattr(self, "phone_names", names))
        self.hub.on_cast = self._on_cast
        self.hub.on_net = lambda n: setattr(self, "net", n)
        self.net = {}
        agent_cfg = {"relay_url": "http://127.0.0.1:8787", "secret": self.cfg["secret"],
                     "max_width": self.cfg["max_width"], "quality": self.cfg["quality"],
                     "fps": self.cfg["fps"], "monitor": self.cfg["monitor"], "projects": self.cfg["projects"]}
        self.agent = agent_mod.Agent(agent_cfg)
        await asyncio.gather(relay_mod.serve(relay_cfg, app), self.agent.run())

    def _on_cast(self, kind: int, data):
        if data is None:                       # stopped
            self.cast_active = False
            self.audio_out.close()
            return
        self.cast_active = True
        if kind == relay_mod.FRAME_CAST:
            try:
                self.cast_frames.put_nowait(data)
            except queue.Full:                 # GUI is behind: drop the oldest
                try:
                    self.cast_frames.get_nowait()
                    self.cast_frames.put_nowait(data)
                except queue.Empty:
                    pass
        elif kind == relay_mod.FRAME_CAST_AUDIO and len(data) > 2:
            rate = int.from_bytes(data[:2], "little")
            threading.Thread(target=self.audio_out.play, args=(rate, data[2:]), daemon=True).start()

    def pair_code(self, guest: bool = False) -> str:
        return asyncio.run_coroutine_threadsafe(self._pair(guest), self.loop).result(5)

    async def _pair(self, guest: bool):
        return self.hub.start_pairing(guest)

    def ring(self):
        asyncio.run_coroutine_threadsafe(self.hub.ring_phones(), self.loop).result(5)


# -------------------------------------------------------------------- GUI ---

BG, PANEL, TEXT, MUTED, ACCENT, OK, WARN, BAD = "#0f1117", "#181b24", "#eef0f5", "#8e94a6", "#4f8cff", "#38d070", "#f5b84a", "#ef5350"
BORDER = "#2a2f3d"


def draw_logo(c: tk.Canvas, x: int, y: int, size: int, tag="logo"):
    """The app icon drawn with canvas primitives (same shape as web/icon.svg)."""
    k = size / 128
    c.create_rectangle(x, y, x + size, y + size, fill="#3b6fd8", outline="", tags=tag)
    c.create_rectangle(x + 22 * k, y + 30 * k, x + 106 * k, y + 84 * k, fill=BG, outline="", tags=tag)
    c.create_rectangle(x + 28 * k, y + 36 * k, x + 100 * k, y + 78 * k, fill="#1b2a4a", outline="", tags=(tag, tag + "_scr"))
    c.create_rectangle(x + 52 * k, y + 86 * k, x + 76 * k, y + 92 * k, fill=BG, outline="", tags=tag)
    c.create_rectangle(x + 40 * k, y + 92 * k, x + 88 * k, y + 98 * k, fill=BG, outline="", tags=tag)
    c.create_arc(x + 51 * k, y + 46 * k, x + 77 * k, y + 72 * k, start=120, extent=300, style="arc",
                 outline=OK, width=max(2, int(4 * k)), tags=(tag, tag + "_pwr"))
    c.create_line(x + 64 * k, y + 46 * k, x + 64 * k, y + 60 * k, fill=OK, width=max(2, int(4 * k)), tags=(tag, tag + "_pwr"))


class Splash(tk.Toplevel):
    """Borderless intro: logo, name, animated dots. Closes when the backend is up."""

    def __init__(self, master):
        super().__init__(master)
        self.overrideredirect(True)
        self.configure(bg=BG)
        w, h = 420, 300
        sw, sh = self.winfo_screenwidth(), self.winfo_screenheight()
        self.geometry(f"{w}x{h}+{(sw - w) // 2}+{(sh - h) // 2}")
        self.attributes("-topmost", True)
        c = tk.Canvas(self, width=w, height=h, bg=BG, highlightthickness=0)
        c.pack()
        self.c = c
        draw_logo(c, (w - 96) // 2, 36, 96)
        c.create_text(w // 2, 170, text=APP_NAME, fill=TEXT, font=("Segoe UI", 22, "bold"))
        c.create_text(w // 2, 200, text="домашний компьютер в кармане", fill=MUTED, font=("Segoe UI", 10))
        self.dots = [c.create_oval(w // 2 - 22 + i * 18, 240, w // 2 - 12 + i * 18, 250, fill=ACCENT, outline="") for i in range(3)]
        self.msg = c.create_text(w // 2, 275, text="запуск…", fill=MUTED, font=("Segoe UI", 9))
        self.i = 0
        self.animate()

    def animate(self):
        for n, d in enumerate(self.dots):
            self.c.itemconfigure(d, fill=ACCENT if n == self.i % 3 else "#2a2f3d")
        self.c.itemconfigure("logo_scr", fill="#2b4a8c" if self.i % 6 < 3 else "#1b2a4a")
        self.i += 1
        self.after(220, self.animate)

    def set_msg(self, text):
        self.c.itemconfigure(self.msg, text=text)

class CastWindow(tk.Toplevel):
    """Full-screen window showing the phone's screen. Esc or close = hide."""

    def __init__(self, master):
        super().__init__(master)
        self.title("Экран телефона")
        self.configure(bg="black")
        self.attributes("-fullscreen", True)
        self.bind("<Escape>", lambda e: self.withdraw())
        self.bind("<Double-Button-1>", lambda e: self.attributes("-fullscreen", not self.attributes("-fullscreen")))
        self.protocol("WM_DELETE_WINDOW", self.withdraw)
        self.label = tk.Label(self, bg="black")
        self.label.pack(fill="both", expand=True)
        self.photo = None

    def show_frame(self, jpeg: bytes):
        img = Image.open(io.BytesIO(jpeg))
        w, h = self.winfo_width(), self.winfo_height()
        if w < 50 or h < 50:  # not mapped yet: use the whole screen
            w, h = self.winfo_screenwidth(), self.winfo_screenheight()
        k = min(w / img.width, h / img.height)
        img = img.resize((max(1, int(img.width * k)), max(1, int(img.height * k))), Image.BILINEAR)
        self.photo = ImageTk.PhotoImage(img)
        self.label.configure(image=self.photo)


class Bubble(tk.Toplevel):
    """A small round PC Remote badge that floats over everything while the window is in the tray.
    Drag it anywhere; a tap brings the window back."""

    SIZE = 46

    def __init__(self, app):
        super().__init__(app)
        self.app = app
        self.overrideredirect(True)
        self.attributes("-topmost", True)
        try:
            self.attributes("-alpha", 0.93)
            self.attributes("-transparentcolor", "#010101")
        except tk.TclError:
            pass
        self.configure(bg="#010101")
        c = tk.Canvas(self, width=self.SIZE, height=self.SIZE, bg="#010101", highlightthickness=0, cursor="hand2")
        c.pack()
        r = self.SIZE
        c.create_oval(2, 2, r - 2, r - 2, fill=ACCENT, outline="#ffffff", width=2)
        # a little monitor in the middle
        c.create_rectangle(12, 14, r - 12, r - 18, fill="#ffffff", outline="")
        c.create_rectangle(14, 16, r - 14, r - 20, fill="#1b2a4a", outline="")
        c.create_rectangle(r // 2 - 3, r - 18, r // 2 + 3, r - 15, fill="#ffffff", outline="")
        c.create_rectangle(r // 2 - 8, r - 15, r // 2 + 8, r - 13, fill="#ffffff", outline="")
        self.dot = c.create_oval(r - 14, r - 14, r - 5, r - 5, fill=MUTED, outline="")
        self.c = c
        self._drag = None
        for ev, fn in (("<ButtonPress-1>", self.press), ("<B1-Motion>", self.move), ("<ButtonRelease-1>", self.release)):
            c.bind(ev, fn)
        x, y = app.cfg.get("bubble_pos") or (self.winfo_screenwidth() - r - 24, self.winfo_screenheight() - r - 90)
        self.geometry(f"+{int(x)}+{int(y)}")
        self.withdraw()

    def press(self, e):
        self._drag = (e.x_root, e.y_root, self.winfo_x(), self.winfo_y(), False)

    def move(self, e):
        if not self._drag:
            return
        x0, y0, wx, wy, _ = self._drag
        dx, dy = e.x_root - x0, e.y_root - y0
        moved = self._drag[4] or abs(dx) > 4 or abs(dy) > 4
        self._drag = (x0, y0, wx, wy, moved)
        if moved:
            self.geometry(f"+{wx + dx}+{wy + dy}")

    def release(self, e):
        if not self._drag:
            return
        moved = self._drag[4]
        self._drag = None
        if moved:
            self.app.cfg["bubble_pos"] = [self.winfo_x(), self.winfo_y()]
            save_config(self.app.cfg)
        else:
            self.app.show_window()

    def set_state(self, online: bool):
        self.c.itemconfigure(self.dot, fill=OK if online else MUTED)


class Tray:
    """System tray icon with a menu; notifications when a phone connects. Optional (pystray)."""

    def __init__(self, app):
        self.app = app
        self.icon = None
        try:
            import pystray
        except ImportError:
            return
        img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        from PIL import ImageDraw
        d = ImageDraw.Draw(img)
        d.rounded_rectangle((0, 0, 63, 63), 14, fill="#3b6fd8")
        d.rounded_rectangle((11, 15, 53, 42), 4, fill=BG)
        d.rectangle((14, 18, 50, 39), fill="#1b2a4a")
        d.rectangle((26, 43, 38, 46), fill=BG); d.rounded_rectangle((20, 46, 44, 49), 1, fill=BG)
        d.arc((25, 23, 39, 37), 120, 420, fill=OK, width=3); d.line((32, 23, 32, 30), fill=OK, width=3)
        menu = pystray.Menu(
            pystray.MenuItem("Открыть", lambda: self.app.after(0, self.app.show_window), default=True),
            pystray.MenuItem("Найти телефон", lambda: self.app.after(0, self.app.ring)),
            pystray.MenuItem("Привязать телефон", lambda: self.app.after(0, lambda: (self.app.show_window(), self.app.show_pair()))),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Выход", lambda: self.app.after(0, self.app.quit_app)))
        self.icon = pystray.Icon(APP_NAME, img, APP_NAME, menu)
        threading.Thread(target=self.icon.run, daemon=True).start()

    def notify(self, text):
        if self.icon:
            try:
                self.icon.notify(text, APP_NAME)
            except Exception:  # noqa: BLE001
                pass

    def stop(self):
        if self.icon:
            self.icon.stop()


class App(tk.Tk):
    def __init__(self, cfg: dict, backend: Backend, minimized: bool):
        super().__init__()
        self.cfg, self.backend = cfg, backend
        self.tray = Tray(self)
        self.last_phones = 0
        self.title(APP_NAME)
        self.resizable(False, False)
        self.configure(bg=BG)
        self.protocol("WM_DELETE_WINDOW", self.hide_window)  # close = hide to tray, keep running
        st = ttk.Style(self)
        try:
            st.theme_use("clam")
        except tk.TclError:
            pass
        st.configure(".", background=BG, foreground=TEXT, font=("Segoe UI", 10))
        st.configure("TFrame", background=BG)
        st.configure("TLabel", background=BG, foreground=TEXT)
        st.configure("Muted.TLabel", foreground=MUTED)
        st.configure("TButton", background=PANEL, foreground=TEXT, borderwidth=0, padding=(12, 7), focuscolor=BG)
        st.map("TButton", background=[("active", "#222633"), ("disabled", "#15181f")], foreground=[("disabled", "#555b6a")])
        st.configure("Accent.TButton", background=ACCENT, foreground="#ffffff", font=("Segoe UI", 10, "bold"))
        st.map("Accent.TButton", background=[("active", "#3b6fd8"), ("disabled", "#2a3a5c")])
        st.configure("TCheckbutton", background=BG, foreground=TEXT, focuscolor=BG)
        st.map("TCheckbutton", background=[("active", BG)])
        st.configure("TEntry", fieldbackground=PANEL, foreground=TEXT, insertcolor=TEXT, borderwidth=0)

        pad = {"padx": 14, "pady": 4}
        hdr = tk.Canvas(self, width=440, height=64, bg=BG, highlightthickness=0)
        hdr.grid(row=0, column=0, sticky="we")
        self.hdr = hdr
        draw_logo(hdr, 14, 10, 44)
        hdr.create_text(72, 24, text=APP_NAME, fill=TEXT, anchor="w", font=("Segoe UI", 16, "bold"))
        hdr.create_text(72, 46, text="домашний компьютер в кармане", fill=MUTED, anchor="w", font=("Segoe UI", 9))
        # phone icon on the right; a beam of dots flows PC -> phone while a phone is connected
        hdr.create_rectangle(398, 14, 422, 54, fill=PANEL, outline=BORDER, width=2)
        hdr.create_rectangle(402, 20, 418, 46, fill="#1b2a4a", outline="", tags="phone_scr")
        hdr.create_oval(408, 48, 412, 52, fill=BORDER, outline="")
        self.beam = [hdr.create_oval(0, 0, 0, 0, fill=ACCENT, outline="", state="hidden") for _ in range(5)]
        self.beam_t = 0
        self.after(60, self.animate_beam)
        f = ttk.Frame(self, padding=(12, 0, 12, 12))
        f.grid(row=1, column=0, sticky="we")

        self.status = tk.Label(f, text="  запуск…  ", bg=PANEL, fg=MUTED, font=("Segoe UI", 10), padx=8, pady=5)
        self.status.grid(row=1, column=0, columnspan=2, sticky="we", **pad)
        self.upd_btn = ttk.Button(f, text="Проверить обновления", command=self.update_now)
        self.upd_btn.grid(row=0, column=1, sticky="e", **pad)
        ttk.Label(f, text=f"версия {updater.current_version()}", style="Muted.TLabel").grid(row=0, column=0, sticky="w", **pad)

        ttk.Label(f, text="Адрес для телефона:").grid(row=2, column=0, sticky="w", **pad)
        self.addr = ttk.Entry(f, width=28)
        self.addr.insert(0, f"{cfg['public_ip'] or '?'}:{cfg['port']}")
        self.addr.configure(state="readonly")
        self.addr.grid(row=2, column=1, sticky="w", **pad)

        pf = ttk.Frame(f)
        pf.grid(row=3, column=0, sticky="w", **pad)
        ttk.Button(pf, text="Привязать телефон", style="Accent.TButton", command=self.show_pair).pack(side="left")
        ttk.Button(pf, text="Код для гостя", command=lambda: self.show_pair(True)).pack(side="left", padx=(6, 0))
        self.code = tk.Label(f, text="", font=("Consolas", 26, "bold"), fg=ACCENT, bg=BG)
        self.code.grid(row=3, column=1, sticky="w", **pad)
        self.code_hint = ttk.Label(f, text="", style="Muted.TLabel")
        self.code_hint.grid(row=4, column=0, columnspan=2, sticky="w", **pad)

        self.ring_btn = ttk.Button(f, text="Найти телефон 🔔", command=self.ring, state="disabled")
        self.ring_btn.grid(row=5, column=0, sticky="w", **pad)
        self.cast_btn = ttk.Button(f, text="Экран телефона", command=self.show_cast, state="disabled")
        self.cast_btn.grid(row=5, column=1, sticky="w", **pad)

        self.auto = tk.BooleanVar(value=autostart_enabled())
        ttk.Checkbutton(f, text="Запускать вместе с Windows", variable=self.auto,
                        command=lambda: autostart(self.auto.get())).grid(row=6, column=0, columnspan=2, sticky="w", **pad)

        hint = ("На роутере пробросьте TCP-порт %d на этот ПК.\n"
                "Данные: %s" % (cfg["port"], DATA))
        ttk.Label(f, text=hint, style="Muted.TLabel", justify="left").grid(row=7, column=0, columnspan=2, sticky="w", **pad)
        self.bubble_on = tk.BooleanVar(value=cfg.get("bubble", True))
        ttk.Checkbutton(f, text="Значок на экране, когда окно свёрнуто", variable=self.bubble_on,
                        command=self.toggle_bubble).grid(row=8, column=0, columnspan=2, sticky="w", **pad)
        self.deps_msg = ttk.Label(f, text="", style="Muted.TLabel")
        self.deps_msg.grid(row=9, column=0, sticky="w", **pad)
        ttk.Button(f, text="Выход", command=self.quit_app).grid(row=9, column=1, sticky="e", **pad)
        self.bubble = Bubble(self)
        threading.Thread(target=lambda: deps.ensure(DATA, lambda m: self.after(0, self.deps_msg.configure, {"text": m})), daemon=True).start()
        if "--updated" in sys.argv:
            self.after(1500, lambda: self.tray.notify(f"Обновлено до версии {updater.current_version()}"))

        self.pair_until = 0
        self.cast_win: CastWindow | None = None
        self.update_info = None
        self.after(500, self.tick)
        self.after(40, self.pump_cast)
        self.after(20_000, self.check_updates)
        if minimized:
            self.iconify()

    # --------------------------------------------------------- updates ---
    def check_updates(self):
        def worker():
            try:
                info = updater.check()
            except Exception as e:  # noqa: BLE001
                log.info("update check failed: %s", e)
                info = None
            self.after(0, lambda: self.on_update_info(info))
        threading.Thread(target=worker, daemon=True).start()
        self.after(24 * 3600 * 1000, self.check_updates)  # once a day

    def on_update_info(self, info):
        self.update_info = info
        if not info:
            return
        self.upd_btn.configure(text=f"Обновить до {info['version']}", style="Accent.TButton")
        if self.cfg.get("auto_update", True):
            self.update_now()

    def update_now(self):
        info = self.update_info
        if not info:
            self.upd_btn.configure(text="Проверяю…")
            self.after(3000, lambda: self.update_info is None and self.upd_btn.configure(text="Обновлений нет"))
            self.after(6000, lambda: self.update_info is None and self.upd_btn.configure(text="Проверить обновления"))
            self.check_updates()
            return
        self.upd_btn.configure(text="Загрузка…", state="disabled")

        def worker():
            try:
                exe = updater.download(info["url"], DATA / "update" / "PC-Remote.exe")
                if updater.apply(exe):
                    self.after(0, self.destroy)   # the script restarts us with the new exe
                else:
                    self.after(0, lambda: self.upd_btn.configure(text="скачано (не exe)", state="normal"))
            except Exception as e:  # noqa: BLE001
                log.exception("update failed")
                self.after(0, lambda: self.upd_btn.configure(text=f"ошибка обновления", state="normal"))
                self.after(0, lambda: self.code_hint.configure(text=f"Обновление: {e}"))
        threading.Thread(target=worker, daemon=True).start()

    def animate_beam(self):
        """Dots travelling from the PC logo (x≈300) to the phone (x≈398) in the header."""
        on = self.backend.phones > 0
        self.hdr.itemconfigure("phone_scr", fill="#2b5fd0" if on else "#1b2a4a")
        for i, d in enumerate(self.beam):
            if not on:
                self.hdr.itemconfigure(d, state="hidden")
                continue
            f = ((self.beam_t + i * 12) % 60) / 60
            x = 300 + f * 92
            r = 2 + 2 * (1 - abs(f - .5) * 2)
            self.hdr.coords(d, x - r, 34 - r, x + r, 34 + r)
            self.hdr.itemconfigure(d, state="normal")
        self.beam_t += 1
        self.after(60, self.animate_beam)

    def roll_code(self, code: str, step=0):
        """Slot-machine style reveal of the pairing code."""
        import random
        if step < 12:
            shown = "".join(c if i < step // 2 else str(random.randrange(10)) for i, c in enumerate(code))
            self.code.configure(text=f"{shown[:3]} {shown[3:]}", fg=MUTED)
            self.after(50, self.roll_code, code, step + 1)
        else:
            self.code.configure(text=f"{code[:3]} {code[3:]}", fg=ACCENT)

    def show_window(self):
        self.bubble.withdraw()
        self.deiconify(); self.lift(); self.focus_force()

    def hide_window(self):
        """Closing the window never stops the link: it goes to the tray (and the floating badge)."""
        if self.tray.icon:
            self.withdraw()
        else:
            self.iconify()
        if self.bubble_on.get():
            self.bubble.deiconify(); self.bubble.lift()

    def toggle_bubble(self):
        self.cfg["bubble"] = self.bubble_on.get()
        save_config(self.cfg)
        if not self.bubble_on.get():
            self.bubble.withdraw()

    def quit_app(self):
        """Exit only after an explicit confirmation: the phone would lose the PC."""
        from tkinter import messagebox
        self.show_window()
        if messagebox.askyesno(APP_NAME, "Закрыть PC Remote?\n\nТелефон потеряет доступ к ПК, пока программа не будет запущена снова.",
                               icon="warning", default="no", parent=self):
            self.destroy()

    def destroy(self):
        self.tray.stop()
        super().destroy()

    def show_pair(self, guest: bool = False):
        try:
            code = self.backend.pair_code(guest)
        except Exception as e:  # noqa: BLE001
            self.code_hint.configure(text=f"Ошибка: {e}")
            return
        self.pair_until = time.time() + 300
        self.roll_code(code)
        self.backend.paired_ip = ""
        self.pair_kind = "гость (только смотреть, включать и выключать)" if guest else "полный доступ"

    def ring(self):
        try:
            self.backend.ring()
            self.code_hint.configure(text="Телефон звонит. Выключить можно на самом телефоне.")
        except Exception as e:  # noqa: BLE001
            self.code_hint.configure(text=f"Ошибка: {e}")

    def show_cast(self):
        if self.cast_win is None or not self.cast_win.winfo_exists():
            self.cast_win = CastWindow(self)
        self.cast_win.deiconify()
        self.cast_win.lift()

    def pump_cast(self):
        """Move phone frames from the backend queue onto the cast window."""
        b = self.backend
        try:
            frame = b.cast_frames.get_nowait()
        except queue.Empty:
            frame = None
        if frame is not None:
            if self.cast_win is None or not self.cast_win.winfo_exists() or self.cast_win.state() == "withdrawn":
                if not getattr(self, "_cast_shown", False):
                    self.show_cast()          # phone started casting: pop the window up
                    self._cast_shown = True
            if self.cast_win and self.cast_win.winfo_exists():
                try:
                    self.cast_win.show_frame(frame)
                except Exception:  # noqa: BLE001
                    log.exception("cast frame")
        if not b.cast_active and getattr(self, "_cast_shown", False):
            self._cast_shown = False
            if self.cast_win and self.cast_win.winfo_exists():
                self.cast_win.withdraw()
        self.after(40, self.pump_cast)

    def tick(self):
        b = self.backend
        self.bubble.set_state(b.hub is not None and b.phones > 0)
        if b.error:
            self.status.configure(text=f"  ✖ Ошибка: {b.error}  ", fg=BAD)
        elif b.hub is None:
            self.status.configure(text="  запуск…  ", fg=MUTED)
        else:
            phones = b.phones
            who = ", ".join(b.phone_names) if b.phone_names else ("телефонов на связи: %d" % phones if phones else ("ожидает телефон, зову каждую минуту" if b.hub.had_phone else "ожидает телефон"))
            txt = f"  ● {who} · https://{self.cfg['public_ip']}:{self.cfg['port']}"
            if b.cast_active:
                txt += " · идёт трансляция с телефона"
            net = getattr(b, "net", None) or b.hub.net
            if net.get("vpn"):
                txt += " · VPN включён" + (", связь через кабель" if net.get("pinned") else "")
            self.status.configure(text=txt + "  ", fg=OK if phones else WARN)
            # phones vanished right after VPN came up and did not return: almost always a kill switch
            if net.get("vpn") and self.last_phones and not phones:
                self.code_hint.configure(text="Телефоны отвалились после включения VPN: выключите Kill Switch в VPN-клиенте.")
            if phones > self.last_phones:
                self.tray.notify("Телефон подключился")
            self.last_phones = phones
            self.ring_btn.configure(state="normal" if phones else "disabled")
            self.cast_btn.configure(state="normal" if b.cast_active else "disabled")
        left = int(self.pair_until - time.time())
        if b.paired_ip:
            self.code.configure(text="✓")
            self.code_hint.configure(text=f"Телефон привязан ({b.paired_ip})")
        elif left > 0:
            self.code_hint.configure(text=f"Введите код в приложении на телефоне ({getattr(self, 'pair_kind', 'полный доступ')}). Действует ещё {left // 60}:{left % 60:02d}")
        elif self.code.cget("text") not in ("", "✓"):
            self.code.configure(text="")
            self.code_hint.configure(text="Код истёк, нажмите ещё раз")
        self.after(1000, self.tick)


def main():
    DATA.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        handlers=[logging.FileHandler(DATA / "pcapp.log", encoding="utf-8")])
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:  # noqa: BLE001
        pass
    cfg = load_config()
    try:
        install_phone_cli()
    except Exception:  # noqa: BLE001
        log.exception("phone cli")
    try:
        firewall_open(cfg["port"])
    except Exception:  # noqa: BLE001
        log.exception("firewall")
    if cfg["_first_run"]:
        try:
            autostart(True)
        except Exception:  # noqa: BLE001
            log.exception("autostart")
    backend = Backend(cfg)
    backend.start()
    app = App(cfg, backend, minimized=True)
    app.withdraw()
    splash = Splash(app)

    def finish(tries=0):
        if backend.hub is not None or backend.error or tries > 40:
            splash.destroy()
            if "--minimized" not in sys.argv:
                app.show_window()
            elif not app.tray.icon:
                app.iconify()
        else:
            splash.set_msg("запуск…" if tries < 10 else "создаю сертификаты…")
            app.after(150, finish, tries + 1)
    app.after(900, finish)
    app.mainloop()


if __name__ == "__main__":
    main()
