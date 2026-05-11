package com.kimptoc.storageminer.viewmodel

import android.app.Application
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kimptoc.storageminer.model.ScanState
import com.kimptoc.storageminer.model.StorageItem
import com.kimptoc.storageminer.model.StorageScanResult
import com.kimptoc.storageminer.scanner.StorageScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class StorageMinerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val APPS_PATH = "__apps__"
        const val OTHER_APPS_PATH = "__other_apps__"
        const val SYSTEM_PATH = "__system__"
        const val APP_SETTINGS_PREFIX = "__app_settings__:"
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState = _scanState.asStateFlow()

    private val pathStack = ArrayDeque<String>()
    private val resultCache = mutableMapOf<String, StorageScanResult>()

    val canNavigateUp = MutableStateFlow(false)

    private var scanJob: Job? = null

    // Store the residual so we can break it down when drilled into
    private var lastSystemOtherBytes = 0L

    private val defaultRoot: String
        get() = Environment.getExternalStorageDirectory().absolutePath

    fun hasUsageStatsPermission(): Boolean {
        val appOps = getApplication<Application>()
            .getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            getApplication<Application>().packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun startScan(path: String = defaultRoot) {
        scanJob?.cancel()
        val scanner = StorageScanner()
        val isRootScan = (path == defaultRoot)

        scanJob = viewModelScope.launch {
            _scanState.value = ScanState.Scanning(
                currentFolder = "",
                foldersScanned = 0,
                bytesScanned = 0
            )

            val progressJob = launch {
                combine(
                    scanner.currentFolder,
                    scanner.foldersScanned,
                    scanner.bytesScanned
                ) { folder, folders, bytes ->
                    ScanState.Scanning(folder, folders, bytes)
                }.collect { _scanState.value = it }
            }

            try {
                val items = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    scanner.scan(path)
                }
                progressJob.cancel()

                val (totalStorage, freeStorage) = getDeviceStorage()
                val filesScanned = items.sumOf { it.sizeBytes }
                var totalScanned = filesScanned

                val topItems = groupSmallItems(items).toMutableList()

                // At root level, inject extra categories
                if (isRootScan) {
                    var appsTotal = 0L
                    var trashTotal = 0L

                    if (hasUsageStatsPermission()) {
                        val appItems = getAppStorageStats()
                        appsTotal = appItems.sumOf { it.sizeBytes }
                        if (appsTotal > 0) {
                            topItems.add(
                                StorageItem(
                                    name = "Apps (installed)",
                                    sizeBytes = appsTotal,
                                    isDirectory = true,
                                    path = APPS_PATH
                                )
                            )
                            totalScanned += appsTotal
                        }
                    }

                    // Trash
                    trashTotal = getTrashedFilesSize()
                    if (trashTotal > 0) {
                        topItems.add(
                            StorageItem(
                                name = "Trash",
                                sizeBytes = trashTotal,
                                isDirectory = false,
                                path = ""
                            )
                        )
                        totalScanned += trashTotal
                    }

                    // System & other (residual)
                    val usedStorage = totalStorage - freeStorage
                    val systemOther = usedStorage - totalScanned
                    if (systemOther > 0) {
                        lastSystemOtherBytes = systemOther
                        topItems.add(
                            StorageItem(
                                name = "System & other",
                                sizeBytes = systemOther,
                                isDirectory = true,
                                path = SYSTEM_PATH
                            )
                        )
                        totalScanned += systemOther
                    }

                    topItems.sortByDescending { it.sizeBytes }
                }

                val result = StorageScanResult(
                    items = topItems,
                    totalScanned = totalScanned,
                    totalDeviceStorage = totalStorage,
                    freeDeviceStorage = freeStorage,
                    wasCancelled = false,
                    scannedPath = path
                )
                resultCache[path] = result
                _scanState.value = ScanState.Completed(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                progressJob.cancel()
                val totalScanned = scanner.bytesScanned.value
                val (totalStorage, freeStorage) = getDeviceStorage()
                _scanState.value = ScanState.Completed(
                    StorageScanResult(
                        items = emptyList(),
                        totalScanned = totalScanned,
                        totalDeviceStorage = totalStorage,
                        freeDeviceStorage = freeStorage,
                        wasCancelled = true,
                        scannedPath = path
                    )
                )
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    fun reset() {
        scanJob?.cancel()
        pathStack.clear()
        resultCache.clear()
        canNavigateUp.value = false
        _scanState.value = ScanState.Idle
    }

    fun drillDown(item: StorageItem) {
        if (!item.isDirectory || item.path.isEmpty()) return

        val currentPath = getCurrentScannedPath() ?: return

        // Handle Apps drilldown
        if (item.path == APPS_PATH) {
            pathStack.addLast(currentPath)
            canNavigateUp.value = true
            showAppsBreakdown(fullList = false)
            return
        }

        // Handle "Other apps" drilldown
        if (item.path == OTHER_APPS_PATH) {
            pathStack.addLast(currentPath)
            canNavigateUp.value = true
            showAppsBreakdown(fullList = true)
            return
        }

        // Handle "System & other" drilldown
        if (item.path == SYSTEM_PATH) {
            pathStack.addLast(currentPath)
            canNavigateUp.value = true
            showSystemBreakdown()
            return
        }

        pathStack.addLast(currentPath)
        canNavigateUp.value = true
        startScan(item.path)
    }

    fun navigateUp() {
        if (pathStack.isEmpty()) return
        val parentPath = pathStack.removeLast()
        canNavigateUp.value = pathStack.isNotEmpty()

        // Try to restore from cache instead of re-scanning
        val cached = resultCache[parentPath]
        if (cached != null) {
            _scanState.value = ScanState.Completed(cached)
            return
        }

        startScan(parentPath)
    }

    private fun showAppsBreakdown(fullList: Boolean) {
        val allApps = getAppStorageStats()
        val (totalStorage, freeStorage) = getDeviceStorage()
        val appsTotal = allApps.sumOf { it.sizeBytes }

        val displayItems = if (fullList) {
            allApps
        } else {
            groupSmallApps(allApps)
        }

        val scannedPath = if (fullList) OTHER_APPS_PATH else APPS_PATH
        val result = StorageScanResult(
            items = displayItems,
            totalScanned = appsTotal,
            totalDeviceStorage = totalStorage,
            freeDeviceStorage = freeStorage,
            wasCancelled = false,
            scannedPath = scannedPath
        )
        resultCache[scannedPath] = result
        _scanState.value = ScanState.Completed(result)
    }

    private fun showSystemBreakdown() {
        val (totalStorage, freeStorage) = getDeviceStorage()
        val items = mutableListOf<StorageItem>()

        // Try to estimate OS partition size from raw device capacity
        val rawCapacity = getRawDeviceCapacity()
        val usableCapacity = totalStorage
        if (rawCapacity > usableCapacity) {
            val osPartitions = rawCapacity - usableCapacity
            items.add(
                StorageItem(
                    name = "OS & firmware (estimated)",
                    sizeBytes = osPartitions,
                    isDirectory = false,
                    path = ""
                )
            )
        }

        // Dalvik cache / runtime estimate via /data partition overhead
        val dataPartitionOverhead = getDataPartitionOverhead()
        if (dataPartitionOverhead > 0) {
            items.add(
                StorageItem(
                    name = "Filesystem overhead (estimated)",
                    sizeBytes = dataPartitionOverhead,
                    isDirectory = false,
                    path = ""
                )
            )
        }

        // Whatever remains unaccounted
        val estimated = items.sumOf { it.sizeBytes }
        val remainder = lastSystemOtherBytes - estimated
        if (remainder > 0) {
            items.add(
                StorageItem(
                    name = "Caches, logs & system data",
                    sizeBytes = remainder,
                    isDirectory = false,
                    path = ""
                )
            )
        }

        // If we couldn't estimate anything, show the total as one item
        if (items.isEmpty()) {
            items.add(
                StorageItem(
                    name = "System (inaccessible to apps)",
                    sizeBytes = lastSystemOtherBytes,
                    isDirectory = false,
                    path = ""
                )
            )
        }

        val result = StorageScanResult(
            items = items,
            totalScanned = lastSystemOtherBytes,
            totalDeviceStorage = totalStorage,
            freeDeviceStorage = freeStorage,
            wasCancelled = false,
            scannedPath = SYSTEM_PATH
        )
        resultCache[SYSTEM_PATH] = result
        _scanState.value = ScanState.Completed(result)
    }

    private fun getRawDeviceCapacity(): Long {
        // Read /proc/partitions to find the main block device (largest one)
        return try {
            val lines = File("/proc/partitions").readLines()
            var maxBlocks = 0L
            for (line in lines) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val blocks = parts[2].toLongOrNull() ?: continue
                    if (blocks > maxBlocks) {
                        maxBlocks = blocks
                    }
                }
            }
            // /proc/partitions reports in 1KB blocks
            maxBlocks * 1024
        } catch (_: Exception) {
            0L
        }
    }

    private fun getDataPartitionOverhead(): Long {
        // Compare StatFs reported total vs what StorageStatsManager reports
        // The difference is filesystem metadata, reserved blocks, journals
        return try {
            val statFs = StatFs(Environment.getDataDirectory().absolutePath)
            val partitionTotal = statFs.totalBytes
            val statsManager = getApplication<Application>()
                .getSystemService(StorageStatsManager::class.java)
            val usableTotal = statsManager.getTotalBytes(StorageManager.UUID_DEFAULT)
            val overhead = partitionTotal - usableTotal
            if (overhead > 0) overhead else 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun getCurrentScannedPath(): String? {
        val state = _scanState.value
        return when (state) {
            is ScanState.Completed -> state.result.scannedPath
            is ScanState.Scanning -> null
            is ScanState.Idle -> defaultRoot
        }
    }

    private fun getAppStorageStats(): List<StorageItem> {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val statsManager = app.getSystemService(StorageStatsManager::class.java)
        val uuid = StorageManager.UUID_DEFAULT

        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val items = mutableListOf<StorageItem>()

        for (appInfo in apps) {
            try {
                val stats = statsManager.queryStatsForPackage(
                    uuid,
                    appInfo.packageName,
                    Process.myUserHandle()
                )
                val totalBytes = stats.appBytes + stats.dataBytes + stats.cacheBytes
                if (totalBytes <= 0) continue

                val label = pm.getApplicationLabel(appInfo).toString()
                items.add(
                    StorageItem(
                        name = label,
                        sizeBytes = totalBytes,
                        isDirectory = false,
                        path = APP_SETTINGS_PREFIX + appInfo.packageName
                    )
                )
            } catch (_: Exception) {
                // Some system packages may throw — skip them
            }
        }

        return items.sortedByDescending { it.sizeBytes }
    }

    private fun getTrashedFilesSize(): Long {
        return try {
            val app = getApplication<Application>()
            var totalTrash = 0L

            val collections = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            )

            for (uri in collections) {
                val queryArgs = Bundle().apply {
                    putInt(
                        MediaStore.QUERY_ARG_MATCH_TRASHED,
                        MediaStore.MATCH_ONLY
                    )
                    putStringArray(
                        "android:query-arg-sql-selection",
                        arrayOf()
                    )
                }

                app.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.SIZE),
                    queryArgs,
                    null
                )?.use { cursor ->
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        totalTrash += cursor.getLong(sizeCol)
                    }
                }
            }

            totalTrash
        } catch (_: Exception) {
            0L
        }
    }

    private fun groupSmallApps(items: List<StorageItem>): List<StorageItem> {
        if (items.size <= 10) return items
        val total = items.sumOf { it.sizeBytes }.coerceAtLeast(1)
        val threshold = total * 0.02
        val big = items.filter { it.sizeBytes >= threshold }
        val small = items.filter { it.sizeBytes < threshold }
        val smallSum = small.sumOf { it.sizeBytes }
        return if (smallSum > 0) {
            big + StorageItem(
                name = "Other apps (${small.size})",
                sizeBytes = smallSum,
                isDirectory = true,
                path = OTHER_APPS_PATH
            )
        } else {
            big
        }
    }

    private fun groupSmallItems(
        items: List<StorageItem>,
        label: String = "Other"
    ): List<StorageItem> {
        if (items.size <= 10) return items
        val total = items.sumOf { it.sizeBytes }.coerceAtLeast(1)
        val threshold = total * 0.02 // 2% threshold
        val big = items.filter { it.sizeBytes >= threshold }
        val smallSum = items.filter { it.sizeBytes < threshold }.sumOf { it.sizeBytes }
        return if (smallSum > 0) {
            big + StorageItem(name = label, sizeBytes = smallSum, isDirectory = false, path = "")
        } else {
            big
        }
    }

    private fun getDeviceStorage(): Pair<Long, Long> {
        return try {
            val statsManager = getApplication<Application>()
                .getSystemService(StorageStatsManager::class.java)
            val uuid = StorageManager.UUID_DEFAULT
            val total = statsManager.getTotalBytes(uuid)
            val free = statsManager.getFreeBytes(uuid)
            Pair(total, free)
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }
}
