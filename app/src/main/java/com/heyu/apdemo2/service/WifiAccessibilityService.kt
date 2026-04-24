package com.heyu.apdemo2.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * WiFi Accessibility Auto-Connect Service
 *
 * 稳定版本：
 * 1. 主流程：点击SSID → 处理密码 → 等待WiFi广播确认 → 返回
 * 2. 保底：超时后强制返回
 */
class WifiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "[WifiAccessibility]"

        private const val MAX_RETRIES       = 20
        private const val RETRY_MS          = 300L
        private const val FIRST_TRY_MS      = 500L
        private const val PWD_WAIT_MS       = 500L
        private const val TOTAL_TIMEOUT_MS  = 20_000L
        private const val FORCE_RETURN_MS   = 5_000L   // 点击SSID后多久强制返回

        @Volatile private var instance: WifiAccessibilityService? = null
        fun getInstance(): WifiAccessibilityService? = instance

        fun isEnabled(context: Context): Boolean {
            val id = "${context.packageName}/${WifiAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(id, ignoreCase = true) }
        }
    }

    interface ConnectionCallback {
        fun onConnected(ssid: String)
        fun onFailed(ssid: String, reason: String)
    }

    // ── State ──────────────────────────────────────────────────────────

    @Volatile private var targetSsid: String? = null
    @Volatile private var targetPassword: String? = null
    private var connectionCallback: ConnectionCallback? = null
    @Volatile private var openedByService: Boolean = false

    @Volatile private var ssidClicked: Boolean = false
    @Volatile private var waitingForConnection: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var totalTimeoutRunnable: Runnable? = null
    private var forceReturnRunnable: Runnable? = null
    private var retryCount = 0
    private var wifiReceiver: BroadcastReceiver? = null

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "Service unbound")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
        resetState()
    }

    override fun onDestroy() {
        instance = null
        resetState()
        super.onDestroy()
    }

    // ── Public API ─────────────────────────────────────────────────────

    fun prepareManualConnect(
        ssid: String,
        password: String?,
        isOpen: Boolean,
        callback: ConnectionCallback
    ) {
        resetState()
        targetSsid = ssid
        targetPassword = if (isOpen) null else password
        connectionCallback = callback
        openedByService = false
        ssidClicked = false
        waitingForConnection = false
        registerWifiReceiver()
        scheduleTotalTimeout()
        Log.i(TAG, "Manual connection prepared: $ssid")
    }

    fun connectFromBackground(
        ssid: String,
        password: String?,
        isOpen: Boolean,
        callback: ConnectionCallback
    ) {
        resetState()
        targetSsid = ssid
        targetPassword = if (isOpen) null else password
        connectionCallback = callback
        openedByService = true
        ssidClicked = false
        waitingForConnection = false
        registerWifiReceiver()
        scheduleTotalTimeout()
        Log.i(TAG, "Background connection: $ssid")

        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WiFi settings: ${e.message}")
            val cb = connectionCallback
            resetState()
            cb?.onFailed(ssid, "Unable to open WiFi settings")
        }
    }

    fun cancel() {
        if (targetSsid != null || ssidClicked) {
            Log.i(TAG, "Cancelling")
            resetState()
        }
    }

    // ── Accessibility Event ───────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (targetSsid == null && !ssidClicked) return

        // 还没点击SSID，尝试点击
        if (!ssidClicked && targetSsid != null) {
            scheduleSsidClick()
        }
    }

    // ── Phase 1: Click SSID ──────────────────────────────────────────────

    private fun scheduleSsidClick() {
        if (retryRunnable != null) return
        if (ssidClicked) return

        val runnable = object : Runnable {
            override fun run() {
                val ssid = targetSsid
                if (ssid == null) {
                    resetState()
                    return
                }

                if (tryClickSsid(ssid)) {
                    Log.i(TAG, "SSID clicked: $ssid")
                    ssidClicked = true
                    retryRunnable = null
                    retryCount = 0

                    // 安排强制返回保底
                    scheduleForceReturn()

                    // 如果有密码，等待密码对话框
                    val pwd = targetPassword
                    if (!pwd.isNullOrEmpty()) {
                        handler.postDelayed({ handlePasswordDialog(pwd) }, PWD_WAIT_MS)
                    }
                    // 无密码或已保存网络：等待WiFi广播确认连接成功
                } else if (retryCount < MAX_RETRIES) {
                    retryCount++
                    handler.postDelayed(this, RETRY_MS)
                } else {
                    Log.w(TAG, "SSID not found: $ssid")
                    val cb = connectionCallback
                    resetState()
                    cb?.onFailed(ssid, "Network not found")
                }
            }
        }

        retryRunnable = runnable
        handler.postDelayed(runnable, FIRST_TRY_MS)
    }

    private fun tryClickSsid(ssid: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(ssid)
        if (nodes.isNullOrEmpty()) return false

        for (node in nodes) {
            val text = node.text?.toString()?.trim('"') ?: ""
            val desc = node.contentDescription?.toString()?.trim('"') ?: ""

            if (text == ssid || desc == ssid || text.contains(ssid)) {
                if (performClick(node)) return true
                if (clickClickableParent(node)) return true
            }
        }

        // 只有一个候选时，直接点击
        if (nodes.size == 1) {
            if (performClick(nodes[0])) return true
            if (clickClickableParent(nodes[0])) return true
        }

        return false
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Log.e(TAG, "Click failed: ${e.message}")
            false
        }
    }

    private fun clickClickableParent(node: AccessibilityNodeInfo): Boolean {
        var cur = node.parent
        repeat(5) {
            if (cur?.isClickable == true) {
                return try {
                    cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (e: Exception) {
                    false
                }
            }
            cur = cur?.parent
        }
        return false
    }

    // ── Phase 2: Handle Password ─────────────────────────────────────────

    private fun handlePasswordDialog(password: String) {
        if (!ssidClicked) return

        val root = rootInActiveWindow ?: return

        val pwdNode = findPasswordNode(root)
        if (pwdNode != null) {
            // 输入密码
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    password
                )
            }
            pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "Password entered")

            // 点击连接按钮
            handler.postDelayed({
                val currentRoot = rootInActiveWindow ?: return@postDelayed
                val btn = findConnectButton(currentRoot)
                if (btn != null) {
                    performClick(btn) || clickClickableParent(btn)
                    Log.i(TAG, "Connect button clicked")
                }
            }, 300L)
        } else {
            // 没找到密码框，可能网络已保存
            Log.d(TAG, "No password field, network may be saved")
        }
        // 之后等待WiFi广播确认连接成功
    }

    // ── Phase 3: WiFi Broadcast (Connection Confirmation) ───────────────

    private fun registerWifiReceiver() {
        unregisterWifiReceiver()
        wifiReceiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return
                val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    ?: return

                if (info.detailedState == NetworkInfo.DetailedState.CONNECTED) {
                    val connectedSsid = getCurrentSsid()
                    Log.d(TAG, "WiFi connected: $connectedSsid, ssidClicked=$ssidClicked")

                    if (ssidClicked && connectedSsid != null) {
                        // 确认连接成功
                        val target = targetSsid
                        if (target == null || target == connectedSsid) {
                            Log.i(TAG, "Connection confirmed: $connectedSsid")
                            onConnectionSuccess(connectedSsid)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(wifiReceiver, filter)
        }
    }

    private fun unregisterWifiReceiver() {
        try {
            wifiReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Unregister receiver failed: ${e.message}")
        }
        wifiReceiver = null
    }

    @Suppress("DEPRECATION")
    private fun getCurrentSsid(): String? {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid ?: return null
        return ssid.removePrefix("\"").removeSuffix("\"")
            .takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }

    private fun onConnectionSuccess(ssid: String) {
        val cb = connectionCallback
        val fromBg = openedByService
        resetState()

        handler.postDelayed({
            Log.i(TAG, "Returning, fromBg=$fromBg")
            if (fromBg) {
                returnToApp()
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            cb?.onConnected(ssid)
        }, 300L)
    }

    // ── Force Return (Fallback) ──────────────────────────────────────────

    private fun scheduleForceReturn() {
        cancelForceReturn()
        forceReturnRunnable = Runnable {
            if (!ssidClicked) return@Runnable

            Log.i(TAG, "Force return triggered (timeout)")
            val ssid = targetSsid ?: "unknown"
            val cb = connectionCallback
            val fromBg = openedByService
            resetState()

            handler.postDelayed({
                Log.i(TAG, "Force returning, fromBg=$fromBg")
                if (fromBg) {
                    returnToApp()
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                cb?.onConnected(ssid)
            }, 200L)
        }
        handler.postDelayed(forceReturnRunnable!!, FORCE_RETURN_MS)
    }

    private fun returnToApp() {
        // 优先使用 BACK，最稳定
        Log.d(TAG, "Returning via GLOBAL_ACTION_BACK")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ── Total Timeout ────────────────────────────────────────────────────

    private fun scheduleTotalTimeout() {
        cancelTotalTimeout()
        totalTimeoutRunnable = Runnable {
            Log.w(TAG, "Total timeout")
            val ssid = targetSsid ?: "unknown"
            val cb = connectionCallback
            resetState()
            cb?.onFailed(ssid, "Connection timeout")
        }
        handler.postDelayed(totalTimeoutRunnable!!, TOTAL_TIMEOUT_MS)
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private fun findPasswordNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val className = node.className?.toString() ?: ""
            if (className.contains("EditText") || className.contains("Edit")) {
                val t = node.inputType
                val isPassword =
                    (t and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) ||
                    (t and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0) ||
                    t == 0x81 || t == 0x91 || t == 0x12
                if (isPassword) return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findConnectButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val texts = listOf("连接", "Connect", "加入", "Join", "确定", "OK")
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) return node
                var parent = node.parent
                repeat(3) {
                    if (parent?.isClickable == true) return parent
                    parent = parent?.parent
                }
            }
        }
        return null
    }

    // ── State Cleanup ──────────────────────────────────────────────────────

    private fun resetState() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        totalTimeoutRunnable?.let { handler.removeCallbacks(it) }
        forceReturnRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        totalTimeoutRunnable = null
        forceReturnRunnable = null
        retryCount = 0
        targetSsid = null
        targetPassword = null
        connectionCallback = null
        openedByService = false
        ssidClicked = false
        waitingForConnection = false
        unregisterWifiReceiver()
    }

    private fun cancelTotalTimeout() {
        totalTimeoutRunnable?.let { handler.removeCallbacks(it) }
        totalTimeoutRunnable = null
    }

    private fun cancelForceReturn() {
        forceReturnRunnable?.let { handler.removeCallbacks(it) }
        forceReturnRunnable = null
    }
}
