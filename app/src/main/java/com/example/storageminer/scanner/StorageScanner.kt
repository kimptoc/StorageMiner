package com.example.storageminer.scanner

import com.example.storageminer.model.StorageItem
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
                val size = calculateDirectorySize(entry)
                items.add(
                    StorageItem(
                        name = entry.name,
                        sizeBytes = size,
                        isDirectory = true,
                        path = entry.absolutePath
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
                        path = entry.absolutePath
                    )
                )
            }
        }

        return items.sortedByDescending { it.sizeBytes }
    }

    private suspend fun calculateDirectorySize(dir: File): Long {
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        _foldersScanned.value++

        for (file in files) {
            coroutineContext.ensureActive()
            size += if (file.isDirectory) {
                _currentFolder.value = file.name
                calculateDirectorySize(file)
            } else {
                val fileSize = file.length()
                _bytesScanned.value += fileSize
                fileSize
            }
        }
        return size
    }
}
