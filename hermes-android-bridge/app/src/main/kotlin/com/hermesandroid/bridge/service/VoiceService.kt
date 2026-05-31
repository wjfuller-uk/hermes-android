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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.hermesandroid.bridge.MainActivity
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.RelayClient
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.Socket

/**
 * Foreground service that captures microphone audio and streams it to the relay server
 * as binary WebSocket frames.
 *
 * Architecture:
 *   AudioRecord (PCM 16kHz mono) → Opus encoder → RelayClient.sendBinary()
 *
 * For v1, sends raw PCM (no Opus — ~256kbps, fine for WiFi/always-powered).
 * The VPS relay routes binary frames to the voice pipeline (STT → Hermes → TTS).
 *
 * Wake word detection (Porcupine) is planned for v2.
 * For v1, voice mode is explicitly started/stopped via commands from the relay.
 */
class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        const val CHANNEL_ID = "hermes_voice_channel"
        const val NOTIFICATION_ID = 2001

        // Audio format: matches VPS pipeline (android_voice.py)
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Buffer: 40ms frames (640 samples = 1280 bytes)
        const val FRAME_MS = 40
        val FRAME_SAMPLES: Int = SAMPLE_RATE * FRAME_MS / 1000  // 640
        val FRAME_BYTES: Int = FRAME_SAMPLES * 2                 // 1280

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val binder = LocalBinder()
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService(): VoiceService = this@VoiceService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "VoiceService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        startCapture()
        Log.i(TAG, "VoiceService started — streaming mic audio")
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        scope.cancel()
        isRunning = false
        Log.i(TAG, "VoiceService destroyed")
        super.onDestroy()
    }

    private fun startCapture() {
        if (isRunning) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBufferSize, FRAME_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
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
                        // Send raw PCM to relay as binary WebSocket frame
                        try {
                            RelayClient.sendBinary(buffer.copyOf(bytesRead))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send audio frame: ${e.message}")
                        }
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord read error: $bytesRead")
                        break
                    }
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission denied", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            stopSelf()
        }
    }

    private fun stopCapture() {
        isRunning = false
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord?.release()
        audioRecord = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hermes Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Microphone active for Hermes voice assistant"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Hermes listening...")
                .setContentText("Microphone active — speak to interact")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Hermes listening...")
                .setContentText("Microphone active — speak to interact")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    /**
     * Called by ActionExecutor when relay sends /voice/start.
     */
    fun activate() {
        if (!isRunning) {
            val intent = Intent(this, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                startService(intent)
            }
        }
    }

    /**
     * Called by ActionExecutor when relay sends /voice/stop.
     */
    fun deactivate() {
        stopSelf()
    }
}
