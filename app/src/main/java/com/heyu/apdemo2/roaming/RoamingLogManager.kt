package com.heyu.apdemo2.roaming

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 漫游算法执行日志管理器
 * 记录漫游算法的详细执行过程，便于调试
 */
class RoamingLogManager private constructor(context: Context) {

    fun interface OnLogListener {
        fun onNewLog(logLine: String)
    }

    companion object {
        private const val TAG = "[RoamingLog]"
        private const val LOG_FILE_NAME = "roaming_algorithm.log"
        private const val MAX_LINES = 500 // 内存最多保留行数
        private const val TRIM_TO = 350   // 超限后裁剪到此行数（保留最新）

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

    // 内存日志缓冲（有序，最新在末尾）
    private val logLines = mutableListOf<String>()

    init {
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
        // 启动时从文件加载最近日志
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
     * 记录日志
     */
    fun log(level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] $message"

        // 同时输出到Logcat
        when (level) {
            "D" -> Log.d(TAG, message)
            "I" -> Log.i(TAG, message)
            "W" -> Log.w(TAG, message)
            "E" -> Log.e(TAG, message)
            else -> Log.d(TAG, message)
        }

        // 追加到内存缓冲
        synchronized(logLines) {
            logLines.add(logLine)
            if (logLines.size > MAX_LINES) {
                val removeCount = logLines.size - TRIM_TO
                logLines.subList(0, removeCount).clear()
            }
        }

        // 写入文件
        try {
            logFile.appendText(logLine + "\n")
            // 文件超限时重写为内存中的内容
            if (logFile.length() > 512 * 1024L) {
                synchronized(logLines) {
                    logFile.writeText(logLines.joinToString("\n") + "\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入日志失败: ${e.message}")
        }

        // 通知监听器
        synchronized(listeners) {
            listeners.forEach { it.onNewLog(logLine) }
        }
    }

    fun d(message: String) = log("D", message)
    fun i(message: String) = log("I", message)
    fun w(message: String) = log("W", message)
    fun e(message: String) = log("E", message)

    /** 带颜色的阶段标签，用于 WebView 渲染 */
    fun phase(tag: String, color: String, message: String) {
        log("I", "<span style='background:$color;color:#fff;padding:1px 6px;border-radius:3px;font-weight:bold'>$tag</span> $message")
    }

    /**
     * 获取日志内容（正序，最新在末尾）
     */
    fun getLogs(): String {
        return synchronized(logLines) {
            if (logLines.isEmpty()) "暂无日志"
            else logLines.joinToString("\n")
        }
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        synchronized(logLines) { logLines.clear() }
        try {
            logFile.writeText("")
        } catch (e: Exception) {
            Log.e(TAG, "清空日志失败: ${e.message}")
        }
    }

    /**
     * 获取日志文件大小
     */
    fun getLogSize(): Long {
        return if (logFile.exists()) logFile.length() else 0
    }
}
