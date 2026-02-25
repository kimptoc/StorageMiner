package com.example.storageminer

import android.app.AppOpsManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.storageminer.ui.StorageMinerScreen
import com.example.storageminer.ui.theme.StorageMinerTheme
import com.example.storageminer.viewmodel.StorageMinerViewModel

class MainActivity : ComponentActivity() {

    private var hasPermission by mutableStateOf(false)
    private var hasUsageStatsPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hasPermission = Environment.isExternalStorageManager()
        hasUsageStatsPermission = checkUsageStatsPermission()

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = Environment.isExternalStorageManager()
                hasUsageStatsPermission = checkUsageStatsPermission()
            }
        })

        setContent {
            StorageMinerTheme {
                val vm: StorageMinerViewModel = viewModel()
                StorageMinerScreen(
                    viewModel = vm,
                    hasPermission = hasPermission,
                    hasUsageStatsPermission = hasUsageStatsPermission,
                    onRequestPermission = { requestStoragePermission() },
                    onRequestUsageStats = { requestUsageStatsPermission() },
                    onOpenInFiles = { path -> openInFileManager(path) }
                )
            }
        }
    }

    private fun checkUsageStatsPermission(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestStoragePermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestUsageStatsPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }

    private fun openInFileManager(path: String) {
        try {
            val externalRoot = Environment.getExternalStorageDirectory().absolutePath
            val relativePath = path.removePrefix("$externalRoot/").removePrefix(externalRoot)
            val documentId = if (relativePath.isEmpty()) "primary:" else "primary:$relativePath"
            val uri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                documentId
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Fall back to just opening the Files app
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                        "vnd.android.document/root"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                // No file manager available
            }
        }
    }
}
