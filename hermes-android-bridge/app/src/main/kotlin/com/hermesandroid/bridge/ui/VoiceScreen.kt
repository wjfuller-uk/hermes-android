package com.hermesandroid.bridge.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.bridge.client.RelayClient
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

// ── Data model ────────────────────────────────────────────────────────────────

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

enum class VoiceState { IDLE, LISTENING, PROCESSING, SPEAKING }

// ── ViewModel ─────────────────────────────────────────────────────────────────

class VoiceViewModel : ViewModel() {
    var voiceState by mutableStateOf(VoiceState.IDLE)
        private set
    var messages by mutableStateOf(listOf<ChatMessage>())
        private set
    var isConnected by mutableStateOf(false)
        private set
    var deviceName by mutableStateOf("")
        private set
    var isPushToTalk by mutableStateOf(false)
        private set
    var isAlwaysOn by mutableStateOf(false)
        private set
    var micLevel by mutableStateOf(0f)
        private set

    fun updateConnection(connected: Boolean) { isConnected = connected }
    fun updateDeviceName(name: String) { deviceName = name }

    fun updateState(state: VoiceState) { voiceState = state }
    fun updateMicLevel(level: Float) { micLevel = level }

    fun togglePushToTalk() { isPushToTalk = !isPushToTalk }
    fun toggleAlwaysOn() { isAlwaysOn = !isAlwaysOn }

    fun addMessage(text: String, isUser: Boolean) {
        messages = messages + ChatMessage(text = text, isUser = isUser)
    }

    fun clearMessages() { messages = emptyList() }
}

// ── Main screen ───────────────────────────────────────────────────────────────

@Composable
fun VoiceAssistantScreen(
    viewModel: VoiceViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onConnect: (url: String, code: String) -> Unit = { _, _ -> },
    onDisconnect: () -> Unit = {},
    onSendText: (String) -> Unit = {},
    onStartVoice: () -> Unit = {},
    onStopVoice: () -> Unit = {}
) {
    val listState = rememberLazyListState()

    // Auto-scroll to latest message
    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.size - 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0D1117) // GitHub dark
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ──
            VoiceHeader(
                state = viewModel.voiceState,
                isConnected = viewModel.isConnected,
                deviceName = viewModel.deviceName,
                onOpenSettings = onOpenSettings,
                onOpenDiagnostics = onOpenDiagnostics,
                onDisconnect = onDisconnect
            )

            // ── Connection UI (shown when disconnected) ──
            if (!viewModel.isConnected) {
                ConnectionPanel(onConnect = onConnect)
            }

            // ── Messages ──
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(viewModel.messages) { msg ->
                    MessageBubble(message = msg)
                }
                // Empty state
                if (viewModel.messages.isEmpty() && viewModel.isConnected) {
                    item {
                        EmptyStateHint()
                    }
                }
            }

            // ── Text input bar ──
            if (viewModel.isConnected) {
                ChatInputBar(onSend = onSendText)
            }

            // ── Push-to-talk controls ──
            if (viewModel.isConnected) {
                PushToTalkBar(
                    isPushToTalk = viewModel.isPushToTalk,
                    isAlwaysOn = viewModel.isAlwaysOn,
                    voiceState = viewModel.voiceState,
                    onTogglePushToTalk = { viewModel.togglePushToTalk() },
                    onToggleAlwaysOn = { viewModel.toggleAlwaysOn() },
                    onStartVoice = onStartVoice,
                    onStopVoice = onStopVoice
                )
            }

            // ── Waveform bar ──
            VoiceWaveform(state = viewModel.voiceState)

            // ── Status footer ──
            StatusFooter(state = viewModel.voiceState)
        }
    }
}

// ── Connection panel ──────────────────────────────────────────────────────────

