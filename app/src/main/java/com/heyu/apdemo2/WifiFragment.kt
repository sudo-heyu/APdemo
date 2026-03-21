package com.heyu.apdemo2

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.Uri
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

class WifiFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AccessPointAdapter

    private var scanService: ScanForegroundService? = null
    private var connectingSsid: String? = null
    private var connectingPassword: String? = null
    private var wifiStateReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "[WIFI_FRAGMENT]"
        const val FRAGMENT_TAG = "wifi"
    }

    // ── ScanCallback ─────────────────────────────────────────────────────────

    val scanCallback = object : ScanForegroundService.ScanCallback {
        override fun onStatusUpdate(status: String) {
            activity?.runOnUiThread { tvStatus.text = "状态: $status" }
        }
        override fun onDataUpdate(accessPoints: List<AccessPoint>) {
            activity?.runOnUiThread { adapter.updateData(accessPoints) }
        }
        override fun onConnectionChanged(ssid: String?, success: Boolean, errorType: String) {
            activity?.runOnUiThread {
                if (ssid == null || !success) {
                    connectingSsid = null
                    connectingPassword = null
                }
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
        // 连接进行中时不覆盖"等待连接"状态文字
        if (::adapter.isInitialized && connectingSsid == null) {
            syncConnectedSsid()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterWifiStateReceiver()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanService?.unregisterCallback()
        scanService = null
    }

    // ── 供 MainActivity 调用 ──────────────────────────────────────────────────

    fun onServiceBound(service: ScanForegroundService) {
        scanService = service
        service.registerCallback(scanCallback)
        if (::adapter.isInitialized) syncConnectedSsid()
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
        if (ssid != null) {
            if (isConnectEvent && ssid == connectingSsid) {
                val pwd = connectingPassword
                if (!pwd.isNullOrEmpty()) PasswordStore.save(requireContext(), ssid, pwd)
                connectingSsid = null
                connectingPassword = null
            }
            tvStatus.text = "状态: 已连接到 $ssid"
        }
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

    // ── WiFi 连接 ─────────────────────────────────────────────────────────────

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
            initiateConnect(ap.ssid, isOpen = true, password = "")
            return
        }

        val saved = PasswordStore.get(requireContext(), ap.ssid)
        if (saved != null) {
            initiateConnect(ap.ssid, isOpen = false, password = saved)
        } else {
            showPasswordDialog(ap)
        }
    }

    private fun initiateConnect(ssid: String, isOpen: Boolean, password: String) {
        connectingSsid = ssid
        connectingPassword = password
        tvStatus.text = "状态: 等待连接到 $ssid..."

        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(requireContext())
                .setTitle("建议开启无障碍服务")
                .setMessage("开启「一键切换WiFi」服务后可自动连接。\n也可跳过，手动在 WiFi 设置页连接。")
                .setPositiveButton("去开启") { _, _ -> openAccessibilityServiceSettings() }
                .setNegativeButton("跳过，直接去WiFi设置") { _, _ ->
                    openWifiSettingsPage(ssid, isOpen, password)
                }
                .setOnCancelListener { openWifiSettingsPage(ssid, isOpen, password) }
                .show()
            return
        }

        openWifiSettingsPage(ssid, isOpen, password)
    }

    /**
     * 跳转系统 WiFi 设置页（保底方案）。
     * 若无障碍服务已开启，WifiAutoConnectService 会自动完成点击/填密码/连接并返回；
     * 若未开启，用户手动选择目标网络。
     */
    private fun openWifiSettingsPage(ssid: String, isOpen: Boolean, password: String) {
        Log.d(TAG, "跳转 WiFi 设置页: $ssid")
        WifiAutoConnectService.pendingSsid = ssid
        WifiAutoConnectService.pendingPassword = if (isOpen) null else password
        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${requireContext().packageName}/${WifiAutoConnectService::class.java.name}"
        val enabled = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(serviceName, ignoreCase = true) }
    }

    private fun openAccessibilityServiceSettings() {
        val componentName = ComponentName(
            requireContext().packageName,
            WifiAutoConnectService::class.java.name
        ).flattenToString()

        // 优先：直接打开本应用无障碍服务详情页（Android 10+ 均可用，无需滚动）
        try {
            startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                data = Uri.parse("package:${requireContext().packageName}")
            })
            return
        } catch (_: Exception) {}

        // 次选：直接打开指定服务的设置 Activity（AOSP/部分厂商）
        try {
            startActivity(Intent().apply {
                component = ComponentName(
                    "com.android.settings",
                    "com.android.settings.accessibility.AccessibilityServiceSettingsActivity"
                )
                putExtra(":settings:fragment_args_key", componentName)
                putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString(":settings:fragment_args_key", componentName)
                })
            })
            return
        } catch (_: Exception) {}

        // 兜底：通用无障碍列表 + 滚动定位
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", componentName)
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", componentName)
            })
        })
    }

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
                    initiateConnect(ap.ssid, isOpen = false, password = pwd)
                }
            }
            .setNegativeButton("取消") { _, _ ->
                if (adapter.pinnedSsid == ap.ssid) adapter.setPinned(null)
            }
            .show()
    }
}
