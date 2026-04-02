package com.heyu.apdemo2.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.heyu.apdemo2.R
import com.heyu.apdemo2.connection.PasswordStore
import com.heyu.apdemo2.model.AccessPoint
import com.heyu.apdemo2.model.ScanResponse
import com.heyu.apdemo2.network.ApiService
import com.heyu.apdemo2.roaming.ApSelectionManager
import com.heyu.apdemo2.roaming.RoamingLogManager
import com.heyu.apdemo2.scanner.WifiScanner
import com.heyu.apdemo2.ui.MainActivity

class ScanForegroundService : Service() {

    interface ScanCallback {
        fun onStatusUpdate(status: String)
        fun onDataUpdate(accessPoints: List<AccessPoint>)
        fun onConnectionChanged(ssid: String?, success: Boolean, errorType: String = "") {}
        fun onReconnecting(ssid: String, attempt: Int) {}
        fun onApprovalNeeded() {}
        fun onRoamingStatus(status: String) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScanForegroundService = this@ScanForegroundService
    }

    private val binder = LocalBinder()
    private var callback: ScanCallback? = null
    private lateinit var roamingLogManager: RoamingLogManager

    private lateinit var wifiScanner: WifiScanner
    private lateinit var apSelectionManager: ApSelectionManager
    private val apiService = ApiService()

    // WifiNetworkSpecifier 连接管理
    private var specifierNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    var specifierConnectedSsid: String? = null
        private set
    private var isSystemWifiConnection: Boolean = false

    var pinnedSsid: String? = null
        private set
    private var pinnedPassword: String = ""
    private var pinnedIsOpen: Boolean = false

    // 独立后台线程：所有调度、扫描回调、轮询都在这个线程，与主线程完全解耦
    private val handlerThread = HandlerThread("ScanServiceThread")
    private lateinit var handler: Handler

    // WakeLock：防止 CPU 进入休眠，确保 HandlerThread 的定时任务按时执行
    private lateinit var wakeLock: PowerManager.WakeLock

    var cycleInterval: Long = 150000L
        private set
    var pollInterval: Long = 10000L
        private set
    var serverIp: String? = null
        private set
    var serverPort: Int = -1
        private set

    var isRunning = false
        private set
    var autoRoamingEnabled: Boolean = false
        private set
    private var lastRoamingTime: Long = 0
    private val ROAMING_COOLDOWN_MS = 30000L
    private var currentCycleId = 0L

