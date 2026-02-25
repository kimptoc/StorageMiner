package com.example.storageminer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hasPermission = Environment.isExternalStorageManager()

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = Environment.isExternalStorageManager()
            }
        })

        setContent {
            StorageMinerTheme {
                val vm: StorageMinerViewModel = viewModel()
                StorageMinerScreen(
                    viewModel = vm,
                    hasPermission = hasPermission,
                    onRequestPermission = { requestStoragePermission() }
                )
            }
        }
    }

    private fun requestStoragePermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}
