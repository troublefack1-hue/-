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

BITRATE = {"tiny": "48k", "low": "250k", "eco": "500k", "normal": "2500k", "hq": "6000k"}   # "tiny" ≈ 10 KB/s link, "low" = slow link fallback
# Constant quality under the profile's bitrate ceiling: a still desktop costs almost nothing, motion takes what it
# needs up to the ceiling. Measured 05.10.2026 on a 1920x1080 desktop, 20 fps, GTX 1660 SUPER: NVENC CBR 6000k sent
# 732 KB/s even on a still screen (SSIM 0.985); cq 24 sent 87 KB/s still / 359 KB/s scrolling at SSIM 0.984.
# libx264 needs ~3 more crf for the same picture. Thin links (tiny, low rungs) stay on CBR: there the link is the limit.
CQ = {"eco": 30, "normal": 26, "hq": 24}


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


# H.264 encoders in order of preference; each is tried for real once (a build may list
# h264_nvenc without an NVIDIA card), the first that encodes a frame wins for the session.
H264_CHAIN = [
    ("h264_nvenc", ["-c:v", "h264_nvenc", "-preset", "p1", "-tune", "ll", "-rc", "cbr", "-bf", "0", "-profile:v", "baseline"]),
    ("h264_qsv", ["-c:v", "h264_qsv", "-preset", "veryfast", "-bf", "0", "-profile:v", "baseline", "-look_ahead", "0"]),
    ("h264_amf", ["-c:v", "h264_amf", "-usage", "ultralowlatency", "-quality", "speed", "-bf", "0", "-profile:v", "baseline"]),
    ("libx264", ["-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-profile:v", "baseline", "-bf", "0",
                 "-x264-params", "repeat-headers=1:aud=1:sliced-threads=1"]),
]
_h264_pick: dict = {}


def _kbit(br: str) -> int:
    try:
        return int(br.lower().rstrip("k"))
    except ValueError:
        return 10**6


def h264_encoder(ffmpeg: str):
    """(name, args) of the first H.264 encoder that really works on this machine, or None."""
    if ffmpeg in _h264_pick:
        return _h264_pick[ffmpeg]
    have = _encoders(ffmpeg)
    chosen = None
    for name, args in H264_CHAIN:
        if name not in have:
            continue
        if name == "libx264" or _probe(ffmpeg, args):
            chosen = (name, args)
            break
    _h264_pick[ffmpeg] = chosen
    if chosen:
        log.info("h264 encoder: %s", chosen[0])
    return chosen


def _probe(ffmpeg: str, venc: list) -> bool:
    """Encode 2 black frames with this encoder; False if the hardware/driver is not there."""
    try:
        r = subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=black:s=320x240:r=5", "-frames:v", "2",
                            *venc, "-pix_fmt", "yuv420p", "-f", "null", "-"], capture_output=True, timeout=15, creationflags=CREATE_NO_WINDOW)
        return r.returncode == 0
    except Exception:  # noqa: BLE001
        return False


def pick_codec(phone_codecs: list, ffmpeg: str) -> str | None:
    """h264 if both sides can, else vp8, else None."""
    if "avc1" in phone_codecs and h264_encoder(ffmpeg):
        return "h264"
    if "vp8" in phone_codecs and "libvpx" in _encoders(ffmpeg):
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

    def __init__(self, ffmpeg: str, codec: str, width: int, height: int, fps: int, profile: str = "normal", pix_fmt: str = "bgra",
                 bitrate: str | None = None):
        self.codec, self.width, self.height, self.fps = codec, width, height, max(1, fps)
        self.key = None
        have = _encoders(ffmpeg)
        br = bitrate or BITRATE.get(profile, BITRATE["normal"])
        self.bitrate = br
        thin = profile == "tiny" or _kbit(br) <= 150
        # the link is TCP: nothing is lost, so key frames are only for a new viewer (the agent restarts the encoder
        # for one) and as a slow safety refresh; a 1920 key frame of a photo wallpaper is ~200 KB
        gop = str(self.fps * (10 if thin else 30))
        cq = None if thin else CQ.get(profile)
        if codec == "h264":
            name, args = h264_encoder(ffmpeg) or H264_CHAIN[-1]
            self.encoder_name = name
            if cq is not None and name == "h264_nvenc":
                args = [a for i, a in enumerate(args) if not (a == "-rc" or (i and args[i - 1] == "-rc"))]
                venc = [*args, "-rc", "vbr", "-cq", str(cq), "-b:v", "0", "-maxrate", br, "-bufsize", br, "-g", gop]
            elif cq is not None and name == "libx264":
                venc = [*args, "-crf", str(cq + 3), "-maxrate", br, "-bufsize", br, "-g", gop]
            else:
                venc = [*args, "-b:v", br, "-maxrate", br, "-bufsize", br, "-g", gop]
            if name != "libx264":
                # hardware encoders do not emit AUD/repeat headers via x264-params: use the bitstream filter
                venc += ["-bsf:v", "h264_metadata=aud=insert", "-flags", "-global_header"]
            fmt = ["-f", "h264"]
        else:
            self.encoder_name = "libvpx"
            vq = ["-crf", str(min(63, cq + 6))] if cq is not None else []   # constrained quality: crf under the -b:v ceiling
            venc = ["-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "8", *vq, "-b:v", br, "-maxrate", br, "-bufsize", br,
                    "-g", gop, "-lag-in-frames", "0", "-error-resilient", "1", "-auto-alt-ref", "0"]
            fmt = ["-f", "ivf"]
        cmd = [ffmpeg, "-hide_banner", "-loglevel", "error", "-probesize", "32", "-analyzeduration", "0", "-f", "rawvideo", "-pix_fmt", pix_fmt, "-s", f"{width}x{height}",
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
            self.proc.wait(timeout=2)   # reap so it does not linger as a zombie
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
