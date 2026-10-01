# SRCCON Developer Guide

SRCCON 0.0.1 is the native Android MVP of the SeRelCam serial-relay shutter
workflow. It replaces the N.I.N.A./WPF/COM environment with Android USB Host,
Kotlin, Compose, DataStore, coroutines, and a connected-device foreground
service.

Developed and published by **SongPaHaeChi**.

Read [AGENTS.md](AGENTS.md) before changing code. Its USB ownership and relay
safety rules are mandatory.

## Release scope

Implemented in 0.0.1:

- passive enumeration of supported USB serial devices;
- explicit device/port selection, Connect, and Android USB permission;
- short-lived open/configure/write/close transport attempts;
- fixed read-only VCTEC/NOYITO 9600 8N1 profile;
- Manual ON/OFF and timed Single Exposure;
- preflight OFF, serialized retries, STOP, and Safety OFF recovery;
- immutable operation snapshots and stale-generation rejection;
- durable may-be-energized marker and `Fault/UNKNOWN` restart state;
- USB detach handling;
- foreground notification with STOP and a bounded partial wake lock;
- Light, Dark, and Night Compose themes based on the EAFCON look and feel;
- persistent theme and exposure settings;
- software-only Demo Mode.

Deferred beyond 0.0.1:

- Burst Mode;
- editable user profiles and JSON import/export;
- camera preview, file transfer, RAW processing, and FlashAir;
- plate solving, object catalogs, network control, and N.I.N.A. integration;
- automatic USB selection or reconnection.

## Toolchain

| Component | Pinned value |
| --- | --- |
| JDK | 17 |
| Gradle wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose plugin | 2.2.20 |
| Compile / target SDK | 36 |
| Minimum SDK | 26 |
| Compose BOM | 2026.04.01 |
| AndroidX Activity Compose | 1.12.3 |
| AndroidX Lifecycle | 2.10.0 |
| DataStore Preferences | 1.2.0 |
| Kotlin Coroutines | 1.10.2 |
| usb-serial-for-android | 3.11.0 |

The minimum SDK keeps Java 8 platform APIs available while covering the
intended field devices. SDK 36 is installed in the current development
environment.

## Project map

```text
app/src/main/java/dev/sphc/srccon/
  SrcconApplication.kt             process dependency and coordinator owner
  MainActivity.kt                  Compose host and notification permission
  core/
    HexCommandParser.kt
    RelayConfiguration.kt
    RelayProtocolProfile.kt
    OperationCoordinator.kt
  settings/
    SrcconSettingsRepository.kt
    DataStoreRiskMarkerStore.kt
  transport/
    SerialRelayTransport.kt
    FakeSerialRelayTransport.kt
  usb/
    UsbDeviceModels.kt
    UsbDeviceRepository.kt
    UsbPermissionManager.kt
    UsbConnectionSession.kt
    UsbSerialRelayTransport.kt
  service/
    ShutterForegroundService.kt
  ui/
    SrcconTheme.kt
    SrcconViewModel.kt
    SrcconScreen.kt
app/src/test/java/dev/sphc/srccon/
  core/ settings/ usb/
ui-guide/HC Astro UI - 0001/
HC Astro UI - 0001.zip
```

## Runtime architecture

```text
Compose UI -> SrcconViewModel -> process OperationCoordinator
                                     |
             Foreground Service <----+----> StateFlow
                     |               |
              notification STOP      v
              bounded wake lock   routed transport
                                      |
                       +--------------+-------------+
                       |                            |
                 Demo fake                  USB serial attempt
                                          open/write/close
```

The process-level coordinator outlives Activity recreation. The Activity and
Composable submit user actions and render state; they never own timers or an
active configuration. A physical operation starts the foreground service from
a visible user action. The service observes coordinator state, maintains the
notification and wake lock, and exposes STOP.

The durable risk marker is set before an ON attempt and cleared only after an
OFF transmission succeeds. A process restart reads it without opening USB. If
it is set, the app presents `Fault/UNKNOWN`; recovery requires selecting and
connecting the intended device followed by explicit Safety OFF. Version 0.0.1
stores the risk marker as a Boolean; the UI instruction and operator selection
remain the authority for identifying the original relay after a restart.

## USB lifecycle

```text
enumerate descriptors and driver matches
  -> user selects device/port
  -> user presses Connect
  -> request permission if needed
  -> mark logically ready
  -> open/configure/write/close for each serialized command attempt
```

Enumeration uses `UsbManager.deviceList` and the serial library's driver
prober. It never opens a device, requests permission, or transmits. A persisted
device fingerprint is only a UI hint. It never selects or reconnects a device.

The session target contains device name, VID/PID, Android attachment ID, and
port index. Android attachment identity is not persistent across a detach.
Serial number is read only when permission allows it. Multi-port devices are
represented explicitly even though the 0.0.1 UI selects port zero.

The physical transport performs one bounded attempt. The coordinator owns
serialization and retry count. Open is bounded to 2 seconds and write to 1
second. Each attempt closes both port and Android USB connection.

## Operation state and safety

Public phases are:

