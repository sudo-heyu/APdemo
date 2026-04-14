package com.heyu.apdemo2.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
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
 * WiFi 无障碍自动连接服务
 *
 * 架构：
 * - 手动连接（Fragment 触发）：Fragment 打开 WiFi 设置页（同任务栈），服务自动点击后 BACK 一次回到 App
 * - 漫游（后台触发）：服务自己打开 WiFi 设置页，完成后用 returnToApp() 回到 App
 *
 * 核心逻辑：简单重试（每 300ms 扫描一次节点树），不依赖状态机。
 */
class WifiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "[WifiAccessibility]"

        private const val MAX_RETRIES    = 20
        private const val RETRY_MS       = 300L
        private const val AUTO_CLEAR_MS  = 30_000L
        private const val FIRST_TRY_MS   = 800L
        private const val PWD_WAIT_MS    = 700L
        private const val BACK_DELAY_MS  = 200L

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

    // ── 当前连接任务 ──────────────────────────────────────────────────────────

    @Volatile private var targetSsid: String? = null
    @Volatile private var targetPassword: String? = null   // null = 开放网络
    private var connectionCallback: ConnectionCallback? = null
    /** true = 服务自己打开了 WiFi 设置（漫游场景），完成后需要 returnToApp */
    @Volatile private var openedByService: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var clearRunnable: Runnable? = null
    private var retryCount = 0
    private var ssidClicked = false
    private var wifiReceiver: BroadcastReceiver? = null

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "服务被中断")
        resetState()
    }

    override fun onDestroy() {
        instance = null
        resetState()
        super.onDestroy()
    }

    // ── 公开 API ─────────────────────────────────────────────────────────────

    /**
     * 手动连接：Fragment 自己打开 WiFi 设置（同任务栈），服务只负责自动化 + 按一次 BACK 返回。
     * 调用方需在调用此方法后立即执行 startActivity(Settings.ACTION_WIFI_SETTINGS)。
     */
    fun prepareManualConnect(
        ssid: String,
        password: String?,
        isOpen: Boolean,
        callback: ConnectionCallback
    ) {
        resetState()
        targetSsid       = ssid
        targetPassword   = if (isOpen) null else password
        connectionCallback = callback
        openedByService  = false
        registerWifiReceiver()
        Log.i(TAG, "准备手动连接: $ssid, 开放=$isOpen")
    }

    /**
     * 漫游/后台连接：服务自己打开 WiFi 设置，完成后 returnToApp() 返回。
     */
    fun connectFromBackground(
        ssid: String,
        password: String?,
        isOpen: Boolean,
        callback: ConnectionCallback
    ) {
        resetState()
        targetSsid       = ssid
        targetPassword   = if (isOpen) null else password
        connectionCallback = callback
        openedByService  = true
        registerWifiReceiver()
        Log.i(TAG, "后台连接: $ssid, 开放=$isOpen")

        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开 WiFi 设置失败: ${e.message}")
            val cb = connectionCallback
            resetState()
            cb?.onFailed(ssid, "无法打开 WiFi 设置页")
        }
    }

    /** 取消当前任务，恢复空闲 */
    fun cancel() {
        if (targetSsid != null || ssidClicked) {
            Log.i(TAG, "取消连接任务: $targetSsid")
            resetState()
        }
    }

    // ── 无障碍事件入口 ────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (targetSsid == null && !ssidClicked) return
        if (ssidClicked) return   // 已点击 SSID，后续由 handler 延迟任务处理
        scheduleRetry()
    }

    // ── 阶段一：查找并点击 SSID（含重试）────────────────────────────────────

    private fun scheduleRetry() {
        if (retryRunnable != null) return   // 已在队列中，不重复安排

        retryRunnable = object : Runnable {
            override fun run() {
                val ssid = targetSsid
                if (ssid == null) { resetState(); return }

                if (tryClickSsid(ssid)) {
                    Log.i(TAG, "已点击 SSID: $ssid")
                    ssidClicked = true
                    targetSsid  = null
                    retryRunnable = null
                    cancelClearTimer()

                    val pwd = targetPassword
                    if (!pwd.isNullOrEmpty()) {
                        handler.postDelayed({ handlePasswordPhase(pwd) }, PWD_WAIT_MS)
                    }
                    // 开放/已保存网络：不主动导航，等 WiFi 广播确认后再返回
                } else if (retryCount < MAX_RETRIES) {
                    retryCount++
                    handler.postDelayed(this, RETRY_MS)
                } else {
                    Log.w(TAG, "重试耗尽，未找到 SSID: $ssid")
                    cancelClearTimer()
                    val cb = connectionCallback
                    resetState()
                    cb?.onFailed(ssid, "未在 WiFi 列表中找到目标网络")
                }
            }
        }

        val delay = if (retryCount == 0) FIRST_TRY_MS else RETRY_MS
        handler.postDelayed(retryRunnable!!, delay)

        // 超时保护：30s 后清理内部状态 + 回调失败，但不导航（用户仍留在 WiFi 设置页）
        if (clearRunnable == null) {
            clearRunnable = Runnable {
                Log.w(TAG, "自动超时清理（不导航，由用户自行返回）")
                val ssid = targetSsid ?: if (ssidClicked) "unknown" else return@Runnable
                val cb   = connectionCallback
                resetState()
                cb?.onFailed(ssid, "连接超时")
            }
            handler.postDelayed(clearRunnable!!, AUTO_CLEAR_MS)
        }
    }

    private fun tryClickSsid(ssid: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(ssid)
        if (nodes.isNullOrEmpty()) return false

        // 精确匹配 text 或 contentDescription
        for (node in nodes) {
            val text = node.text?.toString()?.trim('"') ?: ""
            val desc = node.contentDescription?.toString()?.trim('"') ?: ""
            if (text == ssid || desc == ssid) {
                findClickableAncestor(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        // 仅有一个候选时降级接受
        if (nodes.size == 1) {
            findClickableAncestor(nodes[0])?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return false
    }

    // ── 阶段二：填写密码并点击"连接" ─────────────────────────────────────────

    private fun handlePasswordPhase(password: String) {
        val root = rootInActiveWindow
        if (root != null) {
            val pwdNode = findPasswordNode(root)
            if (pwdNode != null) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        password
                    )
                }
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.i(TAG, "已填写密码")

                val btn = findConnectButton(root)
                if (btn != null) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "已点击连接按钮")
                } else {
                    Log.w(TAG, "未找到连接按钮，等待广播确认")
                }
            } else {
                Log.d(TAG, "密码框未出现（网络已保存），等待广播确认")
            }
        }
        // 填写密码并点击连接后，不主动导航，等 WiFi 广播确认连接成功后再返回
    }

    /**
     * 把 App 现有任务拉回前台，不创建新 Activity、不破坏返回栈。
     * 优先用 AppTask.moveToFront()（精确），失败时降级为 LaunchIntent。
     * 仅用于后台（漫游）场景，手动场景直接 BACK 即可。
     */
    private fun returnToApp() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val task = am.appTasks.firstOrNull()
            if (task != null) {
                task.moveToFront()
                Log.i(TAG, "已通过 moveToFront 返回 App")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "moveToFront 失败: ${e.message}")
        }
        // 降级：通过 LaunchIntent 拉起（FLAG_SINGLE_TOP 保证不重建已有实例）
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: return
            startActivity(intent)
            Log.i(TAG, "已通过 LaunchIntent 返回 App")
        } catch (e: Exception) {
            Log.w(TAG, "返回 App 失败: ${e.message}")
        }
    }

    // ── WiFi 连接广播（结果确认）────────────────────────────────────────────

    private fun registerWifiReceiver() {
        unregisterWifiReceiver()
        wifiReceiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return
                val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    ?: return
                if (info.detailedState == NetworkInfo.DetailedState.CONNECTED) {
                    val connected = getCurrentSsid() ?: return
                    val target = targetSsid   // 注意：此时 targetSsid 可能已为 null（ssidClicked 阶段清空）
                    // ssidClicked 为 true 时说明我们已操作，且还未被其他连接覆盖
                    if (ssidClicked && (target == null || target == connected)) {
                        Log.i(TAG, "广播确认连接成功: $connected，准备返回 App")
                        val cb     = connectionCallback
                        val ssid   = connected
                        val fromBg = openedByService
                        resetState()
                        // 确认连接成功后才导航返回，避免未连上就跳走
                        handler.postDelayed({
                            if (fromBg) returnToApp() else performGlobalAction(GLOBAL_ACTION_BACK)
                            cb?.onConnected(ssid)
                        }, BACK_DELAY_MS)
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
        try { wifiReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        wifiReceiver = null
    }

    @Suppress("DEPRECATION")
    private fun getCurrentSsid(): String? {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid ?: return null
        return ssid.removePrefix("\"").removeSuffix("\"")
            .takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }

    // ── 工具：节点查找 ────────────────────────────────────────────────────────

    private fun findPasswordNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.className?.contains("EditText") == true) {
                val t = node.inputType
                val isPassword =
                    (t and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) ||
                    (t and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0) ||
                    (t and InputType.TYPE_NUMBER_VARIATION_PASSWORD != 0) ||
                    t == 0x81 || t == 0x91
                if (isPassword) return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun findConnectButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (text in listOf("连接", "加入", "Connect", "Join", "确定", "OK")) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) return node
                findClickableAncestor(node)?.let { return it }
            }
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(8) {
            if (cur?.isClickable == true) return cur
            cur = cur?.parent
        }
        return null
    }

    // ── 状态清理 ──────────────────────────────────────────────────────────────

    private fun resetState() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        retryCount    = 0
        ssidClicked   = false
        targetSsid    = null
        targetPassword = null
        connectionCallback = null
        openedByService = false
        cancelClearTimer()
        unregisterWifiReceiver()
    }

    private fun cancelClearTimer() {
        clearRunnable?.let { handler.removeCallbacks(it) }
        clearRunnable = null
    }
}
