package com.heyu.apdemo2

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WifiAutoConnectService : AccessibilityService() {

    companion object {
        private const val TAG = "WifiAutoConnect"

        /** 由 MainActivity 在打开 WiFi 设置前设置 */
        @Volatile var pendingSsid: String? = null

        /** 对应网络密码（开放网络传 null） */
        @Volatile var pendingPassword: String? = null

        private const val MAX_RETRIES    = 20
        private const val RETRY_MS       = 300L
        private const val AUTO_CLEAR_MS  = 30_000L
        private const val FIRST_TRY_MS   = 800L   // WiFi 设置页首次加载等待
        private const val PWD_WAIT_MS    = 700L   // 点击 SSID 后等待密码框出现
        private const val BACK_DELAY_MS  = 600L   // 操作完成后延迟返回
    }

    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var clearRunnable: Runnable? = null
    private var retryCount = 0
    private var ssidClicked = false   // 已点击 SSID，等待后续处理

    // ── 生命周期 ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (pendingSsid == null && !ssidClicked) return
        if (ssidClicked) return   // 已点击，等待密码阶段的延迟任务
        scheduleRetry()
    }

    // ── 阶段一：查找并点击 SSID ─────────────────────────────────────────────

    private fun scheduleRetry() {
        if (retryRunnable != null) return

        retryRunnable = object : Runnable {
            override fun run() {
                val ssid = pendingSsid
                if (ssid == null) { resetState(); return }

                if (tryClickSsid(ssid)) {
                    Log.d(TAG, "SSID 点击成功: $ssid")
                    ssidClicked = true
                    pendingSsid = null
                    retryRunnable = null
                    cancelClearTimer()

                    val pwd = pendingPassword
                    if (!pwd.isNullOrEmpty()) {
                        // 等待系统弹出密码输入框再处理
                        handler.postDelayed({ handlePasswordPhase(pwd) }, PWD_WAIT_MS)
                    } else {
                        // 开放网络或已保存密码：直接返回
                        scheduleBack()
                    }
                } else if (retryCount < MAX_RETRIES) {
                    retryCount++
                    handler.postDelayed(this, RETRY_MS)
                } else {
                    Log.w(TAG, "重试耗尽，未找到 SSID: $ssid")
                    cancelClearTimer()
                    resetState()
                    // 未能点击：用户仍在 WiFi 设置页，可手动操作
                }
            }
        }

        val delay = if (retryCount == 0) FIRST_TRY_MS else RETRY_MS
        handler.postDelayed(retryRunnable!!, delay)

        if (clearRunnable == null) {
            clearRunnable = Runnable { Log.d(TAG, "超时清理"); resetState() }
            handler.postDelayed(clearRunnable!!, AUTO_CLEAR_MS)
        }
    }

    private fun tryClickSsid(ssid: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(ssid)
        if (nodes.isNullOrEmpty()) return false

        // 优先精确匹配
        for (node in nodes) {
            val text = node.text?.toString()?.trim('"') ?: ""
            val desc = node.contentDescription?.toString()?.trim('"') ?: ""
            if (text == ssid || desc == ssid) {
                findClickableAncestor(node)?.let {
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }
        // 降级：唯一候选时接受
        if (nodes.size == 1) {
            findClickableAncestor(nodes[0])?.let {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    // ── 阶段二：处理密码输入框 ───────────────────────────────────────────────

    private fun handlePasswordPhase(password: String) {
        val root = rootInActiveWindow
        if (root != null) {
            val pwdNode = findPasswordNode(root)
            if (pwdNode != null) {
                // 找到密码框，填入密码
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        password
                    )
                }
                pwdNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                // 找到并点击「连接」按钮
                val btn = findConnectButton(root)
                if (btn != null) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "密码已填写，点击连接")
                } else {
                    Log.w(TAG, "未找到连接按钮")
                }
            } else {
                Log.d(TAG, "未出现密码框（网络已保存）")
            }
        }
        // 无论结果如何，延迟返回
        scheduleBack()
    }

    private fun findPasswordNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.className?.contains("EditText") == true) {
                val t = node.inputType
                val isPassword =
                    t and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0 ||
                    t and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0 ||
                    t and InputType.TYPE_NUMBER_VARIATION_PASSWORD != 0 ||
                    t == 0x81 || t == 0x91   // 常见密码 inputType 原始值
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

    // ── 返回上一页 ──────────────────────────────────────────────────────────

    private fun scheduleBack() {
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            resetState()
        }, BACK_DELAY_MS)
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(8) {
            if (cur?.isClickable == true) return cur
            cur = cur?.parent
        }
        return null
    }

    private fun resetState() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        retryCount = 0
        ssidClicked = false
        pendingSsid = null
        pendingPassword = null
    }

    private fun cancelClearTimer() {
        clearRunnable?.let { handler.removeCallbacks(it) }
        clearRunnable = null
    }

    override fun onInterrupt() { cancelClearTimer(); resetState() }
    override fun onDestroy() { super.onDestroy(); cancelClearTimer(); resetState() }
}