    var currentAccessPoints: List<AccessPoint> = emptyList()
        private set
    var currentStatus: String = "准备就绪"
        private set
    var currentConnectedSsid: String? = null
        private set

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            queryScoresOnly()
            handler.postDelayed(this, pollInterval)
        }
    }

    companion object {
        private const val TAG = "[SCAN_SERVICE]"
        const val CHANNEL_ID = "scan_service_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_IP = "server_ip"
        const val EXTRA_PORT = "server_port"
        const val EXTRA_POLL = "poll_interval"
        const val EXTRA_CYCLE = "cycle_interval"
        // AlarmManager 唤醒下一轮扫描的 Action
        const val ACTION_NEXT_CYCLE = "com.heyu.apdemo2.ACTION_NEXT_CYCLE"
        // SharedPreferences 与 MainActivity 共用同一个文件
        private const val PREFS_NAME = "server_settings"
    }

    override fun onCreate() {
        super.onCreate()

        roamingLogManager = RoamingLogManager.getInstance(this)
        roamingLogManager.i("")
        roamingLogManager.i("=".repeat(60))
        roamingLogManager.i("【服务启动】ScanForegroundService 已创建")
        roamingLogManager.i("=".repeat(60))

        handlerThread.start()
        handler = Handler(handlerThread.looper)

        // 2. 准备 PARTIAL_WAKE_LOCK，仅在扫描+上传期间按需持有，等待阶段释放以节省电量
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "APdemo2:ScanWakeLock")

        // 3. WifiScanner 传入 HandlerThread 的 Looper
        wifiScanner = WifiScanner(this, handlerThread.looper)
        apSelectionManager = ApSelectionManager(this)

        createNotificationChannel()
        startForegroundCompat("等待配置...")
        Log.d(TAG, "服务已创建（HandlerThread + WifiScanner(HandlerThread looper) + WakeLock）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_NEXT_CYCLE -> {
                // AlarmManager 在 Doze 期间唤醒 CPU 后触发下一轮扫描
                Log.d(TAG, "AlarmManager 触发下一轮扫描")
                if (isRunning) {
                    acquireWakeLock()
                    handler.post { startScanCycle() }
                }
            }
            intent != null -> {
                // 正常启动：从 Intent 读取配置，并同步写入 SharedPreferences
                serverIp   = intent.getStringExtra(EXTRA_IP)
                serverPort = intent.getIntExtra(EXTRA_PORT, -1)
                pollInterval  = intent.getLongExtra(EXTRA_POLL, 10000L)
                cycleInterval = intent.getLongExtra(EXTRA_CYCLE, 150000L)
                persistConfig()
                Log.d(TAG, "收到配置: ip=$serverIp port=$serverPort poll=${pollInterval}ms cycle=${cycleInterval}ms")
                if (!isRunning) {
                    acquireWakeLock()
                    startScanCycle()
                }
            }
            else -> {
                // START_STICKY 重启：intent 为 null，从持久化存储恢复配置
                restoreConfig()
                Log.d(TAG, "服务重启（intent=null），恢复配置: ip=$serverIp port=$serverPort")
                if (!isRunning) {
                    acquireWakeLock()
                    startScanCycle()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun registerCallback(cb: ScanCallback) {
        callback = cb
        cb.onStatusUpdate(currentStatus)
        if (currentAccessPoints.isNotEmpty()) cb.onDataUpdate(currentAccessPoints)
    }

    fun unregisterCallback() {
        callback = null
    }

    /**
     * 连接到指定 AP（统一使用 WifiNetworkSpecifier）。
     * 若当前已 pinned 同一 SSID 则忽略；若传入 null 或空密码（开放网络）则直接发起连接。
     */
    fun connectToNetwork(ssid: String, isOpen: Boolean, password: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Android 9 及以下不支持 WifiNetworkSpecifier")
            callback?.onConnectionChanged(null, false, "需要 Android 10+")
            return
        }
        pinnedPassword = password
        pinnedIsOpen = isOpen
        connectWithSpecifier(ssid, password, object : SpecifierConnectionCallback {
            override fun onConnected(connectedSsid: String, isSystemConnection: Boolean) {
                pinnedSsid = ssid
                Log.d(TAG, "已连接并置顶: $ssid")
                callback?.onConnectionChanged(ssid, true)
            }
            override fun onFailed(failedSsid: String, error: String) {
                Log.w(TAG, "连接失败: $error")
                if (pinnedSsid == ssid) pinnedSsid = null
                callback?.onConnectionChanged(null, false, error)
            }
            override fun onLost(lostSsid: String?) {
                if (pinnedSsid == lostSsid) {
                    pinnedSsid = null
                    callback?.onConnectionChanged(null, false)
                }
            }
        })
    }

    /** 断开当前 pinned 连接并清除置顶。 */
    fun disconnectPinned() {
        releaseSpecifierConnection()
        pinnedSsid = null
        callback?.onConnectionChanged(null, false)
    }

    fun updateConfig(ip: String, port: Int, poll: Long, cycle: Long) {
        serverIp = ip
        serverPort = port
        pollInterval = poll
        cycleInterval = cycle
        persistConfig()
        startScanCycle()
    }

    // ── 配置持久化 ───────────────────────────────────────────────────────────

    private fun persistConfig() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("server_ip", serverIp)
            .putInt("server_port", serverPort)
            .putLong("poll_interval", pollInterval)
            .putLong("cycle_interval", cycleInterval)
            .apply()
    }

    private fun restoreConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        serverIp      = prefs.getString("server_ip", null)
        serverPort    = prefs.getInt("server_port", -1)
        pollInterval  = prefs.getLong("poll_interval", 10000L)
        cycleInterval = prefs.getLong("cycle_interval", 150000L)
    }

    // ── 扫描主流程（全部在 HandlerThread 执行）──────────────────────────────

    private fun startScanCycle() {
        Log.d(TAG, ">>> [startScanCycle] 重置流程")
        isRunning = true
        currentCycleId = System.currentTimeMillis()
        handler.removeCallbacksAndMessages(null)
        cancelNextCycleAlarm()
        wifiScanner.stopScan()
        wifiScanner.clearAccumulatedResults()
        handler.post { runNextScanStep(currentCycleId) }
    }

    private fun runNextScanStep(cycleId: Long) {
        if (!isRunning || cycleId != currentCycleId) return
        updateStatus("正在扫描...")
        Log.d(TAG, ">>> [Cycle $cycleId] 开始扫描")
        roamingLogManager.i("")
        roamingLogManager.i("【扫描周期开始】Cycle ID: $cycleId")

        wifiScanner.startScan(
            onSuccess = { accessPoints ->
                if (!isRunning || cycleId != currentCycleId) return@startScan
                currentAccessPoints = accessPoints
                callback?.onDataUpdate(accessPoints)
                Log.d(TAG, "<<< [Cycle $cycleId] 扫描完成，准备上传")
                roamingLogManager.i("【扫描完成】发现 ${accessPoints.size} 个AP")
                accessPoints.forEachIndexed { index, ap ->
                    roamingLogManager.d("  [$index] ${ap.ssid} | RSSI: ${ap.rssi}dBm | 加密: ${if (ap.isSecured()) "是" else "否"}")
                }
                updateStatus("扫描完成，正在请求初始评分...")
                uploadResultsToServer(cycleId, accessPoints)
            },
            onProgressive = { accessPoints, _, _ ->
                if (isRunning && cycleId == currentCycleId) {
                    currentAccessPoints = accessPoints
                    callback?.onDataUpdate(accessPoints)
                }
            },
            onError = { err ->
                if (!isRunning || cycleId != currentCycleId) return@startScan
                Log.e(TAG, "!!! 采样失败: $err")
                startWaitingPhase(cycleId, "扫描阶段异常")
            }
        )
    }

    private fun uploadResultsToServer(cycleId: Long, accessPoints: List<AccessPoint>) {
        // 此方法运行在 HandlerThread
        if (!isRunning || cycleId != currentCycleId) return
        val ip = serverIp
        if (ip == null || serverPort == -1) {
            startWaitingPhase(cycleId, "服务器未配置")
            return
        }

        apiService.uploadScanResults(ip, serverPort, accessPoints, object : ApiService.BatchCallback {
            override fun onSuccess(response: ScanResponse) {
                handler.post {
                    if (!isRunning || cycleId != currentCycleId) return@post
                    roamingLogManager.i("【服务器同步成功】更新 ${response.results?.size ?: 0} 个AP评分")
                    updateScoresFromResponse(response)
                    startWaitingPhase(cycleId, "监控模式")
                }
            }
            override fun onError(error: String) {
                handler.post {
                    if (!isRunning || cycleId != currentCycleId) return@post
                    Log.e(TAG, "!!! 初始同步失败: $error")
                    roamingLogManager.e("【服务器同步失败】$error")
                    // 服务器失败也触发漫游评估，使用默认评分
                    if (autoRoamingEnabled) {
                        roamingLogManager.i("【降级模式】服务器不可用，使用默认评分执行漫游评估")
                        evaluateAndTriggerRoaming()
                    }
                    startWaitingPhase(cycleId, "评分同步失败")
                }
            }
        })
    }

    private fun startWaitingPhase(cycleId: Long, status: String) {
        // 此方法运行在 HandlerThread
        if (!isRunning || cycleId != currentCycleId) return
        val msg = "$status (下轮扫描在 ${cycleInterval / 1000}s 后)"
        updateStatus(msg)
        Log.d(TAG, ">>> [Cycle $cycleId] 进入等待期，poll=${pollInterval}ms cycle=${cycleInterval}ms")

        // 轮询评分仍用 Handler（短间隔，Doze 期间可接受暂停）
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, pollInterval)

        // 下一轮扫描改用 AlarmManager：setAndAllowWhileIdle 可在 Doze 期间唤醒 CPU
        scheduleNextCycleAlarm(cycleInterval)

        // 扫描+上传已完成，释放 WakeLock，让 CPU 进入低功耗状态
        releaseWakeLock()
    }

    private fun queryScoresOnly() {
        val ip = serverIp
        if (ip == null || serverPort == -1) {
            roamingLogManager.w("轮询跳过: 服务器未配置")
            return
        }
        if (currentAccessPoints.isEmpty()) {
            roamingLogManager.w("轮询跳过: 无扫描结果")
            return
        }
        Log.d(TAG, ">>> [poll] 轮询评分")

        apiService.uploadScanResults(ip, serverPort, currentAccessPoints, object : ApiService.BatchCallback {
            override fun onSuccess(response: ScanResponse) {
                handler.post {
                    if (isRunning) {
                        roamingLogManager.i("【轮询同步成功】更新 ${response.results?.size ?: 0} 个AP评分")
                        updateScoresFromResponse(response)
                    }
                }
            }
            override fun onError(error: String) {
                Log.e(TAG, "轮询评分失败: $error")
                handler.post {
                    roamingLogManager.e("【轮询同步失败】$error")
                    // 轮询失败也触发漫游评估，使用现有评分（可能是默认值）
                    if (autoRoamingEnabled) {
                        roamingLogManager.i("【降级模式】轮询失败，使用现有评分执行漫游评估")
                        evaluateAndTriggerRoaming()
                    }
                }
            }
        })
    }

    private fun updateScoresFromResponse(response: ScanResponse) {
        var updatedCount = 0
        response.results?.forEach { result ->
            currentAccessPoints.find { it.ssid == result.ssid }?.let { ap ->
                ap.score = result.score
                ap.reason = result.reason
                updatedCount++
            }
        }
        callback?.onDataUpdate(currentAccessPoints)

        currentAccessPoints.forEach { ap ->
            ap.score?.let { score ->
                apSelectionManager.setApScore(ap.ssid, score.toFloat())
            }
        }

        roamingLogManager.d("【评分更新】成功更新 $updatedCount 个AP的评分")

        if (autoRoamingEnabled) {
            evaluateAndTriggerRoaming()
        } else {
            roamingLogManager.i("【自动漫游已关闭】跳过漫游评估")
        }
    }

    /**
     * 评估并触发漫游切换
     */
    private fun evaluateAndTriggerRoaming() {
        val now = System.currentTimeMillis()

        roamingLogManager.i("")
        roamingLogManager.i("=".repeat(60))
        roamingLogManager.i("【漫游评估开始】时间: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now))}")

        if (currentAccessPoints.isEmpty()) {
            roamingLogManager.w("【漫游评估】候选AP列表为空，跳过评估")
            roamingLogManager.i("=".repeat(60))
            return
        }

        // 过滤掉没有保存密码的加密AP（只保留开放网络 + 已存密码的加密网络）
        val connectableAps = currentAccessPoints.filter { ap ->
            if (!ap.isSecured()) {
                true // 开放网络，可连接
            } else {
                val hasPassword = !PasswordStore.get(this, ap.ssid).isNullOrEmpty()
                if (!hasPassword) {
                    roamingLogManager.d("【密码过滤】排除 ${ap.ssid}（加密但未保存密码）")
                }
                hasPassword
            }
        }

        if (connectableAps.isEmpty()) {
            roamingLogManager.w("【漫游评估】过滤后无可连接AP（均需密码但未保存）")
            roamingLogManager.i("=".repeat(60))
            return
        }

        roamingLogManager.i("【密码过滤】${currentAccessPoints.size} 个AP → ${connectableAps.size} 个可连接AP")

        val currentAp = connectableAps.find { it.ssid == currentConnectedSsid }
        roamingLogManager.i("【当前连接】${currentAp?.ssid ?: "无"}, RSSI: ${currentAp?.rssi ?: "N/A"}dBm, 评分: ${currentAp?.score ?: "N/A"}")

        roamingLogManager.d("【候选AP概览（可连接）】")
        connectableAps.forEach { ap ->
            val marker = if (ap.ssid == currentConnectedSsid) " ← 当前" else ""
            roamingLogManager.d("  ${ap.ssid} | RSSI: ${ap.rssi}dBm | 评分: ${ap.score ?: "N/A"}$marker")
        }

        val bestAp = apSelectionManager.selectBestAp(connectableAps, isGameMode = false)

        if (bestAp == null) {
            roamingLogManager.i("【算法推荐】无法选出最佳AP")
            roamingLogManager.i("=".repeat(60))
            return
        }

        roamingLogManager.i("【算法推荐】最佳AP: ${bestAp.ssid}, RSSI: ${bestAp.rssi}dBm, 评分: ${bestAp.score ?: "N/A"}")

        if (bestAp.ssid == currentConnectedSsid) {
            roamingLogManager.i("【决策】当前AP已是最优，无需切换")
            roamingLogManager.i("=".repeat(60))
            return
        }

        if (now - lastRoamingTime < ROAMING_COOLDOWN_MS) {
            val remaining = (ROAMING_COOLDOWN_MS - (now - lastRoamingTime)) / 1000
            roamingLogManager.i("【决策】推荐切换到 ${bestAp.ssid}，但处于冷却期（剩余 ${remaining}s）")
            roamingLogManager.i("=".repeat(60))
            return
        }

        if (currentAp == null) {
            roamingLogManager.i("【决策】当前无连接，连接最佳AP: ${bestAp.ssid}")
            triggerRoamingConnection(bestAp)
            lastRoamingTime = now
            roamingLogManager.i("=".repeat(60))
            return
        }

        roamingLogManager.i("【决策】执行漫游: ${currentAp.ssid}(${currentAp.rssi}dBm) → ${bestAp.ssid}(${bestAp.rssi}dBm)")
        triggerRoamingConnection(bestAp)
        lastRoamingTime = now
        roamingLogManager.i("=".repeat(60))
    }

    /**
     * 触发漫游连接（统一使用 WifiNetworkSpecifier）
     */
    private fun triggerRoamingConnection(targetAp: AccessPoint) {
        val password = PasswordStore.get(this, targetAp.ssid) ?: ""
        val isOpen = !targetAp.isSecured()

        if (!isOpen && password.isEmpty()) {
            roamingLogManager.w("【切换失败】${targetAp.ssid} 需要密码但未保存")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            roamingLogManager.w("【切换失败】Android 9 及以下不支持自动漫游")
            return
        }

        roamingLogManager.i("【开始切换】目标AP: ${targetAp.ssid}, RSSI: ${targetAp.rssi}dBm, 类型: ${if (isOpen) "开放" else "加密"}")

        connectWithSpecifier(targetAp.ssid, password, object : SpecifierConnectionCallback {
            override fun onConnected(connectedSsid: String, isSystemConnection: Boolean) {
                currentConnectedSsid = targetAp.ssid
                roamingLogManager.i("【切换成功】已连接到 ${targetAp.ssid}")
                callback?.onConnectionChanged(targetAp.ssid, true)
            }
            override fun onFailed(failedSsid: String, error: String) {
                roamingLogManager.e("【切换失败】${targetAp.ssid}: $error")
                callback?.onConnectionChanged(null, false, error)
            }
            override fun onLost(lostSsid: String?) {
                if (currentConnectedSsid == lostSsid) {
                    currentConnectedSsid = null
                    roamingLogManager.i("【连接断开】${lostSsid}")
                    callback?.onConnectionChanged(null, false)
                }
            }
        })
    }

    /**
     * 设置自动漫游开关
     */
    fun setAutoRoamingEnabled(enabled: Boolean) {
        autoRoamingEnabled = enabled
        roamingLogManager.i("")
        roamingLogManager.i("=".repeat(60))
        roamingLogManager.i("【自动漫游】状态: ${if (enabled) "已开启" else "已关闭"}")
        roamingLogManager.i("=".repeat(60))
    }

    /**
     * 更新当前连接的SSID
     */
    fun updateConnectedSsid(ssid: String?) {
        if (currentConnectedSsid != ssid) {
            if (ssid != null) {
                roamingLogManager.i("【连接状态】已连接到: $ssid")
            } else {
                roamingLogManager.i("【连接状态】连接已断开")
            }
        }
        currentConnectedSsid = ssid
    }

    // ── WakeLock 管理（按需持有，减少耗电）─────────────────────────────────

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(60_000L) // 最长持有 60s，防止泄漏
            Log.d(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.d(TAG, "WakeLock released")
        }
    }

    // ── AlarmManager 周期调度（可在 Doze 低功耗状态下唤醒 CPU）───────────

    private fun scheduleNextCycleAlarm(delayMs: Long) {
        val pi = buildCycleAlarmPendingIntent(PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            ?: return
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        val am = getSystemService(AlarmManager::class.java)
        // setAndAllowWhileIdle：Doze 期间也会触发，无需 SCHEDULE_EXACT_ALARM 权限
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        Log.d(TAG, "AlarmManager 已设置，${delayMs / 1000}s 后触发")
    }

    private fun cancelNextCycleAlarm() {
        val pi = buildCycleAlarmPendingIntent(PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
            ?: return
        getSystemService(AlarmManager::class.java).cancel(pi)
        Log.d(TAG, "AlarmManager 已取消")
    }

    private fun buildCycleAlarmPendingIntent(flags: Int): PendingIntent? {
        val intent = Intent(this, ScanForegroundService::class.java).apply {
            action = ACTION_NEXT_CYCLE
        }
        return PendingIntent.getService(this, 0, intent, flags)
    }

    // ── 通知 ────────────────────────────────────────────────────────────────

    private fun updateStatus(status: String) {
        currentStatus = status
        callback?.onStatusUpdate(status)
        updateNotification(status)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "WiFi扫描服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持WiFi扫描在后台运行" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi扫描运行中")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_signal_4)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(status: String) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")
        roamingLogManager.i("=".repeat(60))
        roamingLogManager.i("【服务停止】ScanForegroundService 已销毁")
        roamingLogManager.i("=".repeat(60))
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        cancelNextCycleAlarm()
        handlerThread.quit()
        wifiScanner.stopScan()
        apSelectionManager.close()
        releaseSpecifierConnection()
        releaseWakeLock()
    }

    // ── WifiNetworkSpecifier 连接管理 ─────────────────────────────────────────

    /**
     * 使用 WifiNetworkSpecifier 发起连接
     * @param ssid 目标SSID
     * @param password 密码（开放网络传空）
     * @param callback 连接状态回调
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun connectWithSpecifier(ssid: String, password: String, callback: SpecifierConnectionCallback) {
        releaseSpecifierConnection()

        val isOpen = password.isEmpty()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply { if (!isOpen) setWpa2Passphrase(password) }
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        specifierNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    connectivityManager.bindProcessToNetwork(network)
                    specifierConnectedSsid = ssid

                    val caps = connectivityManager.getNetworkCapabilities(network)
                    isSystemWifiConnection =
                        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                    roamingLogManager.i("【Specifier连接成功】SSID: $ssid, 类型: ${if (isSystemWifiConnection) "系统级" else "本地"}")
                    callback.onConnected(ssid, isSystemWifiConnection)
                }
            }

            override fun onUnavailable() {
                handler.post {
                    roamingLogManager.w("【Specifier连接失败】SSID: $ssid")
                    callback.onFailed(ssid, "连接失败或被用户取消")
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    connectivityManager.bindProcessToNetwork(null)
                    val wasSsid = specifierConnectedSsid
                    specifierConnectedSsid = null
                    isSystemWifiConnection = false
                    roamingLogManager.i("【Specifier连接断开】SSID: $wasSsid")
                    callback.onLost(wasSsid)
                }
            }
        }

        try {
            connectivityManager.requestNetwork(request, specifierNetworkCallback!!)
            roamingLogManager.i("【Specifier发起连接】SSID: $ssid")
        } catch (e: Exception) {
            roamingLogManager.e("【Specifier连接异常】${e.message}")
            callback.onFailed(ssid, e.message ?: "未知错误")
        }
    }

    /**
     * 释放 Specifier 连接
     */
    fun releaseSpecifierConnection() {
        specifierNetworkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        specifierNetworkCallback = null
        specifierConnectedSsid = null
        isSystemWifiConnection = false
        try { connectivityManager.bindProcessToNetwork(null) } catch (_: Exception) {}
    }

    /**
     * 获取当前 Specifier 连接状态
     */
    fun getSpecifierConnectionInfo(): Pair<String?, Boolean> {
        return Pair(specifierConnectedSsid, isSystemWifiConnection)
    }

    interface SpecifierConnectionCallback {
        fun onConnected(ssid: String, isSystemConnection: Boolean)
        fun onFailed(ssid: String, error: String)
        fun onLost(ssid: String?)
    }
}
