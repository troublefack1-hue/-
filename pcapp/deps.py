"""
Self-provisioning for PC Remote.

Run from source: missing Python packages are installed with pip automatically.
Run as the exe: optional components listed in the release asset `components.json`
(name, url, sha256, dest) are downloaded into %LOCALAPPDATA%\\pc-remote\\components
when missing or changed. Both happen at start, in the background, with a status
line in the window; nothing here is required for the basic link to work.
"""
import hashlib
import importlib
import json
import logging
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

log = logging.getLogger("deps")
REPO = "troublefack1-hue/-"
CREATE_NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0

# import name -> pip name, Windows-only ones flagged
PACKAGES = [("aiohttp", "aiohttp", False), ("mss", "mss", False), ("PIL", "Pillow", False), ("cryptography", "cryptography", False),
            ("psutil", "psutil", False), ("pystray", "pystray", False), ("qrcode", "qrcode", False),
            ("pyaudiowpatch", "PyAudioWPatch", True), ("winpty", "pywinpty", True), ("pycaw", "pycaw", True),
            ("dxcam", "dxcam", True)]


def missing_packages() -> list[str]:
    out = []
    for mod, pip_name, win_only in PACKAGES:
        if win_only and sys.platform != "win32":
            continue
        try:
            importlib.import_module(mod)
        except Exception:  # noqa: BLE001
            out.append(pip_name)
    return out


def install_packages(names: list[str], status) -> bool:
    if not names:
        return True
    status(f"устанавливаю: {', '.join(names)}…")
    try:
        subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", "--disable-pip-version-check", *names],
                       check=True, timeout=600, creationflags=CREATE_NO_WINDOW)
        importlib.invalidate_caches()
        return True
    except Exception as e:  # noqa: BLE001
        log.warning("pip install failed: %s", e)
        status(f"не удалось установить {', '.join(names)}: {e}")
        return False


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


# Components every install wants, even without a components.json in the release.
# ffmpeg turns the screen into real H.264/VP8 video (agent/video.py); ~100 MB once.
BUILTIN = [
    # GitHub (BtbN static GPL build: libx264 + nvenc/amf/qsv): gyan.dev crawled at ~50 KB/s here, GitHub ~2 MB/s
    {"name": "ffmpeg/ffmpeg.exe", "url": "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip",
     "zip_member": "bin/ffmpeg.exe", "windows": True, "label": "ffmpeg (видео-поток, ~200 МБ)"},
]


def fetch_components(data_dir: Path, status, want_video: bool = True) -> int:
    """Download components from the latest release manifest (+ built-ins). Returns how many were fetched."""
    manifest = {"files": []}
    try:
        req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases/latest",
                                     headers={"User-Agent": "pc-remote", "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(req, timeout=15) as r:
            rel = json.load(r)
        url = next((a["browser_download_url"] for a in rel.get("assets", []) if a.get("name") == "components.json"), None)
        if url:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "pc-remote"}), timeout=15) as r:
                manifest = json.load(r)
    except Exception as e:  # noqa: BLE001
        log.info("components manifest: %s", e)
    files = list(manifest.get("files", []))
    if want_video:
        files += [c for c in BUILTIN if not (c.get("windows") and sys.platform != "win32")]
    root = data_dir / "components"
    root.mkdir(parents=True, exist_ok=True)
    got = 0
    for item in files:
        name = str(item.get("name", "")).replace("\\", "/").lstrip("/")
        if not name or ".." in name or not item.get("url"):
            continue
        dest = root / name
        if dest.exists() and (not item.get("sha256") or _sha256(dest) == item["sha256"]):
            continue
        status(f"докачиваю {item.get('label') or name}…")
        dest.parent.mkdir(parents=True, exist_ok=True)
        part = dest.with_suffix(dest.suffix + ".part")
        for attempt in range(3):   # a VPN or a flaky link cuts big downloads short: a cut zip is "not a zip file"
            try:
                with urllib.request.urlopen(urllib.request.Request(item["url"], headers={"User-Agent": "pc-remote"}), timeout=120) as r, open(part, "wb") as f:
                    total = int(r.headers.get("Content-Length") or 0)
                    done = 0
                    while chunk := r.read(1 << 17):
                        f.write(chunk)
                        done += len(chunk)
                        if total and done % (8 << 20) < (1 << 17):
                            status(f"докачиваю {item.get('label') or name}: {done * 100 // total}%")
                if total and done != total:
                    raise OSError(f"got {done} of {total} bytes")
                break
            except Exception as e:  # noqa: BLE001
                log.warning("component %s: download attempt %d failed: %s", name, attempt + 1, e)
                part.unlink(missing_ok=True)
        else:
            continue
        if item.get("zip_member"):
            # take one file out of the archive (e.g. bin/ffmpeg.exe), whatever the top folder is called
            import zipfile
            member = str(item["zip_member"]).replace("\\", "/")
            with zipfile.ZipFile(part) as z:
                hit = next((n for n in z.namelist() if n.replace("\\", "/").endswith("/" + member) or n == member), None)
                if not hit:
                    part.unlink(missing_ok=True)
                    log.warning("component %s: %s not in archive", name, member)
                    continue
                with z.open(hit) as src, open(dest.with_suffix(dest.suffix + ".tmp"), "wb") as out:
                    shutil.copyfileobj(src, out, 1 << 20)
            part.unlink(missing_ok=True)
            part = dest.with_suffix(dest.suffix + ".tmp")
        if item.get("sha256") and _sha256(part) != item["sha256"]:
            part.unlink(missing_ok=True)
            log.warning("component %s: checksum mismatch", name)
            continue
        part.replace(dest)
        got += 1
        if item.get("run"):  # optional installer step, e.g. a redistributable
            try:
                subprocess.run([str(dest), *item.get("args", [])], timeout=600, creationflags=CREATE_NO_WINDOW)
            except Exception as e:  # noqa: BLE001
                log.warning("component %s: run failed: %s", name, e)
    return got


def ensure(data_dir: Path, status=lambda s: None, want_video: bool = True) -> None:
    """Call from a background thread at start. `status` gets short Russian progress lines."""
    if not getattr(sys, "frozen", False):
        miss = missing_packages()
        if miss:
            install_packages(miss, status)
    try:
        n = fetch_components(data_dir, status, want_video)
        status(f"докачано компонентов: {n}" if n else "компоненты на месте")
    except Exception as e:  # noqa: BLE001
        log.info("components: %s", e)
        status(f"компоненты: {e}")
