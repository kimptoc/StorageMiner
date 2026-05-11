# File count per directory — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show recursive file counts alongside size/percentage in the storage scan results legend, for real filesystem directories only.

**Architecture:** Add a nullable `fileCount: Int?` field to `StorageItem` (defaults to null). The scanner populates it for real filesystem items (file = 1, directory = recursive sum). Synthetic legend items constructed by the ViewModel (Apps, Trash, System, "Other") leave it null. The UI in `PieChart.LegendItem` shows the count only when `fileCount != null && isDirectory`, formatted via a new `formatFileCount` pluralization helper.

**Tech Stack:** Kotlin, Jetpack Compose with Material 3, Kotlin coroutines, JUnit 4 (already configured), Gradle 9.

**Spec:** `docs/superpowers/specs/2026-05-11-file-count-per-directory-design.md`

---

## File Structure

**Modify:**
- `app/src/main/java/com/kimptoc/storageminer/model/StorageItem.kt` — add `fileCount: Int? = null` field
- `app/src/main/java/com/kimptoc/storageminer/scanner/StorageScanner.kt` — replace `calculateDirectorySize` with `calculateDirectoryStats` returning `Pair<Long, Int>`; populate `fileCount` on all items the scanner produces
- `app/src/main/java/com/kimptoc/storageminer/util/FormatUtils.kt` — add `formatFileCount(count: Int): String`
- `app/src/main/java/com/kimptoc/storageminer/ui/components/PieChart.kt` — append `" · N files"` to the size/percentage line in `LegendItem` when `fileCount != null && isDirectory`

**Create:**
- `app/src/test/java/com/kimptoc/storageminer/util/FormatUtilsTest.kt` — three pluralization cases
- `app/src/test/java/com/kimptoc/storageminer/scanner/StorageScannerTest.kt` — empty dir, single file, nested files, top-level file

No ViewModel changes; the nullable-default leaves all existing synthetic `StorageItem(...)` construction sites correct unchanged.

---

## Task 1: Add `fileCount` field to `StorageItem`

**Files:**
- Modify: `app/src/main/java/com/kimptoc/storageminer/model/StorageItem.kt`

- [ ] **Step 1: Add the nullable field**

Edit `app/src/main/java/com/kimptoc/storageminer/model/StorageItem.kt` so the data class becomes:

```kotlin
package com.kimptoc.storageminer.model

data class StorageItem(
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val path: String,
    val fileCount: Int? = null,
)

data class StorageScanResult(
    val items: List<StorageItem>,
    val totalScanned: Long,
    val totalDeviceStorage: Long,
    val freeDeviceStorage: Long,
    val wasCancelled: Boolean,
    val scannedPath: String,
)
```

The default `null` keeps every existing `StorageItem(...)` call site compiling unchanged.

- [ ] **Step 2: Verify the project still compiles**

Run: `./gradlew compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. The two pre-existing `unsafeCheckOpNoThrow` deprecation warnings are unrelated and acceptable.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kimptoc/storageminer/model/StorageItem.kt
git commit -m "Add nullable fileCount field to StorageItem

Defaults to null so all existing call sites (scanner + ViewModel
synthetic items) remain valid unchanged. The scanner will populate it
for real filesystem items in a follow-up commit."
```

---

## Task 2: Add `formatFileCount` helper with tests

**Files:**
- Create: `app/src/test/java/com/kimptoc/storageminer/util/FormatUtilsTest.kt`
- Modify: `app/src/main/java/com/kimptoc/storageminer/util/FormatUtils.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/kimptoc/storageminer/util/FormatUtilsTest.kt`:

```kotlin
package com.kimptoc.storageminer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun `formatFileCount uses singular for one`() {
        assertEquals("1 file", formatFileCount(1))
    }

    @Test
    fun `formatFileCount uses plural for zero`() {
        assertEquals("0 files", formatFileCount(0))
    }

    @Test
    fun `formatFileCount uses plural for many`() {
        assertEquals("42 files", formatFileCount(42))
    }
}
```

