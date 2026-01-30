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
        private const val SCAN_TIMEOUT = 10000L // 10秒超时
        private const val MAX_SCAN_ATTEMPTS = 1 // 只扫描一次
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
                val errorMsg = "WiFi未开启，请先开启WiFi"
                Log.w(TAG, errorMsg)
                isScanInProgress = false
                errorCallback?.invoke(errorMsg)
                return
            }
            
            // 检查权限
            val hasPermissions = hasRequiredPermissions()
            Log.d(TAG, "权限检查结果: $hasPermissions")
            if (!hasPermissions) {
                val errorMsg = "缺少必要权限，请授予WiFi和位置权限"
                Log.w(TAG, errorMsg)
                isScanInProgress = false
                errorCallback?.invoke(errorMsg)
                return
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
     * 执行单次扫描
     */
    private fun performEnhancedScan() {
        // 使用类级别的累积数据结构
        var attemptCount = 0
        
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
                            Log.d(TAG, "获取到 ${scanResults.size} 个扫描结果")
                            
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
                            
                            // 按信号强度排序
                            val sortedAccessPoints = allAccessPoints.sortedByDescending { it.rssi }
                            
                            // 调用渐进式更新回调
                            Log.d(TAG, "调用渐进式更新回调，总计: ${sortedAccessPoints.size} 个热点")
                            progressiveCallback?.invoke(sortedAccessPoints, attemptCount, newResultsCount)
                            Log.d(TAG, "扫描完成，累计 ${sortedAccessPoints.size} 个热点，新增 $newResultsCount 个")
                            
                            // 完成扫描
                            finishScan(sortedAccessPoints)
                            
                        } catch (e: SecurityException) {
                            Log.e(TAG, "获取扫描结果时权限被拒绝", e)
                            finishScan(emptyList())
                        } catch (e: Exception) {
                            Log.e(TAG, "处理扫描结果时出错", e)
                            finishScan(emptyList())
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
                Log.w(TAG, "扫描启动失败")
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Receiver not registered", e)
                }
                finishScan(emptyList())
            } else {
                Log.d(TAG, "扫描已启动")
            }
        }
        
        // 开始扫描
        doSingleScan()
    }
    
    /**
     * 完成扫描并返回最终结果
     */
    private fun finishScan(accessPoints: List<AccessPoint>) {
        Log.d(TAG, "=== 扫描完成 ===")
        Log.d(TAG, "总共找到 ${accessPoints.size} 个唯一热点")
        
        // 按信号强度排序（降序）
        val sortedAccessPoints = accessPoints.sortedByDescending { it.rssi }
        
        // 详细记录最终结果
        sortedAccessPoints.forEachIndexed { index, ap ->
            Log.d(TAG, "最终结果[$index]: ${ap.ssid}, RSSI: ${ap.rssi}, 频率: ${ap.frequency}")
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
     * 清空累积的扫描结果，为新周期做准备
     */
    fun clearAccumulatedResults() {
        accumulatedResults.clear()
        allAccessPoints.clear()
        Log.d(TAG, "已清空累积的扫描结果")
    }
}