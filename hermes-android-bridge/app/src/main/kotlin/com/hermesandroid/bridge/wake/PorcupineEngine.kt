package com.hermesandroid.bridge.wake

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * Wake word detection using Picovoice Porcupine v4.x (manual AudioRecord).
 *
 * Loads a custom .ppn model from res/raw/hey_bob_tv.ppn, falling back
 * to the built-in "Jarvis" keyword if the custom model isn't available.
 *
 * Porcupine processes raw PCM frames (16kHz, mono, 16-bit). We handle
 * mic capture with AudioRecord on an IO thread and feed frames to Porcupine.
 *
 * Lifecycle: create → start() → callback on wake word → stop() → delete()
 *
 * Thread safety: mic capture runs on IO dispatcher. Callback fires on IO dispatcher.
 */
class PorcupineEngine(
    private val appContext: Context,
    private val onWakeWord: () -> Unit
) {
    companion object {
        private const val TAG = "PorcupineEngine"

        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Minimum seconds between wake word triggers (prevents double-taps). */
        const val COOLDOWN_MS = 3_000L

        /** Custom wake word model filename in res/raw/. */
        private const val CUSTOM_MODEL = "hey_bob_tv"
    }

    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenJob: Job? = null
    private var lastTriggerTime = 0L

    @Volatile
    var isListening: Boolean = false
        private set

    /** Start wake word detection. Safe to call multiple times. */
    fun start() {
        if (isListening) return
        if (porcupine != null) return

        scope.launch {
            try {
                initPorcupine()
                initAudioRecord()
                isListening = true
                Log.i(TAG, "Wake word detection started")
                runLoop()
            } catch (e: PorcupineException) {
                Log.e(TAG, "Failed to initialize Porcupine", e)
                scope.launch(Dispatchers.Main) {
                    onWakeWord()  // Fallback
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio capture", e)
            }
        }
    }

    /** Stop wake word detection and release resources. */
    fun stop() {
        isListening = false
        listenJob?.cancel()
        listenJob = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        porcupine?.delete()
        porcupine = null
        Log.d(TAG, "Wake word detection stopped")
    }

    /** Release all resources. */
    fun destroy() {
        stop()
        scope.cancel()
    }

    /** Resume detection after overlay dismisses. */
    fun resume() {
        if (porcupine == null) return
        scope.launch {
            try {
                initAudioRecord()
                isListening = true
                runLoop()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resume wake word detection", e)
            }
        }
    }

    // ── Initialization ──────────────────────────────────────────────────────

    private fun initPorcupine() {
        val ppnPath = copyModelToFile()
        if (ppnPath != null) {
            Log.i(TAG, "Using custom wake word model: $ppnPath")
            porcupine = Porcupine.Builder()
                .setKeywordPath(ppnPath)
                .build(appContext)
        } else {
            Log.w(TAG, "Custom model not found, using built-in 'Jarvis'")
            porcupine = Porcupine.Builder()
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .build(appContext)
        }
    }

    private fun initAudioRecord() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer, 2048)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw RuntimeException("AudioRecord failed to initialize")
        }
        audioRecord?.startRecording()
    }

    /**
     * Extract the custom .ppn model from res/raw to a temp file.
     * Returns the file path, or null if the resource doesn't exist.
     */
    private fun copyModelToFile(): String? {
        return try {
            val resId = appContext.resources.getIdentifier(
                CUSTOM_MODEL, "raw", appContext.packageName
            )
            if (resId == 0) {
                Log.w(TAG, "Resource $CUSTOM_MODEL not found in res/raw/")
                return null
            }

            val inputStream = appContext.resources.openRawResource(resId)
            val outFile = File(appContext.filesDir, "$CUSTOM_MODEL.ppn")
            if (outFile.exists() && outFile.length() > 0) {
                inputStream.close()
                return outFile.absolutePath
            }

            FileOutputStream(outFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            outFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy custom model", e)
            null
        }
    }

    // ── Recording loop ──────────────────────────────────────────────────────

    private fun runLoop() {
        val p = porcupine!!  // Must have been initialized by this point
        val recorder = audioRecord!!
        val frameLength = p.frameLength
        val buffer = ShortArray(frameLength)

        listenJob = scope.launch {
            try {
                while (isActive && isListening) {
                    val readCount = recorder.read(buffer, 0, frameLength)
                    if (readCount < frameLength) {
                        Log.w(TAG, "Incomplete audio frame: $readCount/$frameLength")
                        continue
                    }

                    val keywordIndex = p.process(buffer)
                    if (keywordIndex >= 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastTriggerTime < COOLDOWN_MS) {
                            Log.d(TAG, "Wake word ignored (cooldown)")
                            continue
                        }
                        lastTriggerTime = now
                        Log.i(TAG, "Wake word detected! (index=$keywordIndex)")

                        // Pause detection to avoid double-fire
                        isListening = false
                        try { recorder.stop() } catch (_: Exception) {}

                        scope.launch(Dispatchers.Main) {
                            onWakeWord()
                        }
                        return@launch
                    }

                    yield()  // Cooperate with cancellation
                }
            } catch (e: PorcupineException) {
                Log.e(TAG, "Porcupine processing error", e)
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
            }
        }
    }
}
