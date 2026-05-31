package com.hermesandroid.bridge.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a widget card from a ToolEvent.
 * Uses WidgetRegistry to find a matching widget.
 *
 * Shows:
 * - tool.started → inline "Checking calendar…" indicator
 * - tool.completed → full widget card (if matched) or fallback
 */
@Composable
fun WidgetCard(event: ToolEvent, modifier: Modifier = Modifier) {
    when (event.event) {
        "tool.started" -> ToolStartedIndicator(event = event, modifier = modifier)
        "tool.completed" -> ToolCompletedCard(event = event, modifier = modifier)
    }
}

@Composable
private fun ToolStartedIndicator(event: ToolEvent, modifier: Modifier) {
    val label = event.preview ?: when (event.tool) {
        "google_calendar" -> "Checking your calendar…"
        "calendar" -> "Checking your calendar…"
        "web_search" -> "Searching the web…"
        "terminal" -> "Running a command…"
        "shopify" -> "Checking Shopify…"
        "cron" -> "Checking scheduled jobs…"
        else -> "Working…"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1C2128),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color(0xFFF0883E),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = Color(0xFFF0883E),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = event.tool,
                    color = Color(0xFF484F58),
                    fontSize = 11.sp
                )
            }
            if (event.duration != null) {
                Text(
                    text = "${"%.1f".format(event.duration)}s",
                    color = Color(0xFF8B949E),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ToolCompletedCard(event: ToolEvent, modifier: Modifier) {
    val match = WidgetRegistry.match(event)

    if (match != null) {
        val (widget, data) = match
        if (data != null) {
            @Suppress("UNCHECKED_CAST")
            (widget.card as @Composable (Any, ToolEvent) -> Unit)(data, event)
        }
    } else {
        // Fallback: show tool name + raw output summary
        FallbackToolCard(event = event, modifier = modifier)
    }
}

@Composable
private fun FallbackToolCard(event: ToolEvent, modifier: Modifier) {
    val summary = when (val out = event.output) {
        is String -> out.take(200)
        is Map<*, *> -> "Response with ${out.size} fields"
        is List<*> -> "${out.size} items"
        else -> "Tool completed"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1C2128),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D))
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
                    text = event.tool.ifBlank { "Tool" },
                    color = Color(0xFF3FB950),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                if (event.duration != null) {
                    Text(
                        text = "${"%.1f".format(event.duration)}s",
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp
                    )
                }
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
