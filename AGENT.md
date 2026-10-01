# SRCCON Project Brief

SRCCON is the native Android port and companion application for SeRelCam.
It controls a serial-command relay through Android USB Host/OTG so that the
relay can close and open a camera SNAP or wired remote shutter contact.
SRCCON runs independently of N.I.N.A. and does not execute or wrap the Windows
`SeRelCam.dll`.

Developed and published by **SongPaHaeChi**.

## Project identity

- Product and repository name: **SRCCON**
- Relationship: Android-native SeRelCam port/companion
- Platform: Android phone or tablet with USB Host/OTG support
- Language and UI: Kotlin and Jetpack Compose
- Initial branch plan: `main` and `feature/android-mvp`
- Windows behavioral reference: <https://github.com/jbjsin/SeRelCam>

The Android project is separate from the Windows SeRelCam repository. Windows
WPF, N.I.N.A. MEF APIs, `System.IO.Ports`, COM-port identities, and plugin DLLs
are not portable application components.

## Product rule

USB hardware ownership always follows this sequence:

```text
DISCOVER -> INFORM -> USER SELECTS -> USER CONNECTS
```

Scanning is informational. Discovery, selection, USB permission, logical
connection, and relay activation are separate actions. The app must not claim,
open, configure, probe, or transmit to a serial device merely because it was
found or resembles a known relay.

## Reference hardware

The initial validated Windows configuration, and the intended Android hardware
validation target, is:

| Item | Reference value |
| --- | --- |
| Relay | VCTEC/NOYITO one-channel USB relay |
| USB UART | CH340 |
| Serial framing | 9600 baud, 8 data bits, no parity, 1 stop bit |
| DTR / RTS | `false` / `false` |
| ON | `A0 01 01 A2` |
| OFF | `A0 01 00 A1` |
| Camera | Fujifilm X-T20 |

VCTEC/NOYITO is a reference profile, not a universal compatibility claim. The
reference relay may retain its contact state after the serial port is closed.

## Android 0.0.1 scope

The first runnable MVP implements non-invasive USB discovery, explicit device
selection and connection, the fixed read-only VCTEC/NOYITO profile, manual
ON/OFF, timed single exposure, immediate STOP, persistent settings, foreground
screen-off operation, USB-detach fault handling, crash-risk recovery, and a
software-only Demo Mode.

Editable profiles, JSON import/export, and repeated Burst Mode are planned for
a later version. Do not present them as 0.0.1 features.

Image preview, UVC, FlashAir networking, RAW decoding, plate solving, N.I.N.A.
interoperability, remote network control, and automatic reconnection are
outside 0.0.1.

## Safety summary

Before ON, validate the selected device, permission, driver, serial settings,
both ON and OFF commands, duration, and maximum duration. Every operation owns
an immutable configuration snapshot. A new operation requires a successful
preflight OFF. Manual, Single, and Burst share one logical owner, and stale or
cancelled work must never affect a newer operation.

The protocol is write-only. SRCCON may report that a command was transmitted;
it must not claim that the physical relay changed state. If OFF cannot be
transmitted, state is `Fault` / `UNKNOWN` rather than `Idle` / `OFF`.

## Current status

The Android application and local JVM safety tests exist on
`feature/android-mvp`. The 2026-10-01 clean Gradle verification passed with
24/24 JVM tests, APK assembly, and zero Android Lint errors. Exact evidence is
recorded in `README-DEV.md`. No Android relay hardware validation has been
completed. Until the manual hardware checklist is completed, report hardware
status as:

```text
Hardware validation pending
```

For mandatory coding and hardware rules, read [AGENTS.md](AGENTS.md). For the
architecture and development workflow, read [README-DEV.md](README-DEV.md).
