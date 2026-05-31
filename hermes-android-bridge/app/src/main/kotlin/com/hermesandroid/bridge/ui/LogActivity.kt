package com.hermesandroid.bridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.core.content.FileProvider
import com.hermesandroid.bridge.util.AppLogger
import kotlinx.coroutines.delay

class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LogViewerScreen(onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(AppLogger.getEntries()) }
    val listState = rememberLazyListState()

    // Refresh logs every 1 second
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            entries = AppLogger.getEntries()
        }
    }

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0D1117)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("\u2190 Back", color = Color(0xFF8B949E), fontSize = 14.sp)
                }
                Text(
                    "Logs",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Row {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Hermes Logs", AppLogger.getFormattedText()))
                    }) {
                        Text("Copy", color = Color(0xFF58A6FF), fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        shareLogs(context)
                    }) {
                        Text("Share", color = Color(0xFF58A6FF), fontSize = 13.sp)
                    }
                }
            }

            // Log entries
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(entries) { entry ->
                    LogEntryRow(entry)
                }
                if (entries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No log entries yet",
                                color = Color(0xFF484F58),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntryRow(entry: AppLogger.LogEntry) {
    val badgeColor = when (entry.levelChar) {
        'V' -> Color(0xFF6E7681) // gray
        'D' -> Color(0xFF58A6FF) // blue
        'I' -> Color(0xFF3FB950) // green
        'W' -> Color(0xFFD29922) // amber
        'E' -> Color(0xFFF85149) // red
        else -> Color(0xFF6E7681)
    }
    val timeStr = remember(entry.timestamp) {
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(entry.timestamp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1117))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Level badge
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = badgeColor.copy(alpha = 0.2f),
            modifier = Modifier.padding(top = 2.dp)
        ) {
            Text(
                text = entry.level,
                color = badgeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        // Time
        Text(
            text = timeStr,
            color = Color(0xFF484F58),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(6.dp))
        // Tag
        Text(
            text = entry.tag,
            color = Color(0xFF8B949E),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
        Spacer(Modifier.width(6.dp))
        // Message
        Text(
            text = entry.message,
            color = Color(0xFFC9D1D9),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun shareLogs(context: Context) {
    try {
        val logFile = AppLogger.getLogFile()
        if (logFile != null) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Logs"))
        } else {
            // Fallback: share as text
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, AppLogger.getFormattedText())
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Logs"))
        }
    } catch (e: Exception) {
        // Fallback to text sharing
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, AppLogger.getFormattedText())
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Logs"))
    }
}
