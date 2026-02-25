package com.example.storageminer.viewmodel

import android.app.Application
import android.app.usage.StorageStatsManager
import android.os.Environment
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

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState = _scanState.asStateFlow()

    private val pathStack = ArrayDeque<String>()

    val canNavigateUp = MutableStateFlow(false)

    private var scanJob: Job? = null

    private val defaultRoot: String
        get() = Environment.getExternalStorageDirectory().absolutePath

    fun startScan(path: String = defaultRoot) {
        scanJob?.cancel()
        val scanner = StorageScanner()

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
                val totalScanned = items.sumOf { it.sizeBytes }

                val topItems = groupSmallItems(items)

                _scanState.value = ScanState.Completed(
                    StorageScanResult(
                        items = topItems,
                        totalScanned = totalScanned,
                        totalDeviceStorage = totalStorage,
                        freeDeviceStorage = freeStorage,
                        wasCancelled = false,
                        scannedPath = path
                    )
                )
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
        canNavigateUp.value = false
        _scanState.value = ScanState.Idle
    }

    fun drillDown(item: StorageItem) {
        if (!item.isDirectory || item.path.isEmpty()) return

        val currentPath = getCurrentScannedPath() ?: return
        pathStack.addLast(currentPath)
        canNavigateUp.value = true
        startScan(item.path)
    }

    fun navigateUp() {
        if (pathStack.isEmpty()) return
        val parentPath = pathStack.removeLast()
        canNavigateUp.value = pathStack.isNotEmpty()
        startScan(parentPath)
    }

    private fun getCurrentScannedPath(): String? {
        val state = _scanState.value
        return when (state) {
            is ScanState.Completed -> state.result.scannedPath
            is ScanState.Scanning -> null
            is ScanState.Idle -> defaultRoot
        }
    }

    private fun groupSmallItems(items: List<StorageItem>): List<StorageItem> {
        if (items.size <= 10) return items
        val total = items.sumOf { it.sizeBytes }.coerceAtLeast(1)
        val threshold = total * 0.02 // 2% threshold
        val big = items.filter { it.sizeBytes >= threshold }
        val smallSum = items.filter { it.sizeBytes < threshold }.sumOf { it.sizeBytes }
        return if (smallSum > 0) {
            big + StorageItem(name = "Other", sizeBytes = smallSum, isDirectory = false, path = "")
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
