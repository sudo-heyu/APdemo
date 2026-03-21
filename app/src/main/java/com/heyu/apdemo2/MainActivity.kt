package com.heyu.apdemo2

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.heyu.apdemo2.adapter.AccessPointAdapter
import com.heyu.apdemo2.model.AccessPoint
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AccessPointAdapter
    private lateinit var tvStatus: TextView
    private lateinit var recyclerView: RecyclerView

    private var scanService: ScanForegroundService? = null
    private var isBound = false

    // 防止每次 onStart 都重复启动服务
    private var serviceStarted = false

    companion object {
        private const val TAG = "[MAIN_ACTIVITY]"
        private const val PREFS_NAME = "server_settings"
        private const val KEY_IP = "server_ip"
        private const val KEY_PORT = "server_port"
        private const val KEY_POLL_INTERVAL = "poll_interval"
        private const val KEY_CYCLE_INTERVAL = "cycle_interval"
    }

    private val scanCallback = object : ScanForegroundService.ScanCallback {
        override fun onStatusUpdate(status: String) {
            runOnUiThread { tvStatus.text = "状态: $status" }
        }
        override fun onDataUpdate(accessPoints: List<AccessPoint>) {
            runOnUiThread { adapter.updateData(accessPoints) }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScanForegroundService.LocalBinder
            scanService = binder.getService()
            isBound = true
            scanService?.registerCallback(scanCallback)
            Log.d(TAG, "服务已绑定，isRunning=${scanService?.isRunning}")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            scanService = null
            isBound = false
            Log.d(TAG, "服务连接断开")
        }
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        setupRecyclerView()

        val (ip, port) = getServerAddress()
        if (ip == null || port == -1) {
            showServerInputDialog()
        } else {
            checkAndRequestPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        // 仅绑定，不重复启动（服务已在运行则连接，否则等 startAndBindService 显式启动）
        if (!isBound) {
            val intent = Intent(this, ScanForegroundService::class.java)
            val bound = bindService(intent, serviceConnection, 0)
            Log.d(TAG, "onStart 尝试绑定已有服务: $bound")
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回后再次检查，若仍未豁免则重新弹窗引导
        if (serviceStarted && !batteryDialogShowing) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryExemptionDialog()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            scanService?.unregisterCallback()
            unbindService(serviceConnection)
            isBound = false
            Log.d(TAG, "已解绑服务（服务仍在后台运行）")
        }
    }

    // ── 初始化 ──────────────────────────────────────────────────────────────

    private fun initViews() {
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        tvStatus = findViewById(R.id.tv_status)
        recyclerView = findViewById(R.id.recycler_view)
        tvStatus.text = "状态: 准备就绪"
    }

    private fun setupRecyclerView() {
        adapter = AccessPointAdapter()
        recyclerView.adapter = adapter
    }

    // ── 服务启动/绑定 ────────────────────────────────────────────────────────

    /**
     * 用 startForegroundService 显式启动服务（让它成为 started service，Activity 解绑后仍存活）
     * 只在权限全部就绪后调用一次，后续 onStart 只绑定不重启。
     */
    private fun startAndBindService() {
        val (ip, port) = getServerAddress()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val poll = prefs.getLong(KEY_POLL_INTERVAL, 10000L)
        val cycle = prefs.getLong(KEY_CYCLE_INTERVAL, 150000L)

        val intent = Intent(this, ScanForegroundService::class.java).apply {
            putExtra(ScanForegroundService.EXTRA_IP, ip)
            putExtra(ScanForegroundService.EXTRA_PORT, port)
            putExtra(ScanForegroundService.EXTRA_POLL, poll)
            putExtra(ScanForegroundService.EXTRA_CYCLE, cycle)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        serviceStarted = true
        Log.d(TAG, "startForegroundService 已调用")

        if (!isBound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "bindService 已调用")
        }
    }

    // ── 权限流程 ────────────────────────────────────────────────────────────

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            checkWifiAndLocationPermissions()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) checkBackgroundLocationPermission()
            else Toast.makeText(this, "未获得必要权限", Toast.LENGTH_SHORT).show()
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startAndBindService()
            requestBatteryOptimizationExemption()
        }

    /**
     * 权限链入口：POST_NOTIFICATIONS → WiFi/Location → 后台位置 → 电池优化豁免 → 启动服务
     */
    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkWifiAndLocationPermissions()
        }
    }

    private fun checkWifiAndLocationPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) checkBackgroundLocationPermission()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            startAndBindService()
            requestBatteryOptimizationExemption()
        }
    }

    // 防止 onResume 在对话框已显示时重复弹窗
    private var batteryDialogShowing = false

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        showBatteryExemptionDialog()
    }

    private fun showBatteryExemptionDialog() {
        batteryDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("需要后台运行权限")
            .setMessage("为确保息屏后仍能持续扫描和发送请求，请在接下来的页面中开启「允许后台高耗电」或「无限制」。")
            .setCancelable(false)
            .setPositiveButton("去开启") { _, _ ->
                batteryDialogShowing = false
                launchBatterySettings()
            }
            .setNegativeButton("跳过") { _, _ ->
                batteryDialogShowing = false
            }
            .show()
    }

    private fun launchBatterySettings() {
        // 先尝试系统级弹窗（原生 Android 会在应用内直接弹对话框）
        var handled = false
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
            handled = true
        } catch (_: Exception) {}

        // 国产 ROM 不支持系统弹窗时，直接跳到本应用详情页（点「电池」即可设置）
        if (!handled) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                Log.w(TAG, "无法跳转应用设置: ${e.message}")
                Toast.makeText(this, "请手动前往 设置→应用→本应用→电池，选择无限制", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── 菜单 ────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> { showServerInputDialog(); true }
            R.id.action_help -> { showHelpDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showHelpDialog() {
        val message = """
            💡 后台运行说明：

            本应用使用前台服务 + WakeLock + 独立后台线程保持扫描不中断。

            若切到后台后请求仍然停止，说明手机厂商的省电管理覆盖了标准 Android 行为，需手动设置：

            【华为/荣耀】设置 → 应用 → 应用启动管理 → 找到本应用 → 改为"手动管理"，勾选所有选项

            【小米/红米】设置 → 应用 → 权限 → 自启动 → 允许本应用；
            同时到 电量 → 省电 → 无限制 选择本应用

            【OPPO/一加】设置 → 电池 → 耗电保护 → 找到本应用 → 关闭限制

            【VIVO】设置 → 电池 → 后台高耗电 → 允许本应用

            通用：设置 → 应用 → 本应用 → 电池 → 不限制
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("帮助")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ── 配置对话框 ──────────────────────────────────────────────────────────

    private fun getServerAddress(): Pair<String?, Int> {
        val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(sharedPref.getString(KEY_IP, null), sharedPref.getInt(KEY_PORT, -1))
    }

    private fun showServerInputDialog() {
        val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentIp = sharedPref.getString(KEY_IP, "")
        val currentPort = sharedPref.getInt(KEY_PORT, -1)
        val currentPoll = sharedPref.getLong(KEY_POLL_INTERVAL, 10000L) / 1000
        val currentCycle = sharedPref.getLong(KEY_CYCLE_INTERVAL, 150000L) / 1000

        val builder = AlertDialog.Builder(this).setTitle("参数配置")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        val ipInput = EditText(this).apply { hint = "服务器 IP"; setText(currentIp) }
        val portInput = EditText(this).apply {
            hint = "端口"; inputType = InputType.TYPE_CLASS_NUMBER
            if (currentPort != -1) setText(currentPort.toString())
        }
        val pollInput = EditText(this).apply {
            hint = "Reason刷新 (秒)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentPoll.toString())
        }
        val cycleInput = EditText(this).apply {
            hint = "WiFi扫描频率 (秒)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentCycle.toString())
        }

        container.addView(ipInput)
        container.addView(portInput)
        container.addView(TextView(this).apply { text = "\nReason刷新 (秒):" })
        container.addView(pollInput)
        container.addView(TextView(this).apply { text = "\nWiFi扫描频率 (秒):" })
        container.addView(cycleInput)

        builder.setView(container)
        builder.setPositiveButton("保存") { _, _ ->
            val ip = ipInput.text.toString().trim()
            val p = portInput.text.toString().trim()
            val poll = pollInput.text.toString().trim().toLongOrNull() ?: 10L
            val cycle = cycleInput.text.toString().trim().toLongOrNull() ?: 150L

            if (ip.isNotEmpty() && p.isNotEmpty()) {
                val port = p.toInt()
                sharedPref.edit()
                    .putString(KEY_IP, ip)
                    .putInt(KEY_PORT, port)
                    .putLong(KEY_POLL_INTERVAL, poll * 1000)
                    .putLong(KEY_CYCLE_INTERVAL, cycle * 1000)
                    .apply()

                if (isBound) {
                    scanService?.updateConfig(ip, port, poll * 1000, cycle * 1000)
                } else {
                    checkAndRequestPermissions()
                }
            }
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }
}
