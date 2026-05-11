package com.example.storageminer.model

sealed interface ScanState {
    data object Idle : ScanState

    data class Scanning(
        val currentFolder: String,
        val foldersScanned: Int,
        val bytesScanned: Long
    ) : ScanState

    data class Completed(val result: StorageScanResult) : ScanState
}
