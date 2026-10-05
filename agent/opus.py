"""
Opus for the phone: 16 kHz mono PCM -> ffmpeg (libopus) -> raw Opus packets.

Raw PCM at 16 kHz is 32 KB/s; Opus at 24 kbit/s is 3 KB/s for the same intelligibility,
which is what makes sound usable on a thin mobile link. ffmpeg only writes Opus inside
Ogg, so the pages are split back into packets here (Ogg is small: a 27-byte header, a
lacing table, and 255-byte continuation segments). The first two packets (OpusHead,
OpusTags) are dropped; WebCodecs needs neither for mono.

Frame to the phone: 0x0A + packet (20 ms of audio each, 48 kHz on the decoder side).
"""
import logging
import os
import queue
import subprocess
import threading

log = logging.getLogger("agent.opus")
FRAME_AUDIO_OPUS = b"\x0a"
CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0


def available(ffmpeg: str | None) -> bool:
    if not ffmpeg:
        return False
    try:
        from video import _encoders
        return "libopus" in _encoders(ffmpeg)
    except Exception:  # noqa: BLE001
        return False


class OpusEncoder:
    def __init__(self, ffmpeg: str, rate: int = 16000, bitrate: str = "24k"):
        cmd = [ffmpeg, "-hide_banner", "-loglevel", "error", "-probesize", "32", "-analyzeduration", "0", "-f", "s16le", "-ar", str(rate), "-ac", "1", "-i", "pipe:0",
               "-c:a", "libopus", "-b:a", bitrate, "-vbr", "on", "-application", "audio", "-frame_duration", "20",
               "-page_duration", "20000", "-flush_packets", "1", "-f", "ogg", "pipe:1"]   # probesize: no 1-2 s of input buffering
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     creationflags=CREATE_NO_WINDOW, bufsize=0)
        self.out: queue.Queue = queue.Queue(maxsize=200)
        self.alive = True
        self.bitrate = bitrate
        threading.Thread(target=self._reader, daemon=True, name="opus-out").start()

    def write(self, pcm: bytes) -> bool:
        try:
            self.proc.stdin.write(pcm)
            return True
        except (BrokenPipeError, OSError, ValueError):
            self.alive = False
            return False

    def packets(self) -> list:
        """Everything encoded so far (non-blocking)."""
        out = []
        while True:
            try:
                out.append(self.out.get_nowait())
            except queue.Empty:
                return out

    def close(self):
        self.alive = False
        for f in (self.proc.stdin, self.proc.stdout):
            try:
                f.close()
            except Exception:  # noqa: BLE001
                pass
        try:
            self.proc.kill()
            self.proc.wait(timeout=2)
        except Exception:  # noqa: BLE001
            pass

    def _reader(self):
        try:
            for pkt in ogg_packets(self.proc.stdout):
                if pkt.startswith(b"OpusHead") or pkt.startswith(b"OpusTags"):
                    continue
                try:
                    self.out.put_nowait(pkt)
                except queue.Full:
                    try:
                        self.out.get_nowait()
                    except queue.Empty:
                        pass
        except Exception as e:  # noqa: BLE001
            log.info("opus output ended: %s", e)
        self.alive = False


def ogg_packets(stream):
    """Yield logical packets from an Ogg byte stream (packets may span pages)."""
    partial = b""
    while True:
        hdr = _read(stream, 27)
        if hdr is None:
            return
        if hdr[:4] != b"OggS":
            # resync: look for the next capture pattern byte by byte
            buf = hdr
            while b"OggS" not in buf:
                b = _read(stream, 1)
                if b is None:
                    return
                buf = (buf + b)[-4:]
            i = buf.index(b"OggS")
            rest = _read(stream, 27 - (len(buf) - i))
            if rest is None:
                return
            hdr = buf[i:] + rest
        nseg = hdr[26]
        table = _read(stream, nseg)
        if table is None:
            return
        for lacing in table:
            seg = _read(stream, lacing) if lacing else b""
            if seg is None:
                return
            partial += seg
            if lacing < 255:
                yield partial
                partial = b""


def _read(stream, n: int):
    data = b""
    while len(data) < n:
        chunk = stream.read(n - len(data))
        if not chunk:
            return None
        data += chunk
    return data
