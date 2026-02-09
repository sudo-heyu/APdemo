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
import com.heyu.apdemo2.model.ApDetailResponse
import com.heyu.apdemo2.network.ApiService
import com.heyu.apdemo2.scanner.WifiScanner
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var wifiScanner: WifiScanner
    private lateinit var wifiManager: WifiManager
    private lateinit var adapter: AccessPointAdapter
    private lateinit var tvStatus: TextView
    private lateinit var recyclerView: RecyclerView

    private val apiService = ApiService()

    private val handler = Handler(Looper.getMainLooper())
    private val cycleInterval: Long = 120000 // 2分钟
    private var isScanning = false
    private var scanCycleCount = 0

    companion object {
        private const val TAG = "[SCAN_DEBUG]"
        private const val PREFS_NAME = "server_settings"
        private const val KEY_IP = "server_ip"
        private const val KEY_PORT = "server_port"
    }

    // 辅助函数：简单的气泡提示
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
        adapter = AccessPointAdapter { accessPoint ->
            fetchSingleApDetail(accessPoint)
        }
        recyclerView.adapter = adapter
    }

    // 1. 启动大周期
    private fun startScanCycle() {
        scanCycleCount = 0
        wifiScanner.clearAccumulatedResults()
        toast("🚀 开始新一轮扫描周期 (共4次)")
        runNextScanStep()
    }

    // 2. 自动运行下一步
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
                    adapter.updateData(accessPoints)
                    Log.d(TAG, "<<< [第 $scanCycleCount 次扫描] 完成，当前热点数: ${accessPoints.size}")

                    if (scanCycleCount < 4) {
                        // 1秒后自动进行下一次扫描
                        handler.postDelayed({ runNextScanStep() }, 1000)
                    } else {
                        // 4次扫描全部结束，立即上传
                        toast("✅ 4次扫描结束，准备上传数据")
                        uploadResultsToServer(accessPoints)
                    }
                }
            },
            onProgressive = { accessPoints, _, _ ->
                runOnUiThread { adapter.updateData(accessPoints) }
            },
            onError = { err ->
                runOnUiThread {
                    isScanning = false
                    Log.e(TAG, "!!! 扫描出错: $err")
                    toast("❌ 扫描出错: $err")
                    if (scanCycleCount < 4) runNextScanStep() else startWaitingPhase()
                }
            }
        )
    }

    // 3. 自动执行 HTTP 上传
    private fun uploadResultsToServer(accessPoints: List<AccessPoint>) {
        val (ip, port) = getServerAddress()
        if (ip == null || port == -1) {
            toast("⚠️ 上传失败：服务器地址未配置")
            startWaitingPhase()
            return
        }

        showStatusMessage("正在上传数据到 $ip:$port...")
        Log.d(TAG, ">>> [关键步骤] 发起批量上传请求，包含 ${accessPoints.size} 个热点")

        apiService.uploadScanResults(ip, port, accessPoints, object : ApiService.BatchCallback {
            override fun onSuccess(message: String) {
                runOnUiThread {
                    Log.d(TAG, "<<< 上传成功: $message")
                    toast("📡 数据上传成功!")
                    startWaitingPhase()
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Log.e(TAG, "!!! 上传请求失败: $error")
                    toast("⛔ 上传失败，请检查网络和服务器日志")
                    startWaitingPhase()
                }
            }
        })
    }

    private fun startWaitingPhase() {
        showStatusMessage("等待 2 分钟后自动开启下一轮...")
        Log.d(TAG, ">>> 进入休眠等待 (2分钟)")
        handler.postDelayed({
            if (!isFinishing) startScanCycle()
        }, cycleInterval)
    }

    private fun fetchSingleApDetail(accessPoint: AccessPoint) {
        val (ip, port) = getServerAddress()
        if (ip != null && port != -1) {
            apiService.fetchApDetails(ip, port, accessPoint.bssid, object : ApiService.Callback {
                override fun onSuccess(response: ApDetailResponse) {
                    runOnUiThread {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("AP详情")
                            .setMessage("SSID: ${response.ssid}\nBSSID: ${response.bssid}\n制造商: ${response.manufacturer}")
                            .setPositiveButton("确定", null).show()
                    }
                }
                override fun onError(error: String) {
                    runOnUiThread { toast("详情查询失败: $error") }
                }
            })
        }
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
            else -> super.onOptionsItemSelected(item)
        }
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
