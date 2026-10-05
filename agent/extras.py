"""
pc-remote agent extras: system stats, processes, timers, devices, speech,
downloads, printing. Windows-first, degrades gracefully elsewhere.
"""
import asyncio
import ctypes
import logging
import os
import shutil
import subprocess
import time
import urllib.request
from pathlib import Path

log = logging.getLogger("agent.extras")
CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0


def _run(cmd, timeout=10) -> str:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                              creationflags=CREATE_NO_WINDOW, shell=isinstance(cmd, str)).stdout
    except Exception:  # noqa: BLE001
        return ""


# ------------------------------------------------------------------ stats ---

class Stats:
    """CPU / RAM / disks / GPU / temps / battery / uptime, via psutil + nvidia-smi."""

    def __init__(self):
        try:
            import psutil
            self.ps = psutil
            psutil.cpu_percent(None)
        except ImportError:
            self.ps = None
        self._net_last = None
        self.has_nvidia = shutil.which("nvidia-smi") is not None

    def gpu(self):
        if not self.has_nvidia:
            return None
        out = _run(["nvidia-smi", "--query-gpu=name,utilization.gpu,temperature.gpu,memory.used,memory.total",
                    "--format=csv,noheader,nounits"], timeout=4).strip().splitlines()
        if not out:
            return None
        try:
            name, load, temp, mu, mt = [x.strip() for x in out[0].split(",")]
            return {"name": name, "load": int(load), "temp": int(temp), "mem_used": int(mu), "mem_total": int(mt)}
        except ValueError:
            return None

    def cpu_temp(self):
        """Best effort: WMI thermal zone (not every board exposes it)."""
        if os.name != "nt":
            return None
        out = _run('powershell -NoProfile -Command "Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature '
                   '| Select-Object -First 1 -ExpandProperty CurrentTemperature"', timeout=6).strip()
        try:
            return round(int(out) / 10 - 273.15)
        except ValueError:
            return None

    def snapshot(self, with_temps: bool = False) -> dict:
        if not self.ps:
            return {"error": "psutil not installed"}
        ps = self.ps
        vm = ps.virtual_memory()
        disks = []
        for part in ps.disk_partitions(all=False):
            try:
                u = ps.disk_usage(part.mountpoint)
                disks.append({"name": part.device.rstrip("\\"), "used": u.used, "total": u.total})
            except Exception:  # noqa: BLE001
                continue
        now = time.time()
        io = ps.net_io_counters()
        up = down = 0
        if self._net_last:
            dt = max(0.001, now - self._net_last[0])
            up = (io.bytes_sent - self._net_last[1]) / dt
            down = (io.bytes_recv - self._net_last[2]) / dt
        self._net_last = (now, io.bytes_sent, io.bytes_recv)
        bat = None
        try:
            b = ps.sensors_battery()
            if b:
                bat = {"percent": b.percent, "plugged": b.power_plugged}
        except Exception:  # noqa: BLE001
            pass
        return {
            "cpu": ps.cpu_percent(None), "cpu_cores": ps.cpu_count(), "cpu_freq": int(ps.cpu_freq().current) if ps.cpu_freq() else None,
            "ram_used": vm.used, "ram_total": vm.total, "disks": disks,
            "gpu": self.gpu(), "cpu_temp": self.cpu_temp() if with_temps else None,
            "uptime": int(now - ps.boot_time()), "battery": bat, "net_up": int(up), "net_down": int(down),
        }

    def processes(self, limit: int = 40) -> list:
        if not self.ps:
            return []
        ps = self.ps
        rows = []
        for p in ps.process_iter(["pid", "name", "cpu_percent", "memory_info"]):
            try:
                rows.append({"pid": p.info["pid"], "name": p.info["name"] or "?",
                             "cpu": p.info["cpu_percent"] or 0.0,
                             "mem": p.info["memory_info"].rss if p.info["memory_info"] else 0})
            except Exception:  # noqa: BLE001
                continue
        rows.sort(key=lambda r: (r["cpu"], r["mem"]), reverse=True)
        return rows[:limit]

    def kill(self, pid: int) -> str:
        if not self.ps:
            return "psutil not installed"
        try:
            p = self.ps.Process(int(pid))
            name = p.name()
            if name.lower() in ("explorer.exe", "csrss.exe", "winlogon.exe", "system", "pc remote.exe", "pc-remote.exe"):
                return "защищённый процесс"
            p.terminate()
            return "ok"
        except Exception as e:  # noqa: BLE001
            return str(e)


# ----------------------------------------------------------------- timers ---

class Timers:
    """Delayed power actions. shutdown/reboot use Windows' own scheduler so they
    survive even if this agent is restarted; others are asyncio tasks."""

    def __init__(self, run_command):
        self.run_command = run_command
        self.timers: dict[str, dict] = {}

    def list(self) -> list:
        now = time.time()
        return [{"id": k, "action": v["action"], "at": v["at"], "left": max(0, int(v["at"] - now))} for k, v in self.timers.items()]

    async def set(self, action: str, seconds: int) -> str:
        seconds = max(10, min(int(seconds), 7 * 24 * 3600))
        if action not in ("shutdown", "reboot", "sleep", "lock"):
            return "unknown action"
        await self.cancel_all(silent=True)  # one timer at a time: the newest replaces the old
        tid = f"{action}-{int(time.time())}"
        if action in ("shutdown", "reboot") and os.name == "nt":
            _run(["shutdown", "/s" if action == "shutdown" else "/r", "/t", str(seconds), "/f"])
            task = None
        else:
            task = asyncio.create_task(self._fire(tid, action, seconds))
        self.timers[tid] = {"action": action, "at": time.time() + seconds, "task": task}
        return "ok"

    async def _fire(self, tid, action, seconds):
        await asyncio.sleep(seconds)
        self.timers.pop(tid, None)
        self.run_command(action)

    async def cancel_all(self, silent=False) -> str:
        for v in self.timers.values():
            if v["task"]:
                v["task"].cancel()
        if os.name == "nt" and any(v["task"] is None for v in self.timers.values()):
            _run(["shutdown", "/a"])
        self.timers.clear()
        return "ok"


