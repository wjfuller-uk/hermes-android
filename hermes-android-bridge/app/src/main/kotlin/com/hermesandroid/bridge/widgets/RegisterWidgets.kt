package com.hermesandroid.bridge.widgets

/**
 * Call once at app startup to register all built-in widget renderers.
 */
fun registerAllWidgets() {
    WidgetRegistry.register("calendar") { data ->
        val calendarData = data as? CalendarData ?: return@register
        CalendarCard(data = calendarData)
    }
    // Future: WidgetRegistry.register("jobs") { ... }
    // Future: WidgetRegistry.register("search") { ... }
    // Future: WidgetRegistry.register("image") { ... }
}
