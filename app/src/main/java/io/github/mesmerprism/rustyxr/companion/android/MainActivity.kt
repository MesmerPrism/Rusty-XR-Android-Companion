package io.github.mesmerprism.rustyxr.companion.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mesmerprism.rustyxr.companion.android.service.QuestSessionService
import io.github.mesmerprism.rustyxr.companion.android.ui.RustyXrCompanionRoot
import io.github.mesmerprism.rustyxr.companion.android.ui.theme.RustyXrCompanionTheme

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionController = (application as RustyXrCompanionApplication).sessionController
        ContextCompat.startForegroundService(this, Intent(this, QuestSessionService::class.java))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestBluetoothPermissionsIfNeeded()

        setContent {
            RustyXrCompanionTheme {
                val uiState = sessionController.uiState.collectAsStateWithLifecycle()
                RustyXrCompanionRoot(
                    state = uiState.value,
                    controller = sessionController
                )
            }
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        val missing = permissions
            .filter { permission -> checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
        if (missing.isNotEmpty()) {
            bluetoothPermissionLauncher.launch(missing)
        }
    }
}


