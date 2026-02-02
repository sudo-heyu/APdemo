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
        private const val MAX_SCAN_ATTEMPTS = 3 // 3次连续扫描
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
     * 执行增强扫描（3次连续扫描）
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
                                Log.d(TAG, "结果[$index]: SSID='${result.SSID}', BSSID=${result.BSSID}, RSSI=${result.level}")
                                
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
                                    Log.d(TAG, "新增热点: ${result.SSID}, RSSI: ${result.level}")
                                }
                            }
                            
                            // 合并相同SSID的信号（选择信号最强的）
                            val mergedAccessPoints = mergeSameSSIDSignals(allAccessPoints)
                            
                            // 按信号强度排序
                            val sortedAccessPoints = mergedAccessPoints.sortedByDescending { it.rssi }
                            
                            // 调用渐进式更新回调
                            Log.d(TAG, "调用渐进式更新回调，总计: ${sortedAccessPoints.size} 个热点（合并前: ${allAccessPoints.size} 个）")
                            progressiveCallback?.invoke(sortedAccessPoints, attemptCount, newResultsCount)
                            Log.d(TAG, "第${attemptCount}次扫描完成，累计 ${sortedAccessPoints.size} 个热点，新增 $newResultsCount 个")
                            
                            // 判断是否继续下一次扫描
                            if (attemptCount < MAX_SCAN_ATTEMPTS) {
                                Log.d(TAG, "等待2秒后进行第${attemptCount + 1}次扫描")
                                // 延迟2秒后进行下次扫描
                                CoroutineScope(Dispatchers.Main).launch {
                                    kotlinx.coroutines.delay(2000)
                                    doSingleScan()
                                }
                            } else {
                                // 所有扫描完成
                                Log.d(TAG, "=== 3次扫描全部完成 ===")
                                Log.d(TAG, "总扫描次数: $attemptCount")
                                Log.d(TAG, "累计发现热点数: ${sortedAccessPoints.size}")
                                
                                // 统计所有扫描结果
                                val totalUniqueBSSIDs = mutableSetOf<String>()
                                scanResultsList.forEach { results ->
                                    results.forEach { result ->
                                        if (result.SSID.isNotEmpty()) {
                                            totalUniqueBSSIDs.add(result.BSSID)
                                        }
                                    }
                                }
                                Log.d(TAG, "去重后总热点数: ${totalUniqueBSSIDs.size}")
                                
                                // 完成扫描
                                finishScan(sortedAccessPoints)
                            }
                            
                        } catch (e: SecurityException) {
                            Log.e(TAG, "获取扫描结果时权限被拒绝", e)
                            // 权限问题时不立即结束，返回当前累积结果
                            val currentResults = allAccessPoints.sortedByDescending { it.rssi }
                            Log.d(TAG, "权限问题，返回当前累积结果: ${currentResults.size} 个")
                            finishScan(currentResults)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理扫描结果时出错", e)
                            // 一般异常时不立即结束，返回当前累积结果
                            val currentResults = allAccessPoints.sortedByDescending { it.rssi }
                            Log.d(TAG, "处理异常，返回当前累积结果: ${currentResults.size} 个")
                            finishScan(currentResults)
                        }
                        
                        // 清理接收器
                        try {
                            context?.unregisterReceiver(receiver)
                            Log.d(TAG, "扫描接收器已注销")
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "Receiver not registered", e)
                        }
                    }
                }
            }
            
            // 注册广播接收器
            val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            try {
                context.registerReceiver(receiver, intentFilter)
                Log.d(TAG, "扫描广播接收器已注册")
            } catch (e: Exception) {
                Log.e(TAG, "注册接收器失败", e)
                try {
                    context.unregisterReceiver(receiver)
                } catch (ignored: Exception) {}
                finishScan(emptyList())
                return
            }
            
            // 启动扫描
            val scanStarted = wifiManager.startScan()
            Log.d(TAG, "startScan()返回值: $scanStarted")
            
            if (!scanStarted) {
                Log.w(TAG, "扫描启动失败，但仍继续等待扫描结果")
                // 不立即结束，继续等待可能的扫描结果
                // 设置超时机制
                CoroutineScope(Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(SCAN_TIMEOUT)
                    Log.w(TAG, "扫描超时，强制结束本次扫描")
                    try {
                        context?.unregisterReceiver(receiver)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Receiver not registered", e)
                    }
                    // 即使超时也返回当前累积的结果
                    val currentResults = allAccessPoints.sortedByDescending { it.rssi }
                    finishScan(currentResults)
                }
            } else {
                Log.d(TAG, "第${attemptCount}次扫描已启动")
                // 设置超时保护
                CoroutineScope(Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(SCAN_TIMEOUT)
                    Log.w(TAG, "第${attemptCount}次扫描超时")
                    try {
                        context?.unregisterReceiver(receiver)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Receiver not registered", e)
                    }
                    // 继续下一次扫描或完成
                    if (attemptCount < MAX_SCAN_ATTEMPTS) {
                        doSingleScan()
                    } else {
                        val currentResults = allAccessPoints.sortedByDescending { it.rssi }
                        finishScan(currentResults)
                    }
                }
            }
        }
        
        // 开始第一次扫描
        doSingleScan()
    }
    
    /**
     * 完成扫描并返回最终结果
     */
    private fun finishScan(accessPoints: List<AccessPoint>) {
        Log.d(TAG, "=== 扫描完成 ===")
        Log.d(TAG, "总共找到 ${accessPoints.size} 个唯一热点")
        
        // 合并相同SSID的信号
        val mergedAccessPoints = mergeSameSSIDSignals(accessPoints)
        
        // 按信号强度排序（降序）
        val sortedAccessPoints = mergedAccessPoints.sortedByDescending { it.rssi }
        
        // 详细记录最终结果
        if (sortedAccessPoints.isEmpty()) {
            Log.w(TAG, "警告：扫描结果为空列表")
            Log.d(TAG, "累积结果集大小: ${allAccessPoints.size}")
            Log.d(TAG, "去重集合大小: ${accumulatedResults.size}")
            Log.d(TAG, "合并后结果集大小: ${mergedAccessPoints.size}")
        } else {
            Log.d(TAG, "合并前热点数: ${accessPoints.size}, 合并后热点数: ${sortedAccessPoints.size}")
            sortedAccessPoints.forEachIndexed { index, ap ->
                Log.d(TAG, "最终结果[$index]: ${ap.ssid}, RSSI: ${ap.rssi}, 频率: ${ap.frequency}")
            }
        }
        
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
        
        Log.d(TAG, "权限检查详情 - WiFi状态: $hasWifiState, 改变WiFi: $hasChangeWifiState, 位置: $hasLocation")
        
        return hasWifiState && hasChangeWifiState && hasLocation
    }
    
    /**
     * 获取WiFi状态
     */
    fun isWifiEnabled(): Boolean {
        return wifiManager.isWifiEnabled
    }
    
    /**
     * 启用WiFi（需要系统权限）
     */
    fun enableWifi(): Boolean {
        return if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
            true
        } else {
            true
        }
    }
    
    /**
     * 合并相同SSID的WiFi信号
     * 对于相同SSID的多个信号，选择信号最强的那个
     * @param accessPoints 原始访问点列表
     * @return 合并后的访问点列表
     */
    private fun mergeSameSSIDSignals(accessPoints: List<AccessPoint>): List<AccessPoint> {
        if (accessPoints.isEmpty()) return emptyList()
        
        val ssidMap = mutableMapOf<String, AccessPoint>()
        
        accessPoints.forEach { ap ->
            val currentBest = ssidMap[ap.ssid]
            if (currentBest == null || ap.rssi > currentBest.rssi) {
                // 如果是新的SSID或者信号更强，则更新
                ssidMap[ap.ssid] = ap
                if (currentBest != null) {
                    Log.d(TAG, "合并信号: '${ap.ssid}' 选择了更强的信号 ${ap.rssi}dBm (原: ${currentBest.rssi}dBm)")
                }
            } else {
                Log.d(TAG, "合并信号: '${ap.ssid}' 保留现有信号 ${currentBest.rssi}dBm (新: ${ap.rssi}dBm)")
            }
        }
        
        val result = ssidMap.values.toList()
        Log.d(TAG, "信号合并完成: ${accessPoints.size} -> ${result.size} 个唯一SSID")
        return result
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