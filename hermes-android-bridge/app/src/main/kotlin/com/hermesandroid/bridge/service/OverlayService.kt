package com.hermesandroid.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.hermesandroid.bridge.SettingsManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.ui.HermesOverlayActivity
import com.hermesandroid.bridge.util.AppLogger
import com.hermesandroid.bridge.wake.VoskWakeWordEngine

/**
 * Always-on foreground service for TV boxes. Starts on boot, runs Vosk
 * wake word detection (on-device, keyword spotting), and launches the
 * translucent overlay when "hey bob" is detected.
 *
 * Vosk owns the mic — this service does NOT open AudioRecord itself.
 * When the overlay appears, it pauses Vosk and uses its own AudioCapture
 * for the voice interaction. When the overlay dismisses, Vosk resumes.
 *
 * Lifecycle: Boot → start → Vosk listening → "hey bob" → overlay → dismiss → Vosk resumes.
 */
class OverlayService : Service() {

    companion object {
        const val TAG = "OverlayService"
        const val CHANNEL_ID = "hermes_overlay"
        const val NOTIFICATION_ID = 4001

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(ctx: Context) {
            if (isRunning) return
            SettingsManager.init(ctx)
            val intent = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private var wakeEngine: VoskWakeWordEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : android.os.Binder() {
        fun getService(): OverlayService = this@OverlayService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            acquireWakeLock()
            startForeground(NOTIFICATION_ID, buildNotification())
            isRunning = true
            if (!RelayClient.isConnected) {
                RelayClient.autoConnect()
            }
            startWakeDetection()
            AppLogger.i(TAG, "OverlayService started — Vosk keyword spotting")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    override fun onDestroy() {
        isRunning = false
        wakeEngine?.destroy()
        wakeEngine = null
        releaseWakeLock()
        AppLogger.i(TAG, "OverlayService destroyed")
        super.onDestroy()
    }

    // ── Wake detection ──────────────────────────────────────────────────────

    private fun startWakeDetection() {
        wakeEngine = VoskWakeWordEngine(this) {
            // Wake word detected — pause Vosk (releases mic) and launch overlay
            AppLogger.i(TAG, "Wake word detected — launching overlay")
            wakeEngine?.stop()
            launchOverlay()
        }
        wakeEngine?.start()
    }

    /** Called by the overlay when it dismisses. Resumes Vosk wake word detection. */
    fun onResume() {
        AppLogger.i(TAG, "Resuming wake word detection")
        wakeEngine?.start()
    }

    private fun launchOverlay() {
        val intent = Intent(this, HermesOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                     Intent.FLAG_ACTIVITY_SINGLE_TOP or
                     Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch overlay", e)
            // Overlay failed to launch — resume Vosk immediately
            wakeEngine?.start()
        }
    }

    // ── Wake lock ──────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "hermes:overlay"
        ).apply {
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hermes Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Always listening for \"Hey Bob\""
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, HermesOverlayActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Hermes listening")
                .setContentText("Say \"Hey Bob\" to interact")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Hermes listening")
                .setContentText("Say \"Hey Bob\" to interact")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}