# ---------------------------------------------------------------- devices ---

def monitor_power(on: bool):
    """Turn the monitor(s) off via the system power broadcast, or wake them."""
    if os.name != "nt":
        return "windows only"
    user32 = ctypes.WinDLL("user32")
    HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER = 0xFFFF, 0x0112, 0xF170
    if on:
        user32.mouse_event(0x0001, 1, 0, 0, 0)  # a tiny mouse move wakes the display
        user32.mouse_event(0x0001, -1, 0, 0, 0)
        user32.SendMessageW(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, -1)
    else:
        user32.SendMessageW(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, 2)
    return "ok"


def power_plans() -> list:
    out = _run(["powercfg", "/list"]) if os.name == "nt" else ""
    plans = []
    for line in out.splitlines():
        if "GUID" in line and ":" in line:
            try:
                guid = line.split(":")[1].split()[0]
                name = line.split("(")[1].split(")")[0]
                plans.append({"guid": guid, "name": name, "active": "*" in line})
            except (IndexError, ValueError):
                continue
    return plans


def set_power_plan(guid: str) -> str:
    if not all(c in "0123456789abcdefABCDEF-" for c in guid):
        return "bad guid"
    _run(["powercfg", "/setactive", guid])
    return "ok"


def say(text: str) -> str:
    """Speak through the PC speakers (Windows System.Speech, picks a Russian voice if present)."""
    if os.name != "nt":
        return "windows only"
    text = text.replace("'", "’")[:500]
    script = ("Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
              "$v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'ru*' } | Select-Object -First 1; "
              "if ($v) { $s.SelectVoice($v.VoiceInfo.Name) }; $s.Rate = 0; $s.Speak('" + text + "')")
    subprocess.Popen(["powershell", "-NoProfile", "-Command", script], creationflags=CREATE_NO_WINDOW)
    return "ok"


def print_file(path: str) -> str:
    if not os.path.isfile(path):
        return "нет файла"
    try:
        os.startfile(path, "print")  # noqa: S606
        return "ok"
    except Exception as e:  # noqa: BLE001
        return str(e)


# -------------------------------------------------------------- downloads ---

class Downloads:
    """The PC downloads a link into a folder; progress is pushed to the phone."""

    def __init__(self, default_dir: str, notify):
        self.default_dir = default_dir
        self.notify = notify   # async callable(dict)
        self.items: dict[str, dict] = {}

    def list(self) -> list:
        return [{k: v for k, v in d.items() if k != "task"} for d in self.items.values()]

    async def start(self, url: str, folder: str | None) -> str:
        url = url.strip()
        if not (url.startswith("http://") or url.startswith("https://")):
            return "only http(s)"
        folder = folder if folder and os.path.isdir(folder) else self.default_dir
        name = os.path.basename(url.split("?")[0]) or "download"
        name = "".join(c for c in name if c not in '<>:"/\\|?*')[:120] or "download"
        did = f"d{int(time.time() * 1000)}"
        self.items[did] = {"id": did, "name": name, "folder": folder, "done": 0, "total": 0, "status": "идёт", "task": None}
        self.items[did]["task"] = asyncio.create_task(self._fetch(did, url, Path(folder) / name))
        return "ok"

    async def _fetch(self, did, url, dest: Path):
        item = self.items[did]
        loop = asyncio.get_running_loop()
        dest.parent.mkdir(parents=True, exist_ok=True)
        try:
            def work():
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 pc-remote"})
                with urllib.request.urlopen(req, timeout=30) as r, open(dest.with_suffix(dest.suffix + ".part"), "wb") as f:
                    item["total"] = int(r.headers.get("Content-Length") or 0)
                    cd = r.headers.get("Content-Disposition", "")
                    if "filename=" in cd:
                        # the server picks the name: keep only a bare file name, never a path
                        nm = cd.split("filename=")[-1].strip('"; ').replace("\\", "/").rsplit("/", 1)[-1]
                        nm = "".join(c for c in nm if c not in '<>:"/\\|?*' and ord(c) >= 32).strip(". ")[:120]
                        if nm and nm not in (".", ".."):
                            item["name"] = nm
                    last = 0
                    while chunk := r.read(1 << 17):
                        f.write(chunk)
                        item["done"] += len(chunk)
                        if time.time() - last > 0.7:
                            last = time.time()
                            asyncio.run_coroutine_threadsafe(self.notify({"t": "dl", **{k: v for k, v in item.items() if k != "task"}}), loop)
                final = dest.parent / item["name"]
                n = 1
                while final.exists():
                    final = dest.parent / f"{Path(item['name']).stem} ({n}){Path(item['name']).suffix}"
                    n += 1
                dest.with_suffix(dest.suffix + ".part").rename(final)
                item["name"] = final.name
            await loop.run_in_executor(None, work)
            item["status"] = "готово"
        except Exception as e:  # noqa: BLE001
            item["status"] = f"ошибка: {str(e)[:80]}"
        await self.notify({"t": "dl", **{k: v for k, v in item.items() if k != "task"}})

    def cancel(self, did: str) -> str:
        it = self.items.get(did)
        if it and it["task"]:
            it["task"].cancel()
            it["status"] = "отменено"
        return "ok"
