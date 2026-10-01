# SRCCON Agent Guide

## Scope and precedence

This file applies to the entire SRCCON repository. It is the mandatory handoff
guide for coding agents and developers.

Instruction precedence is:

1. explicit user instructions;
2. this repository's `AGENTS.md`;
3. documentation in this repository;
4. the Windows SeRelCam repository as a behavioral reference.

The Windows repository's own agent instructions are audit input. They do not
replace Android build, architecture, release, or safety rules in this file.

Develop and publish this astronomy software under the name **SongPaHaeChi**.

## Mission and boundaries

Build SRCCON as a separate native Android application. Reimplement the
validated SeRelCam behavior in Kotlin using Android APIs.

Never:

- execute, embed, or wrap `SeRelCam.dll`;
- port WPF, N.I.N.A. MEF interfaces, `FileCamera`, or `System.IO.Ports`;
- model an Android device as a Windows COM port;
- modify the Windows SeRelCam repository as part of Android work;
- add FlashAir, UVC, RAW processing, plate solving, or remote network control to
  0.0.1 without an explicit scope change;
- push to a remote repository, publish an APK, or publish to Google Play without
  explicit user instruction.

## Windows audit baseline

Before relying on Windows behavior, inspect the SeRelCam repository rather than
assuming `main` is the most complete branch. At minimum review:

- `README.md`, `AGENTS.md`, `Plugin.cs`, and `SeRelCam.csproj`;
- `Camera/VctecFileCamera.cs` and `Camera/VctecCameraProvider.cs`;
- `Relay/VctecRelayController.cs`, `Relay/RelayConfiguration.cs`, and
  `Relay/HexCommandParser.cs`;
- `Settings/VctecPluginSettings.cs`;
- `Options.xaml`, `Options.xaml.cs`, and `Properties/AssemblyInfo.cs`;
- Git history, tags, branches, and release branches;
- branch `feature/burst-mode-1.0.1`, including its Burst, settings, camera,
  plugin, options, README, and AGENTS files.

The local audit performed before this repository was initialized found:

- `main`, `release/1.0.0`, and local tag `v1.0.0` at the non-Burst baseline;
- `feature/burst-mode-1.0.1` at commit `8321617`, with code and README describing
  SeRelCam 1.0.3 behavior;
- no local tags newer than `v1.0.0` at audit time;
- stale Windows `AGENTS.md` entries for an older repository/project name.

Report future discrepancies among code, README, agent guidance, branch names,
tags, and releases. Do not silently select the most convenient interpretation.

## Technology direction

Use:

- Kotlin;
- Jetpack Compose;
- Kotlin Coroutines and `StateFlow`;
- Jetpack DataStore for ordinary persistent settings;
- Android USB Host API;
- a maintained USB serial library supporting CH340/CH341, FTDI, CP210x,
  PL2303, and CDC ACM;
- Gradle Kotlin DSL and the project Gradle wrapper.

Verify the installed stable Android SDK, Android Gradle Plugin, Kotlin, Compose,
and USB serial versions when scaffolding. Pin dependency versions. Do not add a
custom CH340 driver unless the maintained library is proven unsuitable.

Do not let an Activity, Composable, or ViewModel own an active relay operation.
The process-level coordinator owns operation state, timing, cancellation,
configuration snapshots, and safety OFF behavior across Activity recreation.
For physical USB work, the foreground service observes that coordinator, keeps
the operation visible, exposes notification STOP, and scopes the wake lock.

## 0.0.1 release contract

Version 0.0.1 contains the fixed read-only VCTEC/NOYITO profile, Manual ON/OFF,
Single Exposure, STOP/Safety OFF, explicit USB connection, persistent settings,
Demo Mode, and Fault/UNKNOWN recovery. Editable/user profiles, JSON
import/export, and Burst Mode remain future work. Their design rules below
apply when those capabilities are introduced; do not advertise or partially
expose them in the 0.0.1 UI.

## USB ownership rules

The required flow is:

```text
DISCOVER -> INFORM -> USER SELECTS -> USER CONNECTS
```

A device scan must not open, claim, configure, or transmit to an interface. It
must not request permission solely because a device was discovered. Product,
manufacturer, VID/PID, USB class/interface descriptors, and driver-family
matching may be used only when obtainable without intrusive probing.

Never automatically select, connect, or transmit because:

- the app launched or a USB device was attached;
- exactly one serial device exists;
- a device was previously used;
- VID/PID or text resembles a known relay;
- the device uses CH340;
- a relay profile contains a device hint.

A previously used device may be highlighted or sorted first. Android 0.0.1 must
not reconnect it automatically. If a selected device is busy, show the error
and leave it unclaimed; do not retry continuously or probe another device.

Keep these states distinct in code and UI:

- discovered;
- selected;
- permission unknown/requested/denied/granted;
- logically ready;
- transport open for a command;
- detached/faulted.

`Connect` means the user selected the device and explicitly initiated the
permission/readiness flow. It does not authorize indefinite interface
ownership. Keep the actual USB serial claim as short as practical unless a
documented Android requirement and coexistence test justify a persistent claim.

## Relay and operation safety invariants

