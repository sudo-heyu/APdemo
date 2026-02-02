package com.heyu.apdemo2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.heyu.apdemo2.adapter.AccessPointAdapter
import com.heyu.apdemo2.scanner.WifiScanner

class MainActivity : AppCompatActivity() {
    
    private lateinit var wifiScanner: WifiScanner
    private lateinit var wifiManager: WifiManager
    private lateinit var adapter: AccessPointAdapter
    private lateinit var tvStatus: TextView
    private lateinit var recyclerView: RecyclerView
    
    // 定时刷新相关
    private val handler = Handler(Looper.getMainLooper())
    private val cycleInterval: Long = 120000 // 2分钟循环间隔
    private val scanStepInterval: Long = 2000 // 2秒扫描步长间隔
    private var isScanning = false
    private var hasInitialScanCompleted = false
    private var currentAccessPointCount = 0 // 保存当前热点数量
    private var scanCycleCount = 0 // 当前扫描周期内的步骤计数
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private val scanCycleRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                Log.d(TAG, "=== 开始新的扫描周期 ===")
                scanCycleCount = 0
                startScanCycle()
            }
        }
    }
    
    private val scanStepRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && scanCycleCount < 4) {
                scanCycleCount++
                Log.d(TAG, "执行扫描步骤 $scanCycleCount")
                
                when (scanCycleCount) {
                    1 -> {
                        // 第一步：立即扫描并显示结果
                        startImmediateScan()
                        handler.postDelayed(this, scanStepInterval)
                    }
                    2,3 -> {
                        // 第二、三步：2秒后再次扫描
                        startImmediateScan()
                        handler.postDelayed(this, scanStepInterval)
                    }
                    4 -> {
                        // 第四步：再次扫描并更新列表（保留公共项）
                        startImmediateScan()
                        // 2分钟后开始下一个完整周期，但不清空当前结果显示
                        handler.postDelayed({
                            if (!isFinishing) {
                                Log.d(TAG, "=== 2分钟周期结束，开始新的扫描周期 ===")
                                startScanCycle()
                            }
                        }, cycleInterval)
                    }
                }
            }
        }
    }
    
    // 权限请求启动器
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "权限获取成功，开始扫描")
            startScanCycle()
        } else {
            val errorMsg = "需要WiFi和位置权限才能扫描"
            showStatusMessage(errorMsg)
            Log.e(TAG, errorMsg)
            Toast.makeText(this, "请授予WiFi和位置权限", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "=== onCreate called ===")
        
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        try {
            initViews()
            initWifiScanner()
            setupRecyclerView()
            
            // 应用启动时自动开始扫描
            checkAndRequestPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "初始化过程中发生错误", e)
            showStatusMessage("应用初始化失败: ${e.message}")
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        // 恢复状态显示
        if (hasInitialScanCompleted && !isScanning) {
            showStatusMessage("发现 ${currentAccessPointCount} 个WiFi信号")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        stopScanCycle()
    }
    
    private fun initViews() {
        Log.d(TAG, "初始化视图")
        tvStatus = findViewById(R.id.tv_status)
        recyclerView = findViewById(R.id.recycler_view)
        
        showStatusMessage("正在搜索WiFi信号...")
    }
    
    private fun initWifiScanner() {
        Log.d(TAG, "初始化WiFi扫描器")
        wifiScanner = WifiScanner(this)
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    
    private fun setupRecyclerView() {
        Log.d(TAG, "设置RecyclerView")
        adapter = AccessPointAdapter { accessPoint ->
            Toast.makeText(this, "点击了: ${accessPoint.ssid}", Toast.LENGTH_SHORT).show()
        }
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(AccessPointAdapter.ItemDecoration())
    }
    
    private fun checkAndRequestPermissions() {
        Log.d(TAG, "=== 开始权限检查 ===")
        val permissions = arrayOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        
        val permissionsToRequest = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        if (permissionsToRequest.isEmpty()) {
            Log.d(TAG, "已有所有权限，检查WiFi状态")
            checkWifiAndStartScan()
        } else {
            Log.d(TAG, "需要请求权限: ${permissionsToRequest.joinToString()}")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
        Log.d(TAG, "=== 权限检查完成 ===")
    }
    
    private fun checkWifiAndStartScan() {
        Log.d(TAG, "检查WiFi状态")
        val isWifiEnabled = wifiManager.isWifiEnabled
        Log.d(TAG, "WiFi启用状态: $isWifiEnabled")
        
        if (!isWifiEnabled) {
            showStatusMessage("请开启WiFi功能")
            Toast.makeText(this, "请在系统设置中开启WiFi", Toast.LENGTH_LONG).show()
        }
        
        // 立即开始第一个扫描周期
        startScanCycle()
    }
    
    private fun startScanCycle() {
        Log.d(TAG, "开始扫描周期")
        scanCycleCount = 0
        // 清空列表和累积结果，开始新的扫描周期
        wifiScanner.clearAccumulatedResults()
        adapter.updateData(emptyList())
        recyclerView.adapter?.notifyDataSetChanged()
        currentAccessPointCount = 0
        showStatusMessage("正在搜索WiFi信号...")
        handler.post(scanStepRunnable)
    }
    
    private fun startImmediateScan() {
        if (isScanning) {
            Log.d(TAG, "已在扫描中，跳过本次扫描")
            return
        }
        isScanning = true
        Log.d(TAG, "开始即时WiFi扫描")
        // 保持列表始终可见，不显示加载状态
        try {
            wifiScanner.startScan(
                onSuccess = { accessPoints ->
                    runOnUiThread {
                        isScanning = false
                        // 保持列表始终可见
                        hasInitialScanCompleted = true
                        currentAccessPointCount = accessPoints.size
                        adapter.updateData(accessPoints)
                        recyclerView.adapter?.notifyDataSetChanged()
                        if (accessPoints.isEmpty()) {
                            showStatusMessage("发现 0 个WiFi信号")
                            Log.w(TAG, "扫描完成但未发现任何信号")
                        } else {
                            showStatusMessage("发现 ${accessPoints.size} 个WiFi信号")
                            Log.d(TAG, "扫描完成，找到 ${accessPoints.size} 个热点")
                        }
                        Log.d(TAG, "列表已更新，显示${recyclerView.adapter?.itemCount ?: 0}个项目")
                    }
                },
                onProgressive = { accessPoints, scanRound, newCount ->
                    runOnUiThread {
                        Log.d(TAG, "扫描进度更新 - 轮次: $scanRound, 总数: ${accessPoints.size}, 新增: $newCount")
                        
                        // 实时更新列表显示
                        adapter.updateData(accessPoints)
                        recyclerView.adapter?.notifyDataSetChanged()
                        currentAccessPointCount = accessPoints.size
                        
                        showStatusMessage("发现 ${accessPoints.size} 个WiFi信号")
                    }
                },
                onError = { errorMessage ->
                    runOnUiThread {
                        isScanning = false
                        // 保持列表始终可见
                        hasInitialScanCompleted = true
                        
                        showStatusMessage("发现 0 个WiFi信号")
                        Log.e(TAG, "扫描错误: $errorMessage")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "扫描过程中发生异常", e)
            isScanning = false
            // 保持列表始终可见
            showStatusMessage("扫描发生异常")
        }
    }
    
    private fun stopScanCycle() {
        Log.d(TAG, "停止扫描周期")
        handler.removeCallbacks(scanStepRunnable)
        handler.removeCallbacks(scanCycleRunnable)
    }
    
    private fun showStatusMessage(message: String) {
        Log.d(TAG, "状态消息: $message")
        tvStatus.text = message
    }
}