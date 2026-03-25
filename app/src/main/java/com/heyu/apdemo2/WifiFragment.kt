package com.heyu.apdemo2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.heyu.apdemo2.adapter.AccessPointAdapter
import com.heyu.apdemo2.model.AccessPoint
import com.heyu.apdemo2.connection.Method2ActionWifiAddNetworks

class WifiFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AccessPointAdapter

    private var scanService: ScanForegroundService? = null
    private var wifiStateReceiver: BroadcastReceiver? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 当前正在连接的目标信息
    private var connectingSsid: String? = null
    private var connectingPassword: String? = null
    private var connectingCapabilities: String = ""
    private var connectingTimeoutRunnable: Runnable? = null

    // 方式二：系统弹窗连接（用于未保存网络）
    private lateinit var method2Connector: Method2ActionWifiAddNetworks

    companion object {
        private const val TAG = "[WIFI_FRAGMENT]"
        const val FRAGMENT_TAG = "wifi"
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }

    // ── ScanCallback ─────────────────────────────────────────────────────────

    val scanCallback = object : ScanForegroundService.ScanCallback {
        override fun onStatusUpdate(status: String) {
            // 连接进行中时不用扫描状态覆盖连接状态
            activity?.runOnUiThread {
                if (connectingSsid == null) tvStatus.text = "状态: $status"
            }
        }
        override fun onDataUpdate(accessPoints: List<AccessPoint>) {
            activity?.runOnUiThread { adapter.updateData(accessPoints) }
        }
        override fun onConnectionChanged(ssid: String?, success: Boolean, errorType: String) {
            activity?.runOnUiThread {
                if (ssid == null || !success) clearConnectingState()
            }
        }
        override fun onReconnecting(ssid: String, attempt: Int) {
            activity?.runOnUiThread {
                tvStatus.text = "状态: 正在重新连接 $ssid（第 $attempt 次）..."
            }
        }
        override fun onApprovalNeeded() {}
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化方式二连接器（仅用于未保存网络）
        method2Connector = Method2ActionWifiAddNetworks(this,
            object : Method2ActionWifiAddNetworks.ConnectionCallback {
                override fun onLog(message: String) {
                    Log.d(TAG, message)
                }
                override fun onSystemDialogShown(ssid: String) {
                    tvStatus.text = "状态: 等待系统确认..."
                }
                override fun onUserConfirmed(ssid: String) {
                    tvStatus.text = "状态: 正在连接到 $ssid..."
                    scheduleConnectingTimeout(ssid)
                }
                override fun onUserCancelled(ssid: String) {
                    tvStatus.text = "状态: 已取消"
                    clearConnectingState()
                }
                override fun onAddFailed(ssid: String, reason: String) {
                    tvStatus.text = "状态: 连接失败 - $reason"
                    clearConnectingState()
                }
                override fun onNotSupported(ssid: String) {
                    tvStatus.text = "状态: 系统不支持此功能"
                    clearConnectingState()
                }
                override fun onSuccess(ssid: String) {
                    // 系统弹窗方式连接成功
                    activity?.runOnUiThread {
                        cancelConnectingTimeout()
                        val pwd = connectingPassword
                        if (!pwd.isNullOrEmpty()) PasswordStore.save(requireContext(), ssid, pwd)
                        clearConnectingState()
                        tvStatus.text = "状态: 已连接到 $ssid"
                        syncConnectedSsid()
                        Toast.makeText(requireContext(), "已连接到 $ssid", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onAlreadyExists(ssid: String, password: String, capabilities: String) {
                    // 网络已存在：使用无障碍服务连接
                    activity?.runOnUiThread {
                        Log.d(TAG, "网络 $ssid 已保存，切换到无障碍服务连接")
                        val isOpen = capabilities.isEmpty() ||
                               (!capabilities.contains("WPA") &&
                                !capabilities.contains("WEP") &&
                                !capabilities.contains("SAE"))
                        tryAccessibilityServiceConnect(ssid, isOpen, password)
                    }
                }
            })
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_wifi, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvStatus = view.findViewById(R.id.tv_status)
        recyclerView = view.findViewById(R.id.recycler_view)
        adapter = AccessPointAdapter()
        recyclerView.adapter = adapter
        adapter.onItemClickListener = object : AccessPointAdapter.OnItemClickListener {
            override fun onItemClick(ap: AccessPoint) { handleApClick(ap) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (scanService == null) {
            (activity as? MainActivity)?.getScanService()?.let { onServiceBound(it) }
        }
        registerWifiStateReceiver()
        if (::adapter.isInitialized && connectingSsid == null) syncConnectedSsid()
    }

    override fun onPause() {
        super.onPause()
        unregisterWifiStateReceiver()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelConnectingTimeout()
        scanService?.unregisterCallback()
        scanService = null
    }

    // ── 供 MainActivity 调用 ──────────────────────────────────────────────────

    fun onServiceBound(service: ScanForegroundService) {
        scanService = service
        service.registerCallback(scanCallback)
        if (::adapter.isInitialized && connectingSsid == null) syncConnectedSsid()
        Log.d(TAG, "服务已绑定")
    }

    fun onServiceUnbound() {
        scanService = null
        Log.d(TAG, "服务已解绑")
    }

    // ── 系统 WiFi 状态同步 ────────────────────────────────────────────────────

    private fun syncConnectedSsid(isConnectEvent: Boolean = false) {
        val ssid = getSystemConnectedSsid()
        adapter.setPinned(ssid)
        if (ssid == null) return

        if (isConnectEvent && ssid == connectingSsid) {
            // 目标网络连接成功
            cancelConnectingTimeout()
            val pwd = connectingPassword
            if (!pwd.isNullOrEmpty()) PasswordStore.save(requireContext(), ssid, pwd)
            clearConnectingState()
            tvStatus.text = "状态: 已连接到 $ssid"
        } else if (connectingSsid == null) {
            tvStatus.text = "状态: 已连接到 $ssid"
        }
        // connectingSsid 指向别的网络时不覆盖状态
    }

    @Suppress("DEPRECATION")
    private fun getSystemConnectedSsid(): String? {
        val wm = requireContext().applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid ?: return null
        return ssid.removePrefix("\"").removeSuffix("\"")
            .takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }

    private fun registerWifiStateReceiver() {
        unregisterWifiStateReceiver()
        wifiStateReceiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val info = intent?.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                val state = info?.detailedState
                if (state == NetworkInfo.DetailedState.CONNECTED ||
                    state == NetworkInfo.DetailedState.DISCONNECTED
                ) {
                    if (::adapter.isInitialized) {
                        syncConnectedSsid(isConnectEvent = state == NetworkInfo.DetailedState.CONNECTED)
                    }
                }
            }
        }
        val filter = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(wifiStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(wifiStateReceiver, filter)
        }
    }

    private fun unregisterWifiStateReceiver() {
        try { wifiStateReceiver?.let { requireContext().unregisterReceiver(it) } } catch (_: Exception) {}
        wifiStateReceiver = null
    }

    // ── WiFi 连接主流程 ───────────────────────────────────────────────────────

    private fun handleApClick(ap: AccessPoint) {
        if (ap.ssid == adapter.pinnedSsid) {
            AlertDialog.Builder(requireContext())
                .setTitle("断开连接")
                .setMessage("取消连接并取消置顶 ${ap.ssid}？")
                .setPositiveButton("断开") { _, _ -> scanService?.disconnectPinned() }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (!ap.isSecured()) {
            // 开放网络：未保存用系统弹窗，已保存用无障碍服务
            initiateConnect(ap.ssid, ap.capabilities, isOpen = true, password = "")
            return
        }

        val saved = PasswordStore.get(requireContext(), ap.ssid)
        if (saved != null) {
            // 有密码：先尝试系统弹窗（未保存情况），失败会自动走无障碍服务
            initiateConnect(ap.ssid, ap.capabilities, isOpen = false, password = saved)
        } else {
            showPasswordDialog(ap)
        }
    }

    private fun initiateConnect(ssid: String, capabilities: String, isOpen: Boolean, password: String) {
        connectingSsid = ssid
        connectingPassword = password
        connectingCapabilities = capabilities

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：优先尝试系统弹窗（适用于未保存网络）
            // 如果网络已保存，系统会返回 ALREADY_EXISTS，此时会走无障碍服务
            tvStatus.text = "状态: 正在发起连接..."
            val success = method2Connector.connect(ssid, password, capabilities)
            if (!success) {
                // 系统不支持，尝试无障碍服务
                tryAccessibilityServiceConnect(ssid, isOpen, password)
            }
        } else {
            // Android 9-10：使用无障碍服务
            tryAccessibilityServiceConnect(ssid, isOpen, password)
        }
    }

    /**
     * 使用无障碍服务连接（适用于已保存网络）
     */
    private fun tryAccessibilityServiceConnect(ssid: String, isOpen: Boolean, password: String) {
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityPrompt(ssid, isOpen, password)
            return
        }

        tvStatus.text = "状态: 正在跳转 WiFi 设置..."
        WifiAutoConnectService.pendingSsid = ssid
        WifiAutoConnectService.pendingPassword = if (isOpen) null else password

        // 启动 WiFi 设置页
        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        scheduleConnectingTimeout(ssid)
    }

    /**
     * 检查无障碍服务是否开启
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${requireContext().packageName}/${WifiAutoConnectService::class.java.name}"
        val enabled = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(serviceName, ignoreCase = true) }
    }

    /**
     * 提示开启无障碍服务
     */
    private fun showAccessibilityPrompt(ssid: String, isOpen: Boolean, password: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("需要开启无障碍服务")
            .setMessage("该 WiFi 已保存，需要开启「WiFi 一键切换」无障碍服务才能自动连接。")
            .setPositiveButton("去开启") { _, _ ->
                // 保存参数，开启后回来可以继续
                WifiAutoConnectService.pendingSsid = ssid
                WifiAutoConnectService.pendingPassword = if (isOpen) null else password
                startActivity(buildAccessibilityServiceIntent())
            }
            .setNegativeButton("取消") { _, _ ->
                clearConnectingState()
                tvStatus.text = "状态: 已取消"
            }
            .show()
    }

    /**
     * 构建直接跳转到「WiFi 一键切换」无障碍服务设置页的 Intent。
     * 在原生 Android 上会直接定位到该服务；不支持时回退到无障碍总列表。
     */
    private fun buildAccessibilityServiceIntent(): Intent {
        val componentName = "${requireContext().packageName}/${WifiAutoConnectService::class.java.name}"
        return try {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                val args = android.os.Bundle().apply {
                    putString(":settings:fragment_args_key", componentName)
                }
                putExtra(":settings:show_fragment_args", args)
            }
        } catch (_: Exception) {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
    }

    // ── 方式一：Android 9-（API 28-）：传统 WifiConfiguration API ─────────────

    @Suppress("DEPRECATION")
    private fun connectLegacy(ssid: String, isOpen: Boolean, password: String) {
        val wm = requireContext().applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
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
            Log.d(TAG, "Legacy 连接已触发: $ssid")
            scheduleConnectingTimeout(ssid)
        } else {
            Log.w(TAG, "addNetwork 返回 -1: $ssid")
            tvStatus.text = "状态: 连接失败，请在系统设置中手动连接"
            clearConnectingState()
        }
    }

    // ── 连接状态管理 ──────────────────────────────────────────────────────────

    private fun scheduleConnectingTimeout(targetSsid: String) {
        cancelConnectingTimeout()
        connectingTimeoutRunnable = Runnable {
            if (connectingSsid == targetSsid) {
                Log.w(TAG, "连接超时(${CONNECT_TIMEOUT_MS / 1000}s): $targetSsid")
                tvStatus.text = "状态: 连接超时，请重试或前往WiFi设置手动连接"
                clearConnectingState()
            }
        }.also { mainHandler.postDelayed(it, CONNECT_TIMEOUT_MS) }
    }

    private fun cancelConnectingTimeout() {
        connectingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectingTimeoutRunnable = null
    }

    private fun clearConnectingState() {
        cancelConnectingTimeout()
        connectingSsid = null
        connectingPassword = null
        connectingCapabilities = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            method2Connector.clearState()
        }
    }

    // ── 密码输入弹窗 ──────────────────────────────────────────────────────────

    private fun showPasswordDialog(ap: AccessPoint) {
        val passwordInput = EditText(requireContext()).apply {
            hint = "请输入 WiFi 密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
            addView(passwordInput)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("连接到 ${ap.ssid}")
            .setView(container)
            .setPositiveButton("连接") { _, _ ->
                val pwd = passwordInput.text.toString()
                if (pwd.length < 8) {
                    Toast.makeText(requireContext(), "密码至少 8 位", Toast.LENGTH_SHORT).show()
                } else {
                    initiateConnect(ap.ssid, ap.capabilities, isOpen = false, password = pwd)
                }
            }
            .setNegativeButton("取消") { _, _ ->
                if (adapter.pinnedSsid == ap.ssid) adapter.setPinned(null)
            }
            .show()
    }
}