- [ ] **Step 2: Run test, expect failure**

Run: `./gradlew test --tests "com.kimptoc.storageminer.util.FormatUtilsTest"`
Expected: FAIL — compilation error, `formatFileCount` does not exist yet.

- [ ] **Step 3: Add the implementation**

Open `app/src/main/java/com/kimptoc/storageminer/util/FormatUtils.kt` and append a new top-level function (keep the existing `formatFileSize` exactly as it is — do not edit it):

```kotlin
fun formatFileCount(count: Int): String =
    if (count == 1) "1 file" else "$count files"
```

- [ ] **Step 4: Run test, expect pass**

Run: `./gradlew test --tests "com.kimptoc.storageminer.util.FormatUtilsTest"`
Expected: `BUILD SUCCESSFUL`, all three tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kimptoc/storageminer/util/FormatUtils.kt \
        app/src/test/java/com/kimptoc/storageminer/util/FormatUtilsTest.kt
git commit -m "Add formatFileCount pluralization helper

Used by the storage legend to render '1 file' or 'N files' next to
directory sizes. Covered by FormatUtilsTest (singular, zero, many)."
```

---

## Task 3: Make the scanner count files

**Files:**
- Create: `app/src/test/java/com/kimptoc/storageminer/scanner/StorageScannerTest.kt`
- Modify: `app/src/main/java/com/kimptoc/storageminer/scanner/StorageScanner.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/kimptoc/storageminer/scanner/StorageScannerTest.kt`. The tests build small temp directory trees on disk and call `scanner.scan(root.absolutePath)`, then look up items by name in the returned list. They cover the four cases pinned by the spec:

```kotlin
package com.kimptoc.storageminer.scanner

