package com.hermesandroid.bridge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.widgets.CardData
import com.hermesandroid.bridge.BuildConfig
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

// ═══════════════════════════════════════════════════════════════════════════════
// Theme
// ═══════════════════════════════════════════════════════════════════════════════

private val BG       = Color(0xFF0A0E14)
private val Surface  = Color(0xFF151A22)
private val Card     = Color(0xFF1A1F2B)
private val BubbleH  = Color(0xFF1A1F2B)  // Hermes bubble
private val BubbleU  = Color(0xFF1B4332)  // User bubble
private val Accent   = Color(0xFF3B82F6)
private val Green    = Color(0xFF22C55E)
private val Amber    = Color(0xFFF59E0B)
private val Purple   = Color(0xFFA855F7)
private val Orange   = Color(0xFFF97316)
private val Red      = Color(0xFFEF4444)
private val Text1    = Color(0xFFE6EDF3)
private val Text2    = Color(0xFF8B949E)
private val Text3    = Color(0xFF484F58)
private val Border   = Color(0xFF21262D)

// ═══════════════════════════════════════════════════════════════════════════════
// Data model
// ═══════════════════════════════════════════════════════════════════════════════

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

enum class VoiceState { IDLE, LISTENING, PROCESSING, SPEAKING }

// ═══════════════════════════════════════════════════════════════════════════════
// ViewModel
// ═══════════════════════════════════════════════════════════════════════════════

class VoiceViewModel : ViewModel() {
    var voiceState by mutableStateOf(VoiceState.IDLE)
        private set
    var messages by mutableStateOf(listOf<ChatMessage>())
        private set
    var toolEvents by mutableStateOf(listOf<CardData>())
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
    var partialTranscript by mutableStateOf("")
        private set
    var voiceStatusMessage by mutableStateOf("")
    var isThinking by mutableStateOf(false)

    fun updateConnection(connected: Boolean) { isConnected = connected }
    fun updateDeviceName(name: String) { deviceName = name }

    fun updateState(state: VoiceState) {
        voiceState = state
        if (state == VoiceState.IDLE) isThinking = false
    }
    fun updateMicLevel(level: Float) { micLevel = level }

    fun updateTranscript(text: String, isFinal: Boolean) {
        if (isFinal) {
            partialTranscript = ""
            if (text.isNotBlank()) {
                messages = messages + ChatMessage(text = text, isUser = true)
                isThinking = true       // Hermes is about to respond
                updateState(VoiceState.PROCESSING)
            }
        } else {
            partialTranscript = text
        }
    }

    fun addCards(cards: List<CardData>) {
        toolEvents = toolEvents + cards
    }

    fun togglePushToTalk() { isPushToTalk = !isPushToTalk }
    fun toggleAlwaysOn() { isAlwaysOn = !isAlwaysOn }

    fun addMessage(text: String, isUser: Boolean) {
        messages = messages + ChatMessage(text = text, isUser = isUser)
        if (isUser) isThinking = true
        else isThinking = false
    }

