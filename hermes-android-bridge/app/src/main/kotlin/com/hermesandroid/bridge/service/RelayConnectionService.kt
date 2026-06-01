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
import com.hermesandroid.bridge.MainActivity
import com.hermesandroid.bridge.client.RelayClient

/**
 * Foreground service that keeps the Hermes WebSocket alive in the background.
 * Started from the Activity (main thread) to comply with Android 14+ restrictions.
 *
 * Shows a persistent "Hermes connected" notification so Android knows
 * not to kill the process when the user switches apps.
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Don't start foreground if we already promoted
        if (!isRunning) {
            startForeground(NOTIFICATION_ID, buildNotification())
            isRunning = true
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hermes",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Hermes connected for hands-free voice access"
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
                .setContentTitle("Hermes")
                .setContentText("Connected — tap to open")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Hermes")
                .setContentText("Connected — tap to open")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }
}