@Composable
fun ConnectionPanel(onConnect: (url: String, code: String) -> Unit) {
    var serverUrl by remember { mutableStateOf(RelayClient.serverUrl ?: "") }
    var pairingCode by remember { mutableStateOf(RelayClient.pairingCode ?: "") }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF161B22)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Connect to Server",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                placeholder = { Text("wss://relay.example.com", color = Color(0xFF484F58)) },
                label = { Text("Server URL", color = Color(0xFF8B949E)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF58A6FF),
                    unfocusedBorderColor = Color(0xFF30363D),
                    cursorColor = Color(0xFF58A6FF)
                )
            )
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it },
                placeholder = { Text("Pairing code", color = Color(0xFF484F58)) },
                label = { Text("Pairing Code", color = Color(0xFF8B949E)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF58A6FF),
                    unfocusedBorderColor = Color(0xFF30363D),
                    cursorColor = Color(0xFF58A6FF)
                )
            )
            Button(
                onClick = {
                    if (serverUrl.isNotBlank() && pairingCode.isNotBlank()) {
                        onConnect(serverUrl.trim(), pairingCode.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF0883E)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "CONNECT",
                    color = Color(0xFF0D1117),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

// ── Chat input bar ──────────────────────────────────────────────────────────

@Composable
fun ChatInputBar(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF161B22),
        border = BorderStroke(1.dp, Color(0xFF30363D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Text field
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        "Type a message...",
                        color = Color(0xFF484F58),
                        fontSize = 15.sp
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color(0xFF58A6FF)
                ),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
            )

            // Send button
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text.trim())
                        text = ""
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (text.isNotBlank()) Color(0xFFF0883E) else Color(0xFF21262D)
                    )
            ) {
                Text(
                    text = "↑",
                    color = if (text.isNotBlank()) Color(0xFF0D1117) else Color(0xFF484F58),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── Push-to-talk bar ────────────────────────────────────────────────────────

@Composable
fun PushToTalkBar(
    isPushToTalk: Boolean,
    isAlwaysOn: Boolean,
    voiceState: VoiceState,
    onTogglePushToTalk: () -> Unit,
    onToggleAlwaysOn: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Push-to-talk toggle
        Surface(
            onClick = onTogglePushToTalk,
            shape = RoundedCornerShape(20.dp),
            color = if (isPushToTalk) Color(0xFF238636) else Color(0xFF21262D)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🎤",
                    fontSize = 16.sp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isPushToTalk) "PTT On" else "PTT Off",
                    color = if (isPushToTalk) Color.White else Color(0xFF8B949E),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Always-on toggle
        Surface(
            onClick = onToggleAlwaysOn,
            shape = RoundedCornerShape(20.dp),
            color = if (isAlwaysOn) Color(0xFF8957E5) else Color(0xFF21262D)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🔊",
                    fontSize = 16.sp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isAlwaysOn) "Always On" else "Always Off",
                    color = if (isAlwaysOn) Color.White else Color(0xFF8B949E),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Manual voice button (hold to talk when PTT is on)
        if (isPushToTalk) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        when (voiceState) {
                            VoiceState.LISTENING -> Color(0xFF58A6FF)
                            VoiceState.PROCESSING -> Color(0xFFD29922)
                            VoiceState.SPEAKING -> Color(0xFFA371F7)
                            else -> Color(0xFFF0883E)
                        }
                    )
                    .clickable {
                        if (voiceState == VoiceState.IDLE) {
                            onStartVoice()
                        } else {
                            onStopVoice()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (voiceState) {
                        VoiceState.LISTENING -> "🎙"
                        VoiceState.PROCESSING -> "⏳"
                        VoiceState.SPEAKING -> "🔊"
                        else -> "🎤"
                    },
                    fontSize = 24.sp
                )
            }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
fun VoiceHeader(
    state: VoiceState,
    isConnected: Boolean,
    deviceName: String,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161B22))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left side: status dot + title + device name
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Connection status dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !isConnected -> Color(0xFFF85149) // red
                            state == VoiceState.IDLE -> Color(0xFF3FB950) // green
                            state == VoiceState.LISTENING -> Color(0xFF58A6FF) // blue
                            state == VoiceState.PROCESSING -> Color(0xFFD29922) // amber
                            state == VoiceState.SPEAKING -> Color(0xFFA371F7) // purple
                            else -> Color(0xFF3FB950) // fallback green
                        }
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "Hermes",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                if (isConnected && deviceName.isNotBlank()) {
                    Text(
                        text = deviceName,
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Right side: diagnostics + settings gear + disconnect
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Diagnostics icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onOpenDiagnostics() }
                    .background(Color(0xFF21262D)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🔍",
                    color = Color(0xFF8B949E),
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            // Settings gear icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onOpenSettings() }
                    .background(Color(0xFF21262D)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚙",
                    color = Color(0xFF8B949E),
                    fontSize = 18.sp
                )
            }
            if (isConnected) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDisconnect) {
                    Text("Disconnect", color = Color(0xFF8B949E), fontSize = 13.sp)
                }
            }
        }
    }
}

