package com.hermesandroid.bridge.widgets

/**
 * Call once at app startup to register all built-in widgets.
 * New widgets are added by registering them here.
 */
fun registerAllWidgets() {
    WidgetRegistry.register(calendarWidget)
    // Future widgets:
    // WidgetRegistry.register(jobsWidget)
    // WidgetRegistry.register(webSearchWidget)
    // WidgetRegistry.register(imageWidget)
}
