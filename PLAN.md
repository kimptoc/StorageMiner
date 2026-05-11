# StorageMiner - Implementation Plan

## Context

Build an Android app that visualizes device storage usage. On launch, the app scans external storage, traversing directories to total file sizes, then displays results as a pie chart. The scan can be slow, so the UI shows progress (current folder, animation) and a stop button.

The project already exists as a fresh Android Studio template with Jetpack Compose + Material 3, minSdk 33, targetSdk 36, package `com.kimptoc.storageminer`.

## Architecture

Single-screen app with 3 UI states: **Permission Request** → **Scanning** → **Results**

- **ViewModel + StateFlow** drives the UI
- **Coroutines** for background scanning with cancellation support
- **Custom Compose Canvas** pie chart (no third-party charting library)
- **MANAGE_EXTERNAL_STORAGE** permission for filesystem access (required on Android 13+)

### What's scannable on Android 13+
- `/storage/emulated/0/` (external storage): DCIM, Download, Documents, Music, Pictures, etc.
- `Android/data/` and `Android/obb/` are NOT accessible even with full storage permission
- Internal `/data/` is not accessible
- Scanner handles inaccessible directories gracefully (skips with "Restricted" label)

## Files to Create

### 1. `app/src/main/java/com/example/storageminer/model/StorageItem.kt`
Data classes: `StorageItem(name, sizeBytes, isDirectory, path)` and `StorageScanResult(items, totalScanned, totalDeviceStorage, freeDeviceStorage, wasCancelled)`

### 2. `app/src/main/java/com/example/storageminer/model/ScanState.kt`
Sealed interface for UI state:
- `Idle` - initial state
- `Scanning(currentFolder, foldersScanned, bytesScanned)` - during scan
- `Completed(result: StorageScanResult)` - scan done or stopped

### 3. `app/src/main/java/com/example/storageminer/scanner/StorageScanner.kt`
Core logic: recursive filesystem traversal using `File.listFiles()`. Exposes `StateFlow`s for `currentFolder`, `foldersScanned`, `bytesScanned`. Uses `ensureActive()` for responsive cancellation. Returns top-level folder sizes, grouping small items as "Other".

### 4. `app/src/main/java/com/example/storageminer/viewmodel/StorageMinerViewModel.kt`
`AndroidViewModel` that:
- Launches scan coroutine, collects scanner progress into `ScanState`
- Provides `startScan()`, `stopScan()`, `reset()` actions
- Queries `StorageStatsManager` for total/free device storage
- On cancellation, produces partial results with `wasCancelled = true`

### 5. `app/src/main/java/com/example/storageminer/ui/components/PieChart.kt`
Custom Compose `Canvas` pie chart using `drawArc()`. Includes a legend showing colored squares + folder names + formatted sizes. Uses a 12-color palette.

### 6. `app/src/main/java/com/example/storageminer/ui/components/ScanningIndicator.kt`
Scanning progress UI: indeterminate `CircularProgressIndicator`, current folder name (truncated), bytes scanned so far, and a "Stop Scan" `OutlinedButton`.

### 7. `app/src/main/java/com/example/storageminer/ui/StorageMinerScreen.kt`
Main screen composable. Routes between permission request, scanning, and results views based on `ScanState`.

### 8. `app/src/main/java/com/example/storageminer/util/FormatUtils.kt`
`formatFileSize(bytes: Long): String` - converts bytes to human-readable KB/MB/GB.

## Files to Modify

### 9. `app/src/main/AndroidManifest.xml`
Add `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />` before `<application>`.

### 10. `gradle/libs.versions.toml`
Add `lifecycle-viewmodel-compose` library entry (reuses existing `lifecycleRuntimeKtx` version).

### 11. `app/build.gradle.kts`
Add `implementation(libs.androidx.lifecycle.viewmodel.compose)` to dependencies.

### 12. `app/src/main/java/com/example/storageminer/ui/theme/Color.kt`
Add `PieColors` list with 12 distinct colors for chart slices.

### 13. `app/src/main/java/com/example/storageminer/MainActivity.kt`
Replace template content. Wire up:
- Permission check via `Environment.isExternalStorageManager()`
- Permission request via `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` intent
- Re-check permission in `onResume` using `LifecycleEventObserver`
- `viewModel()` instantiation and `StorageMinerScreen` composable

## Implementation Order

1. Dependencies: `libs.versions.toml`, `build.gradle.kts`
2. Permission: `AndroidManifest.xml`
3. Data layer: `StorageItem.kt`, `ScanState.kt`, `FormatUtils.kt`
4. Scanner: `StorageScanner.kt`
5. ViewModel: `StorageMinerViewModel.kt`
6. UI components: `Color.kt` (pie colors), `PieChart.kt`, `ScanningIndicator.kt`
7. Main screen: `StorageMinerScreen.kt`
8. Wire up: `MainActivity.kt`

## Verification

1. Build the project: `./gradlew assembleDebug`
2. Install on device/emulator running Android 13+
3. Grant "All files access" permission when prompted
4. Verify scan starts, shows current folder + animation
5. Test "Stop Scan" button produces partial results
6. Verify pie chart renders with correct proportions and legend
7. Test "Scan Again" restarts the process