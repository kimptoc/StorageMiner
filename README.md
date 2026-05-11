# StorageMiner

Android app that visualizes device storage usage as a pie chart.

On launch, it scans external storage, traverses directories to total file sizes, and displays the breakdown by top-level folder. The scan runs in the background with progress feedback, and can be stopped at any time to show partial results.

## Features

- Pie chart breakdown of storage usage by folder
- Live scan progress (current folder, bytes scanned)
- Stoppable scans with partial results
- Per-app storage stats with drill-down
- System / trash space estimates
- Cached results to avoid re-scanning unnecessarily

## Requirements

- Android 13+ (`minSdk = 33`)
- "All files access" permission (`MANAGE_EXTERNAL_STORAGE`), granted on first run
- `PACKAGE_USAGE_STATS` and `QUERY_ALL_PACKAGES` for per-app stats

## Tech

- Kotlin + Jetpack Compose with Material 3
- `ViewModel` + `StateFlow` for UI state
- Coroutines for cancellable background scanning
- Custom `Canvas` pie chart (no third-party charting libraries)

## Build

```bash
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

To install on a connected device:

```bash
./gradlew installDebug
```

## Project layout

```
app/src/main/java/com/kimptoc/storageminer/
├── MainActivity.kt              # Entry point, permission handling
├── model/                       # StorageItem, ScanState
├── scanner/StorageScanner.kt    # Recursive filesystem traversal
├── viewmodel/                   # StorageMinerViewModel
├── ui/                          # Screen + Compose components
│   ├── StorageMinerScreen.kt
│   ├── components/PieChart.kt
│   └── components/ScanningIndicator.kt
└── util/FormatUtils.kt          # Human-readable byte formatting
```

See [PLAN.md](PLAN.md) for the original design notes.

## Notes on Android 13+ scanning

Even with full storage access, `Android/data/` and `Android/obb/` remain inaccessible — those bytes show up under a "System / restricted" estimate rather than per-folder. Internal `/data/` is not accessible to user apps at all.
