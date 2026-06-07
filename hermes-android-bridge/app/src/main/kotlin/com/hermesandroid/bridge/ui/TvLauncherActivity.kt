package com.hermesandroid.bridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.hermesandroid.bridge.SettingsManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.service.OverlayService

/**
 * Leanback launcher entry point for TV. Starts the OverlayService (wake word detection)
 * and shows a minimal status screen. The real UI is the translucent overlay
 * that appears on wake word detection.
 */
class TvLauncherActivity : ComponentActivity() {

    private var permissionChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)

        setContent {
            LaunchedEffect(Unit) {
                if (!permissionChecked) {
                    permissionChecked = true
                    requestPermissionsAndStart()
                }
            }

            // Minimal status screen — dark background with connection indicator
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
                contentAlignment = Alignment.Center
            ) {
                val connected by remember { mutableStateOf(RelayClient.isConnected) }
                val voiceRunning by remember { mutableStateOf(com.hermesandroid.bridge.service.OverlayService.isRunning) }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                if (connected) Color(0xFF00E676)
                                else if (voiceRunning) Color(0xFFFFAB00)
                                else Color(0xFFFF1744),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            )
                    )
                    androidx.compose.material3.Text(
                        "Hermes Bridge",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val badge = when {
                        voiceRunning -> "Mic Active" to Color(0xFFFFAB00)
                        connected -> "Connected" to Color(0xFF00E676)
                        else -> "Disconnected" to Color(0xFFFF1744)
                    }
                    Box(
                        modifier = Modifier
                            .background(badge.second.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        androidx.compose.material3.Text(
                            badge.first,
                            color = badge.second,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    androidx.compose.material3.Text(
                        "Listening for \"Hey Bob\"…",
                        color = Color(0xFF666666),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasNotify = if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasMic && hasNotify) {
            OverlayService.start(this)
        } else if (!hasMic) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    OverlayService.start(this)
                }
            }.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
                OverlayService.start(this)
            }.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop OverlayService — it should persist
    }
}
