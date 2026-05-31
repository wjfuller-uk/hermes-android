package com.hermesandroid.bridge.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a widget card from a CardData object.
 * Uses WidgetRegistry to find the matching renderer by type.
 */
@Composable
fun WidgetCard(card: CardData, modifier: Modifier = Modifier) {
    val renderer = WidgetRegistry.get(card.type)

    if (renderer != null) {
        renderer(card.data)
    } else {
        // Fallback for unknown card types
        FallbackCard(card = card, modifier = modifier)
    }
}

@Composable
private fun FallbackCard(card: CardData, modifier: Modifier) {
    val summary = when (val data = card.data) {
        is String -> data.take(200)
        is Map<*, *> -> "Response with ${data.size} fields"
        is List<*> -> "${data.size} items"
        else -> "Card: ${card.type}"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1C2128),
        border = BorderStroke(1.dp, Color(0xFF30363D))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3FB950))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = card.type.ifBlank { "Card" },
                    color = Color(0xFF3FB950),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = summary,
                color = Color(0xFF8B949E),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}
