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
import time
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
    sums = next((a["browser_download_url"] for a in rel.get("assets", []) if a.get("name") == "SHA256SUMS"), None)
    cur = current_version()
    if not url or cur == "dev" or _tuple(tag) <= _tuple(cur):
        return None
    return {"version": tag.lstrip("v"), "url": url, "notes": rel.get("body", ""), "sums": sums}


def expected_sha256(sums_url: str | None, name: str) -> str | None:
    """The published checksum for `name`, or None when the release has no SHA256SUMS."""
    if not sums_url:
        return None
    try:
        with urllib.request.urlopen(urllib.request.Request(sums_url, headers={"User-Agent": "pc-remote"}), timeout=15) as r:
            for line in r.read().decode("utf-8", "replace").splitlines():
                parts = line.split()
                if len(parts) == 2 and parts[1].lstrip("*") == name:
                    return parts[0].lower()
    except Exception as e:  # noqa: BLE001
        log.info("SHA256SUMS unavailable: %s", e)
    return None


def download(url: str, dest: Path, sha256: str | None = None, exe: bool = True) -> Path:
    import hashlib
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(".part")
    req = urllib.request.Request(url, headers={"User-Agent": "pc-remote"})
    h = hashlib.sha256()
    with urllib.request.urlopen(req, timeout=60) as r, open(part, "wb") as f:
        while chunk := r.read(1 << 16):
            f.write(chunk)
            h.update(chunk)
    head = part.read_bytes()[:2] if part.stat().st_size >= 2 else b""
    if exe and (part.stat().st_size < 1_000_000 or head != b"MZ"):
        part.unlink(missing_ok=True)
        raise RuntimeError("downloaded file is not a valid exe")
    if not exe and head != b"PK":
        part.unlink(missing_ok=True)
        raise RuntimeError("downloaded file is not a valid apk")
    if sha256 and h.hexdigest() != sha256:
        part.unlink(missing_ok=True)
        raise RuntimeError("checksum mismatch: the download does not match the published SHA256SUMS")
    part.replace(dest)
    return dest


def apply(new_exe: Path, version: str = "") -> bool:
    """Replace the running exe with new_exe and restart. Only when frozen."""
    if not getattr(sys, "frozen", False):
        log.info("not frozen: update downloaded to %s but not applied", new_exe)
        return False
    me = Path(sys.executable)
    script = Path(tempfile.gettempdir()) / "pc-remote-update.cmd"
    script.write_bytes(b"")   # must exist for its short name
    # Until 05.10.2026 PC Remote closed for an update and never came back. Three reasons, each checked:
    # DETACHED_PROCESS makes cmd.exe exit at once with code 1; without a console cmd reads the script in another
    # code page, so "C:\Users\Николай\..." became garbage (8.3 short paths are ASCII); and `find`/`timeout`
    # resolved to Git's find (when started from Git Bash) and need a console — system tools by full path, PING to wait.
    pid, new, cur = os.getpid(), _short(new_exe), _short(me)
    script.write_bytes((
        "@echo off\r\n"
        "set S=%SystemRoot%\\System32\r\n"
        f":wait\r\n\"%S%\\tasklist.exe\" /FI \"PID eq {pid}\" /NH | \"%S%\\find.exe\" \"{pid}\" >nul && (\"%S%\\PING.EXE\" -n 2 127.0.0.1 >nul & goto wait)\r\n"
        "set N=0\r\n"
        f":copy\r\ncopy /y \"{new}\" \"{cur}\" >nul && goto copied\r\n"
        "set /a N+=1\r\nif %N% lss 20 (\"%S%\\PING.EXE\" -n 2 127.0.0.1 >nul & goto copy)\r\n"
        f":copied\r\ndel \"{new}\" 2>nul\r\n"
        f"start \"\" \"{cur}\" --minimized --updated={version}\r\n"
        # make sure it really came up (05.10.2026 the swap worked but the new copy was gone): up to 3 more starts
        "set R=0\r\n"
        f":check\r\n\"%S%\\PING.EXE\" -n 6 127.0.0.1 >nul\r\n"
        f"\"%S%\\tasklist.exe\" /FI \"IMAGENAME eq {me.name}\" /NH | \"%S%\\find.exe\" /I \"{me.name}\" >nul && goto done\r\n"
        "set /a R+=1\r\n"
        f"if %R% lss 4 (start \"\" \"{cur}\" --minimized --updated={version} & goto check)\r\n"
        ":done\r\n"
        "del \"%~f0\"\r\n").encode("ascii", "replace"))
    subprocess.Popen(["cmd", "/c", _short(script)], creationflags=0x08000000 | 0x00000200)  # NO_WINDOW, NEW_PROCESS_GROUP
    return True


