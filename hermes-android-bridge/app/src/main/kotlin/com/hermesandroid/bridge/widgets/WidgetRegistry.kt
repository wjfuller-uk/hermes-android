package com.hermesandroid.bridge.widgets

import androidx.compose.runtime.Composable

/**
 * Card data injected by the relay alongside a chat response.
 * Mirrors TBFAI's pattern: response text + optional cards array.
 *
 * Example relay JSON:
 * {
 *   "type": "chat_response",
 *   "text": "Here's your week...",
 *   "cards": [{ "type": "calendar", "data": { "events": [...] } }]
 * }
 */
data class CardData(
    val type: String,       // "calendar", "jobs", "search", "image", "plain"
    val data: Any? = null   // Widget-specific payload (Map, List, String, etc.)
)

/**
 * Registry: card type → composable renderer.
 * Simpler than the PRD's ToolEvent matcher — just a type lookup.
 */
object WidgetRegistry {
    private val _renderers = mutableMapOf<String, @Composable (Any?) -> Unit>()

    fun register(type: String, renderer: @Composable (Any?) -> Unit) {
        _renderers[type] = renderer
    }

    fun get(type: String): (@Composable (Any?) -> Unit)? = _renderers[type]
}
