package com.example.storageminer.model

data class StorageItem(
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val path: String
)

data class StorageScanResult(
    val items: List<StorageItem>,
    val totalScanned: Long,
    val totalDeviceStorage: Long,
    val freeDeviceStorage: Long,
    val wasCancelled: Boolean,
    val scannedPath: String
)