def _short(path) -> str:
    """The 8.3 name of an existing path: plain ASCII, safe inside a .cmd whatever the code page."""
    import ctypes
    buf = ctypes.create_unicode_buffer(1024)
    return buf.value if ctypes.windll.kernel32.GetShortPathNameW(str(path), buf, 1024) else str(path)


# ------------------------------------------------------------ phone apps ---
APK_NAMES = ["pcremote.apk", "pcremote-net.apk", "pcremote-files.apk"]


def refresh_apks(data: Path, status=None, pairing: dict | None = None) -> dict | None:
    """Fetch the release's phone apps, check their sums, re-sign them with this PC's key (apksign.py)
    and keep them in data/apk for the phones to update from. Returns the index, None if nothing to do."""
    import json
    import apksign
    folder = data / "apk"
    folder.mkdir(parents=True, exist_ok=True)
    index_path = folder / "index.json"
    try:
        index = json.loads(index_path.read_text("utf-8")) if index_path.exists() else {}
    except ValueError:
        index = {}
    try:
        req = urllib.request.Request(API, headers={"User-Agent": "pc-remote", "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(req, timeout=20) as r:
            rel = json.load(r)
    except Exception as e:  # noqa: BLE001
        log.info("apk refresh: release unavailable: %s", e)
        return index or None
    version = str(rel.get("tag_name", "")).lstrip("v")
    if not version:
        return index or None
    assets = {a.get("name"): a.get("browser_download_url") for a in rel.get("assets", [])}
    sums_url = assets.get("SHA256SUMS")
    key, cert = apksign.ensure_key(data)
    fp = apksign.cert_sha256(cert)
    # the apps carry this PC's address, certificate and permanent code (assets/pairing.json): a fresh install,
    # even after the app was removed, pairs by itself. The files are served only to paired phones (/api/apk).
    pairing = {k: v for k, v in (pairing or {}).items() if v}
    if (index.get("version") == version and index.get("cert") == fp and index.get("pairing", {}) == pairing
            and all((folder / n).exists() for n in index.get("files", {}))):
        return index
    extra = {"assets/pairing.json": json.dumps(pairing, ensure_ascii=False).encode("utf-8")} if pairing.get("code") else None
    files = {}
    for name in APK_NAMES:
        url = assets.get(name)
        if not url:
            continue
        if status:
            status(f"приложения для телефона: {name} {version}…")
        try:
            raw = download(url, folder / (name + ".download"), expected_sha256(sums_url, name), exe=False)
            sha = apksign.sign(raw, folder / name, key, cert, extra if name != "pcremote-files.apk" else None)
            raw.unlink(missing_ok=True)
            files[name] = {"sha256": sha, "size": (folder / name).stat().st_size}
        except Exception as e:  # noqa: BLE001
            log.warning("apk %s: %s", name, e)
    if not files:
        return index or None
    index = {"version": version, "cert": fp, "files": files, "at": time.time(), "pairing": pairing}
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=1), "utf-8")
    if status:
        status(f"приложения для телефона готовы: {version}, подпись этого ПК")
    log.info("phone apps re-signed: %s (%s)", version, ", ".join(files))
    return index
