package com.hermesandroid.bridge.widgets

/**
 * Call once at app startup to register all built-in widget renderers.
 * Pattern: WidgetRegistry.register("type") { data -> ComposableCard(data) }
 */
fun registerAllWidgets() {
    WidgetRegistry.register("calendar") { data ->
        val calendarData = parseCalendarData(data)
        if (calendarData != null) {
            CalendarCard(data = calendarData)
        }
    }
    // Future:
    // WidgetRegistry.register("jobs") { data -> JobsCard(parseJobsData(data)) }
    // WidgetRegistry.register("search") { data -> SearchCard(parseSearchData(data)) }
    // WidgetRegistry.register("image") { data -> ImageCard(parseImageData(data)) }
}
