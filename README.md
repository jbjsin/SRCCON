# SRCCON

Developed and published by **SongPaHaeChi**.

SRCCON is a native Android shutter controller derived from the behavior of the
Windows/N.I.N.A. SeRelCam project. It uses a phone or tablet with USB Host/OTG
to send serial commands to a relay connected to a camera's SNAP or wired remote
shutter contact.

SRCCON is an independent Android application. It does not require N.I.N.A. and
does not run, embed, or wrap the Windows SeRelCam plugin.

## Status

Version **0.0.1** is the first runnable Android MVP. It contains the complete
fixed-profile path from explicit USB selection through Manual or Single
Exposure control, foreground operation, STOP, and visible fault recovery.

```text
Software validation passed (Gradle build, 24 JVM tests, Android Lint)
Hardware validation pending
```

## Intended operation

```text
Android UI
  -> operation coordinator / foreground service
  -> USB serial relay
  -> camera SNAP / remote shutter contact
```

Version 0.0.1 includes:

- non-invasive USB serial device discovery;
- explicit device selection and Connect action;
- Android USB permission handling;
- a read-only built-in VCTEC/NOYITO relay protocol profile;
- manual relay ON/OFF;
- timed single exposure;
- immediate STOP and safety OFF attempts;
- persistent settings;
- foreground operation while the screen is off;
- a bounded partial wake lock while a physical operation is active;
- visible USB detach, command failure, and crash-recovery states;
- a software-only Demo Mode that never opens USB hardware.

## USB ownership

SRCCON is intended to coexist with focusers, mounts, Arduino controllers, and
other astronomy applications on the same Android device or OTG hub. It follows
this hardware-ownership rule:

```text
DISCOVER -> INFORM -> USER SELECTS -> USER CONNECTS
```

A scan only lists devices. SRCCON must not automatically open or claim a USB
interface, request permission, select a CH340 device, connect a previously used
device, or transmit a relay command. A user action establishes ownership.

A remembered device may be highlighted on the next launch, but 0.0.1 does not
automatically reconnect it.

## Reference relay profile

The initial built-in profile is based on the hardware used to validate Windows
SeRelCam:

| Setting | VCTEC / NOYITO reference |
| --- | --- |
| USB UART | CH340 |
| Baud | 9600 |
| Data / parity / stop | 8 / none / 1 |
| DTR / RTS | off / off |
| Relay ON | `A0 01 01 A2` |
| Relay OFF | `A0 01 00 A1` |

The reference camera is a Fujifilm X-T20 using a suitable wired shutter cable:
relay COM to camera remote common/GND, relay NO to the shutter contact, and NC
unused.

Electrical pinouts and behavior differ by camera and relay. Verify the correct
cable, voltage, contact mode, and protocol before use. A CH340 USB interface is
not proof that the attached device is a relay.

## Relay Protocol Profile

A USB device answers “which physical serial interface?” A Relay Protocol
Profile answers “which serial settings and command bytes?” SRCCON keeps these
concepts separate and combines them only when starting an operation.

The built-in VCTEC/NOYITO profile is read-only in 0.0.1. Its serial settings and
both command byte arrays are validated and copied into an immutable operation
snapshot. Editable user profiles and versioned JSON import/export are reserved
for a later version.

## Quick start

The safe workflow is:

1. Connect the relay through a USB OTG adapter or powered OTG hub.
2. Launch SRCCON and refresh the informational device list.
3. Inspect product, manufacturer, VID/PID, driver family, and permission state.
4. Select the intended device and press **Connect**.
5. Grant Android USB permission when requested.
6. Select and validate the correct Relay Protocol Profile.
7. Send **OFF** first and physically verify the relay behavior.
8. Use explicit Manual or Single Exposure controls.
9. Use **STOP** whenever the operation must end immediately.

Do not use a camera or valuable equipment for the first protocol test. Validate
relay behavior and wiring separately.

## 0.0.1 scope limits

The initial release does not include:

- UVC or camera image preview;
- image downloading or RAW decoding;
- FlashAir networking;
- plate solving or an astronomy object catalog;
- N.I.N.A. interoperability;
- remote network control;
- automatic USB reconnection;
- editable relay profiles or JSON import/export;
- repeated Burst Mode;
- Google Play publication.

Waiting for an image file to finish transferring is a later-phase feature. A
file-size/timestamp stability check can only suggest transfer completion; it
cannot confirm camera exposure or physical relay state.

## Safety limitations

The reference protocol is write-only and supplies no acknowledgement. SRCCON
can report that bytes were transmitted but cannot confirm that relay contacts
physically moved or that the camera shutter changed state.

The reference relay can retain its contact state after a serial connection is
closed. Software cannot guarantee OFF after Android force-stop, hard process
kill, operating-system failure, phone or USB power loss, cable removal, or
relay hardware failure. Use an independent hardware watchdog where that risk is
unacceptable.

If USB detaches or OFF cannot be transmitted, SRCCON must show the relay state
as unknown rather than claiming OFF.

## Building

Use JDK 17 and the installed Android SDK 36. From the repository root:

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug lintDebug --no-daemon
```

The debug APK is written as `app/build/outputs/apk/debug/SRCCON_0.0.1.apk`.
Automated tests use a fake relay transport and never energize USB hardware.
Build and unit-test results establish software validation only; the manual
relay checklist in [README-DEV.md](README-DEV.md) is still required.

The 2026-10-01 clean verification completed with 24/24 JVM tests passing,
APK assembly successful, and Android Lint reporting zero errors. Hardware and
screen-off timing remain unverified until the manual checklist is performed.

## EAFCON UI look and feel

The reusable visual specification is maintained separately in
[`ui-guide/HC Astro UI - 0001/README.md`](ui-guide/HC%20Astro%20UI%20-%200001/README.md).
It includes generated Light, Dark, and Night example screens. The portable
package is [`HC Astro UI - 0001.zip`](HC%20Astro%20UI%20-%200001.zip).

See [README-DEV.md](README-DEV.md) for the architecture and validation plan.