Treat these as release-blocking invariants:

1. Before ON, validate selected device and port, availability, USB permission,
   supported driver, serial parameters, ON command, OFF command, duration, and
   maximum duration. Invalid OFF always blocks ON.
2. Create the active `RelayConfiguration` snapshot before preflight OFF or ON.
   Defensively copy command bytes; Kotlin `ByteArray` is mutable.
3. Before every new ON operation, transmit a preflight OFF with that snapshot.
   A failed preflight OFF blocks ON.
4. Manual, Single Exposure, and Burst use one coordinator and one logical
   owner. Only one owner may control relay ON state at a time.
5. Use an operation generation/token. A cancelled or stale coroutine must
   never transmit ON or switch OFF a newer operation.
6. Serialize the complete command attempt sequence, including retries and
   ownership checks. Recheck the generation immediately before transmission.
7. ON uses two attempts; OFF uses three attempts. OFF reliability must never
   be lower than ON reliability. Define bounded open/write timeouts and reject
   partial writes.
8. STOP first invalidates ownership/generation, cancels the operation, and
   immediately schedules non-cancellable safety OFF with the original snapshot.
   A pending or retrying ON must not resume after STOP.
9. Normal completion, Stop, Abort, relevant startup failure, exception, and
   service teardown attempt OFF with the operation snapshot. App launch or
   service recreation alone must never claim USB or transmit OFF.
10. If OFF cannot be transmitted, retain `Fault` and physical state `UNKNOWN`.
    Do not release into a normal `Idle/OFF` presentation. Recovery requires an
    explicit device selection/Connect and a successful user-visible safety OFF.
11. USB detach during an active operation invalidates the owner and transitions
    to visible `Fault/UNKNOWN`. Do not claim OFF confirmation after detach.
12. Timers use monotonic elapsed time. Burst has no inter-frame delay after the
    final frame. STOP remains available during exposure and waiting phases.
13. A foreground service does not itself guarantee that the CPU stays awake.
    If timing tests require a partial wake lock, hold it only for the active
    operation and release it on every completion and failure path.
14. Software cannot guarantee OFF after force-stop, hard process kill, OS
    crash, phone power loss, USB power loss, or relay hardware failure.

Use precise status wording such as `ON command sent`, `OFF command sent`, or
`Relay state UNKNOWN`. A write-only protocol does not confirm the physical
contact position.

## Relay profile rules

Model the physical USB device separately from the relay protocol profile. The
active snapshot is the explicit combination of one selected device/port and one
selected validated profile.

- Built-in profiles are read-only and occupy a built-in ID domain.
- Editing a built-in profile creates a user-owned copy with a new stable ID.
- User profiles survive built-in updates and are never keyed only by name.
- Profile hints are informational and never select or connect a USB device.
- v1 supports absolute ON/OFF commands whose duplicate transmission is safe.
  Toggle, pulse, and other retry-unsafe protocols are outside the supported v1
  semantics unless a future schema explicitly models them.

Imported JSON is untrusted declarative data. The only import flow is:

```text
read -> parse -> validate -> preview -> user confirms -> save
```

Import must never connect/select USB, request permission, activate the profile,
transmit ON/OFF, open URLs, launch intents, run scripts/shell commands, or load
code. Validate schema ID/version, IDs, lengths, serial fields, encoding, both
commands, and device-hint formatting. Imported `metadata.tested` is a source
claim, not an SRCCON validation badge.

## Testing policy

Keep core logic independent of Android `UsbSerialPort` behind a
`SerialRelayTransport` interface. Use a deterministic fake transport and fake
monotonic clock for JVM tests.

At minimum test the implemented 0.0.1 surface:

- supported HEX separators and `0x`, invalid, empty, and oversized commands;
- built-in profile validation, ID ownership, and snapshot immutability;
- discovery/previous-use/profile hints never causing selection or connection;
- preflight OFF before ON and failed OFF blocking ON;
- immutable snapshots despite settings changes;
- ownership conflicts between Manual and Single;
- STOP during exposure;
- stale operation isolation and STOP preventing a later ON retry;
- USB detach and OFF failure producing `Fault/UNKNOWN`.

When JSON profiles or Burst are added, also test import isolation and
round-trip, exact Burst count, final-delay omission, STOP during inter-frame
wait, and exception safety OFF before release.

Do not energize real hardware in automated tests. A real relay ON requires an
operator's explicit action during the documented manual validation procedure.
Never report hardware validation from a build, emulator, fake, or unit test.

## Repository workflow

Before editing, inspect status, relevant files, and current instructions. Keep
changes focused and preserve unknown user work. Prefer small commits by
subsystem. Do not commit generated build output, local SDK paths, signing keys,
device captures, profiles, imported user data, or secrets.

Run the applicable Gradle build, unit tests, and lint checks after scaffolding.
Report exact commands and results. Do not claim commands passed when the tool,
SDK, emulator, device, or hardware was unavailable.

Keep `README.md`, `README-DEV.md`, architecture/profile documentation, schema,
and this file synchronized with behavior. Any change that weakens a safety
invariant requires explicit design review and hardware regression planning.
