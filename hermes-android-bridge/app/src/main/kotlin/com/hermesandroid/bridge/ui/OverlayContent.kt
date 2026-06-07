package com.hermesandroid.bridge.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.bridge.ui.OverlayState

/** Hermes brand colors. */
private val HermesGreen = Color(0xFF00E676)
private val HermesBlue = Color(0xFF448AFF)
private val HermesPurple = Color(0xFFAA00FF)
private val HermesWhite = Color(0xFFE0E0E0)
private val DarkOverlay = Color(0xCC0A0A0A)  // ~80% opaque black
private val DimWhite = Color(0x88FFFFFF)

/** Google-style listening bar animation — four colored bars pulse in sequence. */
@Composable
fun ListeningAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "listening")

    val barWidth = 6.dp
    val maxHeight = 48.dp

    val bars = listOf(HermesGreen, HermesBlue, HermesPurple, HermesWhite)

    Row(
        modifier = modifier.padding(bottom = 80.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEachIndexed { index, color ->
            val delay = index * 150
            val height by infiniteTransition.animateFloat(
                initialValue = 8f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$index"
            )

            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height((maxHeight * height).coerceAtLeast(8.dp))
                    .padding(horizontal = 3.dp)
                    .background(color, RoundedCornerShape(3.dp))
            )
        }
    }
}

/** Large centered text for transcriptions and responses. */
@Composable
fun OverlayText(
    text: String,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    fontSize: Int = 28,
    color: Color = Color.White
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 48.dp).alpha(alpha),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = (fontSize * 1.4).sp,
            maxLines = 4
        )
    }
}

/** Google-style "Listening..." indicator. */
@Composable
fun ListeningOverlay(transcription: String, connected: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize().background(DarkOverlay),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (transcription.isNotBlank()) {
                OverlayText(
                    text = transcription,
                    fontSize = 24,
                    color = DimWhite
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = "Listening…",
                color = HermesGreen.copy(alpha = 0.7f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (!connected) {
                Text(
                    text = "⚠ Relay disconnected",
                    color = Color(0xFFFF1744),
                    fontSize = 12.sp
                )
            }
        }

        // Animated bars at the bottom center
        Box(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            contentAlignment = Alignment.BottomCenter
        ) {
            ListeningAnimation()
        }
    }
}

/** Processing/thinking overlay. */
@Composable
fun ProcessingOverlay(transcription: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "processing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier.fillMaxSize().background(DarkOverlay),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (transcription.isNotBlank()) {
                OverlayText(
                    text = transcription,
                    fontSize = 22,
                    color = DimWhite
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = "Thinking…",
                color = HermesBlue.copy(alpha = alpha),
                fontSize = 16.sp
            )
        }
    }
}

/** Response display — shows Hermes response text with optional cards. */
@Composable
fun ResponseOverlay(response: String, cards: List<DisplayCard>) {
    Box(
        modifier = Modifier.fillMaxSize().background(DarkOverlay),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            OverlayText(
                text = response,
                fontSize = 24,
                color = Color.White
            )

            if (cards.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                CardList(cards = cards)
            }
        }
    }
}

/** Stack of cards for reminders, weather, alerts, notifications. */
@Composable
fun CardList(cards: List<DisplayCard>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(0.85f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        cards.forEach { card -> RenderCard(card) }
    }
}

@Composable
fun RenderCard(card: DisplayCard) {
    val (borderColor, bgColor) = when (card.priority) {
        "high" -> HermesPurple.copy(alpha = 0.3f) to HermesPurple.copy(alpha = 0.1f)
        else -> HermesGreen.copy(alpha = 0.2f) to HermesGreen.copy(alpha = 0.08f)
    }

    val icon = when (card.type) {
        "reminder" -> "⏰"
        "weather" -> "🌤"
        "alert" -> "⚠️"
        "notification" -> "📋"
        "calendar" -> "📅"
        "music" -> "🎵"
        else -> "💬"
    }

    val titleColor = when (card.priority) {
        "high" -> HermesPurple
        else -> HermesGreen
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(borderColor, bgColor)
                ),
                RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = icon,
                fontSize = 24.sp
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = card.title,
                    color = titleColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                if (card.body.isNotBlank()) {
                    Text(
                        text = card.body,
                        color = DimWhite,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 3
                    )
                }
            }
        }
    }
}

/** Main composable — dispatches to the right sub-layout based on state. */
@Composable
fun HermesOverlayContent(
    state: OverlayState,
    transcription: String,
    response: String,
    cards: List<DisplayCard>,
    connected: Boolean
) {
    when (state) {
        OverlayState.LISTENING ->
            ListeningOverlay(transcription, connected)
        OverlayState.PROCESSING ->
            ProcessingOverlay(transcription)
        OverlayState.RESPONDING ->
            ResponseOverlay(response, cards)
        OverlayState.IDLE -> {
            // Empty — activity is finishing
        }
    }
}
