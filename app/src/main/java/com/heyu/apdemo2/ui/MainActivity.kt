package com.heyu.apdemo2.ui

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
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
import com.heyu.apdemo2.roaming.RoamingMode

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
        private const val KEY_AUTO_ROAMING  = "auto_roaming"
        private const val KEY_ROAMING_MODE  = "roaming_mode"   // "ML" | "SCORE"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScanForegroundService.LocalBinder
            scanService = binder.getService()
            isBound = true
            getWifiFragment()?.onServiceBound(scanService!!)
            // 同步自动漫游状态 & 漫游模式
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoRoamingEnabled = prefs.getBoolean(KEY_AUTO_ROAMING, false)
            val roamingModeStr = prefs.getString(KEY_ROAMING_MODE, RoamingMode.ML.name)
            val roamingMode = runCatching { RoamingMode.valueOf(roamingModeStr!!) }.getOrDefault(RoamingMode.ML)
            scanService?.setAutoRoamingEnabled(autoRoamingEnabled)
            scanService?.setRoamingMode(roamingMode)
            Log.d(TAG, "服务已绑定，自动漫游: $autoRoamingEnabled，模式: $roamingMode")
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

        val intent = Intent(this, ScanForegroundService::class.java).apply {
            putExtra(ScanForegroundService.EXTRA_IP, ip)
            putExtra(ScanForegroundService.EXTRA_PORT, port)
            putExtra(ScanForegroundService.EXTRA_SCAN_INTERVAL, scanInt)
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
            val actionView = it.actionView ?: return@let
            val textView = actionView.findViewById<TextView>(R.id.roaming_button) ?: return@let
            // 用 GradientDrawable 替换 selector，后续可直接动画改色
            textView.background = makeRoamingDrawable(initialEnabled)
            textView.setTextColor(if (initialEnabled) Color.WHITE else BLUE)
            actionView.setOnClickListener { toggleRoamingState(item) }
        }
    }

    private fun toggleRoamingState(item: MenuItem) {
        val newState = !item.isChecked
        item.isChecked = newState
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_ROAMING, newState).apply()
        scanService?.setAutoRoamingEnabled(newState)
        animateRoamingButton(item, newState)
        Toast.makeText(this, if (newState) "自动漫游已开启" else "自动漫游已关闭", Toast.LENGTH_SHORT).show()
    }

    // ── 漫游按钮动画 ────────────────────────────────────────────────────────

    private val BLUE = Color.parseColor("#2196F3")

    /** 按钮背景 GradientDrawable，enabled=true 为填充蓝，false 为镂空 */
    private fun makeRoamingDrawable(enabled: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 4f * resources.displayMetrics.density
        setColor(if (enabled) BLUE else Color.TRANSPARENT)
        setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), BLUE)
    }

    /**
     * 切换动画：
     *   1. 按压缩放 → OvershootInterpolator 弹回
     *   2. 背景色渐变（透明 ↔ 蓝色填充）
     *   3. 文字色渐变（蓝色 ↔ 白色）
     */
    private fun animateRoamingButton(item: MenuItem?, enabled: Boolean) {
        val actionView = item?.actionView ?: return
        val textView   = actionView.findViewById<TextView>(R.id.roaming_button) ?: return
        val drawable   = textView.background as? GradientDrawable ?: run {
            // 万一背景不是 GradientDrawable，先替换再动画
            val d = makeRoamingDrawable(!enabled)   // 当前状态（切换前）
            textView.background = d; d
        }

        val bgFrom  = if (enabled) Color.TRANSPARENT else BLUE
        val bgTo    = if (enabled) BLUE else Color.TRANSPARENT
        val txFrom  = if (enabled) BLUE else Color.WHITE
        val txTo    = if (enabled) Color.WHITE else BLUE

        // 1. 按压缩放反馈
        actionView.animate()
            .scaleX(0.88f).scaleY(0.88f)
            .setDuration(80)
            .withEndAction {
                actionView.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }.start()

        // 2. 背景色渐变
        ValueAnimator.ofObject(ArgbEvaluator(), bgFrom, bgTo).apply {
            duration = 260
            addUpdateListener { drawable.setColor(it.animatedValue as Int) }
            start()
        }

        // 3. 文字色渐变
        ValueAnimator.ofObject(ArgbEvaluator(), txFrom, txTo).apply {
            duration = 260
            addUpdateListener { textView.setTextColor(it.animatedValue as Int) }
            start()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> { showServerInputDialog(); true }
            R.id.action_roaming_log -> { showRoamingLog(); true }
            R.id.action_request_scores -> { requestScores(); true }
            R.id.action_export_log -> { exportRoamingLog(); true }
            R.id.action_auto_roaming -> {
                // 点击事件已在 setupRoamingButton 中处理
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun requestScores() {
        val service = scanService
        if (service == null) {
            Toast.makeText(this, "服务未启动", Toast.LENGTH_SHORT).show()
            return
        }
        if (service.currentAccessPoints.isEmpty()) {
            Toast.makeText(this, "暂无扫描数据", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在请求评分...", Toast.LENGTH_SHORT).show()
        service.requestScores()
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
        val currentIp      = sharedPref.getString(KEY_IP, "")
        val currentPort    = sharedPref.getInt(KEY_PORT, -1)
        val currentScanInt = sharedPref.getLong(KEY_SCAN_INTERVAL, 35000L) / 1000
        val currentMode    = runCatching {
            RoamingMode.valueOf(sharedPref.getString(KEY_ROAMING_MODE, RoamingMode.ML.name)!!)
        }.getOrDefault(RoamingMode.ML)

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

        // ── 漫游策略切换器（无 ripple 残影的自绘 segmented control）────────
        var selectedMode = currentMode
        val density = resources.displayMetrics.density

        fun segBtn(label: String) = TextView(this).apply {
            text = label
            textSize = 13f
            setPadding((14 * density).toInt(), (8 * density).toInt(),
                       (14 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4f * density
                setColor(Color.TRANSPARENT)
                setStroke(density.toInt().coerceAtLeast(1), BLUE)
            }
            setTextColor(BLUE)
            isClickable = true
            isFocusable = true
        }

        val btnMl    = segBtn("ML 漫游")
        val btnScore = segBtn("评分漫游")

        fun applySegState(target: TextView, active: Boolean, animate: Boolean) {
            val bgTo = if (active) BLUE else Color.TRANSPARENT
            val txTo = if (active) Color.WHITE else BLUE
            if (animate) {
                val bgFrom = if (active) Color.TRANSPARENT else BLUE
                val txFrom = if (active) BLUE else Color.WHITE
                ValueAnimator.ofObject(ArgbEvaluator(), bgFrom, bgTo).apply {
                    duration = 200
                    addUpdateListener {
                        (target.background as? GradientDrawable)?.setColor(it.animatedValue as Int)
                    }
                    start()
                }
                ValueAnimator.ofObject(ArgbEvaluator(), txFrom, txTo).apply {
                    duration = 200
                    addUpdateListener { target.setTextColor(it.animatedValue as Int) }
                    start()
                }
            } else {
                (target.background as? GradientDrawable)?.setColor(bgTo)
                target.setTextColor(txTo)
            }
        }

        // 初始状态（不播动画）
        applySegState(btnMl,    selectedMode == RoamingMode.ML,    animate = false)
        applySegState(btnScore, selectedMode == RoamingMode.SCORE, animate = false)

        btnMl.setOnClickListener {
            if (selectedMode != RoamingMode.ML) {
                selectedMode = RoamingMode.ML
                applySegState(btnMl,    active = true,  animate = true)
                applySegState(btnScore, active = false, animate = true)
            }
        }
        btnScore.setOnClickListener {
            if (selectedMode != RoamingMode.SCORE) {
                selectedMode = RoamingMode.SCORE
                applySegState(btnScore, active = true,  animate = true)
                applySegState(btnMl,    active = false, animate = true)
            }
        }

        val segRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = (6 * density).toInt()
            addView(btnMl,    lp)
            addView(btnScore, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        // ─────────────────────────────────────────────────────────────────────

        container.addView(ipInput)
        container.addView(portInput)
        container.addView(TextView(this).apply { text = "\n扫描间隔 (秒):" })
        container.addView(scanIntInput)
        container.addView(TextView(this).apply { text = "\n漫游策略:" })
        container.addView(segRow)

        AlertDialog.Builder(this)
            .setTitle("参数配置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val ip = ipInput.text.toString().trim()
                val p  = portInput.text.toString().trim()
                val scanInt = scanIntInput.text.toString().trim().toLongOrNull() ?: 35L
                val newMode = selectedMode

                if (ip.isNotEmpty() && p.isNotEmpty()) {
                    val port = p.toInt()
                    sharedPref.edit()
                        .putString(KEY_IP, ip)
                        .putInt(KEY_PORT, port)
                        .putLong(KEY_SCAN_INTERVAL, scanInt * 1000)
                        .putString(KEY_ROAMING_MODE, newMode.name)
                        .apply()
                    scanService?.setRoamingMode(newMode)
                    if (isBound) {
                        scanService?.updateConfig(ip, port, scanInt * 1000)
                    } else {
                        checkAndRequestPermissions()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
