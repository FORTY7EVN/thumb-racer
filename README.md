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
- `ThumbRacerHost.exe`, `config.toml`, `ViGEmClient.dll` → PC, all in the same folder
- `ViGEmBusSetup_x64.msi` → PC, install once

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

The host shows up in Windows as a **virtual Xbox 360 controller** by default, which works in NFS Heat,
Forza and almost every racing game with no binding.

1. Run `ViGEmBusSetup_x64.msi` once (the virtual-gamepad driver), then restart the PC.
2. Run `ThumbRacerHost.exe`. When Windows Firewall asks, allow it on **Private** networks.
3. In the game choose controller / gamepad input. Default layout (matches NFS Heat):

| Phone | Xbox pad |
| --- | --- |
| Steer | Left stick |
| Gas / brake | RT / LT |
| Handbrake | X |
| Nitro | A |
| Shift up / down | RB / LB |

**Sims that bind any axis (Assetto Corsa etc.):** set `output = "vjoy"` in `config.toml`, install the
vJoy driver (BrunnerInnovation fork, v2.2.x), enable device 1 with axes X, Y, Z, Rx and 8 buttons, and bind
X = steer, Y = gas, Z = brake, Rx or button 4 = handbrake, buttons 1/2 = shift, button 3 = nitro.

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
