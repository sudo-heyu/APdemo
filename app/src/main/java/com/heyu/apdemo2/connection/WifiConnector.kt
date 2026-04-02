package com.heyu.apdemo2.connection

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.util.Log

/**
 * WiFi 连接管理器（已精简）。
 *
 * 【重要】Android 10+ 请使用 ScanForegroundService.connectWithSpecifier()，
 * 此类仅保留用于 Android 9 及以下设备的传统连接方式。
 *
 * Android 9 及以下：使用 WifiManager 传统 API（addNetwork + enableNetwork），直接替换系统 WiFi 连接。
 */
class WifiConnector(private val context: Context, private val handler: Handler) {

    private val wm = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    companion object {
        private const val TAG = "[WifiConnector]"
    }

    /**
     * 连接到指定 WiFi 网络。
     * 【注意】Android 10+ 上此方法会失败，请使用 WifiNetworkSpecifier 方式。
     */
    fun connect(
        ssid: String,
        isOpen: Boolean,
        password: String,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            onFailed("Android 10+ 请使用 WifiNetworkSpecifier 方式连接")
            return
        }
        connectLegacy(ssid, isOpen, password, onConnected, onFailed)
    }

    fun disconnect() {
        Log.d(TAG, "主动断开")
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
            SSID = ""$ssid""
            if (isOpen) {
                allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = ""$password""
                allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
            }
        }
        val networkId = wm.addNetwork(config)
        if (networkId != -1) {
            wm.disconnect()
            wm.enableNetwork(networkId, true)
            wm.reconnect()
            Log.d(TAG, "Legacy 连接已触发: $ssid (networkId=$networkId)")
            handler.post { onConnected() }
        } else {
            Log.w(TAG, "addNetwork 返回 -1: $ssid")
            handler.post { onFailed("无法添加网络配置，请在系统设置中手动连接") }
        }
    }
}