import com.kimptoc.storageminer.model.StorageItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class StorageScannerTest {

    private lateinit var root: File
    private val scanner = StorageScanner()

    @Before
    fun setUp() {
        root = createTempDirectory(prefix = "storageminer-test-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun mkdir(relPath: String): File {
        val d = File(root, relPath)
        d.mkdirs()
        return d
    }

    private fun mkfile(relPath: String, contents: ByteArray): File {
        val f = File(root, relPath)
        f.parentFile?.mkdirs()
        f.writeBytes(contents)
        return f
    }

    @Test
    fun `empty subdirectory has size zero and zero file count`() = runBlocking {
        mkdir("empty")

        val items = scanner.scan(root.absolutePath)

        val item = items.find { it.name == "empty" }
        assertNotNull("Expected an item for 'empty/'", item)
        assertEquals(true, item!!.isDirectory)
        assertEquals(0L, item.sizeBytes)
        assertEquals(0, item.fileCount)
    }

    @Test
    fun `directory with a single file has matching size and count one`() = runBlocking {
        mkfile("dir/a.txt", ByteArray(10))

        val items = scanner.scan(root.absolutePath)

        val item = items.find { it.name == "dir" }
        assertNotNull(item)
        assertEquals(true, item!!.isDirectory)
        assertEquals(10L, item.sizeBytes)
        assertEquals(1, item.fileCount)
    }

    @Test
    fun `directory counts files in nested subdirectories recursively`() = runBlocking {
        mkfile("dir/a.txt", ByteArray(5))
        mkfile("dir/sub/b.txt", ByteArray(3))
        mkfile("dir/sub/c.txt", ByteArray(2))

        val items = scanner.scan(root.absolutePath)

        val item = items.find { it.name == "dir" }
        assertNotNull(item)
        assertEquals(true, item!!.isDirectory)
        assertEquals(10L, item.sizeBytes)
        assertEquals(3, item.fileCount)
    }

    @Test
    fun `top-level file is reported as non-directory with count one`() = runBlocking {
        mkfile("loose.txt", ByteArray(7))

        val items = scanner.scan(root.absolutePath)

        val item = items.find { it.name == "loose.txt" }
        assertNotNull(item)
        assertEquals(false, item!!.isDirectory)
        assertEquals(7L, item.sizeBytes)
        assertEquals(1, item.fileCount)
    }
}
```

Notes for the implementer:
- `kotlinx.coroutines.runBlocking` is on the test classpath transitively via `lifecycle-runtime-ktx`. If for any reason it doesn't resolve, add `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")` to `app/build.gradle.kts` — but try the build first; it should just work.
- `kotlin.io.path.createTempDirectory()` is stdlib.
- The tests reach for items by name with `items.find { it.name == ... }`, not the private `calculateDirectoryStats`. This keeps them testing the public scanner surface.

- [ ] **Step 2: Run tests, expect failure**

Run: `./gradlew test --tests "com.kimptoc.storageminer.scanner.StorageScannerTest"`
Expected: FAIL — compilation passes (the `StorageItem.fileCount` field exists from Task 1) but assertions on `item.fileCount` will fail with `expected:<0> but was:<null>` (etc.) because the scanner does not yet populate the field.

- [ ] **Step 3: Refactor the scanner to compute and emit counts**

Replace the entire contents of `app/src/main/java/com/kimptoc/storageminer/scanner/StorageScanner.kt` with:

```kotlin
package com.kimptoc.storageminer.scanner

import com.kimptoc.storageminer.model.StorageItem
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.coroutines.coroutineContext

class StorageScanner {

    private val _currentFolder = MutableStateFlow("")
    val currentFolder = _currentFolder.asStateFlow()

    private val _foldersScanned = MutableStateFlow(0)
    val foldersScanned = _foldersScanned.asStateFlow()

    private val _bytesScanned = MutableStateFlow(0L)
    val bytesScanned = _bytesScanned.asStateFlow()

    suspend fun scan(rootPath: String): List<StorageItem> {
        val root = File(rootPath)
        val entries = root.listFiles() ?: return emptyList()

        val items = mutableListOf<StorageItem>()

        for (entry in entries) {
            coroutineContext.ensureActive()
            if (entry.isDirectory) {
                _currentFolder.value = entry.name
                val (size, count) = calculateDirectoryStats(entry)
                items.add(
                    StorageItem(
                        name = entry.name,
                        sizeBytes = size,
                        isDirectory = true,
                        path = entry.absolutePath,
                        fileCount = count,
                    )
                )
            } else {
                val size = entry.length()
                _bytesScanned.value += size
                items.add(
                    StorageItem(
                        name = entry.name,
                        sizeBytes = size,
                        isDirectory = false,
                        path = entry.absolutePath,
                        fileCount = 1,
                    )
                )
            }
        }

        return items.sortedByDescending { it.sizeBytes }
    }

    private suspend fun calculateDirectoryStats(dir: File): Pair<Long, Int> {
        var size = 0L
        var count = 0
        val files = dir.listFiles() ?: return 0L to 0
        _foldersScanned.value++

        for (file in files) {
            coroutineContext.ensureActive()
            if (file.isDirectory) {
                _currentFolder.value = file.name
                val (childSize, childCount) = calculateDirectoryStats(file)
                size += childSize
                count += childCount
            } else {
                val fileSize = file.length()
                _bytesScanned.value += fileSize
                size += fileSize
                count += 1
            }
        }

        return size to count
    }
}
```

Key behaviours preserved from the original:
- Cancellation via `coroutineContext.ensureActive()` at every iteration.
- Progress flows (`_currentFolder`, `_foldersScanned`, `_bytesScanned`) updated on the same events as before.
- Sort order at the top level (descending by `sizeBytes`).
- Inaccessible directory (`listFiles()` returns `null`) → `(0, 0)`, matching the previous "return 0L" behaviour.

- [ ] **Step 4: Run tests, expect pass**

Run: `./gradlew test --tests "com.kimptoc.storageminer.scanner.StorageScannerTest"`
Expected: `BUILD SUCCESSFUL`, all four tests pass.

- [ ] **Step 5: Verify everything still compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. Same two pre-existing deprecation warnings as before, nothing new.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kimptoc/storageminer/scanner/StorageScanner.kt \
        app/src/test/java/com/kimptoc/storageminer/scanner/StorageScannerTest.kt
git commit -m "Scanner records recursive file count per item

calculateDirectorySize becomes calculateDirectoryStats returning
(sizeBytes, fileCount). Leaf files get fileCount=1, directories get
the recursive sum, inaccessible directories get (0, 0). Covered by
four StorageScannerTest cases on temp directory trees."
```

---

## Task 4: Render the file count in the legend

**Files:**
- Modify: `app/src/main/java/com/kimptoc/storageminer/ui/components/PieChart.kt`

- [ ] **Step 1: Update the LegendItem subtitle**

Open `app/src/main/java/com/kimptoc/storageminer/ui/components/PieChart.kt`.

Add this import next to the existing `formatFileSize` import (preserve alphabetical order in the import block):

```kotlin
import com.kimptoc.storageminer.util.formatFileCount
```

Then locate the existing subtitle `Text` block inside `LegendItem` (currently lines ~130–134):

```kotlin
Text(
    text = "${formatFileSize(item.sizeBytes)} (%.1f%%)".format(percentage),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
```

Replace it with:

```kotlin
val countSuffix = if (item.fileCount != null && item.isDirectory) {
    " · ${formatFileCount(item.fileCount)}"
} else {
    ""
}
Text(
    text = "${formatFileSize(item.sizeBytes)}$countSuffix (%.1f%%)".format(percentage),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
```

The `·` escape is the middle-dot `·` character — safer than embedding the literal character in source if the file ever gets encoded oddly.

The visibility rule:
- Real folders (`isDirectory = true`, `fileCount` populated by the scanner) → render `1.2 GB · 234 files (5.3%)`.
- Leaf files (`isDirectory = false`) → unchanged `7 B (0.0%)` — the `1` count we store is intentionally suppressed because "1 file" next to a file row is redundant.
- Synthetic items from the ViewModel — Apps aggregate, individual apps, Trash, System breakdown, "Other" grouped bucket — all have `fileCount = null` and render unchanged.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. No new warnings.

- [ ] **Step 3: Manual visual verification on a device or emulator**

If a device is attached: `./gradlew installDebug` and launch the app. Otherwise install the APK at `app/build/outputs/apk/debug/app-debug.apk` manually.

Grant "All files access" if prompted, run a scan, and check:

- [ ] Real top-level folders (e.g., Download, DCIM, Pictures) show a count: `1.2 GB · 234 files (5.3%)`.
- [ ] Leaf files at the same level, if any, show only size + percentage, no count.
- [ ] At the root level, "Apps (installed)", "Trash", "System & other" all show their size and percentage with **no** file count.
- [ ] Drill into a folder and confirm the subfolders inside it show their own file counts.
- [ ] An empty folder (if you can find or create one) renders as `0 B · 0 files (0.0%)`.

If you don't have a device handy, the unit tests cover the data path and the build verifies the UI compiles. The visual layer is small enough that a follow-up touch-up is cheap if any spacing looks off.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kimptoc/storageminer/ui/components/PieChart.kt
git commit -m "Show file count in pie chart legend for real directories

Appends ' · N files' between size and percentage in LegendItem when
item.fileCount is set and item.isDirectory is true. Synthetic items
(Apps, Trash, System, Other) keep their existing rendering because
they're constructed with the default null fileCount."
```

---

## Task 5: Final verification and push

**Files:** none

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`. All tests pass, including the pre-existing `ExampleUnitTest.addition_isCorrect` and the new `FormatUtilsTest` (3) and `StorageScannerTest` (4).

- [ ] **Step 2: Full debug build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. Only the two pre-existing `unsafeCheckOpNoThrow` deprecation warnings; no new warnings.

- [ ] **Step 3: Push**

```bash
git push
```

Expected: branch advances cleanly on `origin/main`.
