package com.heyu.apdemo2.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.WebView
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.heyu.apdemo2.R
import com.heyu.apdemo2.network.ApiService
import com.heyu.apdemo2.roaming.RoamingLogManager
import com.heyu.apdemo2.service.ScanForegroundService
import com.heyu.apdemo2.roaming.ApPerformanceMonitor

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView

    private var scanService: ScanForegroundService? = null
    private var isBound = false
    private var serviceStarted = false

    companion object {
        private const val TAG = "[MAIN_ACTIVITY]"
        private const val PREFS_NAME = "server_settings"
        private const val KEY_IP = "server_ip"
        private const val KEY_PORT = "server_port"
        private const val KEY_SCAN_INTERVAL = "scan_interval"
        private const val KEY_POLL_INTERVAL = "poll_interval"
        private const val KEY_AUTO_ROAMING = "auto_roaming"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScanForegroundService.LocalBinder
            scanService = binder.getService()
            isBound = true
            getWifiFragment()?.onServiceBound(scanService!!)
            // 同步自动漫游状态
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoRoamingEnabled = prefs.getBoolean(KEY_AUTO_ROAMING, false)
            scanService?.setAutoRoamingEnabled(autoRoamingEnabled)
            Log.d(TAG, "服务已绑定，自动漫游: $autoRoamingEnabled")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            getWifiFragment()?.onServiceUnbound()
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

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        bottomNav = findViewById(R.id.bottom_nav)
        // 完全移除底部导航栏的边距，使其紧贴底部
        bottomNav.setPadding(0, 0, 0, 0)
        bottomNav.minimumHeight = 0
        // 递归移除所有子视图的边距
        for (i in 0 until bottomNav.childCount) {
            val child = bottomNav.getChildAt(i)
            child.setPadding(0, 0, 0, 0)
            if (child is ViewGroup) {
                for (j in 0 until child.childCount) {
                    child.getChildAt(j).setPadding(0, 0, 0, 0)
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { _, insets ->
            // 消费掉所有 insets，不让 BottomNavigationView 自动添加底部 padding
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            showWifiFragment()
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_wifi -> { showWifiFragment(); true }
                R.id.nav_help -> { showHelpFragment(); true }
                else -> false
            }
        }

        val (ip, port) = getServerAddress()
        if (ip == null || port == -1) {
            showServerInputDialog()
        } else {
            checkAndRequestPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isBound) {
            val intent = Intent(this, ScanForegroundService::class.java)
            val bound = bindService(intent, serviceConnection, 0)
            Log.d(TAG, "onStart 尝试绑定已有服务: $bound")
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            scanService?.unregisterCallback()
            unbindService(serviceConnection)
            isBound = false
            Log.d(TAG, "已解绑服务")
        }
        // 停止性能监控服务
        ApPerformanceMonitor.stop(this)
        Log.d(TAG, "性能监控服务已停止")
    }

    // ── Fragment 切换 ────────────────────────────────────────────────────────

    private fun showWifiFragment() {
        toolbar.title = "WIFI"
        val fm = supportFragmentManager
        var frag = getWifiFragment()
        if (frag == null) {
            frag = WifiFragment()
            fm.beginTransaction()
                .replace(R.id.fragment_container, frag, WifiFragment.FRAGMENT_TAG)
                .commit()
        } else {
            fm.beginTransaction()
                .show(frag)
                .commit()
        }
        getHelpFragment()?.let { fm.beginTransaction().hide(it).commit() }
    }

    private fun showHelpFragment() {
        toolbar.title = "帮助"
        val fm = supportFragmentManager
        var frag = getHelpFragment()
        if (frag == null) {
            frag = HelpFragment()
            fm.beginTransaction()
                .replace(R.id.fragment_container, frag, HelpFragment.FRAGMENT_TAG)
                .commit()
        } else {
            fm.beginTransaction()
                .show(frag)
                .commit()
        }
        getWifiFragment()?.let { fm.beginTransaction().hide(it).commit() }
    }

    private fun getWifiFragment() =
        supportFragmentManager.findFragmentByTag(WifiFragment.FRAGMENT_TAG) as? WifiFragment

    private fun getHelpFragment() =
        supportFragmentManager.findFragmentByTag(HelpFragment.FRAGMENT_TAG) as? HelpFragment

    // ── 供 WifiFragment 访问服务 ─────────────────────────────────────────────

    fun getScanService(): ScanForegroundService? = scanService

    // ── 服务启动 ─────────────────────────────────────────────────────────────

    private fun startAndBindService() {
        val (ip, port) = getServerAddress()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val scanInt = prefs.getLong(KEY_SCAN_INTERVAL, 35000L)
        val pollInt = prefs.getLong(KEY_POLL_INTERVAL, 10000L)

        val intent = Intent(this, ScanForegroundService::class.java).apply {
            putExtra(ScanForegroundService.EXTRA_IP, ip)
            putExtra(ScanForegroundService.EXTRA_PORT, port)
            putExtra(ScanForegroundService.EXTRA_SCAN_INTERVAL, scanInt)
            putExtra(ScanForegroundService.EXTRA_POLL_INTERVAL, pollInt)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        serviceStarted = true

        if (!isBound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // 启动性能监控服务
        ApPerformanceMonitor.start(this)
        Log.d(TAG, "性能监控服务已启动")
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
        }

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
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
        }
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
        }
    }

    // ── 菜单 ────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        // 设置自动漫游开关状态，默认关闭
        val autoRoamingItem = menu.findItem(R.id.action_auto_roaming)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoRoamingEnabled = prefs.getBoolean(KEY_AUTO_ROAMING, false)
        autoRoamingItem?.isChecked = autoRoamingEnabled
        // 设置自定义按钮样式和点击事件
        setupRoamingButton(autoRoamingItem, autoRoamingEnabled)
        scanService?.setAutoRoamingEnabled(autoRoamingEnabled)
        return true
    }

    private fun setupRoamingButton(item: MenuItem?, initialEnabled: Boolean) {
        item?.let {
            val actionView = it.actionView
            if (actionView != null) {
                actionView.isSelected = initialEnabled
                val textView = actionView.findViewById<TextView>(R.id.roaming_button)
                textView?.let { tv ->
                    tv.isSelected = initialEnabled
                    // 开启状态：白色文字；关闭状态：蓝色文字
                    tv.setTextColor(if (initialEnabled) Color.WHITE else Color.parseColor("#2196F3"))
                    // 设置点击事件
                    actionView.setOnClickListener {
                        toggleRoamingState(item)
                    }
                }
            }
        }
    }

    private fun toggleRoamingState(item: MenuItem) {
        val newState = !item.isChecked
        item.isChecked = newState
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_ROAMING, newState).apply()
        scanService?.setAutoRoamingEnabled(newState)
        updateRoamingButtonStyle(item, newState)
        Toast.makeText(this, if (newState) "自动漫游已开启" else "自动漫游已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun updateRoamingButtonStyle(item: MenuItem?, enabled: Boolean) {
        item?.let {
            val actionView = it.actionView
            if (actionView != null) {
                actionView.isSelected = enabled
                val textView = actionView.findViewById<TextView>(R.id.roaming_button)
                textView?.let { tv ->
                    tv.isSelected = enabled
                    // 开启状态：白色文字 + 蓝色填充背景；关闭状态：蓝色文字 + 透明背景
                    tv.setTextColor(if (enabled) Color.WHITE else Color.parseColor("#2196F3"))
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> { showServerInputDialog(); true }
            R.id.action_roaming_log -> { showRoamingLog(); true }
            R.id.action_export_log -> { exportRoamingLog(); true }
            R.id.action_auto_roaming -> {
                // 点击事件已在 setupRoamingButton 中处理
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private val logHtmlStyle = """
        body { font-family: monospace; font-size: 11px; color: #222; padding: 8px; line-height: 1.6; }
        table { border-collapse: collapse; margin: 4px 0; font-size: 11px; }
        th, td { border: 1px solid #999; padding: 3px 6px; text-align: center; white-space: nowrap; }
        th { background: #e0e0e0; font-weight: bold; }
        tr:nth-child(even) { background: #f5f5f5; }
    """.trimIndent()

    private fun logsToHtml(logText: String): String {
        val body = if (logText.isBlank() || logText == "暂无日志") "暂无日志"
                   else logText.replace("\n", "<br>")
        return """<html><head><style>$logHtmlStyle</style></head>
            <body><div id="log">$body</div>
            <script>
            var userScrolled = false;
            function atBottom() {
                return (window.innerHeight + window.scrollY) >= (document.body.scrollHeight - 30);
            }
            window.addEventListener('scroll', function() {
                userScrolled = !atBottom();
            });
            function scrollToBottom() {
                userScrolled = false;
                window.scrollTo(0, document.body.scrollHeight);
            }
            function append(html) {
                var d = document.getElementById('log');
                d.insertAdjacentHTML('beforeend', '<br>' + html);
                if (!userScrolled) window.scrollTo(0, document.body.scrollHeight);
            }
            window.onload = function() { window.scrollTo(0, document.body.scrollHeight); };
            </script></body></html>"""
    }

    private fun String.jsEscape(): String = this
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")

    private fun showRoamingLog() {
        val logManager = RoamingLogManager.getInstance(this)
        val logs = logManager.getLogs()

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            loadDataWithBaseURL(null, logsToHtml(logs), "text/html", "UTF-8", null)
        }

        val listener = RoamingLogManager.OnLogListener { logLine ->
            runOnUiThread {
                val escaped = logLine.jsEscape()
                webView.evaluateJavascript("append('$escaped')", null)
            }
        }
        logManager.addListener(listener)

        val dialog = AlertDialog.Builder(this)
            .setTitle("漫游算法日志")
            .setView(webView)
            .setPositiveButton("关闭", null)
            .setNegativeButton("底部", null)
            .setNeutralButton("清空", null)
            .setOnDismissListener {
                logManager.removeListener(listener)
                webView.destroy()
            }
            .show()

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            webView.evaluateJavascript("scrollToBottom()", null)
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            logManager.clearLogs()
            webView.loadDataWithBaseURL(null, logsToHtml("暂无日志"), "text/html", "UTF-8", null)
        }
    }

    private fun exportRoamingLog() {
        val (ip, port) = getServerAddress()
        if (ip.isNullOrBlank() || port == -1) {
            Toast.makeText(this, "请先配置服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        val logManager = RoamingLogManager.getInstance(this)
        val logs = logManager.getLogs()
        if (logs == "暂无日志") {
            Toast.makeText(this, "暂无日志可导出", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在导出...", Toast.LENGTH_SHORT).show()
        ApiService().uploadRoamingLog(ip, port, logs, object : ApiService.SimpleCallback {
            override fun onSuccess() {
                runOnUiThread { Toast.makeText(this@MainActivity, "日志导出成功", Toast.LENGTH_SHORT).show() }
            }
            override fun onError(error: String) {
                runOnUiThread { Toast.makeText(this@MainActivity, "导出失败: $error", Toast.LENGTH_LONG).show() }
            }
        })
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
        val currentScanInt = sharedPref.getLong(KEY_SCAN_INTERVAL, 35000L) / 1000
        val currentPollInt = sharedPref.getLong(KEY_POLL_INTERVAL, 10000L) / 1000

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }
        val ipInput = EditText(this).apply { hint = "服务器 IP"; setText(currentIp) }
        val portInput = EditText(this).apply {
            hint = "端口"; inputType = InputType.TYPE_CLASS_NUMBER
            if (currentPort != -1) setText(currentPort.toString())
        }
        val scanIntInput = EditText(this).apply {
            hint = "扫描间隔 (秒)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentScanInt.toString())
        }
        val pollIntInput = EditText(this).apply {
            hint = "后端请求间隔 (秒)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentPollInt.toString())
        }
        container.addView(ipInput)
        container.addView(portInput)
        container.addView(TextView(this).apply { text = "\n扫描间隔 (秒):" })
        container.addView(scanIntInput)
        container.addView(TextView(this).apply { text = "\n后端请求间隔 (秒):" })
        container.addView(pollIntInput)

        AlertDialog.Builder(this)
            .setTitle("参数配置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val ip = ipInput.text.toString().trim()
                val p = portInput.text.toString().trim()
                val scanInt = scanIntInput.text.toString().trim().toLongOrNull() ?: 35L
                val pollInt = pollIntInput.text.toString().trim().toLongOrNull() ?: 10L
                if (ip.isNotEmpty() && p.isNotEmpty()) {
                    val port = p.toInt()
                    sharedPref.edit()
                        .putString(KEY_IP, ip)
                        .putInt(KEY_PORT, port)
                        .putLong(KEY_SCAN_INTERVAL, scanInt * 1000)
                        .putLong(KEY_POLL_INTERVAL, pollInt * 1000)
                        .apply()
                    if (isBound) {
                        scanService?.updateConfig(ip, port, scanInt * 1000, pollInt * 1000)
                    } else {
                        checkAndRequestPermissions()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
