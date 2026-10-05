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


def load_config() -> dict:
    DATA.mkdir(parents=True, exist_ok=True)
    first_run = not CONFIG.exists()
    cfg = json.loads(CONFIG.read_text("utf-8")) if CONFIG.exists() else {}
    changed = first_run
    if not cfg.get("secret"):
        cfg["secret"] = secrets.token_urlsafe(30)
        changed = True
    cfg.setdefault("port", 8443)
    cfg.setdefault("ntfy_wake_url", "")
    cfg.setdefault("max_width", 1280)
    cfg.setdefault("quality", 55)
    cfg.setdefault("fps", 12)
    cfg.setdefault("monitor", 1)
    cfg.setdefault("auto_update", True)
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
        }
        app = relay_mod.make_app(relay_cfg)
        self.hub = app["hub"]
        self.hub.on_paired = lambda ip: setattr(self, "paired_ip", ip)
        self.hub.on_phones = lambda n: setattr(self, "phones", n)
        self.hub.on_cast = self._on_cast
        agent_cfg = {"relay_url": "http://127.0.0.1:8787", "secret": self.cfg["secret"],
                     "max_width": self.cfg["max_width"], "quality": self.cfg["quality"],
                     "fps": self.cfg["fps"], "monitor": self.cfg["monitor"]}
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

    def pair_code(self) -> str:
        return asyncio.run_coroutine_threadsafe(self._pair(), self.loop).result(5)

    async def _pair(self):
        return self.hub.start_pairing()

    def ring(self):
        asyncio.run_coroutine_threadsafe(self.hub.ring_phones(), self.loop).result(5)


# -------------------------------------------------------------------- GUI ---

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


class App(tk.Tk):
    def __init__(self, cfg: dict, backend: Backend, minimized: bool):
        super().__init__()
        self.cfg, self.backend = cfg, backend
        self.title(APP_NAME)
        self.resizable(False, False)
        self.protocol("WM_DELETE_WINDOW", self.iconify)  # close = hide to taskbar, keep running
        pad = {"padx": 14, "pady": 4}
        f = ttk.Frame(self, padding=12)
        f.grid()

        ttk.Label(f, text=APP_NAME, font=("Segoe UI", 16, "bold")).grid(row=0, column=0, sticky="w", **pad)
        self.upd_btn = ttk.Button(f, text=f"версия {updater.current_version()}", command=self.update_now)
        self.upd_btn.grid(row=0, column=1, sticky="e", **pad)
        self.status = ttk.Label(f, text="запуск…", foreground="#888")
        self.status.grid(row=1, column=0, columnspan=2, sticky="w", **pad)

        ttk.Label(f, text="Адрес для телефона:").grid(row=2, column=0, sticky="w", **pad)
        self.addr = ttk.Entry(f, width=28)
        self.addr.insert(0, f"{cfg['public_ip'] or '?'}:{cfg['port']}")
        self.addr.configure(state="readonly")
        self.addr.grid(row=2, column=1, sticky="w", **pad)

        ttk.Button(f, text="Привязать телефон", command=self.show_pair).grid(row=3, column=0, sticky="w", **pad)
        self.code = ttk.Label(f, text="", font=("Consolas", 22, "bold"), foreground="#1d6fe0")
        self.code.grid(row=3, column=1, sticky="w", **pad)
        self.code_hint = ttk.Label(f, text="", foreground="#888")
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
        ttk.Label(f, text=hint, foreground="#888", justify="left").grid(row=7, column=0, columnspan=2, sticky="w", **pad)
        ttk.Button(f, text="Выход", command=self.destroy).grid(row=8, column=1, sticky="e", **pad)

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
        self.upd_btn.configure(text=f"Обновить до {info['version']}")
        if self.cfg.get("auto_update", True):
            self.update_now()

    def update_now(self):
        info = self.update_info
        if not info:
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

    def show_pair(self):
        try:
            code = self.backend.pair_code()
        except Exception as e:  # noqa: BLE001
            self.code_hint.configure(text=f"Ошибка: {e}")
            return
        self.pair_until = time.time() + 300
        self.code.configure(text=f"{code[:3]} {code[3:]}")
        self.backend.paired_ip = ""

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
        if b.error:
            self.status.configure(text=f"Ошибка: {b.error}", foreground="#d33")
        elif b.hub is None:
            self.status.configure(text="запуск…", foreground="#888")
        else:
            phones = b.phones
            txt = f"Работает · https://{self.cfg['public_ip']}:{self.cfg['port']}"
            txt += f" · телефонов на связи: {phones}" if phones else " · ожидает телефон"
            if b.cast_active:
                txt += " · идёт трансляция с телефона"
            self.status.configure(text=txt, foreground="#2a9d4a" if phones else "#b8860b")
            self.ring_btn.configure(state="normal" if phones else "disabled")
            self.cast_btn.configure(state="normal" if b.cast_active else "disabled")
        left = int(self.pair_until - time.time())
        if b.paired_ip:
            self.code.configure(text="✓")
            self.code_hint.configure(text=f"Телефон привязан ({b.paired_ip})")
        elif left > 0:
            self.code_hint.configure(text=f"Введите код в приложении на телефоне. Действует ещё {left // 60}:{left % 60:02d}")
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
    App(cfg, backend, minimized="--minimized" in sys.argv).mainloop()


if __name__ == "__main__":
    main()
