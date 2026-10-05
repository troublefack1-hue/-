"""
Self-update for PC Remote.

Each push to the repository makes GitHub build a new release (see
.github/workflows/build.yml). The app compares its bundled VERSION with the
latest release tag, downloads the new exe and swaps itself through a tiny
batch script (Windows won't let a running exe overwrite itself).
"""
import json
import logging
import os
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

REPO = "troublefack1-hue/-"
API = f"https://api.github.com/repos/{REPO}/releases/latest"
ASSET = "PC-Remote.exe"
log = logging.getLogger("updater")


def current_version() -> str:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    try:
        return (base / "VERSION").read_text().strip()
    except OSError:
        return "dev"


def _tuple(v: str):
    return tuple(int(x) for x in v.lstrip("v").split(".") if x.isdigit())


def check() -> dict | None:
    """Return {"version", "url", "notes"} if a newer release exists, else None."""
    req = urllib.request.Request(API, headers={"User-Agent": "pc-remote", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        rel = json.load(r)
    tag = rel.get("tag_name", "")
    url = next((a["browser_download_url"] for a in rel.get("assets", []) if a.get("name") == ASSET), None)
    cur = current_version()
    if not url or cur == "dev" or _tuple(tag) <= _tuple(cur):
        return None
    return {"version": tag.lstrip("v"), "url": url, "notes": rel.get("body", "")}


def download(url: str, dest: Path) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(".part")
    req = urllib.request.Request(url, headers={"User-Agent": "pc-remote"})
    with urllib.request.urlopen(req, timeout=60) as r, open(part, "wb") as f:
        while chunk := r.read(1 << 16):
            f.write(chunk)
    if part.stat().st_size < 1_000_000 or part.read_bytes()[:2] != b"MZ":
        part.unlink(missing_ok=True)
        raise RuntimeError("downloaded file is not a valid exe")
    part.replace(dest)
    return dest


def apply(new_exe: Path) -> bool:
    """Replace the running exe with new_exe and restart. Only when frozen."""
    if not getattr(sys, "frozen", False):
        log.info("not frozen: update downloaded to %s but not applied", new_exe)
        return False
    me = Path(sys.executable)
    script = Path(tempfile.gettempdir()) / "pc-remote-update.cmd"
    script.write_text(
        "@echo off\r\n"
        f":wait\r\ntasklist /FI \"PID eq {os.getpid()}\" | find \"{os.getpid()}\" >nul && (timeout /t 1 >nul & goto wait)\r\n"
        f"copy /y \"{new_exe}\" \"{me}\" >nul\r\n"
        f"start \"\" \"{me}\" --minimized --updated\r\n"
        f"del \"{new_exe}\"\r\n"
        "del \"%~f0\"\r\n", encoding="cp866")
    subprocess.Popen(["cmd", "/c", str(script)], creationflags=0x00000008 | 0x00000200)  # DETACHED, NEW_PROCESS_GROUP
    return True
