package com.hermesandroid.bridge.wake

import android.util.Log

/**
 * Simple voice activity detector — energy-based VAD.
 *
 * Triggers on sustained audio above a configurable RMS threshold.
 * Used as a gate before streaming PCM audio to the relay,
 * replacing continuous streaming with speech-detection-gated streaming.
 */
object WakeWordEngine {

    private const val TAG = "WakeWordEngine"

    /** Number of consecutive loud frames to trigger (avoid false positives). */
    private const val TRIGGER_FRAMES = 8  // ~256ms @ 512 samples/frame @ 16kHz

    /** RMS threshold for "loud enough" (0-32768). */
    private var threshold: Int = 800

    @Volatile
    var isInitialized: Boolean = true  // Always "initialized" — pure software
        private set

    private var loudFrameCount = 0

    fun init(context: android.content.Context): Boolean {
        com.hermesandroid.bridge.SettingsManager.init(context)
        threshold = com.hermesandroid.bridge.SettingsManager.vadThreshold
        Log.i(TAG, "Wake word engine using VAD fallback (threshold=$threshold)")
        isInitialized = true
        return true
    }

    /** Update sensitivity at runtime from settings. */
    fun setThreshold(value: Int) {
        threshold = value.coerceIn(200, 2000)
        loudFrameCount = 0
    }

    fun getThreshold(): Int = threshold

    fun process(pcm: ShortArray): Boolean {
        var sum = 0L
        for (sample in pcm) {
            sum += (sample.toLong() * sample)
        }
        val rms = kotlin.math.sqrt(sum.toDouble() / pcm.size)

        if (rms > threshold) {
            loudFrameCount++
            if (loudFrameCount >= TRIGGER_FRAMES) {
                loudFrameCount = 0
                Log.i(TAG, "VAD triggered (RMS=${rms.toInt()})")
                return true
            }
        } else {
            loudFrameCount = 0
        }
        return false
    }

    /** Calculate RMS for a raw byte buffer (16-bit PCM). */
    fun calculateRms(buffer: ByteArray, byteCount: Int): Float {
        var sum = 0L
        var i = 0
        while (i < byteCount - 1) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += (sample.toLong() * sample)
            i += 2
        }
        val count = byteCount / 2
        return if (count > 0) kotlin.math.sqrt(sum.toDouble() / count).toFloat() else 0f
    }

    /** Notify that speech was detected (for state tracking). */
    fun notifySpeech() {}

    /** Notify that silence was detected (for state tracking). */
    fun notifySilence() {}

    val frameLength: Int = 512

    val sampleRate: Int = 16000

    fun release() {
        loudFrameCount = 0
    }
}
