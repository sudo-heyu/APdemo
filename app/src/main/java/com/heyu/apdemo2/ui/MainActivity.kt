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
import android.provider.Settings
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
import com.heyu.apdemo2.roaming.RoamingLogManager
import com.heyu.apdemo2.service.ScanForegroundService
import com.heyu.apdemo2.service.WifiAccessibilityService
import com.heyu.apdemo2.roaming.ApPerformanceMonitor
import com.heyu.apdemo2.roaming.RoamingMode

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView

    private var scanService: ScanForegroundService? = null
    private var isBound = false
    private var serviceStarted = false
    private var a11yPrompted = false

    companion object {
        private const val TAG = "[MAIN_ACTIVITY]"
        private const val PREFS_NAME = "server_settings"
        private const val KEY_IP = "server_ip"
        private const val KEY_PORT = "server_port"
        private const val KEY_SCAN_INTERVAL = "scan_interval"
        private const val KEY_AUTO_ROAMING  = "auto_roaming"
        private const val KEY_ROAMING_MODE  = "roaming_mode"   // "ML" | "SCORE"
        private const val KEY_ROAMING_COOLDOWN = "roaming_cooldown" // Switch cooldown (milliseconds)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScanForegroundService.LocalBinder
            scanService = binder.getService()
            isBound = true
            getWifiFragment()?.onServiceBound(scanService!!)
            // Sync auto-roaming state & roaming mode
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoRoamingEnabled = prefs.getBoolean(KEY_AUTO_ROAMING, false)
            val roamingModeStr = prefs.getString(KEY_ROAMING_MODE, RoamingMode.ML.name)
            val roamingMode = runCatching { RoamingMode.valueOf(roamingModeStr!!) }.getOrDefault(RoamingMode.ML)
            val roamingCooldown = prefs.getLong(KEY_ROAMING_COOLDOWN, 5000L)
            scanService?.setAutoRoamingEnabled(autoRoamingEnabled)
            scanService?.setRoamingMode(roamingMode)
            scanService?.setRoamingCooldown(roamingCooldown)
            Log.d(TAG, "Service bound, auto-roaming: $autoRoamingEnabled, mode: $roamingMode, cooldown: ${roamingCooldown}ms")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            getWifiFragment()?.onServiceUnbound()
            scanService = null
            isBound = false
            Log.d(TAG, "Service connection disconnected")
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

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
        // Completely remove bottom navigation bar margins to make it flush with bottom
        bottomNav.setPadding(0, 0, 0, 0)
        bottomNav.minimumHeight = 0
        // Recursively remove margins from all child views
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
            // Consume all insets, don't let BottomNavigationView auto-add bottom padding
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
            Log.d(TAG, "onStart attempting to bind existing service: $bound")
        }
    }

    override fun onResume() {
        super.onResume()
        if (!a11yPrompted && !WifiAccessibilityService.isEnabled(this)) {
            a11yPrompted = true
            showA11yPrompt()
        }
    }

    private fun showA11yPrompt() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Service Required")
            .setMessage(
                "APdemo2 requires accessibility service permission to perform real WiFi switching (other apps like streaming also follow the switch).\n\n" +
                "Please find \"${getString(R.string.app_name)}\" in \"Accessibility\" → \"Downloaded apps\" and enable it."
            )
            .setPositiveButton("Go to Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            .setNegativeButton("Not Now", null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            scanService?.unregisterCallback()
            unbindService(serviceConnection)
            isBound = false
            Log.d(TAG, "Service unbound")
        }
        // Stop performance monitoring service
        ApPerformanceMonitor.stop(this)
        Log.d(TAG, "Performance monitoring service stopped")
    }

    // ── Fragment Switching ────────────────────────────────────────────────────────

    private fun showWifiFragment() {
        toolbar.title = "WiFi"
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
        toolbar.title = "Help"
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

    // ── For WifiFragment to access service ─────────────────────────────────────────────

    fun getScanService(): ScanForegroundService? = scanService

    // ── Service Startup ─────────────────────────────────────────────────────────────

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

        // Start performance monitoring service
        ApPerformanceMonitor.start(this)
        Log.d(TAG, "Performance monitoring service started")
    }

    // ── Permission Flow ────────────────────────────────────────────────────────────

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            checkWifiAndLocationPermissions()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) checkBackgroundLocationPermission()
            else Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_SHORT).show()
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

    // ── Menu ────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        // Set auto-roaming switch state, default off
        val autoRoamingItem = menu.findItem(R.id.action_auto_roaming)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoRoamingEnabled = prefs.getBoolean(KEY_AUTO_ROAMING, false)
        autoRoamingItem?.isChecked = autoRoamingEnabled
        // Set custom button style and click event
        setupRoamingButton(autoRoamingItem, autoRoamingEnabled)
        scanService?.setAutoRoamingEnabled(autoRoamingEnabled)
        return true
    }

    private fun setupRoamingButton(item: MenuItem?, initialEnabled: Boolean) {
        item?.let {
            val actionView = it.actionView ?: return@let
            val textView = actionView.findViewById<TextView>(R.id.roaming_button) ?: return@let
            // Replace selector with GradientDrawable, can animate colors later
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
        Toast.makeText(this, if (newState) "Auto-roaming enabled" else "Auto-roaming disabled", Toast.LENGTH_SHORT).show()
    }

    // ── Roaming Button Animation ────────────────────────────────────────────────────────

    private val BLUE = Color.parseColor("#2196F3")

    /** Button background GradientDrawable, enabled=true is filled blue, false is outlined */
    private fun makeRoamingDrawable(enabled: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 4f * resources.displayMetrics.density
        setColor(if (enabled) BLUE else Color.TRANSPARENT)
        setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), BLUE)
    }

    /**
     * Toggle animation:
     *   1. Press scale → OvershootInterpolator bounce back
     *   2. Background color gradient (transparent ↔ blue fill)
     *   3. Text color gradient (blue ↔ white)
     */
    private fun animateRoamingButton(item: MenuItem?, enabled: Boolean) {
        val actionView = item?.actionView ?: return
        val textView   = actionView.findViewById<TextView>(R.id.roaming_button) ?: return
        val drawable   = textView.background as? GradientDrawable ?: run {
            // In case background isn't GradientDrawable, replace then animate
            val d = makeRoamingDrawable(!enabled)   // Current state (before toggle)
            textView.background = d; d
        }

        val bgFrom  = if (enabled) Color.TRANSPARENT else BLUE
        val bgTo    = if (enabled) BLUE else Color.TRANSPARENT
        val txFrom  = if (enabled) BLUE else Color.WHITE
        val txTo    = if (enabled) Color.WHITE else BLUE

        // 1. Press scale feedback
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

        // 2. Background color gradient
        ValueAnimator.ofObject(ArgbEvaluator(), bgFrom, bgTo).apply {
            duration = 260
            addUpdateListener { drawable.setColor(it.animatedValue as Int) }
            start()
        }

        // 3. Text color gradient
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
            R.id.action_auto_roaming -> {
                // Click event handled in setupRoamingButton
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun requestScores() {
        val service = scanService
        if (service == null) {
            Toast.makeText(this, "Service not started", Toast.LENGTH_SHORT).show()
            return
        }
        if (service.currentAccessPoints.isEmpty()) {
            Toast.makeText(this, "No scan data available", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Requesting scores...", Toast.LENGTH_SHORT).show()
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
        val body = if (logText.isBlank() || logText == "No logs available") "No logs available"
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
            .setTitle("Roaming Algorithm Log")
            .setView(webView)
            .setPositiveButton("Close", null)
            .setNegativeButton("Bottom", null)
            .setNeutralButton("Clear", null)
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
            webView.loadDataWithBaseURL(null, logsToHtml("No logs available"), "text/html", "UTF-8", null)
        }
    }

    // ── Configuration Dialog ──────────────────────────────────────────────────────────

    private fun getServerAddress(): Pair<String?, Int> {
        val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(sharedPref.getString(KEY_IP, null), sharedPref.getInt(KEY_PORT, -1))
    }

    private fun showServerInputDialog() {
        val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentIp      = sharedPref.getString(KEY_IP, "")
        val currentPort    = sharedPref.getInt(KEY_PORT, -1)
        val currentScanInt = sharedPref.getLong(KEY_SCAN_INTERVAL, 35000L) / 1000
        val currentCooldown = sharedPref.getLong(KEY_ROAMING_COOLDOWN, 5000L) / 1000

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        val ipInput = EditText(this).apply { hint = "Server IP"; setText(currentIp) }
        val portInput = EditText(this).apply {
            hint = "Port"; inputType = InputType.TYPE_CLASS_NUMBER
            if (currentPort != -1) setText(currentPort.toString())
        }
        val scanIntInput = EditText(this).apply {
            hint = "Scan Interval (seconds)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentScanInt.toString())
        }
        val cooldownInput = EditText(this).apply {
            hint = "Switch Cooldown (seconds)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentCooldown.toString())
        }

        container.addView(TextView(this).apply { text = "Server IP:" })
        container.addView(ipInput)
        container.addView(TextView(this).apply { text = "\nPort:" })
        container.addView(portInput)
        container.addView(TextView(this).apply { text = "\nScan Interval (seconds):" })
        container.addView(scanIntInput)
        container.addView(TextView(this).apply { text = "\nSwitch Cooldown (seconds):" })
        container.addView(cooldownInput)

        AlertDialog.Builder(this)
            .setTitle("Parameter Configuration")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val ip = ipInput.text.toString().trim()
                val p  = portInput.text.toString().trim()
                val scanInt = scanIntInput.text.toString().trim().toLongOrNull() ?: 35L
                val cooldown = cooldownInput.text.toString().trim().toLongOrNull() ?: 5L

                if (ip.isNotEmpty() && p.isNotEmpty()) {
                    val port = p.toInt()
                    sharedPref.edit()
                        .putString(KEY_IP, ip)
                        .putInt(KEY_PORT, port)
                        .putLong(KEY_SCAN_INTERVAL, scanInt * 1000)
                        .putLong(KEY_ROAMING_COOLDOWN, cooldown * 1000)
                        .putString(KEY_ROAMING_MODE, RoamingMode.ML.name)
                        .apply()
                    scanService?.setRoamingMode(RoamingMode.ML)
                    scanService?.setRoamingCooldown(cooldown * 1000)
                    if (isBound) {
                        scanService?.updateConfig(ip, port, scanInt * 1000)
                    } else {
                        checkAndRequestPermissions()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
