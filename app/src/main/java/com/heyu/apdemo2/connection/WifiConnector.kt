package com.heyu.apdemo2.connection

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.util.Log

/**
 * WiFi Connection Manager (simplified).
 *
 * [IMPORTANT] On Android 10+, please use ScanForegroundService.connectWithSpecifier(),
 * this class is only kept for legacy connection methods on Android 9 and below.
 *
 * Android 9 and below: Uses WifiManager legacy API (addNetwork + enableNetwork),
 * directly replaces system WiFi connection.
 */
class WifiConnector(private val context: Context, private val handler: Handler) {

    private val wm = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    companion object {
        private const val TAG = "[WifiConnector]"
    }

    /**
     * Connect to specified WiFi network.
     * [NOTE] This method will fail on Android 10+, please use WifiNetworkSpecifier method.
     */
    fun connect(
        ssid: String,
        isOpen: Boolean,
        password: String,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            onFailed("Please use WifiNetworkSpecifier method for Android 10+")
            return
        }
        connectLegacy(ssid, isOpen, password, onConnected, onFailed)
    }

    fun disconnect() {
        Log.d(TAG, "Active disconnect")
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(
        ssid: String,
        isOpen: Boolean,
        password: String,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        val config = android.net.wifi.WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (isOpen) {
                allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$password\""
                allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
            }
        }
        val networkId = wm.addNetwork(config)
        if (networkId != -1) {
            wm.disconnect()
            wm.enableNetwork(networkId, true)
            wm.reconnect()
            Log.d(TAG, "Legacy connection triggered: $ssid (networkId=$networkId)")
            handler.post { onConnected() }
        } else {
            Log.w(TAG, "addNetwork returned -1: $ssid")
            handler.post { onFailed("Cannot add network configuration, please connect manually in system settings") }
        }
    }
}
