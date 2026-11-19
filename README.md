# FordDiag Android
> [!NOTE]  
> This is a work in progress, only tested with ford models listed below. Currently is read only (no ability to send anything to the car) 

Kotlin + Jetpack Compose port of [FordDiag](../fordscanner) for **Android**.

Same goal: diagnose Ford modules over OBD (Focus / Tourneo Connect 2017 profiles), with **OBDLink MX / MX+** as the primary adapter via **Bluetooth Classic (SPP)**.

> Not a FORScan clone. Implements ELM/STN + ISO-TP + UDS from public behaviour. Read-oriented (no As-Built write / PCM flash).

## Requirements

- Android **8.0+** (API 26), tested target API 35
- Bluetooth Classic adapter (OBDLink **MX** or **MX+** for MS-CAN)
- Android Studio Ladybug+ / JDK 17

## Features

- List **bonded** Bluetooth devices
- Connect over SPP (`00001101-0000-1000-8000-00805F9B34FB`)
- Detect STN/OBDLink (ATI, BT name, STI/STDI, or `STP 53` probe) → enable MS-CAN
- Vehicle profiles (Focus 2017 EU, Tourneo Connect 2017)
- Scan modules, read VIN / DIDs / DTCs
- Dump As-Built DIDs and share JSON
- **DTCs** tab: read-all groups + clear per module (with confirm)
- **Live** tab: Mode 01 PIDs + UDS DID polling, share session JSON
- **ECUs** tab: profile scan + brute-force ID discovery
- **Discover** tab: CAN sniff (`ATMA`) + mark-press correlation, DID sweep / snapshot / diff

### Not ported (desktop-only)

- Demo / fixture offline mode
- MCP agent bridge (`:8787`)

## Setup

1. Pair the MX / MX+ in Android Bluetooth settings (ignition ON).
2. Open this project in Android Studio → Sync Gradle (JDK **17** recommended).
3. Grant **Nearby devices** / Bluetooth permissions when prompted.
4. Run on a physical phone (emulators rarely expose Classic BT SPP usefully).

```bash
# from project root, with Android SDK + JDK 17:
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # adjust path
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

| API | Permissions |
|-----|-------------|
| 31+ | `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (not used for location) |
| ≤30 | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (legacy discovery) |

## MX / MX+ vs generic ELM

| | MX / MX+ / STN | Typical ELM clone |
|--|----------------|-------------------|
| HS-CAN / PCM / VIN | Yes | Often |
| MS-CAN / BCM / IPC | Yes (`STP 53`) | Usually no |
| CAN sniff / discovery on MS | Yes | Usually no |

Note: older **OBDLink MX** often answers `ATI` as plain `ELM327 v1.x`. The app also checks the Bluetooth device name and STN probes (`STI` / `STDI` / `STP 53`) so MS-CAN is still enabled.

## Project layout

```
app/src/main/java/com/forddiag/android/
  core/     # Bluetooth SPP, STN, ISO-TP, UDS, OBD, sniff, profiles, scanner
  ui/       # Compose UI + ViewModel (Home / ECUs / DTCs / Live / Discover)
app/src/main/assets/profiles/   # JSON vehicle profiles
```

Protocol logic mirrors the desktop `ford-core` crate so both apps can stay in sync.

## Safety

Read-only diagnostics in this release. ECU writes can brick modules. Keep ignition ON / battery maintained during scans. CAN sniff (`ATMA`) over Bluetooth can be bandwidth-heavy — stop sniff before other diagnostic actions.

## License

MIT — see [LICENSE](LICENSE).
