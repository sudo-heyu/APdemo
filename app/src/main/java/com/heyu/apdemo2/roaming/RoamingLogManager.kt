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

    companion object {
        private const val TAG = "[RoamingLog]"
        private const val LOG_FILE_NAME = "roaming_algorithm.log"
        private const val MAX_LOG_SIZE = 1024 * 1024L // 1MB，超过则清空

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
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    init {
        // 确保日志文件存在
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
    }

    /**
     * 记录日志
     */
    fun log(level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [$level] $message\n"

        // 同时输出到Logcat
        when (level) {
            "D" -> Log.d(TAG, message)
            "I" -> Log.i(TAG, message)
            "W" -> Log.w(TAG, message)
            "E" -> Log.e(TAG, message)
            else -> Log.d(TAG, message)
        }

        // 写入文件
        try {
            // 检查文件大小
            if (logFile.length() > MAX_LOG_SIZE) {
                logFile.writeText("") // 清空文件
                val resetLine = "[$timestamp] [I] 日志文件超过1MB，已自动清空\n"
                logFile.appendText(resetLine)
            }
            logFile.appendText(logLine)
        } catch (e: Exception) {
            Log.e(TAG, "写入日志失败: ${e.message}")
        }
    }

    fun d(message: String) = log("D", message)
    fun i(message: String) = log("I", message)
    fun w(message: String) = log("W", message)
    fun e(message: String) = log("E", message)

    /**
     * 获取所有日志内容
     */
    fun getLogs(): String {
        return try {
            if (logFile.exists()) {
                logFile.readText()
            } else {
                "暂无日志"
            }
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        try {
            logFile.writeText("")
            i("日志已清空")
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
