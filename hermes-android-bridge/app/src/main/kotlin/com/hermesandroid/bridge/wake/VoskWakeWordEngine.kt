package com.hermesandroid.bridge.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Wake word detection using Vosk with manual AudioRecord.
 *
 * Uses VOICE_COMMUNICATION source with 10x pre-gain to compensate for
 * the Thomson 270's very quiet built-in mic.
 *
 * Architecture:
 *   Mic (VOICE_COMMUNICATION, 10x pre-gain) → Vosk Recognizer → keyword match → onWakeWord()
 */
class VoskWakeWordEngine(
    private val appContext: Context,
    private val onWakeWord: () -> Unit
) {
    companion object {
        private const val TAG = "VoskWakeWord"
        private const val MODEL_DIR = "vosk-model-small-en-us"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        val WAKE_PHRASES = listOf("hey bob", "hey bop", "hay bob", "hey bo")
        const val COOLDOWN_MS = 3_000L
        const val PRE_GAIN = 15  // Thomson 270 mic is very quiet — 10x wasn't enough
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listenThread: Thread? = null
    private var lastTriggerTime = 0L

    @Volatile
    var isListening = false
        private set

    fun start() {
        if (isListening) return
        Log.i(TAG, "Initializing Vosk wake word engine...")

        StorageService.unpack(appContext, MODEL_DIR, "model",
            { loadedModel ->
                model = loadedModel
                startListening()
            },
            { exception ->
                Log.e(TAG, "Failed to unpack Vosk model", exception)
                onWakeWord()
            }
        )
    }

    fun stop() {
        isListening = false
        listenThread?.interrupt()
        listenThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        recognizer?.close()
        recognizer = null
        Log.d(TAG, "Vosk wake word detection stopped")
    }

    fun destroy() {
        stop()
        model?.close()
        model = null
    }

    private fun startListening() {
        val m = model ?: return
        isListening = true

        try {
            recognizer = Recognizer(m, SAMPLE_RATE.toFloat())
            recognizer?.setMaxAlternatives(0)
            recognizer?.setPartialWords(true)
            recognizer?.setWords(true)

            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBuffer, SAMPLE_RATE * 2)  // 1 second buffer

            // Use VOICE_COMMUNICATION source (same as old VoiceService — works on Amlogic)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize with VOICE_COMMUNICATION, trying MIC")
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize with MIC too")
                    isListening = false
                    return
                }
            }

            audioRecord?.startRecording()

            listenThread = Thread({
                val buffer = ShortArray(4000)  // ~250ms at 16kHz
                Log.i(TAG, "Vosk wake word detection started (VOICE_COMMUNICATION, ${PRE_GAIN}x pre-gain)")

                while (isListening && !Thread.interrupted()) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readCount <= 0) continue

                    // Apply pre-gain
                    for (i in 0 until readCount) {
                        val amplified = buffer[i].toInt() * PRE_GAIN
                        buffer[i] = amplified.coerceIn(-32768, 32767).toShort()
                    }

                    // Convert to bytes for Vosk (PCM 16-bit LE)
                    val bytes = ByteArray(readCount * 2)
                    for (i in 0 until readCount) {
                        bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }

                    // Feed to Vosk recognizer — note: acceptWaveForm (capital F)
                    val isFinal = recognizer?.acceptWaveForm(bytes, bytes.size) ?: false
                    if (isFinal) {
                        val result = recognizer?.result ?: ""
                        checkForKeyword(result)
                    } else {
                        val partial = recognizer?.partialResult ?: ""
                        checkForKeyword(partial)
                    }
                }
            }, "VoskWakeWordListen")
            listenThread?.isDaemon = true
            listenThread?.start()

        } catch (e: IOException) {
            Log.e(TAG, "Failed to start Vosk speech service", e)
            isListening = false
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission denied", e)
            isListening = false
        }
    }

    private fun checkForKeyword(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < COOLDOWN_MS) return

        // Vosk returns JSON like {"text": "hey bob"} or {"partial": "hey"}
        val cleanText = text
            .replace(Regex("\"(text|partial)\":\\s*\""), "")
            .replace(Regex("\"$"), "")
            .replace(Regex("[{}\"]"), "")
            .trim()
            .lowercase()

        if (cleanText.isBlank()) return

        Log.d(TAG, "Vosk heard: $cleanText")

        for (phrase in WAKE_PHRASES) {
            if (cleanText.contains(phrase)) {
                lastTriggerTime = now
                Log.i(TAG, "Wake word detected! (text=\"$cleanText\", matched=\"$phrase\")")
                onWakeWord()
                return
            }
        }
    }
}
