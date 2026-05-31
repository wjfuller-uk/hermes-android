package com.hermesandroid.bridge.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat

/**
 * Manages notification channels and shows native Android notifications
 * triggered by the `/notify` command from the relay.
 */
object Notifier {

    private const val CHANNEL_HERMES = "hermes"
    private const val CHANNEL_REMINDER = "reminder"
    private const val CHANNEL_ALERT = "alert"
    private const val CHANNEL_CALENDAR = "calendar"

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        val manager = appContext!!.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val defaultSound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val soundAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // "hermes" — default priority
        val hermes = NotificationChannel(
            CHANNEL_HERMES,
            "Hermes",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "General Hermes notifications"
        }

        // "reminder" — high priority with sound
        val reminder = NotificationChannel(
            CHANNEL_REMINDER,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Reminder notifications"
            enableVibration(true)
            setSound(defaultSound, soundAttributes)
        }

        // "alert" — max priority with vibration
        val alert = NotificationChannel(
            CHANNEL_ALERT,
            "Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Urgent alert notifications"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
            setSound(defaultSound, soundAttributes)
        }

        // "calendar" — default priority
        val calendar = NotificationChannel(
            CHANNEL_CALENDAR,
            "Calendar",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Calendar event notifications"
        }

        manager.createNotificationChannels(listOf(hermes, reminder, alert, calendar))
    }

    /**
     * Show a notification using the application context stored during [init].
     * Can be called from any thread.
     */
    fun show(title: String, body: String, channelId: String = CHANNEL_HERMES) {
        val ctx = appContext ?: return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val validChannel = when (channelId) {
            CHANNEL_HERMES, CHANNEL_REMINDER, CHANNEL_ALERT, CHANNEL_CALENDAR -> channelId
            else -> CHANNEL_HERMES
        }

        val notification = NotificationCompat.Builder(ctx, validChannel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()

        val id = System.currentTimeMillis().toInt()
        manager.notify(id, notification)
    }
}
