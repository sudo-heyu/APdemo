package com.heyu.apdemo2.roaming

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Roaming Algorithm Execution Log Manager
 * Records detailed execution process of roaming algorithm for debugging
 */
class RoamingLogManager private constructor(context: Context) {

    fun interface OnLogListener {
        fun onNewLog(logLine: String)
    }

    companion object {
        private const val TAG = "[RoamingLog]"
        private const val LOG_FILE_NAME = "roaming_algorithm.log"
        private const val MAX_LINES = 500 // Max lines to keep in memory
        private const val TRIM_TO = 350   // Trim to this line count when exceeded (keep newest)

        @Volatile
        private var instance: RoamingLogManager? = null

        fun getInstance(context: Context): RoamingLogManager {
            return instance ?: synchronized(this) {
                instance ?: RoamingLogManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val logFile = File(context.filesDir, LOG_FILE_NAME)
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val listeners = mutableListOf<OnLogListener>()

    // Memory log buffer (ordered, newest at end)
    private val logLines = mutableListOf<String>()

    init {
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
        // Load recent logs from file at startup
        try {
            if (logFile.exists()) {
                val lines = logFile.readLines().filter { it.isNotBlank() }
                logLines.addAll(lines.takeLast(MAX_LINES))
            }
        } catch (_: Exception) {}
    }

    fun addListener(listener: OnLogListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: OnLogListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    /**
     * Record log
     */
    fun log(level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] $message"

        // Also output to Logcat
        when (level) {
            "D" -> Log.d(TAG, message)
            "I" -> Log.i(TAG, message)
            "W" -> Log.w(TAG, message)
            "E" -> Log.e(TAG, message)
            else -> Log.d(TAG, message)
        }

        // Append to memory buffer
        synchronized(logLines) {
            logLines.add(logLine)
            if (logLines.size > MAX_LINES) {
                val removeCount = logLines.size - TRIM_TO
                logLines.subList(0, removeCount).clear()
            }
        }

        // Write to file
        try {
            logFile.appendText(logLine + "\n")
            // Rewrite with memory contents when file exceeds limit
            if (logFile.length() > 512 * 1024L) {
                synchronized(logLines) {
                    logFile.writeText(logLines.joinToString("\n") + "\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log: ${e.message}")
        }

        // Notify listeners
        synchronized(listeners) {
            listeners.forEach { it.onNewLog(logLine) }
        }
    }

    fun d(message: String) = log("D", message)
    fun i(message: String) = log("I", message)
    fun w(message: String) = log("W", message)
    fun e(message: String) = log("E", message)

    /** Colored phase tag for WebView rendering */
    fun phase(tag: String, color: String, message: String) {
        log("I", "<span style='background:$color;color:#fff;padding:1px 6px;border-radius:3px;font-weight:bold'>$tag</span> $message")
    }

    /**
     * Get log content (in order, newest at end)
     */
    fun getLogs(): String {
        return synchronized(logLines) {
            if (logLines.isEmpty()) "No logs available"
            else logLines.joinToString("\n")
        }
    }

    /**
     * Clear logs
     */
    fun clearLogs() {
        synchronized(logLines) { logLines.clear() }
        try {
            logFile.writeText("")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs: ${e.message}")
        }
    }

    /**
     * Get log file size
     */
    fun getLogSize(): Long {
        return if (logFile.exists()) logFile.length() else 0
    }
}
