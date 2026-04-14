package com.heyu.apdemo2.roaming

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * AP 性能监控服务
 *
 * 在后台定期采样当前连接 WiFi 的性能数据
 */
class ApPerformanceMonitor : Service() {

    companion object {
        private const val TAG = "[ApPerformanceMonitor]"
        private const val SAMPLE_INTERVAL_MS = 5000L  // 每 5 秒采样一次

        fun start(context: Context) {
            val intent = Intent(context, ApPerformanceMonitor::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ApPerformanceMonitor::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var performanceCache: ApPerformanceCache
    private lateinit var wifiManager: WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastSsid: String? = null
    private var isRunning = false

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            sampleCurrentAp()
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        performanceCache = ApPerformanceCache.getInstance(this)
        wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        Log.d(TAG, "监控服务创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            handler.post(sampleRunnable)
            Log.d(TAG, "性能监控启动")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(sampleRunnable)
        performanceCache.stopMonitoring()
        Log.d(TAG, "性能监控停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sampleCurrentAp() {
        try {
            val connectionInfo: WifiInfo? = wifiManager.connectionInfo
            val ssid = connectionInfo?.ssid?.replace("\"", "")
            val rssi = connectionInfo?.rssi ?: 0

            if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") {
                // 未连接或获取失败
                if (lastSsid != null) {
                    performanceCache.stopMonitoring()
                    lastSsid = null
                }
                return
            }

            // AP 切换了，重新开始监控
            if (ssid != lastSsid) {
                performanceCache.startMonitoring(ssid, rssi)
                lastSsid = ssid
                Log.i(TAG, "切换到新 AP: $ssid, RSSI: $rssi")
            } else {
                // 更新当前 RSSI（信号可能变化）
                performanceCache.updateRssi(rssi)
            }

            // 执行采样
            val record = performanceCache.sample()
            record?.let {
                Log.v(TAG, "${it.ssid}: rx=${it.rxSpeedKbps.toInt()}KB/s, " +
                        "connected=${it.isConnected}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "采样失败: ${e.message}")
        }
    }
}
