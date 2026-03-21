package com.heyu.apdemo2

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
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
        private const val KEY_POLL_INTERVAL = "poll_interval"
        private const val KEY_CYCLE_INTERVAL = "cycle_interval"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScanForegroundService.LocalBinder
            scanService = binder.getService()
            isBound = true
            getWifiFragment()?.onServiceBound(scanService!!)
            Log.d(TAG, "服务已绑定")
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

        if (!isBound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
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
            else -> super.onOptionsItemSelected(item)
        }
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

        AlertDialog.Builder(this)
            .setTitle("参数配置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
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
            .setNegativeButton("取消", null)
            .show()
    }
}
