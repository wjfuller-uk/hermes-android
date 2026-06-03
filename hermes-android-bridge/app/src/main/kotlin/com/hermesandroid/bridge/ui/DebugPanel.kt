package com.hermesandroid.bridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.bridge.SettingsManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.service.VoiceService
import com.hermesandroid.bridge.wake.WakeWordEngine

/**
 * Settings & debug panel overlaid on top of the main voice screen.
 * Opens via gear ⚙ icon. Dismiss with Close or Save.
 */
@Composable
fun DebugPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var vadThreshold by remember { mutableStateOf(SettingsManager.vadThreshold.toFloat()) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings & Debug", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDismiss) {
                    Text("Close", color = Color(0xFF888888))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── VAD Threshold ───────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🎤 VAD Sensitivity", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Lower = more sensitive (triggers on quieter sounds)",
                        color = Color(0xFF888888), fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = vadThreshold,
                        onValueChange = { vadThreshold = it },
                        valueRange = 200f..2000f,
                        steps = 17
                    )
                    Text(
                        "${vadThreshold.toInt()} RMS",
                        color = Color(0xFF00E676),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── TTS Voice ───────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🔊 TTS Voice", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        SettingsManager.ttsVoice,
                        color = Color(0xFF888888),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        "Set via ANDROID_VOICE_TTS_VOICE env var on relay",
                        color = Color(0xFF555555),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Debug Info ──────────────────────────────────────────────
            Text("🛠 Debug Info", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.height(8.dp))

            DebugRow("Connection", if (RelayClient.isConnected) "CONNECTED" else "DISCONNECTED")
            DebugRow("Voice service", if (VoiceService.isRunning) "RUNNING" else "STOPPED")
            DebugRow("AudioRecord", if (VoiceService.isRunning) "STREAMING" else "IDLE")
            DebugRow("Wake engine", "VAD (energy-based)")
            DebugRow("VAD threshold", "${WakeWordEngine.getThreshold()} RMS")
            DebugRow("Device ID", "hermes-bridge")
            DebugRow("Relay", "ws://100.111.44.87:8766")
            DebugRow("WS connected", if (RelayClient.isConnected) "✅ Yes" else "❌ No")
            DebugRow("Version", "0.9.0")

            Spacer(modifier = Modifier.height(24.dp))

            // ── Save button ─────────────────────────────────────────────
            Button(
                onClick = {
                    SettingsManager.vadThreshold = vadThreshold.toInt()
                    WakeWordEngine.setThreshold(vadThreshold.toInt())
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save Settings", color = Color.Black, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF888888), fontSize = 13.sp)
        Text(
            value,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
