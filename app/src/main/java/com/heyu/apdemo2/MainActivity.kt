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

    private val handler = Handler(Looper.getMainLooper())
    
    // --- 周期设置 ---
    private val cycleInterval: Long = 150000 // 150秒 (总周期)
    private val pollInterval: Long = 10000  // 10秒 (监控查询间隔)
    
    private var isScanning = false
    private var scanCycleCount = 0
    private var currentAccessPoints: List<AccessPoint> = emptyList()

    // 轮询 Runnable：用于在等待期间每10秒查询一次服务器分数
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isFinishing) return
            queryScoresOnly()
            handler.postDelayed(this, pollInterval)
        }
    }

    companion object {
        private const val TAG = "[SCAN_DEBUG]"
        private const val PREFS_NAME = "server_settings"
        private const val KEY_IP = "server_ip"
        private const val KEY_PORT = "server_port"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showStatusMessage(message: String) {
        tvStatus.text = "状态: $message"
        Log.d(TAG, "状态更新: $message")
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
        showStatusMessage("系统启动中...")
    }

    private fun initWifiScanner() {
        wifiScanner = WifiScanner(this)
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private fun setupRecyclerView() {
        adapter = AccessPointAdapter()
        recyclerView.adapter = adapter
    }

    private fun startScanCycle() {
        // 开启新一轮扫描前，务必先停止轮询任务
        handler.removeCallbacks(pollRunnable)
        
        scanCycleCount = 0
        wifiScanner.clearAccumulatedResults()
        toast("🚀 开始新一轮扫描 (共4次)")
        runNextScanStep()
    }

    private fun runNextScanStep() {
        if (isFinishing) return

        scanCycleCount++
        showStatusMessage("正在扫描 ($scanCycleCount/4)...")
        Log.d(TAG, ">>> [第 $scanCycleCount 次扫描] 开始...")

        isScanning = true
        wifiScanner.startScan(
            onSuccess = { accessPoints ->
                runOnUiThread {
                    isScanning = false
                    currentAccessPoints = accessPoints
                    adapter.updateData(accessPoints)
                    Log.d(TAG, "<<< [第 $scanCycleCount 次扫描] 完成")

                    if (scanCycleCount < 4) {
                        handler.postDelayed({ runNextScanStep() }, 2000)
                    } else {
                        toast("✅ 4次扫描结束，上传数据并开启监控")
                        uploadResultsToServer(accessPoints)
                    }
                }
            },
            onProgressive = { accessPoints, _, _ ->
                runOnUiThread { 
                    currentAccessPoints = accessPoints
                    adapter.updateData(accessPoints) 
                }
            },
            onError = { err ->
                runOnUiThread {
                    isScanning = false
                    Log.e(TAG, "!!! 扫描出错: $err")
                    toast("❌ 扫描出错: $err")
                    if (scanCycleCount < 4) {
                        handler.postDelayed({ runNextScanStep() }, 2000)
                    } else {
                        startWaitingPhase()
                    }
                }
            }
        )
    }

    private fun uploadResultsToServer(accessPoints: List<AccessPoint>) {
        val (ip, port) = getServerAddress()
        if (ip == null || port == -1) {
            startWaitingPhase()
            return
        }

        showStatusMessage("同步初始评分...")
        apiService.uploadScanResults(ip, port, accessPoints, object : ApiService.BatchCallback {
            override fun onSuccess(response: ScanResponse) {
                runOnUiThread {
                    updateScoresFromResponse(response)
                    toast("📡 初始评分同步成功!")
                    startWaitingPhase()
                }
            }
            override fun onError(error: String) {
                runOnUiThread {
                    Log.e(TAG, "!!! 上传请求失败: $error")
                    toast("⛔ 评分获取失败: $error")
                    startWaitingPhase()
                }
            }
        })
    }

    /**
     * 监控阶段调用的纯查询函数（每10秒一次）
     */
    private fun queryScoresOnly() {
        val (ip, port) = getServerAddress()
        if (ip == null || port == -1 || currentAccessPoints.isEmpty()) return

        Log.d(TAG, ">>> [轮询监控] 正在获取最新评分...")
        apiService.uploadScanResults(ip, port, currentAccessPoints, object : ApiService.BatchCallback {
            override fun onSuccess(response: ScanResponse) {
                runOnUiThread {
                    updateScoresFromResponse(response)
                    Log.d(TAG, "<<< [轮询监控] 评分已更新")
                }
            }
            override fun onError(error: String) {
                Log.e(TAG, "!!! [轮询监控] 失败: $error")
            }
        })
    }

    /**
     * 更新列表中的评分数据
     */
    private fun updateScoresFromResponse(response: ScanResponse) {
        response.results?.forEach { result ->
            currentAccessPoints.find { it.ssid == result.ssid }?.let { ap ->
                ap.score = result.score
                ap.reason = result.reason
            }
        }
        adapter.updateData(currentAccessPoints)
    }

    private fun startWaitingPhase() {
        showStatusMessage("监控中: 每10s刷新评分 (总计150s)...")
        Log.d(TAG, ">>> 进入150s等待期，开启10s轮询查询")

        // 1. 10秒后开始第一次轮询
        handler.postDelayed(pollRunnable, pollInterval)
        
        // 2. 150秒后开启下一轮大扫描
        handler.postDelayed({
            if (!isFinishing) {
                handler.removeCallbacks(pollRunnable)
                startScanCycle()
            }
        }, cycleInterval)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
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
            append("💡 使用说明与常见问题：\n\n")
            append("1. 扫描限制：Android 系统限制应用每 2 分钟最多进行 4 次硬件扫描。如果应用卡在扫描阶段或无数据，请尝试重启 APP。\n\n")
            append("2. 无数据：若 4 次扫描后列表为空，请检查手机 Wi-Fi 是否开启、位置权限是否授予，以及周边是否存在有效热点。\n\n")
            append("3. 服务器连接：若始终显示“评分获取失败”，请检查服务器 IP/端口配置是否正确，并确保手机与服务器处于同一网络环境。\n\n")
            append("4. 动态评分：系统在扫描完成后，每 10 秒会同步一次服务器评分，您可以通过点击列表右侧图标查看详细评估理由。\n\n")
            append("5. 周期运行：应用每 150 秒会自动触发新一轮的完整扫描，期间会自动更新评分。")
        }.toString()

        AlertDialog.Builder(this)
            .setTitle("答疑解惑")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) startScanCycle() else toast("需要定位权限才能扫描")
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
        val (currentIp, currentPort) = getServerAddress()
        val builder = AlertDialog.Builder(this).setTitle("服务器配置")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }
        val ipInput = EditText(this).apply { hint = "服务器 IP 地址"; setText(currentIp) }
        val portInput = EditText(this).apply { hint = "端口号"; inputType = InputType.TYPE_CLASS_NUMBER; if(currentPort != -1) setText(currentPort.toString()) }
        container.addView(ipInput); container.addView(portInput)
        builder.setView(container)
        builder.setPositiveButton("保存") { _, _ ->
            val ip = ipInput.text.toString().trim()
            val p = portInput.text.toString().trim()
            if (ip.isNotEmpty() && p.isNotEmpty()) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_IP, ip).putInt(KEY_PORT, p.toInt()).apply()
                toast("配置已保存")
                startScanCycle()
            }
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }
}
