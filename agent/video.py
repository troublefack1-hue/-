"""
Real video for the screen: raw BGRA frames -> ffmpeg (H.264 or VP8) -> the phone's WebCodecs decoder.

Compared with JPEG-per-frame this is 5-10x less traffic at 30 fps, which is what makes films
and games watchable. ffmpeg is a separate program; PC Remote downloads it on first start
(deps.py) and this module just finds it. If it is missing, or the phone cannot decode,
the JPEG path keeps working as before.

Frame to the phone: 0x09 + flags (bit0 = key frame) + codec (1 = h264, 2 = vp8) + 8 bytes pts (us) + data.
"""
import logging
import os
import queue
import shutil
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path

log = logging.getLogger("agent.video")
FRAME_VIDEO_CODEC = b"\x09"
CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0

BITRATE = {"eco": "500k", "normal": "2500k", "hq": "6000k"}


def find_ffmpeg() -> str | None:
    """ffmpeg next to the exe, in the data folder (downloaded by deps.py), or on PATH."""
    exe = "ffmpeg.exe" if os.name == "nt" else "ffmpeg"
    candidates = []
    data = os.environ.get("PC_REMOTE_DATA") or (os.path.join(os.environ.get("LOCALAPPDATA", ""), "pc-remote") if os.name == "nt" else "")
    if data:
        candidates += [os.path.join(data, "components", "ffmpeg", exe), os.path.join(data, "components", "ffmpeg", "bin", exe)]
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    candidates += [str(base / exe), str(Path(sys.executable).parent / exe)]
    for c in candidates:
        if os.path.isfile(c):
            return c
    return shutil.which("ffmpeg")


def pick_codec(phone_codecs: list, ffmpeg: str) -> str | None:
    """h264 if both sides can, else vp8, else None. Checks the ffmpeg build once."""
    have = _encoders(ffmpeg)
    if "avc1" in phone_codecs and ("libx264" in have or "h264_nvenc" in have):
        return "h264"
    if "vp8" in phone_codecs and "libvpx" in have:
        return "vp8"
    return None


_enc_cache: dict = {}


def _encoders(ffmpeg: str) -> set:
    if ffmpeg in _enc_cache:
        return _enc_cache[ffmpeg]
    try:
        out = subprocess.run([ffmpeg, "-hide_banner", "-encoders"], capture_output=True, text=True, timeout=10,
                             creationflags=CREATE_NO_WINDOW).stdout
    except Exception:  # noqa: BLE001
        out = ""
    have = {w for line in out.splitlines() for w in line.split()[1:2]}
    _enc_cache[ffmpeg] = have
    return have


