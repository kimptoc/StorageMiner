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
