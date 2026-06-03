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
import android.os.IBinder
import com.hermesandroid.bridge.MainActivity
import com.hermesandroid.bridge.SettingsManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.util.AppLogger
import com.hermesandroid.bridge.wake.WakeWordEngine
import kotlinx.coroutines.*

/**
 * Foreground service that captures raw PCM audio from the mic and streams it
 * to the relay server. Audio frames are gated by VAD (WakeWordEngine) —
 * only frames above the configured RMS threshold are sent.
 *
 * The relay handles ALL processing — STT (Hermes transcribe_audio),
 * agent dialogue, and TTS (Hermes text_to_speech_tool).
 *
 * The phone is a thin pipe: mic → VAD gate → PCM → WebSocket → relay → TTS audio → speaker.
 *
 * Auto-stops after 10 seconds to trigger relay-side processing (force_stop).
 * One utterance per mic tap.
 */
class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        const val CHANNEL_ID = "hermes_voice_channel"
        const val NOTIFICATION_ID = 2001

        // Audio format: raw PCM 16-bit signed, little-endian, 16kHz, mono
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

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppLogger.i(TAG, "VoiceService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        startListening()
        AppLogger.i(TAG, "VoiceService started — PCM audio streaming to relay")
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

    // ── PCM capture ─────────────────────────────────────────────────────────

    private fun startListening() {
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

            // Auto-stop after 10 seconds — triggers relay-side force_stop
            scope.launch {
                delay(10_000)
                if (isRunning) {
                    AppLogger.i(TAG, "PCM auto-stop after 10s — triggering relay processing")
                    RelayClient.sendCommand("POST", "/voice/stop")
                    delay(200)
                    stopSelf()
                }
            }

            // Stream PCM frames to relay — gated by VAD threshold
            captureJob = scope.launch {
                val buffer = ByteArray(FRAME_BYTES)
                while (isActive && isRunning) {
                    val bytesRead = audioRecord?.read(buffer, 0, FRAME_BYTES) ?: -1
                    if (bytesRead > 0) {
                        // VAD gate: only stream frames with energy above threshold
                        val rms = WakeWordEngine.calculateRms(buffer, bytesRead)
                        val threshold = SettingsManager.vadThreshold.toFloat()
                        if (rms >= threshold) {
                            try {
                                RelayClient.sendBinary(buffer.copyOf(bytesRead))
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Failed to send audio frame: ${e.message}")
                            }
                        }
                    } else if (bytesRead < 0) {
                        AppLogger.e(TAG, "AudioRecord read error: $bytesRead")
                        break
                    }
                }
            }
            AppLogger.i(TAG, "PCM audio streaming started (${bufferSize}B buffer)")
            RelayClient.notifyVoiceStatus("Mic streaming to relay — speak now", isError = false)
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "Microphone permission denied", e)
            RelayClient.notifyVoiceStatus("Microphone permission denied", isError = true)
            stopSelf()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start audio capture", e)
            RelayClient.notifyVoiceStatus("Mic error: ${e.message}", isError = true)
            stopSelf()
        }
    }

    private fun stopListening() {
        val wasRunning = isRunning
        isRunning = false

        captureJob?.cancel()
        captureJob = null

        try { audioRecord?.stop() } catch (_: Exception) { }
        audioRecord?.release()
        audioRecord = null

        // Tell relay to process buffered audio
        if (wasRunning) {
            try {
                if (RelayClient.isConnected) {
                    RelayClient.sendCommand("POST", "/voice/stop")
                }
            } catch (_: Exception) { }
        }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hermes Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Microphone active for Hermes voice"
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
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