    fun clearMessages() { messages = emptyList(); toolEvents = emptyList() }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Markdown parser
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Lightweight markdown → AnnotatedString for chat bubbles.
 * Handles **bold**, *italic*, `code`, ```code blocks```,
 * [text](url), # headers, - bullets, 1. numbered lists.
 */
fun buildMarkdown(text: String, baseColor: Color = Text1, baseSize: Int = 15): AnnotatedString {
    val lines = text.lines()
    var inCodeBlock = false

    return buildAnnotatedString {
        lines.forEachIndexed { i, line ->
            if (i > 0) append("\n")

            when {
                // Code block toggle
                line.trimStart().startsWith("```") -> {
                    inCodeBlock = !inCodeBlock
                    // Don't render the backticks themselves
                }
                inCodeBlock -> {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        background = Color(0xFF0D1117),
                        color = Color(0xFFC9D1D9)
                    )) {
                        append(line)
                    }
                }
                // Headers
                line.startsWith("### ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseSize + 1).sp, color = Text1)) {
                        append(line.removePrefix("### "))
                    }
                }
                line.startsWith("## ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseSize + 2).sp, color = Text1)) {
                        append(line.removePrefix("## "))
                    }
                }
                line.startsWith("# ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseSize + 3).sp, color = Text1)) {
                        append(line.removePrefix("# "))
                    }
                }
                // Bullet list and other text
                else -> {
                    val trimmed = line.trimStart()
                    when {
                        trimmed.startsWith("- ") -> {
                            append("  •  ")
                            append(parseInline(trimmed.removePrefix("- "), baseColor, baseSize))
                        }
                        trimmed.startsWith("* ") && !trimmed.startsWith("**") -> {
                            append("  •  ")
                            append(parseInline(trimmed.removePrefix("* "), baseColor, baseSize))
                        }
                        else -> {
                            // Numbered list
                            val match = Regex("""^(\d+)\.\s""").find(trimmed)
                            if (match != null) {
                                append("  ${match.groupValues[1]}.  ")
                                append(parseInline(trimmed.removePrefix(match.value), baseColor, baseSize))
                            } else {
                                append(parseInline(trimmed, baseColor, baseSize))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseInline(text: String, baseColor: Color, baseSize: Int): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Bold **text**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Italic *text* (but not the start of **)
                text.startsWith("*", i) && !text.startsWith("**", i) -> {
                    val end = text.indexOf("*", i + 1)
                    if (end != -1 && end > i + 1 && text[i - 1] != '*' && i > 0) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Inline code `text`
                text.startsWith("`", i) -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseSize - 1).sp,
                            background = Color(0xFF0D1117).copy(alpha = 0.7f),
                            color = Color(0xFFF97316)
                        )) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Link [text](url)
                text.startsWith("[", i) -> {
                    val close = text.indexOf("](", i)
                    val end = if (close != -1) text.indexOf(")", close + 2) else -1
                    if (close != -1 && end != -1) {
                        val linkText = text.substring(i + 1, close)
                        val url = text.substring(close + 2, end)
                        pushStringAnnotation("URL", url)
                        withStyle(SpanStyle(
                            color = Accent,
                            textDecoration = TextDecoration.Underline
                        )) {
                            append(linkText)
                        }
                        pop()
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Main screen
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun VoiceAssistantScreen(
    viewModel: VoiceViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onConnect: (url: String) -> Unit = {},
    onDisconnect: () -> Unit = {},
    onSendText: (String) -> Unit = {},
    onStartVoice: () -> Unit = {},
    onStopVoice: () -> Unit = {}
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Auto-scroll when new messages arrive or thinking state changes
    val scrollTarget = viewModel.messages.size + viewModel.toolEvents.size +
            (if (viewModel.isThinking) 1 else 0) +
            (if (viewModel.partialTranscript.isNotBlank()) 1 else 0)
    LaunchedEffect(scrollTarget) {
        if (scrollTarget > 0) {
            listState.animateScrollToItem(scrollTarget)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BG
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ──
            VoiceHeader(
                state = viewModel.voiceState,
                isConnected = viewModel.isConnected,
                deviceName = viewModel.deviceName,
                onOpenSettings = onOpenSettings,
                onOpenDiagnostics = onOpenDiagnostics,
                onDisconnect = onDisconnect,
                onClear = { viewModel.clearMessages() }
            )

            // ── Connection UI ──
            if (!viewModel.isConnected) {
                ConnectionPanel(onConnect = onConnect)
            }

            // ── Messages feed ──
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(viewModel.messages) { index, msg ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(300)) +
                                slideInVertically(animationSpec = tween(300)) { it / 4 }
                    ) {
                        if (msg.isUser) {
                            UserBubble(message = msg)
                        } else {
                            HermesBubble(
                                message = msg,
                                onLinkClick = { url ->
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            )
                        }
                    }
                }

                // Thinking indicator
                if (viewModel.isThinking) {
                    item { ThinkingDots() }
                }

                // Live transcript
                if (viewModel.partialTranscript.isNotBlank()) {
                    item { GhostTranscript(text = viewModel.partialTranscript) }
                }

                // Empty state
                if (viewModel.messages.isEmpty() && viewModel.toolEvents.isEmpty() && viewModel.isConnected) {
                    item { WelcomeHint() }
                }

                // Widget cards
                items(viewModel.toolEvents) { card ->
                    com.hermesandroid.bridge.widgets.WidgetCard(card = card)
                }
            }

            // ── Unified bottom bar ──
            if (viewModel.isConnected) {
                ChatBottomBar(
                    voiceState = viewModel.voiceState,
                    isPushToTalk = viewModel.isPushToTalk,
                    onSendText = onSendText,
                    onStartVoice = onStartVoice,
                    onStopVoice = onStopVoice
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Connection panel
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ConnectionPanel(onConnect: (url: String) -> Unit) {
    var serverUrl by remember { mutableStateOf(RelayClient.serverUrl ?: "ws://100.111.44.87:8766") }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = Surface
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⬡", fontSize = 24.sp, color = Accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Connect to Hermes",
                    color = Text1,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                placeholder = { Text("ws://100.111.44.87:8766", color = Text3) },
                label = { Text("Server URL", color = Text2) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Text1,
                    unfocusedTextColor = Text1,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    cursorColor = Accent
                )
            )
            Button(
                onClick = {
                    if (serverUrl.isNotBlank()) onConnect(serverUrl.trim())
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Connect", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Header
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun VoiceHeader(
    state: VoiceState,
    isConnected: Boolean,
    deviceName: String,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onDisconnect: () -> Unit,
    onClear: () -> Unit = {}
) {
    val statusColor = when {
        !isConnected -> Red
        state == VoiceState.LISTENING -> Accent
        state == VoiceState.PROCESSING -> Amber
        state == VoiceState.SPEAKING -> Purple
        else -> Green
    }
    val statusLabel = when {
        !isConnected -> "Offline"
        state == VoiceState.LISTENING -> "Listening"
        state == VoiceState.PROCESSING -> "Thinking"
        state == VoiceState.SPEAKING -> "Speaking"
        else -> "Connected"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Surface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: status + title
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Animated status dot
                val dotAlpha by if (state != VoiceState.IDLE && state != VoiceState.LISTENING) {
                    rememberInfiniteTransition().animateFloat(
                        initialValue = 0.5f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse)
                    )
                } else remember { mutableFloatStateOf(1f) }

                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = dotAlpha))
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Hermes",
                        color = Text1,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            color = Text3,
                            fontSize = 11.sp
                        )
                        if (isConnected && deviceName.isNotBlank()) {
                            Text(
                                " • $deviceName",
                                color = Text3,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            " • $statusLabel",
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Right: actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderIcon("🔍", onClick = onOpenDiagnostics)
                Spacer(Modifier.width(4.dp))
                HeaderIcon("⚙", onClick = onOpenSettings)
                if (isConnected) {
                    Spacer(Modifier.width(4.dp))
                    HeaderIcon("🗑", onClick = onClear)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDisconnect) {
                        Text("Leave", color = Text2, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderIcon(emoji: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable { onClick() }
            .background(Border),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 15.sp)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Message bubbles
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun HermesBubble(message: ChatMessage, onLinkClick: (String) -> Unit = {}) {
    val annotated = remember(message.text) { buildMarkdown(message.text) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val uriHandler = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text("⬡", fontSize = 14.sp, color = Accent)
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 4.dp, topEnd = 16.dp,
                    bottomStart = 16.dp, bottomEnd = 16.dp
                ),
                color = BubbleH,
                border = BorderStroke(1.dp, Border)
            ) {
                // Use ClickableText to handle URL annotations
                ClickableText(
                    text = annotated,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = TextStyle(
                        color = Text1,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    onClick = { offset ->
                        annotated.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()?.let { annotation ->
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                                uriHandler.startActivity(intent)
                            }
                    }
                )
            }
            Text(
                text = timeFmt.format(Date(message.timestamp)),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                color = Text3,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun UserBubble(message: ChatMessage) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 4.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp
            ),
            color = BubbleU,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = Color(0xFFDCFCE7),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
        Text(
            text = timeFmt.format(Date(message.timestamp)),
            modifier = Modifier.padding(end = 4.dp, top = 2.dp),
            color = Text3,
            fontSize = 10.sp
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Thinking indicator
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ThinkingDots() {
    val dotCount = 3
    val transition = rememberInfiniteTransition()
    val delays = listOf(0, 200, 400)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, top = 8.dp, end = 0.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = BubbleH,
            border = BorderStroke(1.dp, Border)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(dotCount) { i ->
                    val alpha by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = delays[i]),
                            repeatMode = RepeatMode.Reverse
                        )
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Accent.copy(alpha = alpha))
                    )
                    if (i < dotCount - 1) Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Live transcript (ghost bubble)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun GhostTranscript(text: String) {
    val dotAlpha by rememberInfiniteTransition().animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            color = BubbleU.copy(alpha = 0.4f),
            border = BorderStroke(1.dp, Green.copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = text,
                    color = Text1.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Orange.copy(alpha = dotAlpha))
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Welcome state
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun WelcomeHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = Accent.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("⬡", fontSize = 36.sp, color = Accent.copy(alpha = 0.5f))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Hermes is ready",
                color = Text1,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Ask anything or tap the mic to speak",
                color = Text2,
                fontSize = 14.sp
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Unified chat + voice bottom bar
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ChatBottomBar(
    voiceState: VoiceState,
    isPushToTalk: Boolean,
    onSendText: (String) -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    val isListening = voiceState == VoiceState.LISTENING
    val isProcessing = voiceState == VoiceState.PROCESSING
    val isSpeaking = voiceState == VoiceState.SPEAKING
    val isVoiceActive = isListening || isProcessing || isSpeaking

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Voice button (mic)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isVoiceActive -> if (isSpeaking) Purple else if (isListening) Red else Amber
                            else -> Border
                        }
                    )
                    .clickable {
                        if (isVoiceActive) onStopVoice()
                        else onStartVoice()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        isSpeaking -> "🔊"
                        isListening -> "⏹"
                        isProcessing -> "⏳"
                        else -> "🎤"
                    },
                    fontSize = 18.sp,
                    color = if (isVoiceActive) Color.White else Text2
                )
            }

            Spacer(Modifier.width(8.dp))

            // Text input
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                color = Card,
                border = BorderStroke(1.dp, Border)
            ) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            if (isListening) "Listening..." else "Type a message...",
                            color = Text3,
                            fontSize = 14.sp
                        )
                    },
                    modifier = Modifier.height(44.dp),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Text1,
                        unfocusedTextColor = Text1,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Accent
                    ),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Send button
            val hasText = text.isNotBlank()
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (hasText) Accent else Border)
                    .clickable(enabled = hasText) {
                        if (hasText) {
                            onSendText(text.trim())
                            text = ""
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↑",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hasText) Color.White else Text3
                )
            }
        }
    }
}
