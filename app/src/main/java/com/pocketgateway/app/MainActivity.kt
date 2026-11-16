package com.pocketgateway.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pocketgateway.app.ui.AppRoot
import com.pocketgateway.app.ui.theme.PocketGatewayTheme

class MainActivity : ComponentActivity() {

    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 用户选择后无需特殊处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        setContent {
            PocketGatewayTheme {
                AppRoot()
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
