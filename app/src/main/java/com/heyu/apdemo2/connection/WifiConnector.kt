package com.heyu.apdemo2.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.util.Log

/**
 * WiFi 连接管理器。
 *
 * Android 10+ (API 29+)：
 *   使用 WifiNetworkSuggestion —— 系统级真实连接，所有应用流量走此网络。
 *   首次需要用户在通知栏批准「允许 AppName 建议 WiFi」（一次性）；
 *   Android 11+ 可通过 ACTION_WIFI_ADD_NETWORKS 在应用内完成授权（由 MainActivity 处理）。
 *
 * Android 9 及以下：
 *   使用 WifiManager 传统 API（addNetwork + enableNetwork），直接替换系统 WiFi 连接。
 */
class WifiConnector(private val context: Context, private val handler: Handler) {

    private val wm = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var activeSsid: String? = null
    private var activeIsOpen: Boolean = false
    private var activePassword: String = ""

    // 当前活跃的 Suggestion 对象，断开时需要用同一对象移除
    private var activeSuggestion: WifiNetworkSuggestion? = null

    private var stateReceiver: BroadcastReceiver? = null
    private var timeoutRunnable: Runnable? = null

    // 连接成功后才计入重连次数，避免未连上就误判为掉线
    private var wasConnectedToTarget = false
    private var reconnectCount = 0

    companion object {
        private const val TAG = "[WifiConnector]"
        private const val MAX_RECONNECT = 5
        private const val CONNECT_TIMEOUT_MS = 300_000L  // 5分钟：用户在系统 UI 操作可能较慢
        private const val RECONNECT_SCAN_DELAY_MS = 3_000L
    }

    // ── 公开接口 ──────────────────────────────────────────────────────────────

