package com.hermesandroid.bridge.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.hermesandroid.bridge.client.RelayClient
import kotlinx.coroutines.*

/**
 * Captures raw PCM audio from the mic and streams it to the relay server.
 *
 * Unlike the old VoiceService, this is a targeted capture:
 * - Called by the overlay when it's time to listen (after wake word detection)
 * - Streams for a fixed duration, then auto-stops
 * - Relay handles all STT/TTS processing
 *
 * Audio format: 16kHz, mono, 16-bit PCM signed LE
 */
class AudioCapture {

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_DURATION_MS = 10_000L  // 10 seconds max
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var isCapturing: Boolean = false
        private set

    /** Called on each chunk of PCM data. Runs on IO thread. */
    var onAudioChunk: ((ByteArray) -> Unit)? = null

    /** Called when capture completes (auto-stop or explicit stop). Runs on IO thread. */
    var onCaptureComplete: (() -> Unit)? = null

    /** Start capturing audio and streaming to the relay. */
    fun start(onError: (String) -> Unit = {}): Boolean {
        if (isCapturing) return false

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE * 2)  // 1 second buffer

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("AudioRecord failed to initialize")
                return false
            }

            audioRecord?.startRecording()
            isCapturing = true

            // Auto-stop after max duration
            scope.launch {
                delay(MAX_DURATION_MS)
                if (isCapturing) {
                    Log.i(TAG, "Auto-stopping after ${MAX_DURATION_MS}ms")
                    stop()
                }
            }

            // Stream PCM frames to relay
            captureJob = scope.launch {
                val frameSamples = SAMPLE_RATE * 40 / 1000  // 40ms frames
                val frameBytes = frameSamples * 2
                val buffer = ByteArray(frameBytes)

                while (isActive && isCapturing) {
                    val bytesRead = audioRecord?.read(buffer, 0, frameBytes) ?: -1
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        RelayClient.sendBinary(chunk)
                        onAudioChunk?.invoke(chunk)
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord read error: $bytesRead")
                        break
                    }
                }
            }

            // Notify relay
            RelayClient.sendCommand("POST", "/voice/start")
            Log.i(TAG, "Audio capture started (${bufferSize}B buffer)")
            return true
        } catch (e: SecurityException) {
            onError("Microphone permission denied")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            onError("Mic error: ${e.message}")
            return false
        }
    }

    /** Stop capturing and notify relay. */
    fun stop() {
        val wasCapturing = isCapturing
        isCapturing = false

        captureJob?.cancel()
        captureJob = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null

        if (wasCapturing) {
            try {
                if (RelayClient.isConnected) {
                    RelayClient.sendCommand("POST", "/voice/stop")
                }
            } catch (_: Exception) {}
            onCaptureComplete?.invoke()
        }
    }

    /** Release all resources. */
    fun destroy() {
        stop()
        scope.cancel()
    }
}
