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
import com.hermesandroid.bridge.MainActivity
import com.hermesandroid.bridge.VoiceActivity
import com.hermesandroid.bridge.client.RelayClient

/**
 * Foreground service that keeps the Hermes WebSocket alive with a CPU wake lock.
 *
 * Without a PARTIAL_WAKE_LOCK, Android can suspend the CPU even with a foreground
 * service — causing WebSocket connections to drop during Hermes's 10-30s response time.
 *
 * Shows a permanent "Connected to Hermes" notification so the user always knows
 * the connection is alive and one tap returns to the chat.
 */
class RelayConnectionService : Service() {

    companion object {
        const val CHANNEL_ID = "hermes_connection"
        const val NOTIFICATION_ID = 3001

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(ctx: Context) {
            if (isRunning) return
            val intent = Intent(ctx, RelayConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RelayConnectionService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            acquireWakeLock()
            startForeground(NOTIFICATION_ID, buildNotification())
            isRunning = true
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    // ── Wake lock ──────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "hermes:connection"
        ).apply {
            acquire(30 * 60 * 1000L) // 30-minute timeout, refreshed on each start
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
                "Hermes Connection",
                NotificationManager.IMPORTANCE_DEFAULT  // visible, not hidden
            ).apply {
                description = "Keeps Hermes connected for instant responses"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Tap notification → open voice chat directly
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, VoiceActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Connected to Hermes")
                .setContentText("Tap to open chat")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Connected to Hermes")
                .setContentText("Tap to open chat")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .build()
        }
    }
}
