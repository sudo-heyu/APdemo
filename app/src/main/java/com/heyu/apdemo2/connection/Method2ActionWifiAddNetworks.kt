package com.heyu.apdemo2.connection

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment

/**
 * 方式二：ACTION_WIFI_ADD_NETWORKS (Android 11+)
 *
 * API 级别：Android 11 (API 30+)
 * 连接途径：Intent(Settings.ACTION_WIFI_ADD_NETWORKS) → 系统弹窗 → 用户确认 → 立即连接
 * 特点：系统级弹窗，用户确认后立即触发真实 WiFi 切换
 *
 * 【应该有的现象】
 * 1. 弹出系统对话框（白色/灰色背景，标题"连接到网络"，按钮为系统样式）
 * 2. 对话框显示：网络名称、安全类型（如 WPA2）
 * 3. 用户点击"连接"后，对话框消失，状态栏 WiFi 图标开始切换
 * 4. 约 1-3 秒后 WiFi 连接成功
 *
 * 【注意】如果看到的是蓝色调应用内弹窗，说明走到了错误的分支！
 */
class Method2ActionWifiAddNetworks(
    private val fragment: Fragment,
    private val callback: ConnectionCallback
) {
    companion object {
        private const val TAG = "[METHOD2_ActionWiFi]"
    }

    interface ConnectionCallback {
        /** 日志输出，用于调试 */
        fun onLog(message: String)
        /** 系统弹窗已显示，等待用户操作 */
        fun onSystemDialogShown(ssid: String)
        /** 用户点击了系统弹窗的"连接"按钮（RESULT_OK） */
        fun onUserConfirmed(ssid: String)
        /** 用户点击了系统弹窗的"取消"按钮（RESULT_CANCELED） */
        fun onUserCancelled(ssid: String)
        /** 添加网络失败（密码错误或网络无效） */
        fun onAddFailed(ssid: String, reason: String)
        /** 系统不支持此功能 */
        fun onNotSupported(ssid: String)
        /** 连接成功（通过广播确认） */
        fun onSuccess(ssid: String)
        /** 网络已存在，需要 Fragment 决定如何处理（如使用无障碍服务） */
        fun onAlreadyExists(ssid: String, password: String, capabilities: String)
    }

    private var connectingSsid: String? = null
    private var connectingPassword: String? = null
    private var connectingCapabilities: String? = null
    private var reconnectRetryCount: Int = 0
    private val MAX_RECONNECT_RETRIES = 1  // 最多重试1次

    /** ActivityResultLauncher，必须在 Fragment.onCreate() 中初始化 */
    val launcher: ActivityResultLauncher<Intent> = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleActivityResult(result.resultCode, result.data)
    }

    /**
     * 执行连接 - 弹出系统 WiFi 连接对话框
     *
     * @param ssid WiFi 名称
     * @param password 密码（开放网络传空字符串）
     * @param capabilities 网络能力描述
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun connect(ssid: String, password: String, capabilities: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback.onNotSupported(ssid)
            return false
        }

        connectingSsid = ssid
        connectingPassword = password
        connectingCapabilities = capabilities
        callback.onLog("=".repeat(50))
        callback.onLog("【方式二】启动系统 WiFi 连接弹窗")
        callback.onLog("目标网络: $ssid")
        callback.onLog("网络类型: $capabilities")
        callback.onLog("系统版本: API ${Build.VERSION.SDK_INT}")

        // 构建 Suggestion
        val suggestion = buildSuggestion(ssid, password, capabilities)

        // 创建 Intent - 系统弹窗
        val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
            putParcelableArrayListExtra(
                Settings.EXTRA_WIFI_NETWORK_LIST,
                arrayListOf(suggestion)
            )
        }

        // 检查是否有应用能处理此 Intent
        val pm = fragment.requireContext().packageManager
        val resolveInfo = pm.resolveActivity(intent, 0)
        if (resolveInfo == null) {
            callback.onLog("警告: 系统无法处理 ACTION_WIFI_ADD_NETWORKS")
            callback.onLog("将尝试跳转到 WiFi 设置页")
            fallbackToWifiSettings(ssid)
            return false
        }

        callback.onLog("Intent 可解析: ${resolveInfo.activityInfo?.packageName}")
        callback.onLog("正在唤起系统对话框...")
        callback.onSystemDialogShown(ssid)

        try {
            launcher.launch(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            callback.onLog("错误: 系统不支持 ACTION_WIFI_ADD_NETWORKS: ${e.message}")
            fallbackToWifiSettings(ssid)
            return false
        } catch (e: Exception) {
            callback.onLog("错误: 启动失败: ${e.message}")
            fallbackToWifiSettings(ssid)
            return false
        }
    }

    /**
     * Fallback: 跳转到 WiFi 设置页让用户手动连接（简化版）
     */
    private fun fallbackToWifiSettings(ssid: String) {
        callback.onLog("跳转到 WiFi 设置页: $ssid")
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            fragment.startActivity(intent)
        } catch (e: Exception) {
            callback.onLog("跳转失败: ${e.message}")
            callback.onAddFailed(ssid, "请手动前往系统设置连接此 WiFi")
        }
    }

    /**
     * 移除已保存网络后重新连接
     * 方案：由于 Android 10+ 限制了 getConfiguredNetworks() 和 removeNetwork()，
     * 我们尝试用 suggestion 机制来"覆盖"现有网络，或者尝试其他方式移除
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun removeAndReconnect(ssid: String, password: String, capabilities: String) {
        callback.onLog("尝试移除已保存网络: $ssid")
        try {
            val wm = fragment.requireContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            // 方法1: 尝试移除所有现有的 suggestions（如果之前添加过）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val emptyList = emptyList<WifiNetworkSuggestion>()
                    val removeResult = wm.removeNetworkSuggestions(emptyList)
                    callback.onLog("removeNetworkSuggestions 结果: $removeResult")
                } catch (e: Exception) {
                    callback.onLog("移除 suggestions 失败: ${e.message}")
                }
            }

            // 方法2: 尝试遗忘网络（需要反射或特殊权限，大概率失败但值得一试）
            try {
                val forgetMethod = WifiManager::class.java.getMethod("forget", Int::class.java, Any::class.java)
                // 尝试一些常见的 networkId 范围 (0-20)
                for (networkId in 0..20) {
                    try {
                        forgetMethod.invoke(wm, networkId, null)
                    } catch (_: Exception) {}
                }
                callback.onLog("尝试遗忘网络完成")
            } catch (e: Exception) {
                callback.onLog("反射调用 forget 失败: ${e.message}")
            }

            // 短暂延迟后重新尝试连接
            callback.onLog("2秒后重新尝试连接...")
            Handler(Looper.getMainLooper()).postDelayed({
                // 检查 Fragment 是否仍然 attached
                if (!fragment.isAdded || fragment.context == null) {
                    callback.onLog("Fragment 已分离，取消重连")
                    return@postDelayed
                }
                // 重新调用 connect，此时应该可以添加为新网络
                val success = connectInternal(ssid, password, capabilities)
                if (!success) {
                    callback.onLog("重连失败，跳转设置页")
                    fallbackToWifiSettings(ssid)
                }
            }, 2000)

        } catch (e: Exception) {
            callback.onLog("移除后重连失败: ${e.message}")
            fallbackToWifiSettings(ssid)
        }
    }

    /**
     * 内部连接方法（不重置 connectingSsid）
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun connectInternal(ssid: String, password: String, capabilities: String): Boolean {
        // 检查 Fragment 是否仍然 attached
        if (!fragment.isAdded || fragment.context == null) {
            callback.onLog("【内部重连】Fragment 已分离，取消操作")
            return false
        }

        callback.onLog("【内部重连】尝试添加网络: $ssid")

        val suggestion = buildSuggestion(ssid, password, capabilities)

        val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
            putParcelableArrayListExtra(
                Settings.EXTRA_WIFI_NETWORK_LIST,
                arrayListOf(suggestion)
            )
        }

        val pm = fragment.requireContext().packageManager
        val resolveInfo = pm.resolveActivity(intent, 0)
        if (resolveInfo == null) {
            callback.onLog("警告: 系统无法处理 ACTION_WIFI_ADD_NETWORKS")
            return false
        }

        callback.onLog("正在唤起系统对话框...")
        callback.onSystemDialogShown(ssid)

        return try {
            launcher.launch(intent)
            true
        } catch (e: Exception) {
            callback.onLog("错误: 启动失败: ${e.message}")
            false
        }
    }

    /**
     * 启用已保存的网络（应用内直接切换）- 保留作为后备方案
     */
    @Suppress("DEPRECATION")
    private fun enableSavedNetwork(ssid: String, password: String, capabilities: String) {
        callback.onLog("正在启用已保存网络: $ssid")
        try {
            val wm = fragment.requireContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            // 构建配置（尝试更新/添加）
            val config = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (password.isEmpty()) {
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$password\""
                    // 根据能力选择合适的密钥管理
                    when {
                        capabilities.contains("SAE") && !capabilities.contains("WPA2") -> {
                            // WPA3 - 需要特殊处理，但旧版API可能不支持
                            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                        }
                        else -> {
                            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                        }
                    }
                }
            }

            // addNetwork 对已保存的网络会返回现有 networkId
            val networkId = wm.addNetwork(config)
            callback.onLog("addNetwork 返回 networkId: $networkId")

            if (networkId != -1) {
                // 断开当前连接
                wm.disconnect()

                // 启用目标网络（禁用其他网络）
                val enableResult = wm.enableNetwork(networkId, true)
                callback.onLog("enableNetwork 结果: $enableResult")

                // 重新连接
                val reconnectResult = wm.reconnect()
                callback.onLog("reconnect 结果: $reconnectResult")

                if (enableResult && reconnectResult) {
                    callback.onLog("已触发连接，等待系统广播...")
                    scheduleConnectingTimeout(ssid)
                } else {
                    callback.onAddFailed(ssid, "启用网络失败")
                }
            } else {
                callback.onLog("addNetwork 返回 -1，尝试查找现有配置...")
                // 最后的尝试：查找已配置的网络
                fallbackEnableSavedNetwork(ssid, wm)
            }
        } catch (e: SecurityException) {
            callback.onLog("权限不足: ${e.message}")
            callback.onAddFailed(ssid, "需要位置权限才能访问已保存网络")
        } catch (e: Exception) {
            callback.onLog("启用已保存网络失败: ${e.message}")
            callback.onAddFailed(ssid, "连接失败: ${e.message}")
        }
    }

    /**
     * 最后的后备方案：尝试查找并启用已保存的网络
     */
    @Suppress("DEPRECATION")
    private fun fallbackEnableSavedNetwork(ssid: String, wm: WifiManager) {
        try {
            val configuredNetworks = wm.configuredNetworks
            val targetNetwork = configuredNetworks?.find { config ->
                config.SSID == "\"$ssid\"" || config.SSID == ssid
            }

            if (targetNetwork != null) {
                callback.onLog("找到已保存网络，networkId=${targetNetwork.networkId}")
                wm.disconnect()
                val enableResult = wm.enableNetwork(targetNetwork.networkId, true)
                val reconnectResult = wm.reconnect()
                callback.onLog("enable=$enableResult, reconnect=$reconnectResult")
                if (enableResult && reconnectResult) {
                    callback.onLog("已触发连接，等待系统广播...")
                    scheduleConnectingTimeout(ssid)
                } else {
                    callback.onAddFailed(ssid, "启用网络失败")
                }
            } else {
                callback.onLog("未找到已保存网络配置，跳转到设置页...")
                fallbackToWifiSettings(ssid)
            }
        } catch (e: Exception) {
            callback.onLog("后备方案失败: ${e.message}")
            fallbackToWifiSettings(ssid)
        }
    }

    private var connectingTimeoutRunnable: Runnable? = null

    /**
     * 设置连接超时
     */
    private fun scheduleConnectingTimeout(ssid: String) {
        connectingTimeoutRunnable?.let {
            Handler(Looper.getMainLooper()).removeCallbacks(it)
        }
        connectingTimeoutRunnable = Runnable {
            callback.onLog("连接超时: $ssid")
            callback.onAddFailed(ssid, "连接超时，请检查网络或手动连接")
        }.also {
            Handler(Looper.getMainLooper()).postDelayed(it, 30000)
        }
    }

    /**
     * 处理 Activity 返回结果
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun handleActivityResult(resultCode: Int, data: Intent?) {
        val ssid = connectingSsid ?: return

        callback.onLog("系统对话框返回: resultCode=$resultCode")

        when (resultCode) {
            Activity.RESULT_OK -> {
                // 检查返回结果
                val results = data?.getIntegerArrayListExtra(
                    Settings.EXTRA_WIFI_NETWORK_RESULT_LIST
                )

                callback.onLog("返回结果码: $results")

                // 分析具体结果
                val hasAddFailed = results?.any {
                    it == Settings.ADD_WIFI_RESULT_ADD_OR_UPDATE_FAILED
                } == true

                val alreadyExists = results?.any {
                    it == Settings.ADD_WIFI_RESULT_ALREADY_EXISTS
                } == true

                when {
                    hasAddFailed -> {
                        callback.onLog("添加失败: 配置无效或密码错误")
                        callback.onAddFailed(ssid, "网络配置无效或密码错误")
                    }
                    alreadyExists -> {
                        // 网络已存在：通知 Fragment 处理
                        callback.onLog("网络已存在，通知 Fragment 处理...")
                        val pwd = connectingPassword ?: ""
                        val cap = connectingCapabilities ?: ""
                        connectingSsid = null  // 清理状态，由 Fragment 接管
                        callback.onAlreadyExists(ssid, pwd, cap)
                    }
                    else -> {
                        // 添加成功，等待连接
                        callback.onLog("用户已确认，等待连接广播...")
                        callback.onUserConfirmed(ssid)
                    }
                }
            }
            Activity.RESULT_CANCELED -> {
                callback.onLog("用户取消")
                callback.onUserCancelled(ssid)
                connectingSsid = null
            }
            else -> {
                callback.onLog("未知返回码: $resultCode")
                callback.onAddFailed(ssid, "未知错误 (code=$resultCode)")
            }
        }
    }

    /**
     * 当收到系统连接成功广播时调用
     */
    fun onConnectionSuccess(ssid: String) {
        if (connectingSsid == ssid) {
            callback.onLog("广播确认连接成功: $ssid")
            callback.onSuccess(ssid)
            connectingSsid = null
        }
    }

    /**
     * 清除连接状态（用于超时或取消）
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun clearState() {
        connectingSsid = null
        connectingPassword = null
        connectingCapabilities = null
        reconnectRetryCount = 0
        connectingTimeoutRunnable?.let {
            Handler(Looper.getMainLooper()).removeCallbacks(it)
        }
        connectingTimeoutRunnable = null

        // 清理已添加的 suggestions
        try {
            val wm = fragment.requireContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.removeNetworkSuggestions(emptyList())
        } catch (_: Exception) {}
    }

    /**
     * 构建 WifiNetworkSuggestion
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildSuggestion(
        ssid: String,
        password: String,
        capabilities: String
    ): WifiNetworkSuggestion {
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)

        when {
            // 开放网络
            capabilities.isEmpty() ||
            (!capabilities.contains("WPA") &&
             !capabilities.contains("WEP") &&
             !capabilities.contains("SAE")) -> {
                callback.onLog("加密类型: 开放网络")
            }
            // WPA3 网络
            capabilities.contains("SAE") && !capabilities.contains("WPA2") -> {
                callback.onLog("加密类型: WPA3")
                builder.setWpa3Passphrase(password)
            }
            // WPA/WPA2 网络
            else -> {
                callback.onLog("加密类型: WPA/WPA2")
                builder.setWpa2Passphrase(password)
            }
        }

        // Android 11+ 设置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setIsUserInteractionRequired(false)
            builder.setPriority(Int.MAX_VALUE)
        }

        return builder.build()
    }
}
