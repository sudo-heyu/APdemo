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
 * WiFi扫描管理器
 * looper 参数：指定回调/超时运行在哪个线程。
 * 在 Service 里请传入 HandlerThread 的 Looper，避免依赖可能被 OEM 冻结的主线程。
 */
class WifiScanner(private val context: Context, looper: Looper = Looper.getMainLooper()) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var isScanInProgress = false
    private val accumulatedResults = mutableSetOf<String>()
    private val allAccessPoints = mutableListOf<AccessPoint>()

    // 使用传入的 Looper，确保超时任务和广播回调都在同一后台线程执行
    private val handler = Handler(looper)
    private var scanReceiver: BroadcastReceiver? = null
    private var pendingTask: Runnable? = null

    companion object {
        private const val TAG = "WifiScanner"
        private const val SCAN_TIMEOUT = 2000L
    }

    fun stopScan() {
        Log.d(TAG, ">>> [stopScan] 执行清理")
        isScanInProgress = false
        unregisterReceiverSafely()
        pendingTask?.let {
            handler.removeCallbacks(it)
            Log.d(TAG, "已移除待执行的延迟任务")
        }
        pendingTask = null
    }

    fun startScan(
        onSuccess: (List<AccessPoint>) -> Unit,
        onProgressive: (List<AccessPoint>, Int, Int) -> Unit,
        onError: (String) -> Unit
    ) {
        stopScan()
        isScanInProgress = true

        if (!wifiManager.isWifiEnabled) {
            isScanInProgress = false
            onError("WiFi未开启")
            return
        }

        // 超时任务：运行在传入的 Looper 线程
        val timeoutTask = Runnable {
            if (!isScanInProgress) return@Runnable
            Log.w(TAG, "扫描任务超时，强制结束")
            unregisterReceiverSafely()
            processResultsAndFinish(onSuccess, onProgressive)
        }
        pendingTask = timeoutTask

        // 广播接收器：通过 handler 参数让回调也运行在同一线程
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isScanInProgress) return
                handler.removeCallbacks(timeoutTask)
                pendingTask = null
                unregisterReceiverSafely()
                val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
                Log.d(TAG, "收到扫描完成广播 (success=$success)")
                processResultsAndFinish(onSuccess, onProgressive)
            }
        }

        try {
            // 关键：把 handler 传给 registerReceiver，广播回调在 HandlerThread 线程执行
            registerReceiverWithHandler(scanReceiver!!)

            val startSuccess = wifiManager.startScan()
            if (!startSuccess) {
                Log.w(TAG, "WiFi扫描受限 (Throttled)，切换到缓存模式")
                handler.removeCallbacks(timeoutTask)
                val throttledTask = Runnable {
                    if (isScanInProgress) {
                        unregisterReceiverSafely()
                        processResultsAndFinish(onSuccess, onProgressive)
                    }
                }
                pendingTask = throttledTask
                handler.postDelayed(throttledTask, 500)
            } else {
                handler.postDelayed(timeoutTask, SCAN_TIMEOUT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动扫描发生异常: ${e.message}")
            stopScan()
            onError("启动失败")
        }
    }

    @Suppress("DEPRECATION")
    private fun registerReceiverWithHandler(receiver: BroadcastReceiver) {
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要传 flags；系统广播用 RECEIVER_EXPORTED
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
        } else {
            // 旧版本：4 参数版本，指定 handler
            context.registerReceiver(receiver, filter, null, handler)
        }
    }

    private fun processResultsAndFinish(
        onSuccess: (List<AccessPoint>) -> Unit,
        onProgressive: (List<AccessPoint>, Int, Int) -> Unit
    ) {
        if (!isScanInProgress) return

        try {
            val scanResults = wifiManager.scanResults
            var newCount = 0
            scanResults.forEach { result ->
                if (result.SSID.isNotEmpty() && !accumulatedResults.contains(result.BSSID)) {
                    accumulatedResults.add(result.BSSID)
                    allAccessPoints.add(
                        AccessPoint(
                            ssid = result.SSID,
                            bssid = result.BSSID,
                            rssi = result.level,
                            frequency = result.frequency
                        )
                    )
                    newCount++
                }
            }
            val finalData = mergeSameSSIDSignals(allAccessPoints).sortedByDescending { it.rssi }
            onProgressive(finalData, 1, newCount)
            isScanInProgress = false
            onSuccess(finalData)
        } catch (e: Exception) {
            Log.e(TAG, "处理扫描结果出错: ${e.message}")
            isScanInProgress = false
            onSuccess(allAccessPoints.toList())
        }
    }

    private fun unregisterReceiverSafely() {
        try {
            scanReceiver?.let {
                context.unregisterReceiver(it)
                Log.d(TAG, "广播接收器已卸载")
            }
        } catch (e: Exception) { /* ignore */ }
        scanReceiver = null
    }

    private fun mergeSameSSIDSignals(accessPoints: List<AccessPoint>): List<AccessPoint> {
        val ssidMap = mutableMapOf<String, AccessPoint>()
        accessPoints.forEach { ap ->
            val currentBest = ssidMap[ap.ssid]
            if (currentBest == null || ap.rssi > currentBest.rssi) ssidMap[ap.ssid] = ap
        }
        return ssidMap.values.toList()
    }

    fun clearAccumulatedResults() {
        accumulatedResults.clear()
        allAccessPoints.clear()
        Log.d(TAG, "已重置累积数据")
    }
}
