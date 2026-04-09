package com.heyu.apdemo2.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
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
import com.heyu.apdemo2.R
import com.heyu.apdemo2.adapter.AccessPointAdapter
import com.heyu.apdemo2.connection.PasswordStore
import com.heyu.apdemo2.model.AccessPoint
import com.heyu.apdemo2.service.ScanForegroundService

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
    private var connectingTimeoutRunnable: Runnable? = null

    companion object {
        private const val TAG = "[WIFI_FRAGMENT]"
        const val FRAGMENT_TAG = "wifi"
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }

    // ── ScanCallback ─────────────────────────────────────────────────────────

    val scanCallback = object : ScanForegroundService.ScanCallback {
        override fun onStatusUpdate(status: String) {
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
                // 同步连接状态到服务
                (activity as? MainActivity)?.getScanService()?.updateConnectedSsid(ssid)
            }
        }
        override fun onReconnecting(ssid: String, attempt: Int) {
            activity?.runOnUiThread {
                tvStatus.text = "状态: 正在重新连接 $ssid（第 $attempt 次）..."
            }
        }
        override fun onApprovalNeeded() {}
        override fun onRoamingStatus(status: String) {
            // 漫游状态不再显示在状态栏，只记录到日志
        }
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
        // 注意：不在此处断开 Specifier 连接，由 Service 保持连接
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

    private fun syncConnectedSsid() {
        // 正在发起 Specifier 连接时，忽略系统 WiFi 广播，避免系统自动重连覆盖连接状态
        if (connectingSsid != null) {
            Log.d(TAG, "[syncConnectedSsid] 跳过：正在连接 $connectingSsid，忽略系统广播")
            return
        }

        val systemSsid = getSystemConnectedSsid()
        val service = (activity as? MainActivity)?.getScanService()
        val (specifierSsid, _) = service?.getSpecifierConnectionInfo() ?: Pair(null, false)

        Log.d(TAG, "[syncConnectedSsid] systemSsid=$systemSsid, specifierSsid=$specifierSsid")

        // 优先使用 Specifier 连接状态
        val effectiveSsid = specifierSsid ?: systemSsid

        adapter.setPinned(effectiveSsid)

        when {
            specifierSsid != null -> {
                tvStatus.text = "状态: 已连接 ${if (specifierSsid == systemSsid) "[系统]" else "[本地]"} $specifierSsid"
            }
            systemSsid != null -> {
                tvStatus.text = "状态: 已连接 [系统] $systemSsid"
            }
            else -> {
                // 未连接
            }
        }

        // 同步到服务
        service?.updateConnectedSsid(effectiveSsid)
        Log.d(TAG, "[syncConnectedSsid] 同步WiFi状态到服务: $effectiveSsid")
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
                    if (::adapter.isInitialized) syncConnectedSsid()
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
                .setMessage("断开并释放到 ${ap.ssid} 的连接？")
                .setPositiveButton("断开") { _, _ ->
                    (activity as? MainActivity)?.getScanService()?.releaseSpecifierConnection()
                    adapter.setPinned(null)
                    tvStatus.text = "状态: 已断开"
                }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(requireContext(), "WifiNetworkSpecifier 需要 Android 10+", Toast.LENGTH_LONG).show()
            tvStatus.text = "状态: 设备不支持（需 Android 10+）"
            return
        }

        connectingSsid = ssid
        connectingPassword = password

        // 提前保存密码，避免系统弹窗被取消时 onConnected 未触发导致密码丢失
        if (password.isNotEmpty()) {
            PasswordStore.save(requireContext(), ssid, password)
        }

        tvStatus.text = "状态: 正在发起连接..."

        val service = (activity as? MainActivity)?.getScanService()
        if (service == null) {
            tvStatus.text = "状态: 服务未启动，无法连接"
            clearConnectingState()
            return
        }

        service.connectWithSpecifier(ssid, password, isOpen, object : ScanForegroundService.SpecifierConnectionCallback {
            override fun onConnected(connectedSsid: String, isSystemConnection: Boolean) {
                mainHandler.post {
                    cancelConnectingTimeout()
                    val pwd = connectingPassword
                    if (!pwd.isNullOrEmpty()) PasswordStore.save(requireContext(), connectedSsid, pwd)
                    clearConnectingState()
                    tvStatus.text = "状态: 已连接 ${if (isSystemConnection) "[系统]" else "[本地]"} $connectedSsid"
                    adapter.setPinned(connectedSsid)
                    Toast.makeText(requireContext(), "已连接到 $connectedSsid", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailed(failedSsid: String, error: String) {
                mainHandler.post {
                    cancelConnectingTimeout()
                    tvStatus.text = "状态: 连接失败 - $error"
                    clearConnectingState()
                }
            }

            override fun onLost(lostSsid: String?) {
                mainHandler.post {
                    tvStatus.text = "状态: 已断开"
                    adapter.setPinned(null)
                }
            }
        })

        tvStatus.text = "状态: 正在连接 $ssid..."
        scheduleConnectingTimeout(ssid)
    }

    // ── 连接状态管理 ──────────────────────────────────────────────────────────

    private fun scheduleConnectingTimeout(targetSsid: String) {
        cancelConnectingTimeout()
        connectingTimeoutRunnable = Runnable {
            if (connectingSsid != targetSsid) return@Runnable
            Log.w(TAG, "连接超时: $targetSsid")
            val service = (activity as? MainActivity)?.getScanService()
            val (specifierSsid, _) = service?.getSpecifierConnectionInfo() ?: Pair(null, false)
            // 系统 WiFi 已连上目标（直连情况下 onAvailable 可能迟到），视为成功
            if (getSystemConnectedSsid() == targetSsid || specifierSsid == targetSsid) {
                tvStatus.text = "状态: 已连接 $targetSsid"
                adapter.setPinned(targetSsid)
            } else {
                tvStatus.text = "状态: 连接超时，请重试"
            }
            clearConnectingState()
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
                    initiateConnect(ap.ssid, isOpen = false, password = pwd)
                }
            }
            .setNegativeButton("取消") { _, _ ->
                if (adapter.pinnedSsid == ap.ssid) adapter.setPinned(null)
            }
            .show()
    }
}
