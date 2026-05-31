package com.hermesandroid.bridge.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
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

    fun setConnected(connected: Boolean) { isConnected = connected }

    fun setVoiceState(state: VoiceState) { voiceState = state }

    fun addMessage(text: String, isUser: Boolean) {
        messages = messages + ChatMessage(text = text, isUser = isUser)
    }

    fun clearMessages() { messages = emptyList() }
}

// ── Main screen ───────────────────────────────────────────────────────────────

@Composable
fun VoiceAssistantScreen(
    viewModel: VoiceViewModel = viewModel(),
    onDisconnect: () -> Unit = {}
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
                onDisconnect = onDisconnect
            )

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
                if (viewModel.messages.isEmpty()) {
                    item {
                        EmptyStateHint()
                    }
                }
            }

            // ── Waveform bar ──
            VoiceWaveform(state = viewModel.voiceState)

            // ── Status footer ──
            StatusFooter(state = viewModel.voiceState)
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
fun VoiceHeader(
    state: VoiceState,
    isConnected: Boolean,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Status dot
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
            Text(
                text = "Hermes",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", color = Color(0xFF8B949E), fontSize = 13.sp)
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
