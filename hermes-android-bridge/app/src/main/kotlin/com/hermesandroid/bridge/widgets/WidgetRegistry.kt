package com.hermesandroid.bridge.widgets

import androidx.compose.runtime.Composable

/**
 * Widget card data model — mirrors the PRD's TypeScript WidgetDefinition.
 * A widget renders a card in the conversation feed when Hermes calls a tool.
 */
data class WidgetDefinition<T : Any>(
    /** Unique widget identifier */
    val id: String,

    /** Human-readable name */
    val name: String,

    /**
     * Called for every tool.completed event from Hermes.
     * Return extracted data if this widget should handle it,
     * or null to pass.
     */
    val matcher: (ToolEvent) -> T?,

    /**
     * Composable that renders the card.
     */
    val card: @Composable (data: T, event: ToolEvent) -> Unit,

    /** If true, show a notification badge when this widget renders */
    val notificationBadge: Boolean = false
)

/**
 * Represents a tool event from Hermes — tool.started or tool.completed.
 */
data class ToolEvent(
    val event: String,           // "tool.started" or "tool.completed"
    val runId: String? = null,
    val tool: String = "",       // e.g. "google_calendar", "web_search"
    val preview: String? = null, // human-readable label during execution
    val duration: Double? = null,
    val output: Any? = null      // raw tool output (can be Map, List, String)
)

/**
 * Registry of all available widget cards.
 * Widgets self-register via register().
 *
 * Usage:
 *   WidgetRegistry.register(calendarWidget)
 *   WidgetRegistry.register(jobsWidget)
 *
 * Then in the UI:
 *   val widget = WidgetRegistry.match(event)
 *   widget?.card?.invoke(data, event)
 */
object WidgetRegistry {
    private val _widgets = mutableListOf<WidgetDefinition<*>>()

    fun register(widget: WidgetDefinition<*>) {
        _widgets.add(widget)
    }

    fun all(): List<WidgetDefinition<*>> = _widgets.toList()

    /**
     * Find the first widget whose matcher returns non-null for this event.
     * Returns Pair(widget, extractedData) or null.
     */
    fun match(event: ToolEvent): Pair<WidgetDefinition<*>, Any?>? {
        for (widget in _widgets) {
            val data = widget.matcher(event)
            if (data != null) {
                return widget to data
            }
        }
        return null
    }
}
