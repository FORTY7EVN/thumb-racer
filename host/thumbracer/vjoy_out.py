"""Maps decoded InputState onto a vJoy device. Axes take 1..32768, center 16384."""
from .protocol import InputState, NEUTRAL

AXIS_MIN, AXIS_MAX, AXIS_CENTER = 1, 0x8000, 0x4000


def _clamp(v: int) -> int:
    return max(AXIS_MIN, min(AXIS_MAX, v))


def _inv(v: int, invert: bool) -> int:
    return AXIS_MAX + AXIS_MIN - v if invert else v


def to_axes(s: InputState, cfg: dict) -> dict:
    """Return {"x","y","z","rx","buttons"} for vJoy. cfg keys: invert_* and combined_pedals."""
    x = _clamp(AXIS_CENTER + s.steer // 2)
    if cfg.get("combined_pedals"):
        # older games: one axis, gas pushes up from center, brake pulls down
        y, z = _clamp(AXIS_CENTER + (s.gas - s.brake) // 4), AXIS_MIN
    else:
        y, z = _clamp(AXIS_MIN + s.gas // 2), _clamp(AXIS_MIN + s.brake // 2)
    rx = _clamp(AXIS_MIN + s.handbrake // 2)
    return {
        "x": _inv(x, cfg.get("invert_steer", False)),
        "y": _inv(y, cfg.get("invert_gas", False)),
        "z": _inv(z, cfg.get("invert_brake", False)),
        "rx": _inv(rx, cfg.get("invert_handbrake", False)),
        "buttons": s.buttons & 0x1F,
    }


HID_X, HID_Y, HID_Z, HID_RX = 0x30, 0x31, 0x32, 0x33
VJD_STAT_FREE = 1
DEFAULT_DLLS = [r"C:\Program Files\vJoy\x64\vJoyInterface.dll",
                r"C:\Program Files\vJoy\x86\vJoyInterface.dll"]


class VJoyOut:
    """Talks to the installed driver's own vJoyInterface.dll, so DLL and driver versions always match."""

    def __init__(self, device_id: int, cfg: dict):
        import ctypes  # Windows only; imported here so tests run anywhere
        paths = [cfg["vjoy_dll"]] if cfg.get("vjoy_dll") else DEFAULT_DLLS
        if ctypes.sizeof(ctypes.c_void_p) == 4:
            paths = paths[::-1]  # 32-bit Python needs the x86 DLL
        for path in paths:
            try:
                self.dll = ctypes.CDLL(path)  # vJoy SDK exports are __cdecl
                break
            except OSError:
                continue
        else:
            raise RuntimeError(f"vJoyInterface.dll not found in {paths}")
        if not self.dll.vJoyEnabled():
            raise RuntimeError("vJoy driver is not enabled")
        status = self.dll.GetVJDStatus(device_id)
        if status != VJD_STAT_FREE:
            raise RuntimeError(f"vJoy device {device_id} is not free (status {status}: 2=busy, 3=missing)")
        if not self.dll.AcquireVJD(device_id):
            raise RuntimeError(f"could not acquire vJoy device {device_id}")
        self.id, self.cfg, self.buttons = device_id, cfg, None
        self.neutral()

    def apply(self, s: InputState):
        a = to_axes(s, self.cfg)
        for usage, key in ((HID_X, "x"), (HID_Y, "y"), (HID_Z, "z"), (HID_RX, "rx")):
            self.dll.SetAxis(a[key], self.id, usage)
        if a["buttons"] != self.buttons:  # buttons only change on gestures
            for i in range(5):
                self.dll.SetBtn(bool(a["buttons"] >> i & 1), self.id, i + 1)
            self.buttons = a["buttons"]

    def neutral(self):
        self.apply(NEUTRAL)