    fun connect(
        ssid: String,
        isOpen: Boolean,
        password: String,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit,
        onReconnecting: (Int) -> Unit,
        onApprovalNeeded: () -> Unit
    ) {
        cleanupPrevious()
        activeSsid = ssid
        activeIsOpen = isOpen
        activePassword = password
        wasConnectedToTarget = false
        reconnectCount = 0

        Log.d(TAG, "发起连接: ssid=$ssid open=$isOpen")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectViaSuggestion(ssid, isOpen, password, onConnected, onFailed, onReconnecting, onApprovalNeeded)
        } else {
            connectLegacy(ssid, isOpen, password, onConnected, onFailed)
        }
    }

    fun disconnect() {
        Log.d(TAG, "主动断开: ${activeSsid}")
        activeSsid = null
        cleanupPrevious()
    }

    // ── Android 10+：WifiNetworkSuggestion ───────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun connectViaSuggestion(
        ssid: String,
        isOpen: Boolean,
        password: String,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit,
        onReconnecting: (Int) -> Unit,
        onApprovalNeeded: () -> Unit
    ) {
        val suggestion = buildSuggestion(ssid, isOpen, password)
        activeSuggestion = suggestion

        val status = wm.addNetworkSuggestions(listOf(suggestion))
        Log.d(TAG, "addNetworkSuggestions($ssid) → status=$status")

        when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> {
                activeSuggestion = null
                handler.post { onApprovalNeeded() }
                return
            }
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> {
                // 成功或重复添加（重复视为已添加，继续流程）
            }
            else -> {
                activeSuggestion = null
                handler.post { onFailed("添加网络建议失败 (status=$status)") }
                return
            }
        }

        // 注册广播监听连接 / 断线事件
        registerStateReceiver(ssid, onConnected, onFailed, onReconnecting)

        // 若已经连上目标网络，直接回调成功
        if (getConnectedSsid() == ssid) {
            Log.d(TAG, "已连接目标网络: $ssid，直接回调成功")
            cancelTimeout()
            wasConnectedToTarget = true
            handler.post { onConnected() }
            return
        }

        // 触发扫描（让系统知晓建议网络；Android 10+ 上用户通过系统 UI 点击后连接）
        @Suppress("DEPRECATION")
        wm.startScan()

        // 超时保护：仅检查是否已悄悄连上，若未连则静默清理（不回调失败，不弹窗）
        timeoutRunnable = Runnable {
            if (activeSsid != ssid) return@Runnable
            val current = getConnectedSsid()
            if (current == ssid) {
                wasConnectedToTarget = true
                handler.post { onConnected() }
            } else {
                Log.d(TAG, "连接监听超时，静默清理: $ssid")
                unregisterStateReceiver()
                // 不调用 onFailed，由用户主动断开或下次 connect() 触发清理
            }
        }.also { handler.postDelayed(it, CONNECT_TIMEOUT_MS) }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun buildSuggestion(ssid: String, isOpen: Boolean, password: String): WifiNetworkSuggestion {
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        if (!isOpen) {
            // 先尝试 WPA2，失败时 fallback WPA3（API 29+）
            try {
                builder.setWpa2Passphrase(password)
            } catch (_: Exception) {
                try { builder.setWpa3Passphrase(password) } catch (_: Exception) {}
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setIsUserInteractionRequired(false)
            builder.setPriority(Int.MAX_VALUE)
        }
        return builder.build()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun registerStateReceiver(
        ssid: String,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit,
        onReconnecting: (Int) -> Unit
    ) {
        unregisterStateReceiver()

        stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (activeSsid != ssid) return

                when (intent?.action) {
                    // 系统通过 Suggestion 完成连接时触发（最可靠）
                    WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION -> {
                        @Suppress("DEPRECATION")
                        val extra = intent.getParcelableExtra<WifiNetworkSuggestion>(
                            WifiManager.EXTRA_NETWORK_SUGGESTION
                        )
                        val connectedSsid = extra?.ssid ?: getConnectedSsid()
                        if (connectedSsid == ssid) {
                            Log.d(TAG, "Suggestion 连接成功: $ssid")
                            cancelTimeout()
                            wasConnectedToTarget = true
                            reconnectCount = 0
                            handler.post { onConnected() }
                        }
                    }

                    // 备用：监听系统 WiFi 状态变化
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                        val state = info?.detailedState

                        if (state == NetworkInfo.DetailedState.CONNECTED && getConnectedSsid() == ssid) {
                            Log.d(TAG, "NETWORK_STATE CONNECTED: $ssid")
                            cancelTimeout()
                            wasConnectedToTarget = true
                            reconnectCount = 0
                            handler.post { onConnected() }

                        } else if (state == NetworkInfo.DetailedState.DISCONNECTED && wasConnectedToTarget) {
                            // 确认曾经连上，现在掉线 → 触发重连
                            wasConnectedToTarget = false
                            if (reconnectCount < MAX_RECONNECT) {
                                reconnectCount++
                                Log.d(TAG, "掉线，触发重连扫描（第 $reconnectCount 次）: $ssid")
                                handler.post { onReconnecting(reconnectCount) }
                                handler.postDelayed({
                                    if (activeSsid == ssid) {
                                        @Suppress("DEPRECATION")
                                        wm.startScan() // Suggestion 仍在，系统扫描后自动重连
                                    }
                                }, RECONNECT_SCAN_DELAY_MS)
                            } else {
                                Log.w(TAG, "达到最大重连次数，放弃: $ssid")
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(stateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(stateReceiver, filter)
        }
        Log.d(TAG, "状态广播接收器已注册")
    }

    // ── Android 9 及以下：传统 API ────────────────────────────────────────────

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
            Log.d(TAG, "Legacy 连接已触发: $ssid (networkId=$networkId)")
            handler.post { onConnected() }
        } else {
            Log.w(TAG, "addNetwork 返回 -1: $ssid")
            handler.post { onFailed("无法添加网络配置，请在系统设置中手动连接") }
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    private fun cleanupPrevious() {
        cancelTimeout()
        unregisterStateReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            removeSuggestion()
        }
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun unregisterStateReceiver() {
        try { stateReceiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        stateReceiver = null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun removeSuggestion() {
        activeSuggestion?.let {
            try {
                wm.removeNetworkSuggestions(listOf(it))
                Log.d(TAG, "Suggestion 已移除")
            } catch (_: Exception) {}
            activeSuggestion = null
        }
    }

    @Suppress("DEPRECATION")
    private fun getConnectedSsid(): String? {
        val ssid = wm.connectionInfo?.ssid ?: return null
        return ssid.removePrefix("\"").removeSuffix("\"").takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }
}
