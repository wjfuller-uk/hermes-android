package com.hermesandroid.bridge.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Centralized logging utility that stores entries in memory and writes to file.
 * Drop-in replacement for android.util.Log with persistent storage.
 */
object AppLogger {

    private const val MAX_ENTRIES = 500
    private const val TAG = "AppLogger"

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: String,  // V, D, I, W, E
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        val levelChar: Char get() = level[0]

        fun formatted(): String {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            val time = sdf.format(Date(timestamp))
            val throwableStr = throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
            return "$time $level/$tag: $message$throwableStr"
        }
    }

    private val entries = ConcurrentLinkedQueue<LogEntry>()
    private var logDir: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun init(context: Context) {
        logDir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        i(TAG, "AppLogger initialized, log dir: ${logDir?.absolutePath}")
    }

    fun v(tag: String, msg: String, t: Throwable? = null): Int {
        addEntry("V", tag, msg, t)
        return Log.v(tag, msg, t)
    }

    fun d(tag: String, msg: String, t: Throwable? = null): Int {
        addEntry("D", tag, msg, t)
        return Log.d(tag, msg, t)
    }

    fun i(tag: String, msg: String, t: Throwable? = null): Int {
        addEntry("I", tag, msg, t)
        return Log.i(tag, msg, t)
    }

    fun w(tag: String, msg: String, t: Throwable? = null): Int {
        addEntry("W", tag, msg, t)
        return Log.w(tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null): Int {
        addEntry("E", tag, msg, t)
        return Log.e(tag, msg, t)
    }

    private fun addEntry(level: String, tag: String, message: String, throwable: Throwable?) {
        val entry = LogEntry(level = level, tag = tag, message = message, throwable = throwable)
        entries.add(entry)
        // Evict oldest if over limit
        while (entries.size > MAX_ENTRIES) {
            entries.poll()
        }
        // Write to file asynchronously
        writeToFile(entry)
    }

    private fun writeToFile(entry: LogEntry) {
        try {
            val dir = logDir ?: return
            val fileName = "hermes-${dateFormat.format(Date(entry.timestamp))}.log"
            val file = File(dir, fileName)
            file.appendText("${entry.formatted()}\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }

    /** Get log entries since a given timestamp (0 = all). */
    fun getEntries(since: Long = 0): List<LogEntry> {
        return if (since == 0L) entries.toList()
        else entries.filter { it.timestamp >= since }
    }

    /** Get formatted log text since a given timestamp. */
    fun getFormattedText(since: Long = 0): String {
        return getEntries(since).joinToString("\n") { it.formatted() }
    }

    /** Read the current day's log file content. */
    fun getLogFileContent(): String {
        return try {
            val dir = logDir ?: return "Logger not initialized"
            val fileName = "hermes-${dateFormat.format(Date())}.log"
            val file = File(dir, fileName)
            if (file.exists()) file.readText() else "No log file for today"
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }

    /** Get the current log file for sharing. */
    fun getLogFile(): File? {
        val dir = logDir ?: return null
        val fileName = "hermes-${dateFormat.format(Date())}.log"
        val file = File(dir, fileName)
        return if (file.exists()) file else null
    }
}
