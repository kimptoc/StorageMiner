# File count per directory — Design

Date: 2026-05-11

## Goal

In the results pie-chart legend, display the recursive file count alongside each real directory's size and percentage. The user wants to see "how many files" sits under each folder, not just how big it is.

## Scope

**In scope:**
- Counting all files (leaves) under each scanned directory, recursively, including files in nested subdirectories.
- Surfacing the count in the legend rows of `PieChart` for real filesystem directories.
- Unit tests for the new behaviour, in particular the scanner's count and a small format helper.

**Out of scope (deferred):**
- File counts on synthetic / non-filesystem legend items: "Apps (installed)", individual apps, "Trash", "System & other", system-breakdown sub-items, and the "Other" grouped bucket.
- Showing a count on leaf-file legend rows ("1 file" would be redundant).
- Live count in the scanning progress indicator (`ScanningIndicator`). Easy follow-up if wanted.

## Approach

Add a nullable `fileCount: Int?` field to the `StorageItem` data class. The scanner populates it for real filesystem items; the ViewModel leaves it `null` for synthetic items it constructs (Apps, System, Trash, "Other").

The legend renders the count only when `item.fileCount != null && item.isDirectory`.

### Why nullable instead of a `0` sentinel

`null` distinguishes "this concept doesn't apply" (an Apps aggregate) from "this is a genuinely empty folder" (`0`). A `0` sentinel would conflate the two and force the UI to guess from `path` or `isDirectory`, which is fragile. The nullable default also keeps every existing `StorageItem(...)` constructor call site working unchanged.

### Why not also count "Other" or apps

- "Other" groups small items at the same level — a mix of tiny folders and stray files. A combined count would be technically defensible but semantically muddy ("47 files plus some folders that contain other things").
- Apps aren't file containers from the user's point of view; their size comes from `StorageStatsManager`, not a filesystem walk. A count of "files inside the app sandbox" would be both unobtainable (sandbox is opaque) and unhelpful.
- Trash items are MediaStore entries with a real count, but the user asked about directories. Leaving it null keeps scope tight.

## Changes

### `app/src/main/java/com/kimptoc/storageminer/model/StorageItem.kt`

Add a default-null field at the end of the data class:

```kotlin
data class StorageItem(
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val path: String,
    val fileCount: Int? = null,
)
```

Default keeps all existing call sites compiling.

### `app/src/main/java/com/kimptoc/storageminer/scanner/StorageScanner.kt`

Rename `calculateDirectorySize(dir: File): Long` to `calculateDirectoryStats(dir: File): Pair<Long, Int>` returning `(sizeBytes, fileCount)`.

- Files: each leaf contributes `(size, 1)`.
- Directories: accumulate `(sumSize, sumCount)` from children, then return the totals (the directory itself doesn't add to the file count).

In `scan(rootPath)`:
- For each top-level file entry: build a `StorageItem(..., fileCount = 1)`.
- For each top-level directory entry: call `calculateDirectoryStats`, then build a `StorageItem(..., fileCount = stats.second)`.

Cancellation (`coroutineContext.ensureActive()`) and the progress flows (`_bytesScanned`, `_foldersScanned`, `_currentFolder`) remain unchanged.

### `app/src/main/java/com/kimptoc/storageminer/util/FormatUtils.kt`

Add a small helper that handles pluralisation:

```kotlin
fun formatFileCount(count: Int): String =
    if (count == 1) "1 file" else "$count files"
```

### `app/src/main/java/com/kimptoc/storageminer/ui/components/PieChart.kt`

In `LegendItem`, change the size/percentage line so the file count is appended between the size and the percentage *only* when applicable:

```kotlin
val sizeText = formatFileSize(item.sizeBytes)
val countSuffix = if (item.fileCount != null && item.isDirectory)
    " · ${formatFileCount(item.fileCount)}"
else ""
Text(
    text = "$sizeText$countSuffix (%.1f%%)".format(percentage),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
```

Renders as: `1.2 GB · 234 files (5.3%)` for real folders, or unchanged `1.2 GB (5.3%)` for everything else.

### ViewModel — no required changes

`StorageMinerViewModel` constructs synthetic `StorageItem`s for Apps, Trash, System, "Other" grouped buckets, etc. Because `fileCount` defaults to `null`, none of those constructions need editing. They'll naturally fall through the UI guard and render the way they do today.

## Tests

Both run as JVM unit tests under `app/src/test/java/com/kimptoc/storageminer/`. JUnit 4 is already configured.

### `FormatUtilsTest`

- `formatFileCount(0)` → `"0 files"`
- `formatFileCount(1)` → `"1 file"`
- `formatFileCount(42)` → `"42 files"`

### `StorageScannerTest`

Build temporary directory trees with `kotlin.io.path.createTempDirectory()` (clean up in `@After`), then call `scanner.scan(tempRoot.absolutePath)` from `runBlocking`:

| Test case | Tree | Expected for the top-level dir item |
|---|---|---|
| Empty subdir | `root/empty/` | `sizeBytes=0, fileCount=0` |
| One file | `root/dir/a.txt (10 B)` | `sizeBytes=10, fileCount=1` |
| Nested files | `root/dir/a.txt (5B); root/dir/sub/b.txt (3B); root/dir/sub/c.txt (2B)` | `sizeBytes=10, fileCount=3` |
| Top-level file | `root/loose.txt (7B)` | The legend item for `loose.txt` has `sizeBytes=7, fileCount=1, isDirectory=false` |

These also validate the existing size-summation behaviour, so they're not pure-new-feature tests — they pin down behaviour we currently lack any test coverage for.

## Risk and rollback

- `StorageItem` is purely additive with a default; nothing else needs to change to keep building.
- Scanner refactor is the only behavioural change; the new tests catch regressions on size totals as well as counts.
- If anything misbehaves on-device, reverting is one git revert away — there is no migration, no persisted state shape change, no API contract with another app.

## Verification checklist

- [ ] `./gradlew test` passes (new tests included)
- [ ] `./gradlew assembleDebug` succeeds without new warnings
- [ ] On a device, the results legend shows `… · N files (X%)` on real folders and unchanged text on Apps / Trash / System / Other / leaf files
- [ ] Drilling into a folder still works and the sub-folders show their own counts
