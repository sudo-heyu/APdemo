package com.heyu.apdemo2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
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
import androidx.annotation.RequiresApi
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
    private var wifiStateReceiver: BroadcastReceiver? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 当前正在连接的目标信息
    private var connectingSsid: String? = null
    private var connectingPassword: String? = null
    private var connectingTimeoutRunnable: Runnable? = null

    // WifiNetworkSpecifier 使用的回调和连接管理器
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager by lazy {
        requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    // 当前 specifier 连接是系统级（直连）还是本地（仅绑定进程）
    private var isSystemWifiConnection: Boolean = false

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
        if (::adapter.isInitialized && connectingSsid == null) syncConnectedSsid()
    }

    override fun onPause() {
        super.onPause()
        unregisterWifiStateReceiver()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelConnectingTimeout()
        releaseNetworkRequest()
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

    private fun syncConnectedSsid() {
        val systemSsid = getSystemConnectedSsid()

        if (networkCallback != null) {
            // Specifier 请求活跃期间：
            // 若系统 WiFi 已连上目标 SSID（直连情况），立即确认成功并同步 UI
            if (systemSsid != null && systemSsid == connectingSsid) {
                val pwd = connectingPassword
                clearConnectingState()
                if (!pwd.isNullOrEmpty()) PasswordStore.save(requireContext(), systemSsid, pwd)
                isSystemWifiConnection = true
                tvStatus.text = "状态: 已连接 [系统] $systemSsid"
                adapter.setPinned(systemSsid)
            }
            // 其他情况（local-only 时系统 WiFi 无关变化）不干扰 specifier 管理的 pin
            return
        }

        // 无 Specifier 请求时：直接跟随系统 WiFi 状态
        adapter.setPinned(systemSsid)
        if (systemSsid != null && connectingSsid == null) {
            tvStatus.text = "状态: 已连接 [系统] $systemSsid"
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
                    releaseNetworkRequest()
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
        tvStatus.text = "状态: 正在发起连接..."
        connectWithSpecifier(ssid, isOpen, password)
    }

    // ── WifiNetworkSpecifier 连接核心 ─────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectWithSpecifier(ssid: String, isOpen: Boolean, password: String) {
        releaseNetworkRequest()

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply {
                if (!isOpen) setWpa2Passphrase(password)
            }
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    cancelConnectingTimeout()
                    connectivityManager.bindProcessToNetwork(network)
                    val pwd = connectingPassword
                    if (!pwd.isNullOrEmpty()) PasswordStore.save(requireContext(), ssid, pwd)
                    clearConnectingState()
                    // 检查该 Network 对象本身是否有互联网：
                    // NET_CAPABILITY_VALIDATED = 系统已验证此网络可访问互联网
                    // 不能用 SSID 对比，因为 specifier 即使连相同 SSID 也可能拿到 local-only Network
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    isSystemWifiConnection =
                        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    tvStatus.text = if (isSystemWifiConnection)
                        "状态: 已连接 [系统] $ssid"
                    else
                        "状态: 已连接 [本地] $ssid"
                    adapter.setPinned(ssid)
                    Toast.makeText(requireContext(), "已连接到 $ssid", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onUnavailable() {
                mainHandler.post {
                    cancelConnectingTimeout()
                    tvStatus.text = "状态: 连接失败或被用户取消"
                    isSystemWifiConnection = false
                    clearConnectingState()
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    connectivityManager.bindProcessToNetwork(null)
                    tvStatus.text = if (isSystemWifiConnection)
                        "状态: 已断开 [系统]"
                    else
                        "状态: 已断开 [本地]"
                    isSystemWifiConnection = false
                    adapter.setPinned(null)
                }
            }
        }

        try {
            connectivityManager.requestNetwork(request, networkCallback!!)
            tvStatus.text = "状态: 正在连接 $ssid..."
            scheduleConnectingTimeout(ssid)
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork 失败: ${e.message}")
            tvStatus.text = "状态: 发起连接失败 - ${e.message}"
            clearConnectingState()
        }
    }

    private fun releaseNetworkRequest() {
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
        isSystemWifiConnection = false
        connectivityManager.bindProcessToNetwork(null)
    }

    // ── 连接状态管理 ──────────────────────────────────────────────────────────

    private fun scheduleConnectingTimeout(targetSsid: String) {
        cancelConnectingTimeout()
        connectingTimeoutRunnable = Runnable {
            if (connectingSsid != targetSsid) return@Runnable
            Log.w(TAG, "连接超时: $targetSsid")
            // 系统 WiFi 已连上目标（直连情况下 onAvailable 可能迟到），视为成功
            if (getSystemConnectedSsid() == targetSsid) {
                isSystemWifiConnection = true
                tvStatus.text = "状态: 已连接 [系统] $targetSsid"
                adapter.setPinned(targetSsid)
            } else {
                tvStatus.text = "状态: 连接超时，请重试"
                releaseNetworkRequest()
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
