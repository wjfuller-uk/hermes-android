package com.hermesandroid.bridge.util

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

/**
 * Global uncaught exception handler that logs crashes locally
 * and sends them to the relay server for remote debugging.
 */
object CrashReporter {

    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var relayUrl: String? = null
    private var appContext: Application? = null

    fun init(app: Application, serverUrl: String?) {
        appContext = app
        relayUrl = serverUrl
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(thread, throwable)
        }
    }

    private fun handleCrash(thread: Thread, throwable: Throwable) {
        // Build crash report
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val report = buildString {
            appendLine("=== CRASH REPORT ===")
            appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}")
            appendLine("Thread: ${thread.name}")
            appendLine("App version: ${appContext?.packageManager?.getPackageInfo(appContext?.packageName!!, 0)?.versionName ?: "unknown"}")
            appendLine("Device: ${android.os.Build.BRAND} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(stackTrace)
        }

        // Log locally
        AppLogger.e("CRASH", report)

        // Send to relay server (fire-and-forget, don't block crash handler)
        Thread {
            try {
                sendCrash(report)
            } catch (_: Exception) { }
        }.start()

        // Give the crash report a moment to send
        try { Thread.sleep(500) } catch (_: Exception) { }

        // Delegate to original handler or kill process
        originalHandler?.uncaughtException(thread, throwable)
            ?: exitProcess(1)
    }

    private fun sendCrash(report: String) {
        val url = relayUrl ?: return
        try {
            val baseUrl = url.trimEnd('/')
                .removePrefix("ws://").removePrefix("wss://")
                .removePrefix("http://").removePrefix("https://")
            // Extract host:port from the URL
            val hostPort = baseUrl.substringBefore('/')
            val crashUrl = URL("http://$hostPort/crash")

            val conn = crashUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.doOutput = true
            conn.connectTimeout = 2000
            conn.readTimeout = 2000

            conn.outputStream.use { os ->
                os.write(report.toByteArray())
            }

            val code = conn.responseCode
            if (code == 200) {
                AppLogger.i("CrashReporter", "Crash report sent to relay")
            }
        } catch (e: Exception) {
            AppLogger.w("CrashReporter", "Failed to send crash report: ${e.message}")
        }
    }
}
