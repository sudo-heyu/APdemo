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

    // Current connecting target info
    private var connectingSsid: String? = null
    private var connectingPassword: String? = null
    // Only set true after user goes to enable accessibility service, cleared after onResume consumes once, preventing returnToApp from triggering loop again
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
                if (connectingSsid == null) tvStatus.text = "Status: $status"
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
                    tvStatus.text = "Status: Connected to $ssid"
                    adapter.setPinned(ssid)
                } else {
                    clearConnectingState()
                    if (errorType.isNotEmpty()) tvStatus.text = "Status: Connection failed - $errorType"
                    adapter.setPinned(null)
                }
                (activity as? MainActivity)?.getScanService()?.updateConnectedSsid(ssid)
            }
        }
        override fun onReconnecting(ssid: String, attempt: Int) {
            activity?.runOnUiThread {
                tvStatus.text = "Status: Reconnecting to $ssid (attempt $attempt)..."
            }
        }
        override fun onApprovalNeeded() {}
        override fun onRoamingStatus(status: String) {
            // Roaming status no longer displayed in status bar, only logged
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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

        // When returning from accessibility settings: pendingA11yRetry consumed once then cleared immediately, preventing subsequent resume from triggering again
        if (pendingA11yRetry &&
            WifiAccessibilityService.isEnabled(requireContext()) &&
            WifiAccessibilityService.getInstance() != null
        ) {
            val pendingSsid = connectingSsid
            val pendingPwd  = connectingPassword
            pendingA11yRetry = false          // Clear immediately to prevent loop
            if (pendingSsid != null && pendingPwd != null) {
                Log.d(TAG, "Returning from accessibility settings, retrying connection: $pendingSsid")
                clearConnectingState()
                val isOpen = PasswordStore.get(requireContext(), pendingSsid) == null && pendingPwd.isEmpty()
                initiateConnect(pendingSsid, isOpen, pendingPwd)
                return
            }
        }

        // User manually returned from WiFi settings but didn't connect to target network: silently clear connecting state
        if (connectingSsid != null && !pendingA11yRetry) {
            val systemSsid = getSystemConnectedSsid()
            if (systemSsid != connectingSsid) {
                Log.d(TAG, "User manually returned, not connected to target $connectingSsid, clearing state")
                clearConnectingState()
                tvStatus.text = "Status: Not connected"
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

    // ── Called by MainActivity ──────────────────────────────────────────────────

    fun onServiceBound(service: ScanForegroundService) {
        scanService = service
        service.registerCallback(scanCallback)
        if (::adapter.isInitialized && connectingSsid == null) syncConnectedSsid()
        Log.d(TAG, "Service bound")
    }

    fun onServiceUnbound() {
        scanService = null
        Log.d(TAG, "Service unbound")
    }

    // ── System WiFi State Sync ────────────────────────────────────────────────────

    private fun syncConnectedSsid() {
        if (connectingSsid != null) {
            Log.d(TAG, "[syncConnectedSsid] Skipped: connecting to $connectingSsid")
            return
        }

        val systemSsid = getSystemConnectedSsid()
        val service = (activity as? MainActivity)?.getScanService()

        Log.d(TAG, "[syncConnectedSsid] systemSsid=$systemSsid")

        adapter.setPinned(systemSsid)

        if (systemSsid != null) {
            tvStatus.text = "Status: Connected to $systemSsid"
        }

        service?.updateConnectedSsid(systemSsid)
        Log.d(TAG, "[syncConnectedSsid] Synced WiFi state to service: $systemSsid")
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
                            // Connected to the network we wanted, update state immediately
                            cancelConnectingTimeout()
                            clearConnectingState()
                            tvStatus.text = "Status: Connected to $systemSsid"
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

    // ── WiFi Connection Main Flow ───────────────────────────────────────────────────────

    private fun handleApClick(ap: AccessPoint) {
        if (ap.ssid == adapter.pinnedSsid) {
            AlertDialog.Builder(requireContext())
                .setTitle("Disconnect")
                .setMessage("Disconnect from ${ap.ssid}?")
                .setPositiveButton("Disconnect") { _, _ ->
                    (activity as? MainActivity)?.getScanService()?.disconnectPinned()
                    adapter.setPinned(null)
                    tvStatus.text = "Status: Disconnected"
                }
                .setNegativeButton("Cancel", null)
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
     * Check if accessibility service is enabled.
     * - Enabled: initiate connection directly
     * - Not enabled: show dialog to guide user to enable, jump to settings after confirmation
     */
    private fun checkA11yAndConnect(ssid: String, isOpen: Boolean, password: String) {
        if (WifiAccessibilityService.isEnabled(requireContext())) {
            initiateConnect(ssid, isOpen, password)
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("Accessibility Service Required")
                .setMessage(
                    "APdemo2 requires accessibility service permission to perform real WiFi switching (other apps like streaming also follow the switch).\n\n" +
                    "Please find \"${getString(R.string.app_name)}\" in \"Accessibility\" → \"Downloaded apps\" and enable it."
                )
                .setPositiveButton("Go to Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    // Record pending connection target; pendingA11yRetry consumed once in onResume then cleared immediately
                    connectingSsid = ssid
                    connectingPassword = password
                    pendingA11yRetry = true
                }
                .setNegativeButton("Not Now (Fallback)") { _, _ ->
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
            tvStatus.text = "Status: Service not started, cannot connect"
            clearConnectingState()
            return
        }

        tvStatus.text = "Status: Switching (accessibility) $ssid..."

        // Let service prepare accessibility service target (don't open WiFi settings page)
        service.connectToNetwork(ssid, isOpen, password)

        // Fragment opens WiFi settings page itself (same task stack), ensuring one BACK returns to app
        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        scheduleConnectingTimeout(ssid)
    }

    // ── Connection State Management ──────────────────────────────────────────────────────────

    private fun scheduleConnectingTimeout(targetSsid: String) {
        cancelConnectingTimeout()
        connectingTimeoutRunnable = Runnable {
            if (connectingSsid != targetSsid) return@Runnable
            Log.w(TAG, "Connection timeout: $targetSsid")
            if (getSystemConnectedSsid() == targetSsid) {
                tvStatus.text = "Status: Connected to $targetSsid"
                adapter.setPinned(targetSsid)
            } else {
                tvStatus.text = "Status: Connection timeout, please retry"
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

    // ── Password Input Dialog ──────────────────────────────────────────────────────────

    private fun showPasswordDialog(ap: AccessPoint) {
        val passwordInput = EditText(requireContext()).apply {
            hint = "Enter WiFi password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
            addView(passwordInput)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Connect to ${ap.ssid}")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val pwd = passwordInput.text.toString()
                if (pwd.length < 8) {
                    Toast.makeText(requireContext(), "Password must be at least 8 characters", Toast.LENGTH_SHORT).show()
                } else {
                    initiateConnect(ap.ssid, isOpen = false, password = pwd)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (adapter.pinnedSsid == ap.ssid) adapter.setPinned(null)
            }
            .show()
    }
}