// ── Message bubble ────────────────────────────────────────────────────────────

@Composable
fun MessageBubble(message: ChatMessage) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            ),
            color = if (message.isUser) Color(0xFF238636) else Color(0xFF21262D),
            tonalElevation = 1.dp
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
        Text(
            text = timeFormat.format(Date(message.timestamp)),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = Color(0xFF484F58),
            fontSize = 11.sp
        )
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
fun EmptyStateHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "⬡",
                fontSize = 48.sp,
                color = Color(0xFF30363D)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Hermes is listening",
                color = Color(0xFF8B949E),
                fontSize = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Say something to begin",
                color = Color(0xFF484F58),
                fontSize = 13.sp
            )
        }
    }
}

// ── Waveform animation ────────────────────────────────────────────────────────

@Composable
fun VoiceWaveform(state: VoiceState) {
    val barCount = 5
    val barWidths = listOf(0.6f, 1.0f, 0.8f, 1.0f, 0.6f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF0D1117)),
        contentAlignment = Alignment.Center
    ) {
        if (state == VoiceState.IDLE) {
            // Quiet pulse when idle
            val idleAlpha by rememberInfiniteTransition().animateFloat(
                initialValue = 0.15f,
                targetValue = 0.30f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
            IdleBars(alpha = idleAlpha, barWidths = barWidths)
        } else {
            // Animated bars when active
            val animSpec = when (state) {
                VoiceState.LISTENING -> 400  // fast = listening
                VoiceState.PROCESSING -> 900 // slow = thinking
                VoiceState.SPEAKING -> 500   // medium = speaking
                else -> 2000
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(barCount * 2 + 1) { index ->
                    val delay = index * animSpec / (barCount * 2)
                    val infiniteTransition = rememberInfiniteTransition()
                    val height by infiniteTransition.animateFloat(
                        initialValue = 8f,
                        targetValue = 56f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = animSpec,
                                easing = FastOutSlowInEasing,
                                delayMillis = delay
                            ),
                            repeatMode = RepeatMode.Reverse
                        )
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(height.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        when (state) {
                                            VoiceState.LISTENING -> Color(0xFF58A6FF)
                                            VoiceState.PROCESSING -> Color(0xFFD29922)
                                            VoiceState.SPEAKING -> Color(0xFFA371F7)
                                            else -> Color(0xFF30363D)
                                        },
                                        when (state) {
                                            VoiceState.LISTENING -> Color(0xFF1F6FEB)
                                            VoiceState.PROCESSING -> Color(0xFF9E6A03)
                                            VoiceState.SPEAKING -> Color(0xFF6E40C9)
                                            else -> Color(0xFF21262D)
                                        }
                                    )
                                )
                            )
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}

@Composable
fun IdleBars(alpha: Float, barWidths: List<Float>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        barWidths.forEach { width ->
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((40 * width).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF30363D).copy(alpha = alpha))
            )
            Spacer(Modifier.width(6.dp))
        }
    }
}

// ── Status footer ─────────────────────────────────────────────────────────────

@Composable
fun StatusFooter(state: VoiceState) {
    val (label, color) = when (state) {
        VoiceState.IDLE -> "Ready" to Color(0xFF3FB950)
        VoiceState.LISTENING -> "Listening..." to Color(0xFF58A6FF)
        VoiceState.PROCESSING -> "Thinking..." to Color(0xFFD29922)
        VoiceState.SPEAKING -> "Speaking..." to Color(0xFFA371F7)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161B22))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state != VoiceState.IDLE) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = color,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
