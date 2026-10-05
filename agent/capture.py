"""
Screen capture backends, picked and swapped at run time.

  gdi   mss (BitBlt): works everywhere, 15-30 ms per frame, a full copy every time.
  dxgi  Desktop Duplication through dxcam: 1-3 ms, and Windows tells us whether anything
        changed at all, so idle screens cost nothing. Not available for "all monitors as one
        picture", during some full-screen exclusive games, or on a few virtual displays —
        then the agent falls back to GDI and quietly retries DXGI a minute later.

Both expose grab(region=None, force=False) -> (bgra_bytes, (w, h)) or None when the picture
is unchanged (gdi never says "unchanged": the caller hashes). region = (left, top, w, h) in
monitor coordinates.
"""
import logging
import time

log = logging.getLogger("agent.capture")


def dxgi_available() -> bool:
    try:
        import dxcam  # noqa: F401
        return True
    except Exception:  # noqa: BLE001
        return False


class MssCapture:
    name = "gdi"

    def __init__(self, sct, mon: dict):
        self.sct, self.mon = sct, mon
        self.last_ms = 0.0

    def grab(self, region=None, force=False):
        box = self.mon if region is None else {"left": self.mon["left"] + region[0], "top": self.mon["top"] + region[1],
                                               "width": region[2], "height": region[3]}
        t0 = time.monotonic()
        shot = self.sct.grab(box)
        self.last_ms = (time.monotonic() - t0) * 1000
        return bytes(shot.raw), shot.size

    def close(self):
        pass


class DxgiCapture:
    name = "dxgi"

    def __init__(self, output_idx: int, mon: dict):
        import dxcam
        self.mon = mon
        self.cam = dxcam.create(output_idx=output_idx, output_color="BGRA")
        if self.cam is None:
            raise RuntimeError("dxcam: no such output")
        self.last = None            # (bytes, size) of the last frame, for force=True
        self.last_ms = 0.0
        self.errors = 0

    def grab(self, region=None, force=False):
        t0 = time.monotonic()
        rg = None if region is None else (region[0], region[1], region[0] + region[2], region[1] + region[3])
        frame = self.cam.grab(region=rg)
        self.last_ms = (time.monotonic() - t0) * 1000
        if frame is None:                      # nothing changed since the last grab
            if force and self.last is not None and region is None:
                return self.last
            return None
        h, w = frame.shape[0], frame.shape[1]
        out = (frame.tobytes(), (w, h))
        if region is None:
            self.last = out
        return out

    def close(self):
        try:
            self.cam.release()
        except Exception:  # noqa: BLE001
            pass
