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
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.heyu.apdemo2.R
import com.heyu.apdemo2.connection.PasswordStore
import com.heyu.apdemo2.model.AccessPoint
import com.heyu.apdemo2.model.ScanResponse
import com.heyu.apdemo2.network.ApiService
import com.heyu.apdemo2.roaming.ApSelectionManager
import com.heyu.apdemo2.roaming.RoamingLogManager
import com.heyu.apdemo2.roaming.RoamingMode
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

    var pinnedSsid: String? = null
        private set
    private var pinnedPassword: String = ""
    private var pinnedIsOpen: Boolean = false

    // 独立后台线程：所有调度、扫描回调都在这个线程，与主线程完全解耦
    private val handlerThread = HandlerThread("ScanServiceThread")
    private lateinit var handler: Handler

    // WakeLock：防止 CPU 进入休眠，确保 HandlerThread 的定时任务按时执行
    private lateinit var wakeLock: PowerManager.WakeLock

    var scanInterval: Long = 35000L
        private set
    var serverIp: String? = null
        private set
    var serverPort: Int = -1
        private set

    var isRunning = false
        private set
    var autoRoamingEnabled: Boolean = false
        private set
    var roamingMode: RoamingMode = RoamingMode.ML
        private set
    private var lastRoamingTime: Long = 0
    private val ROAMING_COOLDOWN_MS = 30000L

    var currentAccessPoints: List<AccessPoint> = emptyList()
        private set
    var currentStatus: String = "准备就绪"
        private set
    var currentConnectedSsid: String? = null
        private set

    companion object {
        private const val TAG = "[SCAN_SERVICE]"
        const val CHANNEL_ID = "scan_service_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_IP = "server_ip"
        const val EXTRA_PORT = "server_port"
        const val EXTRA_SCAN_INTERVAL = "scan_interval"
        // AlarmManager 唤醒下一轮扫描的 Action
        const val ACTION_NEXT_CYCLE = "com.heyu.apdemo2.ACTION_NEXT_CYCLE"
        // SharedPreferences 与 MainActivity 共用同一个文件
        private const val PREFS_NAME = "server_settings"
    }

    override fun onCreate() {
        super.onCreate()

        roamingLogManager = RoamingLogManager.getInstance(this)
        roamingLogManager.i("【服务启动】ScanForegroundService 已创建")

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
                // AlarmManager 唤醒后触发下一次扫描
                Log.d(TAG, "AlarmManager 触发扫描")
                if (isRunning) {
                    acquireWakeLock()
                    handler.post { executeSingleScan() }
                }
            }
            intent != null -> {
                // 正常启动：从 Intent 读取配置，并同步写入 SharedPreferences
                serverIp   = intent.getStringExtra(EXTRA_IP)
                serverPort = intent.getIntExtra(EXTRA_PORT, -1)
                scanInterval = intent.getLongExtra(EXTRA_SCAN_INTERVAL, 35000L)
                persistConfig()
                Log.d(TAG, "收到配置: ip=$serverIp port=$serverPort scanInterval=${scanInterval}ms")
                // 无论是否已在运行，都立即触发扫描+上报，确保打开APP后立刻同步后端
                acquireWakeLock()
                startScanLoop()
            }
            else -> {
                // START_STICKY 重启：intent 为 null，从持久化存储恢复配置
                restoreConfig()
                Log.d(TAG, "服务重启（intent=null），恢复配置: ip=$serverIp port=$serverPort")
                if (!isRunning) {
                    acquireWakeLock()
                    startScanLoop()
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
     * 手动连接：准备无障碍服务目标，但不打开 WiFi 设置页（由调用方 Fragment 负责打开，
     * 保证同任务栈，一次 BACK 即可返回 App）。
     */
    fun connectToNetwork(ssid: String, isOpen: Boolean, password: String) {
        pinnedPassword = password
        pinnedIsOpen = isOpen

        val a11y = WifiAccessibilityService.getInstance()
        if (a11y == null) {
            roamingLogManager.w("【连接失败】无障碍服务未启用，请在系统设置中开启")
            callback?.onConnectionChanged(null, false, "请先在系统设置中启用无障碍服务")
            return
        }

        a11y.cancel()
        roamingLogManager.i("【连接】准备无障碍连接（等待 Fragment 打开 WiFi 设置）: $ssid")
        a11y.prepareManualConnect(ssid, password, isOpen, object : WifiAccessibilityService.ConnectionCallback {
            override fun onConnected(connectedSsid: String) {
                pinnedSsid = connectedSsid
                currentConnectedSsid = connectedSsid
                Log.d(TAG, "无障碍服务连接成功: $connectedSsid")
                callback?.onConnectionChanged(connectedSsid, true)
            }
            override fun onFailed(failedSsid: String, reason: String) {
                Log.w(TAG, "无障碍服务连接失败: $reason")
                if (pinnedSsid == ssid) pinnedSsid = null
                callback?.onConnectionChanged(null, false, reason)
            }
        })
    }

    /**
     * 取消当前连接操作并清除本地状态。
     * 无障碍服务为真实系统连接，无法通过代码强制断开，只清除本地状态。
     */
    fun disconnectPinned() {
        WifiAccessibilityService.getInstance()?.cancel()
        pinnedSsid = null
        currentConnectedSsid = null
        callback?.onConnectionChanged(null, false)
    }

    fun updateConfig(ip: String, port: Int, scanInt: Long) {
        serverIp = ip
        serverPort = port
        scanInterval = scanInt
        persistConfig()
        startScanLoop()
    }

    // ── 配置持久化 ───────────────────────────────────────────────────────────

    private fun persistConfig() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("server_ip", serverIp)
            .putInt("server_port", serverPort)
            .putLong("scan_interval", scanInterval)
            .apply()
    }

    private fun restoreConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        serverIp     = prefs.getString("server_ip", null)
        serverPort   = prefs.getInt("server_port", -1)
        scanInterval = prefs.getLong("scan_interval", 35000L)
    }

    // ── 用户主动请求评分 ──────────────────────────────────────────────────────────

    fun requestScores() {
        handler.post { queryScoresOnly() }
    }

    private fun queryScoresOnly() {
        val ip = serverIp
        if (ip == null || serverPort == -1) {
            Log.w(TAG, "请求评分跳过: 服务器未配置 (ip=$ip, port=$serverPort)")
            callback?.onStatusUpdate("评分失败: 服务器未配置")
            return
        }
        if (currentAccessPoints.isEmpty()) {
            Log.w(TAG, "请求评分跳过: 无扫描数据")
            callback?.onStatusUpdate("评分失败: 暂无扫描数据")
            return
        }

        Log.d(TAG, "请求评分: ip=$ip port=$serverPort AP数=${currentAccessPoints.size}")
        apiService.uploadScanResults(ip, serverPort, currentAccessPoints, object : ApiService.BatchCallback {
            override fun onSuccess(response: ScanResponse) {
                handler.post {
                    updateScoresFromResponse(response)
                }
            }
            override fun onError(error: String) {
                handler.post {
                    Log.e(TAG, "请求评分失败: $error")
                    callback?.onStatusUpdate("评分失败: $error")
                    if (autoRoamingEnabled) evaluateAndTriggerRoaming()
                }
            }
        })
    }

    // ── 扫描主流程（全部在 HandlerThread 执行）──────────────────────────────

    private fun startScanLoop() {
        Log.d(TAG, ">>> [startScanLoop] 启动扫描循环，间隔=${scanInterval}ms")
        isRunning = true
        handler.removeCallbacksAndMessages(null)
        cancelNextCycleAlarm()
        wifiScanner.stopScan()
        handler.post { executeSingleScan() }
    }

    private fun executeSingleScan() {
        if (!isRunning) return
        updateStatus("正在扫描...")
        Log.d(TAG, ">>> 开始单次扫描")
        roamingLogManager.i("【开始扫描】")

        wifiScanner.startScan(
            onSuccess = { accessPoints ->
                if (!isRunning) return@startScan
                currentAccessPoints = accessPoints
                restoreCachedScores()
                callback?.onDataUpdate(currentAccessPoints)
                roamingLogManager.i("扫描完成: ${accessPoints.size}个AP")
                if (autoRoamingEnabled) evaluateAndTriggerRoaming()
                scheduleNextScan("就绪")
            },
            onError = { err ->
                if (!isRunning) return@startScan
                Log.e(TAG, "扫描失败: $err")
                roamingLogManager.e("【扫描失败】$err")
                scheduleNextScan("扫描失败")
            }
        )
    }

    private fun syncScoresAndEvaluate(accessPoints: List<AccessPoint>) {
        val ip = serverIp
        if (ip == null || serverPort == -1) {
            if (autoRoamingEnabled) evaluateAndTriggerRoaming()
            scheduleNextScan("服务器未配置")
            return
        }

        updateStatus("正在同步评分...")
        apiService.uploadScanResults(ip, serverPort, accessPoints, object : ApiService.BatchCallback {
            override fun onSuccess(response: ScanResponse) {
                handler.post {
                    if (!isRunning) return@post
                    updateScoresFromResponse(response)
                    scheduleNextScan("就绪")
                }
            }
            override fun onError(error: String) {
                handler.post {
                    if (!isRunning) return@post
                    if (autoRoamingEnabled) evaluateAndTriggerRoaming()
                    scheduleNextScan("同步失败")
                }
            }
        })
    }

    private fun scheduleNextScan(status: String) {
        if (!isRunning) return
        val msg = "$status (${scanInterval / 1000}s 后扫描)"
        updateStatus(msg)
        Log.d(TAG, ">>> 下次扫描在 ${scanInterval / 1000}s 后")

        // AlarmManager 确保 Doze 期间也能唤醒
        scheduleNextCycleAlarm(scanInterval)
        releaseWakeLock()
    }

    /**
     * 从 ApSelectionManager 缓存恢复评分到新扫描的 AccessPoint 对象
     */
    private fun restoreCachedScores() {
        currentAccessPoints.forEach { ap ->
            if (ap.score == null) {
                apSelectionManager.getApScore(ap.ssid)?.let { cached ->
                    ap.score = cached.toInt()
                    ap.reason = apSelectionManager.getApReason(ap.ssid)
                }
            }
        }
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
                apSelectionManager.setApScore(ap.ssid, score.toFloat(), ap.reason)
            }
        }

        if (autoRoamingEnabled) {
            evaluateAndTriggerRoaming()
        }
    }

    /**
     * 评估并触发漫游切换
     */
    private fun evaluateAndTriggerRoaming() {
        val now = System.currentTimeMillis()

        if (currentAccessPoints.isEmpty()) return

        // 过滤掉没有保存密码的加密AP
        val connectableAps = currentAccessPoints.filter { ap ->
            !ap.isSecured() || !PasswordStore.get(this, ap.ssid).isNullOrEmpty()
        }
        if (connectableAps.isEmpty()) return

        val currentAp = connectableAps.find { it.ssid == currentConnectedSsid }
        roamingLogManager.phase("开始评估", "#1565C0",
            "当前: ${currentAp?.ssid ?: "无"} (${currentAp?.rssi ?: "--"}dBm), 可选: ${connectableAps.size}个")

        val bestAp = when (roamingMode) {
            RoamingMode.ML    -> apSelectionManager.selectBestAp(connectableAps)
            RoamingMode.SCORE -> apSelectionManager.selectBestApByScore(connectableAps)
        }
        if (bestAp == null) {
            roamingLogManager.phase("评估结束", "#757575", "未找到可用AP")
            return
        }

        if (bestAp.ssid == currentConnectedSsid) {
            roamingLogManager.phase("评估结束", "#4CAF50", "当前已最优 (${bestAp.ssid})，不切换")
            return
        }

        if (now - lastRoamingTime < ROAMING_COOLDOWN_MS) {
            val remaining = (ROAMING_COOLDOWN_MS - (now - lastRoamingTime)) / 1000
            roamingLogManager.phase("评估结束", "#FF9800", "推荐 ${bestAp.ssid}，冷却中（${remaining}s）")
            return
        }

        roamingLogManager.phase("触发切换", "#E65100",
            "${currentAp?.ssid ?: "无"} → ${bestAp.ssid} (${bestAp.rssi}dBm)")
        triggerRoamingConnection(bestAp)
        lastRoamingTime = now
    }

    /**
     * 触发漫游连接（纯无障碍服务，实现真实系统切换）。
     */
    private fun triggerRoamingConnection(targetAp: AccessPoint) {
        val password = PasswordStore.get(this, targetAp.ssid) ?: ""
        val isOpen = !targetAp.isSecured()

        if (!isOpen && password.isEmpty()) {
            roamingLogManager.w("【切换失败】${targetAp.ssid} 需要密码但未保存")
            return
        }

        roamingLogManager.i("【开始切换】目标AP: ${targetAp.ssid}, RSSI: ${targetAp.rssi}dBm, 类型: ${if (isOpen) "开放" else "加密"}")

        val a11y = WifiAccessibilityService.getInstance()
        if (a11y == null) {
            roamingLogManager.w("【切换失败】无障碍服务未启用，请在系统设置中开启")
            return
        }

        a11y.cancel()

        roamingLogManager.i("【切换】使用无障碍服务（后台漫游，服务自开 WiFi 设置）")
        a11y.connectFromBackground(targetAp.ssid, password, isOpen, object : WifiAccessibilityService.ConnectionCallback {
            override fun onConnected(connectedSsid: String) {
                currentConnectedSsid = connectedSsid
                pinnedSsid = connectedSsid
                roamingLogManager.phase("评估结束", "#4CAF50", "切换成功 → $connectedSsid")
                callback?.onConnectionChanged(connectedSsid, true)
            }
            override fun onFailed(failedSsid: String, reason: String) {
                roamingLogManager.phase("评估结束", "#D32F2F", "切换失败: ${targetAp.ssid} ($reason)")
                callback?.onConnectionChanged(null, false, reason)
            }
        })
    }

    fun setAutoRoamingEnabled(enabled: Boolean) {
        autoRoamingEnabled = enabled
        roamingLogManager.i("【自动漫游】状态: ${if (enabled) "已开启" else "已关闭"}")
    }

    fun setRoamingMode(mode: RoamingMode) {
        roamingMode = mode
        roamingLogManager.i("【漫游模式】已切换: ${if (mode == RoamingMode.ML) "ML模型" else "众包评分"}")
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

        roamingLogManager.i("【服务停止】ScanForegroundService 已销毁")

        isRunning = false
        handler.removeCallbacksAndMessages(null)
        cancelNextCycleAlarm()
        handlerThread.quit()
        wifiScanner.stopScan()
        apSelectionManager.close()
        releaseWakeLock()
    }

}