class Encoder:
    """One ffmpeg process. write(bgra) feeds a frame; frames() yields (key, pts_us, bytes)."""

    def __init__(self, ffmpeg: str, codec: str, width: int, height: int, fps: int, profile: str = "normal", pix_fmt: str = "bgra"):
        self.codec, self.width, self.height, self.fps = codec, width, height, max(1, fps)
        self.key = None
        have = _encoders(ffmpeg)
        br = BITRATE.get(profile, BITRATE["normal"])
        gop = str(self.fps * 2)
        if codec == "h264":
            if "h264_nvenc" in have and os.name == "nt":
                venc = ["-c:v", "h264_nvenc", "-preset", "p1", "-tune", "ll", "-rc", "cbr", "-b:v", br, "-g", gop, "-bf", "0", "-profile:v", "baseline"]
            else:
                venc = ["-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-profile:v", "baseline", "-b:v", br,
                        "-maxrate", br, "-bufsize", br, "-g", gop, "-bf", "0", "-x264-params", "repeat-headers=1:aud=1:sliced-threads=1"]
            fmt = ["-f", "h264"]
        else:
            venc = ["-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "8", "-b:v", br, "-maxrate", br, "-bufsize", br,
                    "-g", gop, "-lag-in-frames", "0", "-error-resilient", "1", "-auto-alt-ref", "0"]
            fmt = ["-f", "ivf"]
        cmd = [ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", pix_fmt, "-s", f"{width}x{height}",
               "-r", str(self.fps), "-i", "pipe:0", "-an", *venc, "-pix_fmt", "yuv420p", *fmt, "pipe:1"]
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     creationflags=CREATE_NO_WINDOW, bufsize=0)
        self.out: queue.Queue = queue.Queue(maxsize=60)
        self.alive = True
        self.n_in = 0
        self.t0 = time.monotonic()
        threading.Thread(target=self._reader, daemon=True, name="ffmpeg-out").start()
        threading.Thread(target=self._stderr, daemon=True, name="ffmpeg-err").start()

    def write(self, bgra: bytes) -> bool:
        try:
            self.proc.stdin.write(bgra)
            self.n_in += 1
            return True
        except (BrokenPipeError, OSError, ValueError):
            self.alive = False
            return False

    def get(self, timeout: float = 1.0):
        try:
            return self.out.get(timeout=timeout)
        except queue.Empty:
            return None

    def close(self):
        self.alive = False
        try:
            self.proc.stdin.close()
        except Exception:  # noqa: BLE001
            pass
        try:
            self.proc.kill()
        except Exception:  # noqa: BLE001
            pass

    # ---- output parsing --------------------------------------------------
    def _emit(self, key: bool, data: bytes):
        pts = int((time.monotonic() - self.t0) * 1e6)
        try:
            self.out.put_nowait((key, pts, data))
        except queue.Full:
            try:
                self.out.get_nowait()   # drop the oldest: the link is slower than the encoder
                self.out.put_nowait((key, pts, data))
            except queue.Empty:
                pass

    def _stderr(self):
        for line in self.proc.stderr:
            log.info("ffmpeg: %s", line.decode("utf-8", "replace").rstrip())

    def _reader(self):
        try:
            if self.codec == "vp8":
                self._read_ivf()
            else:
                self._read_annexb()
        except Exception as e:  # noqa: BLE001
            log.info("encoder output ended: %s", e)
        self.alive = False

    def _read_ivf(self):
        r = self.proc.stdout
        hdr = r.read(32)
        if len(hdr) < 32 or hdr[:4] != b"DKIF":
            return
        while True:
            fh = r.read(12)
            if len(fh) < 12:
                return
            size = struct.unpack("<I", fh[:4])[0]
            data = b""
            while len(data) < size:
                chunk = r.read(size - len(data))
                if not chunk:
                    return
                data += chunk
            key = bool(data) and not (data[0] & 0x01)   # VP8 frame tag: bit0 = 0 for key frames
            self._emit(key, data)

    def _read_annexb(self):
        """Split the H.264 byte stream into access units at AUD NALs (aud=1), mark IDR as key."""
        r = self.proc.stdout
        buf = b""
        au = b""
        while True:
            chunk = r.read(65536)
            if not chunk:
                if buf:
                    self._emit(_is_key(buf), buf)
                return
            buf += chunk
            while True:
                i = _find_aud(buf, 4)   # skip the AUD this unit starts with
                if i < 0:
                    break
                au = buf[:i]
                buf = buf[i:]
                if au and au != b"\x00\x00\x00\x01\x09\xf0":
                    self._emit(_is_key(au), au)
            if len(buf) > 8 * 1024 * 1024:   # no AUD seen: not what we configured, flush as one frame
                self._emit(_is_key(buf), buf)
                buf = b""


def _find_aud(buf: bytes, start: int) -> int:
    """Index of the next start code whose NAL type is 9 (access unit delimiter), or -1."""
    i = start
    while True:
        j = buf.find(b"\x00\x00\x01", i)
        if j < 0 or j + 3 >= len(buf):
            return -1
        if buf[j + 3] & 0x1F == 9:
            return j - 1 if j > 0 and buf[j - 1] == 0 else j
        i = j + 3


def _is_key(au: bytes) -> bool:
    i = 0
    while True:
        j = au.find(b"\x00\x00\x01", i)
        if j < 0 or j + 3 >= len(au):
            return False
        if au[j + 3] & 0x1F == 5:
            return True
        i = j + 3
