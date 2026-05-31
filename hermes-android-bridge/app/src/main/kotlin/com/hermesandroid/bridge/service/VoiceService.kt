package com.hermesandroid.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.hermesandroid.bridge.MainActivity
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.util.AppLogger
import kotlinx.coroutines.*

/**
 * Foreground service that captures speech via on-device SpeechRecognizer
 * and streams transcripts to the UI in real time.
 *
 * Architecture (v2 — on-device STT):
 *   Mic → SpeechRecognizer → partial transcripts → RelayClient.onTranscript (UI)
 *                         → final transcript → RelayClient.sendChat() → Hermes
 *
 * Falls back to raw PCM streaming if SpeechRecognizer is unavailable.
 */
class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        const val CHANNEL_ID = "hermes_voice_channel"
        const val NOTIFICATION_ID = 2001

        // Audio format for PCM fallback
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_MS = 40
        val FRAME_SAMPLES: Int = SAMPLE_RATE * FRAME_MS / 1000
        val FRAME_BYTES: Int = FRAME_SAMPLES * 2

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recognizerIntent: Intent? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppLogger.i(TAG, "VoiceService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        startListening()
        AppLogger.i(TAG, "VoiceService started — on-device speech recognition")
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        scope.cancel()
        isRunning = false
        AppLogger.i(TAG, "VoiceService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Speech recognition (primary path) ──────────────────────────────────

    private fun startListening() {
        if (isRunning) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            AppLogger.w(TAG, "SpeechRecognizer not available — falling back to PCM streaming")
            RelayClient.notifyVoiceStatus("On-device speech not available — using server transcription", isError = false)
            fallbackToRawAudio()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(VoiceRecognitionListener())

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800)
            // Prefer on-device recognition (no network)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        try {
            speechRecognizer?.startListening(recognizerIntent)
            isRunning = true
            AppLogger.i(TAG, "SpeechRecognizer started — listening for speech")
        } catch (e: Exception) {
            AppLogger.e(TAG, "SpeechRecognizer failed to start: ${e.message}", e)
            fallbackToRawAudio()
        }
    }

    private fun stopListening() {
        isRunning = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error stopping SpeechRecognizer: ${e.message}")
        }
        speechRecognizer = null
        stopRawAudio()
    }

    private fun restartListening() {
        if (!isRunning) return
        try {
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error restarting SpeechRecognizer: ${e.message}")
            restartListeningDelayed()
        }
    }

    private fun restartListeningDelayed(delayMs: Long = 500) {
        scope.launch {
            delay(delayMs)
            if (isRunning) {
                try {
                    speechRecognizer?.startListening(recognizerIntent)
                } catch (_: Exception) { }
            }
        }
    }

    // ── Fallback: raw PCM streaming (when SpeechRecognizer unavailable) ────

    private fun fallbackToRawAudio() {
        AppLogger.i(TAG, "Switching to raw PCM audio streaming fallback")
        stopListening() // clean up any partial SpeechRecognizer state
        startRawAudio()
    }

    private fun startRawAudio() {
        if (isRunning) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, FRAME_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.e(TAG, "AudioRecord failed to initialize")
                stopSelf()
                return
            }
            audioRecord?.startRecording()
            isRunning = true

            captureJob = scope.launch {
                val buffer = ByteArray(FRAME_BYTES)
                while (isActive && isRunning) {
                    val bytesRead = audioRecord?.read(buffer, 0, FRAME_BYTES) ?: -1
                    if (bytesRead > 0) {
                        try {
                            RelayClient.sendBinary(buffer.copyOf(bytesRead))
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Failed to send audio frame: ${e.message}")
                        }
                    } else if (bytesRead < 0) {
                        AppLogger.e(TAG, "AudioRecord read error: $bytesRead")
                        break
                    }
                }
            }
            AppLogger.i(TAG, "PCM audio streaming started")
            RelayClient.notifyVoiceStatus("Mic streaming to server — speak now", isError = false)
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "Microphone permission denied", e)
            RelayClient.notifyVoiceStatus("Microphone permission denied — grant in Settings", isError = true)
            stopSelf()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start audio capture", e)
            RelayClient.notifyVoiceStatus("Mic error: ${e.message}", isError = true)
            stopSelf()
        }
    }

    private fun stopRawAudio() {
        captureJob?.cancel()
        captureJob = null
        try { audioRecord?.stop() } catch (_: Exception) { }
        audioRecord?.release()
        audioRecord = null
    }

    // ── Recognition listener ───────────────────────────────────────────────

    inner class VoiceRecognitionListener : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            AppLogger.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            AppLogger.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Could update mic level UI here
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            AppLogger.d(TAG, "Speech ended")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: return
            if (matches.isEmpty()) return
            val text = matches[0]
            AppLogger.d(TAG, "Partial: $text")
            // Show live transcript in UI
            RelayClient.notifyTranscript(text, isFinal = false)
        }

        override fun onResults(results: Bundle?) {
            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: return
            if (matches.isEmpty()) return
            val text = matches[0]
            AppLogger.i(TAG, "Final: $text")

            // Show final transcript
            RelayClient.notifyTranscript(text, isFinal = true)

            // Send to Hermes
            if (text.isNotBlank() && RelayClient.isConnected) {
                RelayClient.sendChat(text)
            }

            // Auto-stop service after sending — one utterance per mic tap.
            // The service's onDestroy will clean up the recognizer.
            stopSelf()
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                else -> "Unknown error ($error)"
            }
            AppLogger.w(TAG, "SpeechRecognizer error: $errorMsg")

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // No speech — just restart
                    restartListening()
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    // Network/server issues — fall back to raw audio
                    AppLogger.w(TAG, "SpeechRecognizer network error, falling back to PCM")
                    fallbackToRawAudio()
                }
                else -> {
                    // Other errors — retry after delay
                    restartListeningDelayed(2000)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hermes Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Speech recognition active for Hermes"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Hermes listening...")
                .setContentText("Speak to interact")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Hermes listening...")
                .setContentText("Speak to interact")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}
