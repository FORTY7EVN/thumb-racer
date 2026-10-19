"""Virtual Xbox 360 pad through ViGEmBus, for games that only read XInput (NFS Heat and most arcade racers).

Talks to ViGEmClient.dll (shipped next to the exe) with ctypes. Layout matches NFS Heat's defaults:
left stick X = steer, RT = gas, LT = brake, X = handbrake, A = nitro, RB / LB = shift up / down.
"""
import ctypes
import sys
from pathlib import Path

from .protocol import (BTN_HANDBRAKE, BTN_NITRO, BTN_SHIFT_DOWN, BTN_SHIFT_UP, InputState, NEUTRAL)

XUSB_LB, XUSB_RB, XUSB_A, XUSB_X = 0x0100, 0x0200, 0x1000, 0x4000
BUTTONS = ((BTN_SHIFT_UP, XUSB_RB), (BTN_SHIFT_DOWN, XUSB_LB), (BTN_NITRO, XUSB_A), (BTN_HANDBRAKE, XUSB_X))
VIGEM_OK = 0x20000000
VIGEM_BUS_NOT_FOUND = 0xE0000001


class XUSB_REPORT(ctypes.Structure):
    _fields_ = [("wButtons", ctypes.c_ushort), ("bLeftTrigger", ctypes.c_ubyte), ("bRightTrigger", ctypes.c_ubyte),
                ("sThumbLX", ctypes.c_short), ("sThumbLY", ctypes.c_short),
                ("sThumbRX", ctypes.c_short), ("sThumbRY", ctypes.c_short)]


def to_xbox(s: InputState, cfg: dict) -> dict:
    steer = -s.steer if cfg.get("invert_steer") else s.steer
    buttons = 0
    for ours, xusb in BUTTONS:
        if s.buttons & ours:
            buttons |= xusb
    return {"lx": max(-32768, min(32767, steer)), "rt": s.gas * 255 // 65535, "lt": s.brake * 255 // 65535,
            "buttons": buttons}


def _dll_path(cfg: dict) -> str:
    if cfg.get("vigem_dll"):
        return cfg["vigem_dll"]
    base = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).parent.parent
    return str(base / "ViGEmClient.dll")


class XboxOut:
    def __init__(self, cfg: dict):
        path = _dll_path(cfg)
        try:
            d = ctypes.CDLL(path)
        except OSError:
            raise RuntimeError(f"ViGEmClient.dll not found at {path} (it ships next to ThumbRacerHost.exe)")
        d.vigem_alloc.restype = ctypes.c_void_p
        d.vigem_target_x360_alloc.restype = ctypes.c_void_p
        for f in (d.vigem_connect, d.vigem_target_add, d.vigem_target_x360_update):
            f.restype = ctypes.c_uint
        d.vigem_connect.argtypes = [ctypes.c_void_p]
        d.vigem_target_add.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
        d.vigem_target_x360_update.argtypes = [ctypes.c_void_p, ctypes.c_void_p, XUSB_REPORT]
        self.dll, self.cfg = d, cfg
        self.client = d.vigem_alloc()
        err = d.vigem_connect(self.client)
        if err == VIGEM_BUS_NOT_FOUND:
            raise RuntimeError("ViGEmBus driver not installed: run ViGEmBusSetup_x64.msi (next to the exe), then restart")
        if err != VIGEM_OK:
            raise RuntimeError(f"ViGEmBus connect failed (0x{err:08X})")
        self.target = d.vigem_target_x360_alloc()
        err = d.vigem_target_add(self.client, self.target)
        if err != VIGEM_OK:
            raise RuntimeError(f"could not plug in the virtual Xbox pad (0x{err:08X})")
        self.neutral()

    def apply(self, s: InputState):
        a = to_xbox(s, self.cfg)
        r = XUSB_REPORT(wButtons=a["buttons"], bLeftTrigger=a["lt"], bRightTrigger=a["rt"], sThumbLX=a["lx"])
        self.dll.vigem_target_x360_update(self.client, self.target, r)

    def neutral(self):
        self.apply(NEUTRAL)
