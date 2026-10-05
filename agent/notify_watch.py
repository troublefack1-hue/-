"""
Windows notifications -> phone.

Windows keeps every toast in a small SQLite database
(%LOCALAPPDATA%\\Microsoft\\Windows\\Notifications\\wpndatabase.db). Reading it needs no
WinRT and no extra packages: we copy the file when its mtime changes, open the copy
read-only and pick up rows newer than the last one we saw. Each toast's XML payload
holds the text lines; the handler table maps it to the app that sent it.
"""
import logging
import os
import shutil
import sqlite3
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

log = logging.getLogger("agent.notify")

DB = Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "Windows" / "Notifications" / "wpndatabase.db"

# aumid / handler ids -> friendly names
APP_NAMES = {
    "windows.explorer": "Проводник", "microsoft.windows.explorer": "Проводник", "chrome": "Chrome", "msedge": "Edge",
    "firefox": "Firefox", "telegram": "Telegram", "whatsapp": "WhatsApp", "discord": "Discord", "steam": "Steam",
    "outlook": "Outlook", "mail": "Почта", "windows.systemtoast": "Windows", "securityhealth": "Защитник Windows",
    "windowsupdate": "Центр обновления", "teams": "Teams", "spotify": "Spotify", "zoom": "Zoom",
}


def app_name(handler: str) -> str:
    h = (handler or "").lower()
    for key, name in APP_NAMES.items():
        if key in h:
            return name
    tail = h.replace("\\", "/").rstrip("/").rsplit("/", 1)[-1].rsplit("!", 1)[-1]
    tail = tail.split("_")[0].split(".")[-1] if tail else ""
    return tail[:1].upper() + tail[1:] if tail else "ПК"


def parse_payload(xml_bytes) -> tuple[str, str]:
    """Returns (title, text) from a toast XML; both may be empty."""
    try:
        if isinstance(xml_bytes, (bytes, bytearray)):
            xml_bytes = xml_bytes.decode("utf-8", "replace")
        root = ET.fromstring(xml_bytes)
    except ET.ParseError:
        return "", ""
    lines = [("".join(t.itertext())).strip() for t in root.iter("text")]
    lines = [x for x in lines if x]
    if not lines:
        return "", ""
    return lines[0][:120], " ".join(lines[1:])[:300]


class NotifWatcher:
    def __init__(self, db: Path = DB):
        self.db = db
        self.last_id = None
        self.last_mtime = 0.0
        self.copy = Path(tempfile.gettempdir()) / "pc-remote-wpn.db"
        self.available = db.exists()

    def _snapshot(self) -> bool:
        """Copy the live DB (and its WAL) if it changed. Returns True when there is something new to read."""
        try:
            mtime = max(self.db.stat().st_mtime, (self.db.with_suffix(".db-wal").stat().st_mtime if self.db.with_suffix(".db-wal").exists() else 0))
        except OSError:
            return False
        if mtime == self.last_mtime:
            return False
        self.last_mtime = mtime
        try:
            shutil.copyfile(self.db, self.copy)
            wal = self.db.with_suffix(".db-wal")
            if wal.exists():
                shutil.copyfile(wal, self.copy.with_suffix(".db-wal"))
            elif self.copy.with_suffix(".db-wal").exists():
                self.copy.with_suffix(".db-wal").unlink()
        except OSError as e:
            log.debug("notification db copy failed: %s", e)
            return False
        return True

    def poll(self) -> list:
        """New toasts since the previous call (the first call only remembers where we are)."""
        if not self.available or not self._snapshot():
            return []
        out = []
        try:
            con = sqlite3.connect(f"file:{self.copy}?mode=ro", uri=True, timeout=1)
            try:
                cur = con.execute(
                    "SELECT n.Id, h.PrimaryId, n.Payload, n.ArrivalTime FROM Notification n "
                    "LEFT JOIN NotificationHandler h ON h.RecordId = n.HandlerId "
                    "WHERE n.Type = 'toast' ORDER BY n.Id")
                rows = cur.fetchall()
            finally:
                con.close()
        except sqlite3.Error as e:
            log.debug("notification db read failed: %s", e)
            return []
        if self.last_id is None:
            self.last_id = rows[-1][0] if rows else 0
            return []
        for nid, handler, payload, _arrival in rows:
            if nid <= self.last_id:
                continue
            self.last_id = nid
            title, text = parse_payload(payload)
            if not title and not text:
                continue
            out.append({"app": app_name(handler), "title": title, "text": text, "ts": time.time()})
        return out[-5:]   # a burst after sleep shouldn't flood the phone
