package com.heyu.apdemo2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.heyu.apdemo2.model.ScanResponse
import com.heyu.apdemo2.network.ApiService
import com.heyu.apdemo2.scanner.WifiScanner
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {

    private lateinit var wifiScanner: WifiScanner
    private lateinit var wifiManager: WifiManager
    private lateinit var adapter: AccessPointAdapter
    private lateinit var tvStatus: TextView
    private lateinit var recyclerView: RecyclerView

    private val apiService = ApiService()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 配置参数
    private var cycleInterval: Long = 150000 
    private var pollInterval: Long = 10000  
    
    private var scanCycleCount = 0
    private var currentAccessPoints: List<AccessPoint> = emptyList()
    
    // 状态标记：用于确保回调不会在任务停止后执行
    private var isTaskRunning = false
    private var currentCycleId = 0L // 每个大循环唯一的 ID

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isTaskRunning || isFinishing) return
            queryScoresOnly()
            mainHandler.postDelayed(this, pollInterval)
        }
    }

    companion object {
        private const val TAG = "[SCAN_DEBUG]"
        private const val PREFS_NAME = "server_settings"
        private const val KEY_IP = "server_ip"
        private const val KEY_PORT = "server_port"
        private const val KEY_POLL_INTERVAL = "poll_interval"
        private const val KEY_CYCLE_INTERVAL = "cycle_interval"
    }

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
        initWifiScanner()
        setupRecyclerView()
        loadSettings()

        val (ip, port) = getServerAddress()
        if (ip == null || port == -1) {
            showServerInputDialog()
        } else {
            checkAndRequestPermissions()
        }
    }

    private fun initViews() {
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        tvStatus = findViewById(R.id.tv_status)
        recyclerView = findViewById(R.id.recycler_view)
        tvStatus.text = "状态: 准备就绪"
    }

    private fun initWifiScanner() {
        wifiScanner = WifiScanner(this)
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private fun setupRecyclerView() {
        adapter = AccessPointAdapter()
        recyclerView.adapter = adapter
    }

    private fun loadSettings() {
        val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pollInterval = sharedPref.getLong(KEY_POLL_INTERVAL, 10000L)
        cycleInterval = sharedPref.getLong(KEY_CYCLE_INTERVAL, 150000L)
        Log.d(TAG, "配置加载: 轮询=$pollInterval, 周期=$cycleInterval")
    }

    /**
     * 重置并开始一个全新大循环
     */
    private fun startScanCycle() {
        Log.d(TAG, ">>> [startScanCycle] 重置流程")
        
        // 1. 标记任务状态并清理所有挂起的 Handler 任务
        isTaskRunning = true
        currentCycleId = System.currentTimeMillis()
        mainHandler.removeCallbacksAndMessages(null)
        
        // 2. 停止正在进行的硬件扫描
        wifiScanner.stopScan()
        wifiScanner.clearAccumulatedResults()
        
        scanCycleCount = 0
        Toast.makeText(this, "🚀 开始新一轮循环", Toast.LENGTH_SHORT).show()
        runNextScanStep(currentCycleId)
    }

    private fun runNextScanStep(cycleId: Long) {
        if (!isTaskRunning || isFinishing || cycleId != currentCycleId) {
            Log.d(TAG, "runNextScanStep: 任务已停止或周期不匹配，跳过")
            return
        }

        scanCycleCount++
        val statusMsg = "正在扫描采样 ($scanCycleCount/4)..."
        runOnUiThread { tvStatus.text = "状态: $statusMsg" }
        Log.d(TAG, ">>> [Cycle $cycleId] 第 $scanCycleCount 次采样开始")

        wifiScanner.startScan(
            onSuccess = { accessPoints ->
                runOnUiThread {
                    // 再次检查状态，防止异步回调时状态已变
                    if (!isTaskRunning || cycleId != currentCycleId) return@runOnUiThread

                    currentAccessPoints = accessPoints
                    adapter.updateData(accessPoints)
                    Log.d(TAG, "<<< [Cycle $cycleId] 采样 $scanCycleCount 完成")

                    if (scanCycleCount < 4) {
                        mainHandler.postDelayed({ runNextScanStep(cycleId) }, 2000)
                    } else {
                        tvStatus.text = "状态: 采样完成，正在请求初始评分..."
                        uploadResultsToServer(cycleId, accessPoints)
                    }
                }
            },
            onProgressive = { accessPoints, _, _ ->
                runOnUiThread { 
                    if (isTaskRunning && cycleId == currentCycleId) {
                        currentAccessPoints = accessPoints
                        adapter.updateData(accessPoints) 
                    }
                }
            },
            onError = { err ->
                runOnUiThread {
                    if (!isTaskRunning || cycleId != currentCycleId) return@runOnUiThread
                    Log.e(TAG, "!!! 采样失败: $err")
                    if (scanCycleCount < 4) {
                        mainHandler.postDelayed({ runNextScanStep(cycleId) }, 2000)
                    } else {
                        startWaitingPhase(cycleId, "扫描阶段异常")
                    }
                }
            }
        )
    }

    private fun uploadResultsToServer(cycleId: Long, accessPoints: List<AccessPoint>) {
        if (!isTaskRunning || cycleId != currentCycleId) return
        
        val (ip, port) = getServerAddress()
        if (ip == null || port == -1) {
            startWaitingPhase(cycleId, "服务器未配置")
            return
        }

        apiService.uploadScanResults(ip, port, accessPoints, object : ApiService.BatchCallback {
            override fun onSuccess(response: ScanResponse) {
                runOnUiThread {
                    if (!isTaskRunning || cycleId != currentCycleId) return@runOnUiThread
                    updateScoresFromResponse(response)
                    Toast.makeText(this@MainActivity, "📡 初始评分已同步", Toast.LENGTH_SHORT).show()
                    startWaitingPhase(cycleId, "监控模式")
                }
            }
            override fun onError(error: String) {
                runOnUiThread {
                    if (!isTaskRunning || cycleId != currentCycleId) return@runOnUiThread
                    Log.e(TAG, "!!! 初始同步失败: $error")
                    startWaitingPhase(cycleId, "评分同步失败")
                }
            }
        })
    }

    private fun startWaitingPhase(cycleId: Long, status: String) {
        if (!isTaskRunning || cycleId != currentCycleId) return

        val displayMsg = "$status (下轮扫描在 ${cycleInterval/1000}s 后)"
        runOnUiThread { tvStatus.text = "状态: $displayMsg" }
        Log.d(TAG, ">>> [Cycle $cycleId] 进入等待期")

        // 1. 开启监控轮询
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, pollInterval)
        
        // 2. 预定下一轮大循环
        mainHandler.postDelayed({
            if (isTaskRunning && !isFinishing && cycleId == currentCycleId) {
                startScanCycle()
            }
        }, cycleInterval)
    }

    private fun queryScoresOnly() {
        val (ip, port) = getServerAddress()
        if (ip == null || port == -1 || currentAccessPoints.isEmpty()) return

        apiService.uploadScanResults(ip, port, currentAccessPoints, object : ApiService.BatchCallback {
            override fun onSuccess(response: ScanResponse) {
                runOnUiThread { 
                    if (isTaskRunning) updateScoresFromResponse(response) 
                }
            }
            override fun onError(error: String) {
                Log.e(TAG, "轮询评分失败: $error")
            }
        })
    }

    private fun updateScoresFromResponse(response: ScanResponse) {
        response.results?.forEach { result ->
            currentAccessPoints.find { it.ssid == result.ssid }?.let { ap ->
                ap.score = result.score
                ap.reason = result.reason
            }
        }
        adapter.updateData(currentAccessPoints)
    }

    override fun onDestroy() {
        super.onDestroy()
        isTaskRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        wifiScanner.stopScan()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showServerInputDialog()
                true
            }
            R.id.action_help -> {
                showHelpDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showHelpDialog() {
        val message = StringBuilder().apply {
            append("💡 扫描机制说明：\n\n")
            append("1. 系统限制：Android 限制应用每 2 分钟最多进行 4 次硬件扫描。当受限时，应用将使用缓存数据并模拟扫描过程。\n\n")
            append("2. 自动循环：应用按照“总循环周期”运行，每轮采样 4 次后进入监控模式定时刷新。")
        }.toString()
        AlertDialog.Builder(this).setTitle("帮助").setMessage(message).setPositiveButton("知道了", null).show()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) startScanCycle() else Toast.makeText(this, "未获得必要权限", Toast.LENGTH_SHORT).show()
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startScanCycle() else permissionLauncher.launch(missing.toTypedArray())
    }

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
        val portInput = EditText(this).apply { hint = "端口"; inputType = InputType.TYPE_CLASS_NUMBER; if(currentPort != -1) setText(currentPort.toString()) }
        val pollInput = EditText(this).apply { hint = "评分轮询间隔 (秒)"; inputType = InputType.TYPE_CLASS_NUMBER; setText(currentPoll.toString()) }
        val cycleInput = EditText(this).apply { hint = "总循环周期 (秒)"; inputType = InputType.TYPE_CLASS_NUMBER; setText(currentCycle.toString()) }

        container.addView(ipInput)
        container.addView(portInput)
        container.addView(TextView(this).apply { text = "\n监控刷新频率 (秒):" })
        container.addView(pollInput)
        container.addView(TextView(this).apply { text = "\n总循环周期 (秒):" })
        container.addView(cycleInput)
        
        builder.setView(container)
        builder.setPositiveButton("保存") { _, _ ->
            val ip = ipInput.text.toString().trim()
            val p = portInput.text.toString().trim()
            val poll = pollInput.text.toString().trim().toLongOrNull() ?: 10L
            val cycle = cycleInput.text.toString().trim().toLongOrNull() ?: 150L

            if (ip.isNotEmpty() && p.isNotEmpty()) {
                sharedPref.edit()
                    .putString(KEY_IP, ip)
                    .putInt(KEY_PORT, p.toInt())
                    .putLong(KEY_POLL_INTERVAL, poll * 1000)
                    .putLong(KEY_CYCLE_INTERVAL, cycle * 1000)
                    .apply()
                
                loadSettings()
                startScanCycle()
            }
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }
}
