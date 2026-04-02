package com.heyu.apdemo2.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.heyu.apdemo2.model.AccessPoint

/**
 * WiFi扫描管理器（单次扫描模式）
 * 每次调用 startScan 只触发一次系统扫描，返回去重后的结果。
 */
class WifiScanner(private val context: Context, looper: Looper = Looper.getMainLooper()) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var isScanInProgress = false
    private val handler = Handler(looper)
    private var scanReceiver: BroadcastReceiver? = null
    private var pendingTask: Runnable? = null

    companion object {
        private const val TAG = "WifiScanner"
        private const val SCAN_TIMEOUT = 4000L
    }

    fun stopScan() {
        isScanInProgress = false
        unregisterReceiverSafely()
        pendingTask?.let { handler.removeCallbacks(it) }
        pendingTask = null
    }

    fun startScan(
        onSuccess: (List<AccessPoint>) -> Unit,
        onError: (String) -> Unit
    ) {
        stopScan()
        isScanInProgress = true

        if (!wifiManager.isWifiEnabled) {
            isScanInProgress = false
            onError("WiFi未开启")
            return
        }

        val timeoutTask = Runnable {
            if (!isScanInProgress) return@Runnable
            Log.w(TAG, "扫描超时，使用缓存结果")
            unregisterReceiverSafely()
            deliverResults(onSuccess)
        }
        pendingTask = timeoutTask

        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isScanInProgress) return
                handler.removeCallbacks(timeoutTask)
                pendingTask = null
                unregisterReceiverSafely()
                deliverResults(onSuccess)
            }
        }

        try {
            registerReceiverWithHandler(scanReceiver!!)

            val startSuccess = wifiManager.startScan()
            if (!startSuccess) {
                Log.w(TAG, "扫描受限(Throttled)，使用缓存结果")
                handler.removeCallbacks(timeoutTask)
                val fallbackTask = Runnable {
                    if (isScanInProgress) {
                        unregisterReceiverSafely()
                        deliverResults(onSuccess)
                    }
                }
                pendingTask = fallbackTask
                handler.postDelayed(fallbackTask, 500)
            } else {
                handler.postDelayed(timeoutTask, SCAN_TIMEOUT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动扫描异常: ${e.message}")
            stopScan()
            onError("启动失败")
        }
    }

    @Suppress("DEPRECATION")
    private fun registerReceiverWithHandler(receiver: BroadcastReceiver) {
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }
    }

    private fun deliverResults(onSuccess: (List<AccessPoint>) -> Unit) {
        if (!isScanInProgress) return
        isScanInProgress = false

        try {
            val scanResults = wifiManager.scanResults
            val accessPoints = scanResults
                .filter { it.SSID.isNotEmpty() }
                .map { result ->
                    AccessPoint(
                        ssid = result.SSID,
                        bssid = result.BSSID,
                        rssi = result.level,
                        frequency = result.frequency,
                        capabilities = result.capabilities ?: ""
                    )
                }
            // 同SSID只保留信号最强的
            val merged = accessPoints
                .groupBy { it.ssid }
                .map { (_, aps) -> aps.maxByOrNull { it.rssi }!! }
                .sortedByDescending { it.rssi }

            onSuccess(merged)
        } catch (e: Exception) {
            Log.e(TAG, "处理扫描结果出错: ${e.message}")
            onSuccess(emptyList())
        }
    }

    private fun unregisterReceiverSafely() {
        try {
            scanReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        scanReceiver = null
    }
}
