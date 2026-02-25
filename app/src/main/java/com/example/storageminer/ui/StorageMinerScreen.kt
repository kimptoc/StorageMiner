package com.example.storageminer.ui

import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.storageminer.model.ScanState
import com.example.storageminer.model.StorageScanResult
import com.example.storageminer.ui.components.PieChart
import com.example.storageminer.ui.components.ScanningIndicator
import com.example.storageminer.util.formatFileSize
import com.example.storageminer.viewmodel.StorageMinerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageMinerScreen(
    viewModel: StorageMinerViewModel,
    hasPermission: Boolean,
    hasUsageStatsPermission: Boolean,
    onRequestPermission: () -> Unit,
    onRequestUsageStats: () -> Unit,
    onOpenInFiles: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scanState by viewModel.scanState.collectAsState()
    val canGoUp by viewModel.canNavigateUp.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when (val state = scanState) {
                        is ScanState.Completed -> {
                            Text(
                                text = buildBreadcrumb(state.result.scannedPath),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        else -> Text("StorageMiner")
                    }
                },
                navigationIcon = {
                    if (canGoUp) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Navigate up"
                            )
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        when {
            !hasPermission -> PermissionView(
                onRequestPermission = onRequestPermission,
                modifier = Modifier.padding(innerPadding)
            )
            scanState is ScanState.Idle -> IdleView(
                onStartScan = { viewModel.startScan() },
                modifier = Modifier.padding(innerPadding)
            )
            scanState is ScanState.Scanning -> {
                val state = scanState as ScanState.Scanning
                ScanningIndicator(
                    currentFolder = state.currentFolder,
                    foldersScanned = state.foldersScanned,
                    bytesScanned = state.bytesScanned,
                    onStop = { viewModel.stopScan() },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            scanState is ScanState.Completed -> {
                val state = scanState as ScanState.Completed
                ResultsView(
                    result = state.result,
                    hasUsageStatsPermission = hasUsageStatsPermission,
                    onScanAgain = { viewModel.reset() },
                    onDrillDown = { viewModel.drillDown(it) },
                    onRequestUsageStats = onRequestUsageStats,
                    onOpenInFiles = onOpenInFiles,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun PermissionView(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Storage Access Required",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "StorageMiner needs access to all files to analyze your storage usage.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Access")
        }
    }
}

@Composable
private fun IdleView(
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "StorageMiner",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Analyze your device storage usage",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onStartScan) {
            Text("Scan Storage")
        }
    }
}

@Composable
private fun ResultsView(
    result: StorageScanResult,
    hasUsageStatsPermission: Boolean,
    onScanAgain: () -> Unit,
    onDrillDown: (com.example.storageminer.model.StorageItem) -> Unit,
    onRequestUsageStats: () -> Unit,
    onOpenInFiles: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val externalRoot = Environment.getExternalStorageDirectory().absolutePath
    val isRootLevel = result.scannedPath == externalRoot
    val isAppsView = result.scannedPath == StorageMinerViewModel.APPS_PATH
    val showOpenInFiles = !isAppsView && result.scannedPath.startsWith(externalRoot)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (result.wasCancelled) {
            Text(
                text = "Scan was stopped early. Partial results shown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (result.totalDeviceStorage > 0) {
            val usedStorage = result.totalDeviceStorage - result.freeDeviceStorage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Device: ${formatFileSize(usedStorage)} used of ${formatFileSize(result.totalDeviceStorage)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${formatFileSize(result.freeDeviceStorage)} free",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "Visible to scan: ${formatFileSize(result.totalScanned)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "Scanned: ${formatFileSize(result.totalScanned)}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Usage stats permission prompt at root level
        if (isRootLevel && !hasUsageStatsPermission) {
            TextButton(
                onClick = onRequestUsageStats,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = "Grant usage access to see app storage sizes",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (result.items.isNotEmpty()) {
            PieChart(
                items = result.items,
                onItemClick = onDrillDown,
                modifier = Modifier.weight(1f)
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No items found",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onScanAgain) {
                Text("Scan Again")
            }

            if (showOpenInFiles) {
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = { onOpenInFiles(result.scannedPath) }) {
                    Text("Open in Files")
                }
            }
        }
    }
}

private fun buildBreadcrumb(scannedPath: String): String {
    if (scannedPath == StorageMinerViewModel.APPS_PATH) return "Storage > Apps"
    val externalRoot = Environment.getExternalStorageDirectory().absolutePath
    if (scannedPath == externalRoot) return "Storage"
    val relative = scannedPath.removePrefix("$externalRoot/")
    val parts = relative.split("/")
    return "Storage > " + parts.joinToString(" > ")
}
