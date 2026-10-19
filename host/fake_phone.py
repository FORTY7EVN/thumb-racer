"""Pretends to be the phone: finds the host, connects, streams scripted input at 250 Hz.

    python fake_phone.py                 # discover on the LAN, run 20 s
    python fake_phone.py --host 127.0.0.1 --seconds 10

Watch vJoy Monitor: X sweeps as a sine, Y and Z ramp in turn, buttons blink.
Stopping it (Ctrl+C) should center everything on the PC within 250 ms.
"""
import argparse
import math
import random
import socket
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from thumbracer import protocol as p  # noqa: E402


def discover(port: int, timeout=3.0):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.5)
    nonce = random.getrandbits(32)
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        s.sendto(p.encode_discover(nonce), ("255.255.255.255", port))
        try:
            data, addr = s.recvfrom(128)
        except socket.timeout:
            continue
        h = p.parse_header(data)
        if h and h[1] == p.DISCOVER_REPLY:
            n, data_port, name = p.decode_discover_reply(data)
            if n == nonce:
                print(f"Found '{name}' at {addr[0]}:{data_port}")
                return addr[0], data_port
    raise SystemExit("No host answered discovery.")


def script(t: float):
    """Scripted 'thumbs': steer sine, then gas ramp, then brake ramp, repeating every 6 s."""
    steer = int(32767 * math.sin(t * 2 * math.pi / 3))
    phase = t % 6
    gas = int(65535 * min(1, phase / 2)) if phase < 3 else 0
    brake = int(65535 * min(1, (phase - 3) / 2)) if phase >= 3 else 0
    buttons = p.BTN_ENGAGED | (p.BTN_SHIFT_UP if int(t * 2) % 4 == 0 else 0)
    return steer, gas, brake, buttons


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host")
    ap.add_argument("--port", type=int, default=47101)
    ap.add_argument("--discovery-port", type=int, default=47100)
    ap.add_argument("--seconds", type=float, default=20)
    ap.add_argument("--rate", type=int, default=250)
    a = ap.parse_args()

    host, port = (a.host, a.port) if a.host else discover(a.discovery_port)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(1)
    s.sendto(p.encode_hello("fake-phone"), (host, port))
    data, _ = s.recvfrom(64)
    h = p.parse_header(data)
    if not h or h[1] != p.WELCOME:
        raise SystemExit(f"Host refused: {data!r}")
    session = int.from_bytes(data[4:6], "little")
    print(f"Connected, session {session}. Streaming for {a.seconds:.0f} s...")

    s.setblocking(False)
    start = next_send = time.perf_counter()
    seq, last_ping = 0, 0.0
    try:
        while (now := time.perf_counter()) - start < a.seconds:
            if now >= next_send:
                t = now - start
                steer, gas, brake, buttons = script(t)
                seq += 1
                s.sendto(p.encode_input(p.InputState(session, seq, int(t * 1000) & 0xFFFFFFFF,
                                                     steer, gas, brake, 0, buttons, 1)), (host, port))
                next_send += 1 / a.rate
            if now - last_ping > 0.5:
                s.sendto(p.encode_ping(session, seq), (host, port))
                last_ping = now
            try:
                s.recvfrom(64)  # drain PONGs
            except BlockingIOError:
                pass
            time.sleep(0.0005)
    except KeyboardInterrupt:
        pass
    s.sendto(p.encode_bye(session), (host, port))
    print(f"Sent {seq} packets, bye.")


if __name__ == "__main__":
    main()
