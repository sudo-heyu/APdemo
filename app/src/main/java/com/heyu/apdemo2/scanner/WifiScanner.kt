package com.heyu.apdemo2.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.heyu.apdemo2.model.AccessPoint

/**
 * WiFi扫描管理器
 * 改进版：确保所有延迟任务在 stopScan 时都能被正确取消
 */
class WifiScanner(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var isScanInProgress = false
    private val accumulatedResults = mutableSetOf<String>() 
    private val allAccessPoints = mutableListOf<AccessPoint>()
    
    private val handler = Handler(Looper.getMainLooper())
    private var scanReceiver: BroadcastReceiver? = null
    
    // 统一管理延迟任务，确保可以被取消
    private var pendingTask: Runnable? = null

    companion object {
        private const val TAG = "WifiScanner"
        private const val SCAN_TIMEOUT = 2000L 
    }

    /**
     * 彻底停止当前扫描，清理广播和所有延迟任务
     */
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
        // 启动新任务前先清理旧任务
        stopScan()

        isScanInProgress = true
        
        if (!wifiManager.isWifiEnabled) {
            isScanInProgress = false
            onError("WiFi未开启")
            return
        }

        // 1. 定义超时任务
        val timeoutTask = Runnable {
            if (!isScanInProgress) return@Runnable
            Log.w(TAG, "扫描任务超时，强制结束")
            unregisterReceiverSafely()
            processResultsAndFinish(onSuccess, onProgressive)
        }
        pendingTask = timeoutTask

        // 2. 定义广播接收器
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
            context.registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            
            val startSuccess = wifiManager.startScan()
            if (!startSuccess) {
                Log.w(TAG, "WiFi扫描受限 (Throttled)，切换到缓存模式")
                // 受限时，延迟1秒后返回结果，避免UI状态机切换过快
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
                // 启动成功，开启超时监控
                handler.postDelayed(timeoutTask, SCAN_TIMEOUT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动扫描发生异常: ${e.message}")
            stopScan()
            onError("启动失败")
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
                    allAccessPoints.add(AccessPoint(
                        ssid = result.SSID,
                        bssid = result.BSSID,
                        rssi = result.level,
                        frequency = result.frequency
                    ))
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
        } catch (e: Exception) { }
        scanReceiver = null
    }

    private fun mergeSameSSIDSignals(accessPoints: List<AccessPoint>): List<AccessPoint> {
        val ssidMap = mutableMapOf<String, AccessPoint>()
        accessPoints.forEach { ap ->
            val currentBest = ssidMap[ap.ssid]
            if (currentBest == null || ap.rssi > currentBest.rssi) {
                ssidMap[ap.ssid] = ap
            }
        }
        return ssidMap.values.toList()
    }

    fun clearAccumulatedResults() {
        accumulatedResults.clear()
        allAccessPoints.clear()
        Log.d(TAG, "已重置累积数据")
    }
}
