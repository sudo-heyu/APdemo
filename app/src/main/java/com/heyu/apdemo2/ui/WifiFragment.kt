package com.heyu.apdemo2.ui

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
import com.heyu.apdemo2.R
import com.heyu.apdemo2.adapter.AccessPointAdapter
import com.heyu.apdemo2.connection.PasswordStore
import com.heyu.apdemo2.model.AccessPoint
import com.heyu.apdemo2.service.ScanForegroundService
import com.heyu.apdemo2.service.WifiAccessibilityService

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
    // 仅在"用户去开启无障碍服务"后置 true，onResume 消费一次后立即清除，防止 returnToApp 触发再次循环
    private var pendingA11yRetry: Boolean = false
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
                if (success && ssid != null) {
                    cancelConnectingTimeout()
                    clearConnectingState()
                    tvStatus.text = "状态: 已连接 $ssid"
                    adapter.setPinned(ssid)
                } else {
                    clearConnectingState()
                    if (errorType.isNotEmpty()) tvStatus.text = "状态: 连接失败 - $errorType"
                    adapter.setPinned(null)
                }
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

        // 从无障碍设置页返回时：pendingA11yRetry 消费一次后立即清除，防止后续 resume 重复触发
        if (pendingA11yRetry &&
            WifiAccessibilityService.isEnabled(requireContext()) &&
            WifiAccessibilityService.getInstance() != null
        ) {
            val pendingSsid = connectingSsid
            val pendingPwd  = connectingPassword
            pendingA11yRetry = false          // 立即清除，避免循环
            if (pendingSsid != null && pendingPwd != null) {
                Log.d(TAG, "从无障碍设置返回，重试连接: $pendingSsid")
                clearConnectingState()
                val isOpen = PasswordStore.get(requireContext(), pendingSsid) == null && pendingPwd.isEmpty()
                initiateConnect(pendingSsid, isOpen, pendingPwd)
                return
            }
        }

        // 用户从 WiFi 设置页手动返回但未连上目标网络：静默清除连接中状态
        if (connectingSsid != null && !pendingA11yRetry) {
            val systemSsid = getSystemConnectedSsid()
            if (systemSsid != connectingSsid) {
                Log.d(TAG, "用户手动返回，未连接到目标 $connectingSsid，清除状态")
                clearConnectingState()
                tvStatus.text = "状态: 未连接"
            }
        }

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

    private fun syncConnectedSsid() {
        if (connectingSsid != null) {
            Log.d(TAG, "[syncConnectedSsid] 跳过：正在连接 $connectingSsid")
            return
        }

        val systemSsid = getSystemConnectedSsid()
        val service = (activity as? MainActivity)?.getScanService()

        Log.d(TAG, "[syncConnectedSsid] systemSsid=$systemSsid")

        adapter.setPinned(systemSsid)

        if (systemSsid != null) {
            tvStatus.text = "状态: 已连接 $systemSsid"
        }

        service?.updateConnectedSsid(systemSsid)
        Log.d(TAG, "[syncConnectedSsid] 同步WiFi状态到服务: $systemSsid")
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
                    ?: return
                if (!::adapter.isInitialized) return

                when (info.detailedState) {
                    NetworkInfo.DetailedState.CONNECTED -> {
                        val systemSsid = getSystemConnectedSsid()
                        val target = connectingSsid
                        if (target != null && systemSsid == target) {
                            // 连上了我们想连的网络，立即更新状态
                            cancelConnectingTimeout()
                            clearConnectingState()
                            tvStatus.text = "状态: 已连接 $systemSsid"
                            adapter.setPinned(systemSsid)
                            (activity as? MainActivity)?.getScanService()?.updateConnectedSsid(systemSsid)
                        } else if (target == null) {
                            syncConnectedSsid()
                        }
                    }
                    NetworkInfo.DetailedState.DISCONNECTED -> {
                        if (connectingSsid == null) syncConnectedSsid()
                    }
                    else -> {}
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
                .setMessage("断开到 ${ap.ssid} 的连接？")
                .setPositiveButton("断开") { _, _ ->
                    (activity as? MainActivity)?.getScanService()?.disconnectPinned()
                    adapter.setPinned(null)
                    tvStatus.text = "状态: 已断开"
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (!ap.isSecured()) {
            checkA11yAndConnect(ap.ssid, isOpen = true, password = "")
            return
        }

        val saved = PasswordStore.get(requireContext(), ap.ssid)
        if (saved != null) {
            checkA11yAndConnect(ap.ssid, isOpen = false, password = saved)
        } else {
            showPasswordDialog(ap)
        }
    }

    /**
     * 检查无障碍服务是否已启用。
     * - 已启用：直接发起连接
     * - 未启用：弹窗引导用户开启，用户确认后跳转设置
     */
    private fun checkA11yAndConnect(ssid: String, isOpen: Boolean, password: String) {
        if (WifiAccessibilityService.isEnabled(requireContext())) {
            initiateConnect(ssid, isOpen, password)
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("需要开启无障碍服务")
                .setMessage(
                    "APdemo2 需要无障碍服务权限才能实现真实 WiFi 切换（其他 App 如直播也跟随切换）。\n\n" +
                    "请在「无障碍」→「已下载的应用」中找到「${getString(R.string.app_name)}」并开启。"
                )
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    // 记录待连接目标；pendingA11yRetry 在 onResume 中消费一次后立即清除
                    connectingSsid = ssid
                    connectingPassword = password
                    pendingA11yRetry = true
                }
                .setNegativeButton("暂不（降级连接）") { _, _ ->
                    initiateConnect(ssid, isOpen, password)
                }
                .show()
        }
    }

    private fun initiateConnect(ssid: String, isOpen: Boolean, password: String) {
        connectingSsid = ssid
        connectingPassword = password

        if (password.isNotEmpty()) {
            PasswordStore.save(requireContext(), ssid, password)
        }

        val service = (activity as? MainActivity)?.getScanService()
        if (service == null) {
            tvStatus.text = "状态: 服务未启动，无法连接"
            clearConnectingState()
            return
        }

        tvStatus.text = "状态: 正在切换（无障碍）$ssid..."

        // 让 service 准备无障碍服务目标（不打开 WiFi 设置页）
        service.connectToNetwork(ssid, isOpen, password)

        // Fragment 自己打开 WiFi 设置页（同任务栈），确保一次 BACK 即可返回 App
        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        scheduleConnectingTimeout(ssid)
    }

    // ── 连接状态管理 ──────────────────────────────────────────────────────────

    private fun scheduleConnectingTimeout(targetSsid: String) {
        cancelConnectingTimeout()
        connectingTimeoutRunnable = Runnable {
            if (connectingSsid != targetSsid) return@Runnable
            Log.w(TAG, "连接超时: $targetSsid")
            if (getSystemConnectedSsid() == targetSsid) {
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
        pendingA11yRetry = false
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
