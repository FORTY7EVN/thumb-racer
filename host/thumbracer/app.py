"""Entry point: python -m thumbracer  (or ThumbRacerHost.exe)."""
import argparse
import csv
import socket
import sys
import time
import tomllib
from pathlib import Path

from .server import Host

DEFAULTS = {
    "bind": "0.0.0.0",
    "discovery_port": 47100,
    "data_port": 47101,
    "pc_name": socket.gethostname(),
    "output": "xbox",  # "xbox" (ViGEmBus, works in most games) or "vjoy" (custom joystick for sims)
    "vjoy_id": 1,
    "neutral_timeout_ms": 250,
    "drop_timeout_ms": 3000,
    "invert_steer": False,
    "invert_gas": False,
    "invert_brake": False,
    "invert_handbrake": False,
    "combined_pedals": False,
}


def load_config(path: Path) -> dict:
    cfg = dict(DEFAULTS)
    if path.exists():
        with path.open("rb") as f:
            cfg.update(tomllib.load(f))
    return cfg


class PrintOut:
    """--no-vjoy: test the network path without the driver."""
    def __init__(self, cfg):
        self.cfg = cfg

    def apply(self, s):
        pass

    def neutral(self):
        print("\n[neutral]")


def bar(v: float, width=10) -> str:
    n = round(max(0.0, min(1.0, v)) * width)
    return "#" * n + "." * (width - n)


def main(argv=None):
    base = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path.cwd()
    ap = argparse.ArgumentParser(prog="thumbracer")
    ap.add_argument("--config", type=Path, default=base / "config.toml")
    ap.add_argument("--debug", action="store_true", help="print every decoded packet")
    ap.add_argument("--latency-log", type=Path, help="CSV of phone tMs vs PC receive time")
    ap.add_argument("--no-vjoy", action="store_true", help="run without the vJoy driver")
    args = ap.parse_args(argv)
    cfg = load_config(args.config)

    if args.no_vjoy:
        out = PrintOut(cfg)
    else:
        try:
            if cfg["output"] == "vjoy":
                from .vjoy_out import VJoyOut
                out = VJoyOut(cfg["vjoy_id"], cfg)
            else:
                from .xbox_out import XboxOut
                out = XboxOut(cfg)
        except (OSError, RuntimeError) as e:
            print(f"Controller output '{cfg['output']}' not available: {e}\nSee README > One-time PC setup.")
            input("Press Enter to close.")  # keep the window open when started by double-click
            return 2
        print(f"Output: {'vJoy device ' + str(cfg['vjoy_id']) if cfg['output'] == 'vjoy' else 'virtual Xbox 360 controller'}")

    log_file = writer = None
    if args.latency_log:
        log_file = args.latency_log.open("w", newline="")
        writer = csv.writer(log_file)
        writer.writerow(["seq", "phone_t_ms", "pc_t_ms"])

    def on_input(s):
        if writer:
            writer.writerow([s.seq, s.t_ms, round(time.monotonic() * 1000)])
        if args.debug:
            print(s)

    host = Host(cfg, out, on_input)
    host.start()
    print(f"Thumb Racer host '{cfg['pc_name']}' on UDP {host.discovery_port} (discovery) "
          f"/ {host.data_port} (data). Ctrl+C to quit.")
    last = 0
    try:
        while True:
            time.sleep(1)
            st = host.status()
            rate, last = st["received"] - last, st["received"]
            if not st["phone"]:
                last = 0
                print("\rWaiting for phone...".ljust(100), end="", flush=True)
                continue
            s = st["state"]
            print(f"\r{st['phone']} | {rate:4d} pkt/s | lost {st['lost_pct']:.1f}% | "
                  f"steer {s.steer / 32767:+.2f} gas {bar(s.gas / 65535)} brake {bar(s.brake / 65535)}",
                  end="", flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        host.stop()
        if log_file:
            log_file.close()
        print("\nStopped; controller centered.")
    return 0
