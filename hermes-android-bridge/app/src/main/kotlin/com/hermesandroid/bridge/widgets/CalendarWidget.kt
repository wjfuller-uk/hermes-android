package com.hermesandroid.bridge.widgets

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*

// ── Data models ─────────────────────────────────────────────────────────────

data class CalendarEvent(
    val title: String,
    val start: String,   // ISO datetime string
    val end: String? = null,
    val location: String? = null,
    val allDay: Boolean = false
)

data class CalendarData(
    val events: List<CalendarEvent>,
    val dateRange: String? = null  // e.g. "May 31 – Jun 6"
)

// ── Composable card (registered in RegisterWidgets.kt) ────────────────────

/** Parse raw card data (from relay JSON) into CalendarData */
fun parseCalendarData(data: Any?): CalendarData? {
    return when (data) {
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val raw = data["events"] as? List<Map<String, Any?>>
                ?: data["items"] as? List<Map<String, Any?>>
                ?: return null
            CalendarData(
                events = raw.mapNotNull { parseEvent(it) },
                dateRange = data["dateRange"] as? String
            )
        }
        else -> null
    }
}

private fun parseEvent(map: Map<String, Any?>): CalendarEvent? {
    val title = map["title"] as? String ?: map["summary"] as? String ?: return null
    val start = map["start"] as? String ?: (map["start"] as? Map<*, *>)?.get("dateTime") as? String ?: return null
    return CalendarEvent(
        title = title,
        start = start,
        end = map["end"] as? String ?: (map["end"] as? Map<*, *>)?.get("dateTime") as? String,
        location = map["location"] as? String,
        allDay = map["allDay"] as? Boolean ?: ((map["start"] as? Map<*, *>)?.containsKey("date") ?: false)
    )
}

// ── Calendar card composable ────────────────────────────────────────────────

@Composable
fun CalendarCard(
    data: CalendarData,
    modifier: Modifier = Modifier,
    onEventClick: (CalendarEvent) -> Unit = {}
) {
    val events = data.events.take(7) // max 7 events shown on card
    val today = LocalDate.now()
    val next7Days = (0..6).map { today.plusDays(it.toLong()) }
    val formatter = DateTimeFormatter.ofPattern("EEE d")

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1C2128),
        border = BorderStroke(1.dp, Color(0xFF30363D))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF0883E))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "📅 Calendar",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
                if (data.dateRange != null) {
                    Text(
                        text = data.dateRange,
                        color = Color(0xFF8B949E),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Week day strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                next7Days.forEach { day ->
                    val isToday = day == today
                    val dayName = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(40.dp)
                    ) {
                        Text(
                            text = dayName,
                            color = if (isToday) Color(0xFFF0883E) else Color(0xFF8B949E),
                            fontSize = 11.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        )
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isToday) Color(0xFFF0883E).copy(alpha = 0.2f)
                                    else Color.Transparent
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = day.dayOfMonth.toString(),
                                color = if (isToday) Color(0xFFF0883E) else Color.White,
                                fontSize = 14.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF21262D))
            )

            Spacer(Modifier.height(12.dp))

            // Event chips
            if (events.isEmpty()) {
                Text(
                    text = "No events this week",
                    color = Color(0xFF484F58),
                    fontSize = 13.sp
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    events.forEach { event ->
                        EventChip(event = event, onClick = { onEventClick(event) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EventChip(
    event: CalendarEvent,
    onClick: () -> Unit
) {
    val colors = listOf(
        Color(0xFFF0883E), Color(0xFF58A6FF),
        Color(0xFF3FB950), Color(0xFFA371F7),
        Color(0xFFD29922), Color(0xFFF85149)
    )
    val colorIndex = (event.title.hashCode() and Int.MAX_VALUE) % colors.size
    val accentColor = colors[colorIndex]

    // Parse display time
    val displayTime = try {
        val instant = java.time.Instant.parse(event.start)
        val local = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        if (event.allDay) "All day"
        else local.format(DateTimeFormatter.ofPattern("HH:mm"))
    } catch (_: Exception) { "" }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF161B22)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (event.location != null) {
                    Text(
                        text = "📍 ${event.location}",
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (displayTime.isNotEmpty()) {
                Text(
                    text = displayTime,
                    color = accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
