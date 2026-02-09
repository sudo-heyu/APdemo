package com.heyu.apdemo2.scanner

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.heyu.apdemo2.model.AccessPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WiFi扫描管理器
 * 负责扫描附近的WiFi热点并转换为AccessPoint对象
 */
class WifiScanner(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var scanCallback: ((List<AccessPoint>) -> Unit)? = null
    private var progressiveCallback: ((List<AccessPoint>, Int, Int) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var isScanInProgress = false
    private val accumulatedResults = mutableSetOf<String>() // 用于跨扫描累积去重
    private val allAccessPoints = mutableListOf<AccessPoint>() // 累积的所有热点

    companion object {
        private const val TAG = "WifiScanner"
        private const val SCAN_TIMEOUT = 20000L // 20秒超时
        private const val MAX_SCAN_ATTEMPTS = 1 // 修改为1次，由MainActivity控制大循环节奏
    }

    /**
     * 开始扫描WiFi
     * @param onSuccess 扫描完成回调
     * @param onProgressive 渐进式更新回调
     * @param onError 错误回调
     */
    fun startScan(
        onSuccess: (List<AccessPoint>) -> Unit,
        onProgressive: (List<AccessPoint>, Int, Int) -> Unit,
        onError: (String) -> Unit
    ) {
        // 防止并发扫描
        if (isScanInProgress) {
            Log.w(TAG, "扫描已在进行中，跳过本次请求")
            onError("扫描正在进行中，请稍候")
            return
        }

        this.scanCallback = onSuccess
        this.progressiveCallback = onProgressive
        this.errorCallback = onError
        isScanInProgress = true

        try {
            Log.d(TAG, "=== 开始WiFi扫描流程 ===")

            // 检查WiFi是否启用
            val isWifiEnabled = wifiManager.isWifiEnabled
            Log.d(TAG, "WiFi启用状态: $isWifiEnabled")
            if (!isWifiEnabled) {
                Log.w(TAG, "WiFi未开启，尝试启用WiFi")
                // 尝试启用WiFi
                val wifiEnabled = enableWifi()
                if (!wifiEnabled) {
                    val errorMsg = "WiFi未开启且无法自动启用，请手动开启WiFi"
                    Log.w(TAG, errorMsg)
                    // 不直接返回错误，继续尝试扫描
                }
            }

            // 检查权限（即使权限不足也尝试扫描）
            val hasPermissions = hasRequiredPermissions()
            Log.d(TAG, "权限检查结果: $hasPermissions")
            if (!hasPermissions) {
                Log.w(TAG, "权限不足，但仍尝试进行扫描")
                // 不直接返回错误，继续尝试扫描
            }

            // 执行增强扫描
            performEnhancedScan()

        } catch (e: Exception) {
            Log.e(TAG, "扫描过程中发生异常", e)
            isScanInProgress = false
            errorCallback?.invoke("扫描异常: ${e.message}")
        }
    }

    /**
     * 执行增强扫描
     */
    private fun performEnhancedScan() {
        // 使用类级别的累积数据结构
        var attemptCount = 0
        val scanResultsList = mutableListOf<List<ScanResult>>()

        fun doSingleScan() {
            attemptCount++
            Log.d(TAG, "执行第 $attemptCount 次扫描")

            // 创建接收器变量
            var receiver: BroadcastReceiver? = null

            // 注册扫描结果接收器
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                        val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                        Log.d(TAG, "扫描结果广播收到，success=$success")

                        try {
                            // 获取扫描结果
                            val scanResults = wifiManager.scanResults
                            Log.d(TAG, "第${attemptCount}次扫描获取到 ${scanResults.size} 个扫描结果")

                            // 保存本次扫描结果
                            scanResultsList.add(scanResults)

                            var newResultsCount = 0
                            // 处理每个扫描结果
                            scanResults.forEachIndexed { index, result ->
                                // 使用BSSID作为唯一标识去重
                                if (result.SSID.isNotEmpty() && !accumulatedResults.contains(result.BSSID)) {
                                    accumulatedResults.add(result.BSSID)
                                    val ap = AccessPoint(
                                        ssid = result.SSID,
                                        bssid = result.BSSID,
                                        rssi = result.level,
                                        frequency = result.frequency
                                    )
                                    allAccessPoints.add(ap)
                                    newResultsCount++
                                }
                            }

                            // 合并相同SSID的信号（选择信号最强的）
                            val mergedAccessPoints = mergeSameSSIDSignals(allAccessPoints)

                            // 按信号强度排序
                            val sortedAccessPoints = mergedAccessPoints.sortedByDescending { it.rssi }

                            // 调用渐进式更新回调
                            progressiveCallback?.invoke(sortedAccessPoints, attemptCount, newResultsCount)

                            // 判断是否继续下一次扫描
                            if (attemptCount < MAX_SCAN_ATTEMPTS) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    kotlinx.coroutines.delay(2000)
                                    doSingleScan()
                                }
                            } else {
                                // 完成扫描
                                finishScan(sortedAccessPoints)
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "处理扫描结果时出错", e)
                            val currentResults = allAccessPoints.sortedByDescending { it.rssi }
                            finishScan(currentResults)
                        }

                        // 清理接收器
                        try {
                            context?.unregisterReceiver(this)
                        } catch (e: Exception) {}
                    }
                }
            }

            // 注册广播接收器
            val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            try {
                context.registerReceiver(receiver, intentFilter)
            } catch (e: Exception) {
                finishScan(emptyList())
                return
            }

            // 启动扫描
            if (!wifiManager.startScan()) {
                Log.w(TAG, "扫描启动受限")
            }
        }

        // 开始第一次扫描
        doSingleScan()
    }

    /**
     * 完成扫描并返回最终结果
     */
    private fun finishScan(accessPoints: List<AccessPoint>) {
        // 合并相同SSID的信号
        val mergedAccessPoints = mergeSameSSIDSignals(accessPoints)

        // 按信号强度排序（降序）
        val sortedAccessPoints = mergedAccessPoints.sortedByDescending { it.rssi }

        isScanInProgress = false
        scanCallback?.invoke(sortedAccessPoints)
    }

    /**
     * 检查是否具有必要权限
     */
    private fun hasRequiredPermissions(): Boolean {
        val hasWifiState = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val hasChangeWifiState = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.CHANGE_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val hasLocation = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return hasWifiState && hasChangeWifiState && hasLocation
    }

    /**
     * 启用WiFi（需要系统权限）
     */
    fun enableWifi(): Boolean {
        return try {
            if (!wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = true
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 合并相同SSID的WiFi信号
     */
    private fun mergeSameSSIDSignals(accessPoints: List<AccessPoint>): List<AccessPoint> {
        if (accessPoints.isEmpty()) return emptyList()

        val ssidMap = mutableMapOf<String, AccessPoint>()

        accessPoints.forEach { ap ->
            val currentBest = ssidMap[ap.ssid]
            if (currentBest == null || ap.rssi > currentBest.rssi) {
                ssidMap[ap.ssid] = ap
            }
        }

        return ssidMap.values.toList()
    }

    /**
     * 清空累积的扫描结果，为新周期做准备
     */
    fun clearAccumulatedResults() {
        accumulatedResults.clear()
        allAccessPoints.clear()
        Log.d(TAG, "已清空累积的扫描结果")
    }
}
