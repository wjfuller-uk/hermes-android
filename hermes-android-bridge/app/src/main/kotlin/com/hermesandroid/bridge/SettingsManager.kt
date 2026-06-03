package com.hermesandroid.bridge

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent settings for Hermes Bridge. Backed by SharedPreferences.
 */
object SettingsManager {
    private const val PREFS_NAME = "hermes_bridge_settings"
    private const val KEY_VAD_THRESHOLD = "vad_threshold"
    private const val KEY_TTS_VOICE = "tts_voice"

    private const val DEFAULT_VAD_THRESHOLD = 800
    private const val DEFAULT_TTS_VOICE = "en-GB-LibbyNeural"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    var vadThreshold: Int
        get() = prefs?.getInt(KEY_VAD_THRESHOLD, DEFAULT_VAD_THRESHOLD) ?: DEFAULT_VAD_THRESHOLD
        set(value) { prefs?.edit()?.putInt(KEY_VAD_THRESHOLD, value)?.apply() }

    var ttsVoice: String
        get() = prefs?.getString(KEY_TTS_VOICE, DEFAULT_TTS_VOICE) ?: DEFAULT_TTS_VOICE
        set(value) { prefs?.edit()?.putString(KEY_TTS_VOICE, value)?.apply() }
}
