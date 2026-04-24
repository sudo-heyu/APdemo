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
 * AP Performance Monitoring Service
 *
 * Periodically samples performance data of currently connected WiFi in background
 */
class ApPerformanceMonitor : Service() {

    companion object {
        private const val TAG = "[ApPerformanceMonitor]"
        private const val SAMPLE_INTERVAL_MS = 5000L  // Sample every 5 seconds

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
        Log.d(TAG, "Monitoring service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            handler.post(sampleRunnable)
            Log.d(TAG, "Performance monitoring started")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(sampleRunnable)
        performanceCache.stopMonitoring()
        Log.d(TAG, "Performance monitoring stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sampleCurrentAp() {
        try {
            val connectionInfo: WifiInfo? = wifiManager.connectionInfo
            val ssid = connectionInfo?.ssid?.replace("\"", "")
            val rssi = connectionInfo?.rssi ?: 0

            if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") {
                // Not connected or failed to get info
                if (lastSsid != null) {
                    performanceCache.stopMonitoring()
                    lastSsid = null
                }
                return
            }

            // AP switched, restart monitoring
            if (ssid != lastSsid) {
                performanceCache.startMonitoring(ssid, rssi)
                lastSsid = ssid
                Log.i(TAG, "Switched to new AP: $ssid, RSSI: $rssi")
            } else {
                // Update current RSSI (signal may have changed)
                performanceCache.updateRssi(rssi)
            }

            // Execute sampling
            val record = performanceCache.sample()
            record?.let {
                Log.v(TAG, "${it.ssid}: rx=${it.rxSpeedKbps.toInt()}KB/s, " +
                        "connected=${it.isConnected}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Sampling failed: ${e.message}")
        }
    }
}
