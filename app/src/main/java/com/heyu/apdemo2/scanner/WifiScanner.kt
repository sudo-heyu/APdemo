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
 * WiFi Scanner Manager (single scan mode)
 * Each call to startScan triggers one system scan and returns deduplicated results.
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
            onError("WiFi is not enabled")
            return
        }

        val timeoutTask = Runnable {
            if (!isScanInProgress) return@Runnable
            Log.w(TAG, "Scan timeout, using cached results")
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
                Log.w(TAG, "Scan throttled, using cached results")
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
            Log.e(TAG, "Scan start exception: ${e.message}")
            stopScan()
            onError("Failed to start scan")
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
            // Keep only strongest signal for each SSID
            val merged = accessPoints
                .groupBy { it.ssid }
                .map { (_, aps) -> aps.maxByOrNull { it.rssi }!! }
                .sortedByDescending { it.rssi }

            onSuccess(merged)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing scan results: ${e.message}")
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
