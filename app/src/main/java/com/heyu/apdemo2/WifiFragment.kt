package com.heyu.apdemo2

import android.content.Intent
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
                if (ssid != null && success) {
                    onConnectSuccess(ssid)
                } else {
                    adapter.setPinned(null)
                    connectingSsid = null
                    connectingPassword = null
                    tvStatus.text = "状态: 扫描中"
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
        // 若 MainActivity 已绑定服务但 fragment 刚创建，补注册 callback
        if (scanService == null) {
            (activity as? MainActivity)?.getScanService()?.let { onServiceBound(it) }
        }
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
        if (::adapter.isInitialized) adapter.setPinned(service.pinnedSsid)
        Log.d(TAG, "服务已绑定")
    }

    fun onServiceUnbound() {
        scanService = null
        Log.d(TAG, "服务已解绑")
    }

    // ── WiFi 连接 ─────────────────────────────────────────────────────────────

    private fun handleApClick(ap: AccessPoint) {
        val service = scanService ?: return

        if (ap.ssid == service.pinnedSsid) {
            AlertDialog.Builder(requireContext())
                .setTitle("断开连接")
                .setMessage("取消连接并取消置顶 ${ap.ssid}？")
                .setPositiveButton("断开") { _, _ -> service.disconnectPinned() }
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
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityPrompt()
            return
        }
        connectingSsid = ssid
        connectingPassword = password
        tvStatus.text = "状态: 等待连接到 $ssid..."
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

    private fun showAccessibilityPrompt() {
        AlertDialog.Builder(requireContext())
            .setTitle("需要开启无障碍服务")
            .setMessage("请在无障碍设置中开启「一键切换WiFi」服务，开启后即可自动连接。")
            .setPositiveButton("去开启") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("取消", null)
            .show()
    }
    private fun onConnectSuccess(ssid: String) {
        adapter.setPinned(ssid)
        val pwd = connectingPassword
        if (!pwd.isNullOrEmpty()) PasswordStore.save(requireContext(), ssid, pwd)
        connectingSsid = null
        connectingPassword = null
        tvStatus.text = "状态: 已连接到 $ssid"
        Toast.makeText(requireContext(), "已连接到 $ssid", Toast.LENGTH_SHORT).show()
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