```text
IDLE -> PREFLIGHT -> MANUAL_ON
                  -> SINGLE_EXPOSURE -> STOPPING -> IDLE
                                      -> FAULT/UNKNOWN
```

The write-only relay does not report physical contact position. UI wording
therefore uses `ON command sent`, `OFF command sent`, and `Relay UNKNOWN`.

Each start performs:

1. validate target, serial values, ON/OFF commands, and duration;
2. create an immutable snapshot with defensive byte-array copies;
3. atomically acquire the single owner and generation;
4. require current logical USB readiness;
5. send preflight OFF with up to three attempts;
6. persist the risk marker;
7. send ON with up to two attempts;
8. for Single, wait against a monotonic elapsed-time deadline;
9. send final OFF with up to three attempts and clear the marker.

STOP invalidates the generation before waiting for an in-flight send, cancels
the exposure timer, and sends OFF using the original snapshot in a
non-cancellable context. USB detach invalidates the active owner and leaves the
risk marker set. OFF failure remains visible as `Fault/UNKNOWN`.

No software path can guarantee OFF after Android force-stop, a hard process or
OS failure, phone/USB power loss, cable removal, or relay hardware failure.

## Foreground execution

The service is declared with the `connectedDevice` type and permissions for
foreground service, connected-device foreground service, notifications, and
wake lock. Android 13+ notification permission is requested explicitly before
a physical operation.

The service enters foreground state before the ViewModel starts a USB
operation. Its notification shows phase and remaining Single time, and offers
STOP. A non-reference-counted partial wake lock is held only for active phases,
with a defensive limit of 24 hours and 5 minutes, then released on Idle,
Fault, or service destruction. Real-device screen-off timing is still a manual
validation item.

## Settings

Preferences DataStore stores:

- theme: Light, Dark, or Night;
- maximum exposure seconds, default 600;
- last Single Exposure seconds, default 30;
- previous-device fingerprint hint;
- may-be-energized risk marker.

Maximum exposure is constrained to 1-86,400 seconds. A storage read error in
the risk adapter is treated conservatively as at risk.

## Testing

Local JVM tests cover:

- HEX syntax, invalid tokens, empty input, and the 64-byte bound;
- built-in profile ownership and immutable command snapshots;
- preflight OFF ordering and retry behavior;
- Manual/Single ownership;
- STOP races and prevention of stale ON retry;
- Single completion and safety OFF;
- USB detach and `Fault/UNKNOWN`;
- risk marker recovery;
- device fingerprint/hint behavior;
- settings duration policy.

The fake transport records byte copies and can inject deterministic failures.
Automated tests never access or energize USB hardware.

Run:

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug lintDebug --no-daemon
```

Expected APK location:

```text
app/build/outputs/apk/debug/SRCCON_0.0.1.apk
```

Current verification status:

```text
Software validation passed (2026-10-01)
Hardware validation pending
```

Clean verification command and result:

```text
.\gradlew.bat clean testDebugUnitTest assembleDebug lintDebug --no-daemon
BUILD SUCCESSFUL
24 tests, 0 failures, 0 errors, 0 skipped
Android Lint: 0 errors, 24 non-blocking warnings
```

Final debug APK evidence:

```text
app/build/outputs/apk/debug/SRCCON_0.0.1.apk
13,416,516 bytes
SHA-256 2FA5151AB7537B71A2AE0C482F47CE631915AE2AD97DD667E2EF9FC5C83F45B8
```

The warnings are dependency-update suggestions and resource/style hygiene
items; none is a Lint error, and no notification-permission error remains. The
SDK tools also print a non-fatal XML-version compatibility warning because the
installed command-line tools and SDK metadata were released at different
times.

## Manual hardware validation

Use an unloaded relay or safe test fixture before connecting a camera.

1. Connect the OTG hub and relay.
2. Launch SRCCON and confirm scan does not open it.
3. Verify metadata and driver family.
4. Select the intended device, press Connect, and grant permission explicitly.
5. Validate the built-in VCTEC/NOYITO profile.
6. Send Safety OFF and physically verify it.
7. Explicitly send Manual ON, then OFF.
8. Run a 2-3 second Single Exposure.
9. Test STOP during a longer exposure.
10. Turn the screen off and measure timing and notification behavior.
11. Detach USB during a safe operation and verify `Fault/UNKNOWN`.
12. Reconnect explicitly and verify Safety OFF recovery.
13. Test a busy device and several attached USB serial devices.

Record Android version, phone/tablet and OEM, relay, USB hub/power arrangement,
app commit, and observations. Until this checklist passes, state **Hardware
validation pending**.

## UI specification

The EAFCON-derived visual system is documented with generated example images
in [ui-guide/HC Astro UI - 0001/README.md](ui-guide/HC%20Astro%20UI%20-%200001/README.md).
The standalone archive is [HC Astro UI - 0001.zip](HC%20Astro%20UI%20-%200001.zip).

## Git and release rules

Use `main` as stable and `feature/android-mvp` for this work. Generated build
output, local SDK paths, signing keys, captures, and user data stay untracked.
Do not push, publish an APK, create a store release, or change the Windows
SeRelCam repository without explicit user instruction. Report software and
hardware validation separately.
