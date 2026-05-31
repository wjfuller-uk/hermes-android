package com.hermesandroid.bridge.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.BuildConfig

// ── Data models ──────────────────────────────────────────────────────────────

data class DiagnosticItem(
    val category: String,
    val name: String,
    val value: String,
    val status: Status = Status.INFO
)

enum class Status { OK, WARNING, ERROR, INFO }

// ── Diagnostics Screen ───────────────────────────────────────────────────────

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit = {},
    onOpenLogs: () -> Unit = {}
) {
    val context = LocalContext.current
    val diagnostics = remember { collectDiagnostics(context) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0D1117)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = Color(0xFF58A6FF))
                }
                Text(
                    "Diagnostics",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                TextButton(onClick = onOpenLogs) {
                    Text("📋 Logs", color = Color(0xFF58A6FF), fontSize = 13.sp)
                }
            }

            // Diagnostic items grouped by category
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val grouped = diagnostics.groupBy { it.category }
                grouped.forEach { (category, items) ->
                    item {
                        Text(
                            text = category,
                            color = Color(0xFF58A6FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(items) { item ->
                        DiagnosticRow(item)
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticRow(item: DiagnosticItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF161B22)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.name,
                color = Color(0xFF8B949E),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.value,
                    color = when (item.status) {
                        Status.OK -> Color(0xFF3FB950)
                        Status.WARNING -> Color(0xFFD29922)
                        Status.ERROR -> Color(0xFFF85149)
                        Status.INFO -> Color.White
                    },
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = when (item.status) {
                                Status.OK -> Color(0xFF3FB950)
                                Status.WARNING -> Color(0xFFD29922)
                                Status.ERROR -> Color(0xFFF85149)
                                Status.INFO -> Color(0xFF484F58)
                            },
                            shape = RoundedCornerShape(4.dp)
                        )
                )
            }
        }
    }
}

// ── Collector ────────────────────────────────────────────────────────────────

fun collectDiagnostics(context: Context): List<DiagnosticItem> {
    val items = mutableListOf<DiagnosticItem>()

    // ── App Info ──
    items.add(DiagnosticItem("App", "Version", BuildConfig.VERSION_NAME))
    items.add(DiagnosticItem("App", "Package", context.packageName))

    // ── Device ──
    items.add(DiagnosticItem("Device", "Model", "${Build.BRAND} ${Build.MODEL}"))
    items.add(DiagnosticItem("Device", "Android", "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"))
    items.add(DiagnosticItem("Device", "Hardware", Build.HARDWARE))
    try {
        items.add(DiagnosticItem("Device", "Serial", Build.SERIAL?.take(8) ?: "unknown"))
    } catch (e: SecurityException) {
        items.add(DiagnosticItem("Device", "Serial", "permission denied", Status.WARNING))
    }

    // ── Battery ──
    try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bm?.isCharging ?: false
        } else false
        items.add(DiagnosticItem("Battery", "Level", "${batteryLevel}%",
            if (batteryLevel > 20) Status.OK else Status.WARNING))
        items.add(DiagnosticItem("Battery", "Charging", if (isCharging) "Yes" else "No",
            if (isCharging) Status.OK else Status.INFO))
    } catch (e: Exception) {
        items.add(DiagnosticItem("Battery", "Error", e.message ?: "unknown", Status.ERROR))
    }

    // ── Network ──
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val isConnected = caps != null
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            else -> "None"
        }
        items.add(DiagnosticItem("Network", "Connected", if (isConnected) "Yes" else "No",
            if (isConnected) Status.OK else Status.ERROR))
        items.add(DiagnosticItem("Network", "Transport", transport))
    } catch (e: Exception) {
        items.add(DiagnosticItem("Network", "Error", e.message ?: "unknown", Status.ERROR))
    }

    // ── Tailscale ──
    val tailscaleIps = try {
        val process = Runtime.getRuntime().exec(arrayOf("ip", "addr", "show", "tailscale0"))
        val output = process.inputStream.bufferedReader().readText()
        val ips = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").findAll(output).map { it.groupValues[1] }.toList()
        ips.joinToString(", ").ifEmpty { "not found" }
    } catch (e: Exception) { "error: ${e.message}" }
    items.add(DiagnosticItem("Network", "Tailscale IP", tailscaleIps,
        if (tailscaleIps.contains("100.")) Status.OK else Status.WARNING))

    // ── Connection ──
    items.add(DiagnosticItem("Connection", "Relay Connected", if (RelayClient.isConnected) "Yes" else "No",
        if (RelayClient.isConnected) Status.OK else Status.ERROR))
    items.add(DiagnosticItem("Connection", "Server URL", RelayClient.serverUrl ?: "not set"))
    items.add(DiagnosticItem("Connection", "Auth", "auto (Tailscale)"))

    // ── Permissions ──
    try {
        val permissions = mapOf(
            "RECORD_AUDIO" to Manifest.permission.RECORD_AUDIO,
            "CAMERA" to Manifest.permission.CAMERA,
            "READ_CONTACTS" to Manifest.permission.READ_CONTACTS,
            "ACCESS_FINE_LOCATION" to Manifest.permission.ACCESS_FINE_LOCATION,
            "POST_NOTIFICATIONS" to Manifest.permission.POST_NOTIFICATIONS,
        )
        permissions.forEach { (name, perm) ->
            val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            items.add(DiagnosticItem("Permissions", name, if (granted) "Granted" else "Denied",
                if (granted) Status.OK else Status.WARNING))
        }
    } catch (e: Exception) {
        items.add(DiagnosticItem("Permissions", "Error", e.message ?: "unknown", Status.ERROR))
    }

    // ── Audio ──
    try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val streamVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
        items.add(DiagnosticItem("Audio", "Speaker Volume", "${streamVol}/${maxVol}"))
    } catch (e: Exception) {
        items.add(DiagnosticItem("Audio", "Error", e.message ?: "unknown", Status.ERROR))
    }

    // ── Sensors ──
    try {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensorList = sm?.getSensorList(Sensor.TYPE_ALL) ?: emptyList()
        items.add(DiagnosticItem("Sensors", "Total Count", "${sensorList.size}"))
    } catch (e: Exception) {
        items.add(DiagnosticItem("Sensors", "Error", e.message ?: "unknown", Status.ERROR))
    }

    // ── Power ──
    try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = pm?.isInteractive ?: false
        val isPowerSave = pm?.isPowerSaveMode ?: false
        items.add(DiagnosticItem("Power", "Screen On", if (isInteractive) "Yes" else "No"))
        items.add(DiagnosticItem("Power", "Power Save", if (isPowerSave) "Yes" else "No",
            if (isPowerSave) Status.WARNING else Status.OK))
    } catch (e: Exception) {
        items.add(DiagnosticItem("Power", "Error", e.message ?: "unknown", Status.ERROR))
    }

    return items
}
