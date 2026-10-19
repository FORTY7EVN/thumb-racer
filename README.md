# Thumb Racer

Two thumbs on a Redmi Note 13 become a racing controller for Windows: thumb height difference steers,
thumb spacing is gas/brake. The phone sends 26-byte UDP packets at 250 Hz; the PC host feeds a vJoy joystick.

| Part | Folder |
| --- | --- |
| Windows host (Python) | `host/` |
| Android app (Kotlin) | `android/` |

## Download

Everything is on the [**latest release**](https://github.com/FORTY7EVN/thumb-racer/releases/tag/latest), rebuilt on every push:

- `ThumbRacer.apk` → phone
- `ThumbRacerHost.exe` + `config.toml` → PC (keep them in the same folder)

## Phone setup (Redmi / HyperOS)

1. Open `ThumbRacer.apk` on the phone and allow installing from that app when asked.
2. Settings > Apps > Thumb Racer > Battery saver > **No restrictions**.
3. Phone and PC on the same network (5 GHz Wi-Fi if you can).
4. Open the app, run **Calibrate thumbs** once, then tap your PC in the list.

## Driving

| Do this | Get this |
| --- | --- |
| Rest both thumbs (one per half) | Engage (two short buzzes) |
| Right thumb lower than left | Steer right (and the reverse) |
| Spread thumbs apart | Gas — screen turns green |
| Squeeze thumbs together | Brake — screen turns red (hold at a stop to reverse) |
| Slide both thumbs down | Handbrake — amber border |
| Push both thumbs up | Nitro — cyan border (or wrist flick, in settings) |
| Quick lift + retap right / left thumb | Shift up / down |
| Lift a thumb longer than 0.2 s | Everything released |
| Hold a finger top-center for 1 s | Quick menu: analog/digital, sensitivity, nitro mode |

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
