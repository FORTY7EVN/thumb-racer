# Thumb Racer

Two thumbs on a Redmi Note 13 become a racing controller for Windows: thumb height difference steers,
thumb spacing is gas/brake. The phone sends 26-byte UDP packets at 250 Hz; the PC host feeds a vJoy joystick.

| Part | Folder | Status |
| --- | --- | --- |
| Windows host (Python) | `host/` | done (section 1) |
| Android app (Kotlin) | `android/` | sections 2–5 |

## Get the PC host

Download **ThumbRacerHost-windows** from the latest run of the *PC host (.exe)* workflow in the Actions tab.
It contains `ThumbRacerHost.exe` and `config.toml` (keep them in the same folder).

## One-time PC setup

1. Install the vJoy driver for Windows 10/11 (BrunnerInnovation fork, v2.2.x).
2. Open **Configure vJoy** → device 1: enable axes **X, Y, Z, Rx**, **8 buttons**, 0 POV hats, force feedback off.
3. Run `ThumbRacerHost.exe`. When Windows Firewall asks, allow it on **Private** networks.
4. Bind each game once:

| Control | vJoy input |
| --- | --- |
| Steering | X axis |
| Throttle | Y axis |
| Brake | Z axis |
| Handbrake | Rx axis or button 4 |
| Shift up / down | buttons 1 / 2 |
| Nitro | button 3 |

## Test without the phone

```
python fake_phone.py            # finds the host on the LAN, streams a sine steer + pedal ramps for 20 s
```

Open vJoy Monitor to watch the axes. Stopping the script must center everything within 250 ms.

## Run from source

```
cd host
python -m thumbracer             # options: --debug, --latency-log lat.csv, --no-vjoy
python -m unittest discover -s tests
```

Needs Python 3.11+, no pip packages.
