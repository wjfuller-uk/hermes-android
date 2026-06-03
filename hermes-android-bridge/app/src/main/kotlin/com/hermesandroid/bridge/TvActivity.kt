package com.hermesandroid.bridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.service.VoiceService
import com.hermesandroid.bridge.wake.WakeWordEngine

class TvActivity : ComponentActivity() {

    private lateinit var permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private var permissionChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) checkPermissionsAndStart()
        }

        setContent {
            val ctx = LocalContext.current
            val versionName = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
            } catch (_: Exception) { "?" }

            LaunchedEffect(Unit) {
                if (!permissionChecked) {
                    permissionChecked = true
                    checkPermissionsAndStart()
                }
            }

            TvScreen(versionName = versionName)
        }
    }

    private fun checkPermissionsAndStart() {
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasNotify = if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasMic && hasNotify) {
            VoiceServiceHelper.start(this)
        } else if (!hasMic) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

object VoiceServiceHelper {
    fun start(context: android.content.Context) {
        val intent = android.content.Intent(context, VoiceService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}

// ── TV Screen ──────────────────────────────────────────────────────────────

@Composable
fun TvScreen(versionName: String, modifier: Modifier = Modifier) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Status", "Settings", "Debug")

    val connected = RelayClient.isConnected
    val voiceRunning = VoiceService.isRunning

    val backgroundColor = Color(0xFF0A0A0A)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(backgroundColor, Color.Black),
                    startY = 0f, endY = Float.POSITIVE_INFINITY
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp)
        ) {
            // ── Header ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            if (connected) Color(0xFF00E676)
                            else if (voiceRunning) Color(0xFFFFAB00)
                            else Color(0xFFFF1744),
                            shape = RoundedCornerShape(8.dp)
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("Hermes Bridge", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                Text("v$versionName", color = Color(0xFF555555), fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Status badge ───────────────────────────────────────────
            val (badgeText, badgeColor) = when {
                voiceRunning -> "Mic Active" to Color(0xFFFFAB00)
                connected -> "Connected" to Color(0xFF00E676)
                else -> "Disconnected" to Color(0xFFFF1744)
            }
            Box(
                modifier = Modifier
                    .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(badgeText, color = badgeColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Tab bar (D-pad navigable) ─────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEachIndexed { index, label ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .focusable()
                            .clickable { selectedTab = index }
                            .background(
                                if (isSelected) Color(0xFF00E676).copy(alpha = 0.15f)
                                else Color.White.copy(alpha = 0.04f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color(0xFF00E676) else Color(0xFF888888),
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Tab content ───────────────────────────────────────────
            when (selectedTab) {
                0 -> TvStatusTab(connected, voiceRunning)
                1 -> TvSettingsTab()
                2 -> TvDebugTab(connected, voiceRunning)
            }
        }
    }
}

// ── TV Status Tab ──────────────────────────────────────────────────────────

@Composable
fun TvStatusTab(connected: Boolean, voiceRunning: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        InfoPanel(
            label = "Connection",
            value = if (connected) "✅ Connected to relay" else "❌ Not connected",
            color = if (connected) Color(0xFF00E676) else Color(0xFFFF1744)
        )
        InfoPanel(
            label = "Voice Service",
            value = if (voiceRunning) "🎤 Microphone active — listening" else "⏸ Microphone idle",
            color = if (voiceRunning) Color(0xFFFFAB00) else Color(0xFF666666)
        )
        InfoPanel(
            label = "VAD Threshold",
            value = "${WakeWordEngine.getThreshold()} RMS",
            color = Color(0xFF00E676)
        )
        InfoPanel(
            label = "",
            value = "Say \"Hey Hermes\" to start.\nThe TV is always listening.",
            color = Color(0xFF666666)
        )
    }
}

// ── TV Settings Tab ─────────────────────────────────────────────────────────

@Composable
fun TvSettingsTab() {
    var vadThreshold by remember { mutableStateOf(SettingsManager.vadThreshold) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(title = "🎤  Microphone Sensitivity") {
            Spacer(modifier = Modifier.height(4.dp))
            Text("How loud you need to speak to trigger listening", color = Color(0xFF888888), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvButton("−", Color(0xFFFF1744)) {
                    vadThreshold = (vadThreshold - 100).coerceIn(200, 2000)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${vadThreshold} RMS", color = Color(0xFF00E676), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .width(200.dp).height(6.dp)
                            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = (vadThreshold - 200f) / 1800f)
                                .height(6.dp)
                                .background(Color(0xFF00E676), RoundedCornerShape(3.dp))
                        )
                    }
                }

                TvButton("+", Color(0xFF00E676)) {
                    vadThreshold = (vadThreshold + 100).coerceIn(200, 2000)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Lower = more sensitive  |  Higher = less sensitive",
                color = Color(0xFF666666), fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
        }

        SectionCard(title = "🔊  TTS Voice") {
            Text(SettingsManager.ttsVoice, color = Color(0xFF888888), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Text("Configured on the relay server", color = Color(0xFF555555), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TvButton("💾  Save Settings", Color(0xFF00E676)) {
                SettingsManager.vadThreshold = vadThreshold
                WakeWordEngine.setThreshold(vadThreshold)
            }
        }
    }
}

// ── TV Debug Tab ────────────────────────────────────────────────────────────

@Composable
fun TvDebugTab(connected: Boolean, voiceRunning: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionCard(title = "🛠  System Info") {
            DebugLabel("WS connected", if (connected) "✅ Yes" else "❌ No")
            DebugLabel("Voice service", if (voiceRunning) "RUNNING" else "STOPPED")
            DebugLabel("Wake engine", "VAD (energy-based)")
            DebugLabel("VAD threshold", "${WakeWordEngine.getThreshold()} RMS")
            DebugLabel("Device ID", "hermes-bridge-tv")
            DebugLabel("Relay", "ws://100.111.44.87:8766")
            DebugLabel("Version", "0.9.0")
        }
    }
}

// ── Reusable TV components ─────────────────────────────────────────────────

@Composable
fun TvButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .focusable()
            .clickable { onClick() }
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(text, color = color, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .padding(20.dp)
    ) {
        Column {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun InfoPanel(label: String, value: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(20.dp)
    ) {
        Column {
            if (label.isNotBlank()) {
                Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
            }
            Text(value, color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp, lineHeight = 24.sp)
        }
    }
}

@Composable
fun DebugLabel(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF888888), fontSize = 14.sp)
        Text(value, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}
