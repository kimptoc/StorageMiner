package com.example.storageminer.viewmodel

import android.app.Application
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.storageminer.model.ScanState
import com.example.storageminer.model.StorageItem
import com.example.storageminer.model.StorageScanResult
import com.example.storageminer.scanner.StorageScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class StorageMinerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val APPS_PATH = "__apps__"
        const val OTHER_APPS_PATH = "__other_apps__"
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState = _scanState.asStateFlow()

    private val pathStack = ArrayDeque<String>()
    private val resultCache = mutableMapOf<String, StorageScanResult>()

    val canNavigateUp = MutableStateFlow(false)

    private var scanJob: Job? = null

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
                var totalScanned = items.sumOf { it.sizeBytes }

                val topItems = groupSmallItems(items).toMutableList()

                // At root level, inject "Apps (installed)" if we have usage stats permission
                if (isRootScan && hasUsageStatsPermission()) {
                    val appItems = getAppStorageStats()
                    val appsTotal = appItems.sumOf { it.sizeBytes }
                    if (appsTotal > 0) {
                        topItems.add(
                            StorageItem(
                                name = "Apps (installed)",
                                sizeBytes = appsTotal,
                                isDirectory = true,
                                path = APPS_PATH
                            )
                        )
                        topItems.sortByDescending { it.sizeBytes }
                        totalScanned += appsTotal
                    }
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

        // Handle Apps drilldown — show per-app breakdown directly
        if (item.path == APPS_PATH) {
            pathStack.addLast(currentPath)
            canNavigateUp.value = true
            showAppsBreakdown(fullList = false)
            return
        }

        // Handle "Other apps" drilldown — show all the small apps
        if (item.path == OTHER_APPS_PATH) {
            pathStack.addLast(currentPath)
            canNavigateUp.value = true
            showAppsBreakdown(fullList = true)
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
                        path = ""
                    )
                )
            } catch (_: Exception) {
                // Some system packages may throw — skip them
            }
        }

        return items.sortedByDescending { it.sizeBytes }
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
