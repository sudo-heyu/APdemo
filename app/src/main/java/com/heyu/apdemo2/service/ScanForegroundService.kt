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

    // Independent background thread: all scheduling and scan callbacks run on this thread, completely decoupled from the main thread
    private val handlerThread = HandlerThread("ScanServiceThread")
    private lateinit var handler: Handler

    // WakeLock: prevents CPU from sleeping, ensuring HandlerThread's scheduled tasks execute on time
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
    var roamingCooldown: Long = 5000L  // Switch cooldown period, default 5 seconds
        private set

    /** Timestamp of last successful roaming switch (milliseconds), used for cooldown check */
    private var lastRoamingSwitchTime: Long = 0L

    var currentAccessPoints: List<AccessPoint> = emptyList()
        private set
    var currentStatus: String = "Ready"
        private set
    var currentConnectedSsid: String? = null
        private set

    /** True when user is manually connecting, auto-roaming is skipped during this time */
    private var isUserConnecting: Boolean = false

    companion object {
        private const val TAG = "[SCAN_SERVICE]"
        const val CHANNEL_ID = "scan_service_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_IP = "server_ip"
        const val EXTRA_PORT = "server_port"
        const val EXTRA_SCAN_INTERVAL = "scan_interval"
        // AlarmManager action to wake up next scan cycle
        const val ACTION_NEXT_CYCLE = "com.heyu.apdemo2.ACTION_NEXT_CYCLE"
        // SharedPreferences shares the same file with MainActivity
        private const val PREFS_NAME = "server_settings"
        // AP protection lifetime: minimum retention time for weak signal APs (milliseconds)
        private const val AP_LIFETIME_MS = 20_000L
    }

    override fun onCreate() {
        super.onCreate()

        roamingLogManager = RoamingLogManager.getInstance(this)
        roamingLogManager.i("[Service Started] ScanForegroundService created")

        handlerThread.start()
        handler = Handler(handlerThread.looper)

        // 2. Prepare PARTIAL_WAKE_LOCK, held on-demand during scan+upload, released during waiting to save battery
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "APdemo2:ScanWakeLock")

        // 3. Pass HandlerThread's Looper to WifiScanner
        wifiScanner = WifiScanner(this, handlerThread.looper)
        apSelectionManager = ApSelectionManager(this)

        createNotificationChannel()
        startForegroundCompat("Waiting for configuration...")
        Log.d(TAG, "Service created (HandlerThread + WifiScanner(HandlerThread looper) + WakeLock)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_NEXT_CYCLE -> {
                // Triggered by AlarmManager for next scan
                Log.d(TAG, "AlarmManager triggered scan")
                if (isRunning) {
                    acquireWakeLock()
                    handler.post { executeSingleScan() }
                }
            }
            intent != null -> {
                // Normal startup: read config from Intent and persist to SharedPreferences
                serverIp   = intent.getStringExtra(EXTRA_IP)
                serverPort = intent.getIntExtra(EXTRA_PORT, -1)
                scanInterval = intent.getLongExtra(EXTRA_SCAN_INTERVAL, 35000L)
                persistConfig()
                Log.d(TAG, "Received config: ip=$serverIp port=$serverPort scanInterval=${scanInterval}ms")
                // Trigger scan+upload immediately regardless of running state, ensuring sync with backend when app opens
                acquireWakeLock()
                startScanLoop()
            }
            else -> {
                // START_STICKY restart: intent is null, restore config from persistent storage
                restoreConfig()
                Log.d(TAG, "Service restarted (intent=null), restored config: ip=$serverIp port=$serverPort")
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
     * Manual connection: prepare accessibility service target, but don't open WiFi settings page
     * (the calling Fragment is responsible for opening it, ensuring same task stack,
     * so one BACK returns to the app).
     */
    fun connectToNetwork(ssid: String, isOpen: Boolean, password: String) {
        pinnedPassword = password
        pinnedIsOpen = isOpen
        isUserConnecting = true  // Mark user is connecting

        val a11y = WifiAccessibilityService.getInstance()
        if (a11y == null) {
            roamingLogManager.w("[Connection Failed] Accessibility service not enabled, please enable in system settings")
            isUserConnecting = false
            callback?.onConnectionChanged(null, false, "Please enable accessibility service in system settings first")
            return
        }

        a11y.cancel()
        roamingLogManager.i("[Connection] Preparing accessibility connection (waiting for Fragment to open WiFi settings): $ssid")
        a11y.prepareManualConnect(ssid, password, isOpen, object : WifiAccessibilityService.ConnectionCallback {
            override fun onConnected(connectedSsid: String) {
                isUserConnecting = false  // Clear mark
                pinnedSsid = connectedSsid
                currentConnectedSsid = connectedSsid
                Log.d(TAG, "Accessibility service connection successful: $connectedSsid")
                callback?.onConnectionChanged(connectedSsid, true)
            }
            override fun onFailed(failedSsid: String, reason: String) {
                isUserConnecting = false  // Clear mark
                Log.w(TAG, "Accessibility service connection failed: $reason")
                if (pinnedSsid == ssid) pinnedSsid = null
                callback?.onConnectionChanged(null, false, reason)
            }
        })
    }

    /**
     * Cancel current connection operation and clear local state.
     * Accessibility service performs real system connection, cannot be forcibly disconnected by code, only clear local state.
     */
    fun disconnectPinned() {
        WifiAccessibilityService.getInstance()?.cancel()
        isUserConnecting = false
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

    // ── Configuration Persistence ───────────────────────────────────────────────────────────

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
        roamingCooldown = prefs.getLong("roaming_cooldown", 5000L)
    }

    // ── User-initiated Score Request ──────────────────────────────────────────────────────────

    fun requestScores() {
        handler.post { queryScoresOnly() }
    }

    private fun queryScoresOnly() {
        val ip = serverIp
        if (ip == null || serverPort == -1) {
            Log.w(TAG, "Score request skipped: server not configured (ip=$ip, port=$serverPort)")
            callback?.onStatusUpdate("Score request failed: server not configured")
            return
        }
        if (currentAccessPoints.isEmpty()) {
            Log.w(TAG, "Score request skipped: no scan data")
            callback?.onStatusUpdate("Score request failed: no scan data available")
            return
        }

        Log.d(TAG, "Requesting scores: ip=$ip port=$serverPort AP count=${currentAccessPoints.size}")
        apiService.uploadScanResults(ip, serverPort, currentAccessPoints, object : ApiService.BatchCallback {
            override fun onSuccess(response: ScanResponse) {
                handler.post {
                    updateScoresFromResponse(response)
                }
            }
            override fun onError(error: String) {
                handler.post {
                    Log.e(TAG, "Score request failed: $error")
                    callback?.onStatusUpdate("Score request failed: $error")
                    if (autoRoamingEnabled) evaluateAndTriggerRoaming()
                }
            }
        })
    }

    // ── Main Scan Flow (all executed in HandlerThread) ──────────────────────────────

    private fun startScanLoop() {
        Log.d(TAG, ">>> [startScanLoop] Starting scan loop, interval=${scanInterval}ms")
        isRunning = true
        handler.removeCallbacksAndMessages(null)
        cancelNextCycleAlarm()
        wifiScanner.stopScan()
        handler.post { executeSingleScan() }
    }

    private fun executeSingleScan() {
        if (!isRunning) return
        updateStatus("Scanning...")
        Log.d(TAG, ">>> Starting single scan")
        roamingLogManager.i("[Scan Started]")

        wifiScanner.startScan(
            onSuccess = { accessPoints ->
                if (!isRunning) return@startScan
                // Use merge logic with protection mechanism
                currentAccessPoints = mergeScanResults(accessPoints)
                restoreCachedScores()
                callback?.onDataUpdate(currentAccessPoints)
                roamingLogManager.i("Scan complete: ${accessPoints.size} APs, list retained: ${currentAccessPoints.size} APs")
                if (autoRoamingEnabled) evaluateAndTriggerRoaming()
                scheduleNextScan("Ready")
            },
            onError = { err ->
                if (!isRunning) return@startScan
                Log.e(TAG, "Scan failed: $err")
                roamingLogManager.e("[Scan Failed] $err")
                scheduleNextScan("Scan failed")
            }
        )
    }

    private fun syncScoresAndEvaluate(accessPoints: List<AccessPoint>) {
        val ip = serverIp
        if (ip == null || serverPort == -1) {
            if (autoRoamingEnabled) evaluateAndTriggerRoaming()
            scheduleNextScan("Server not configured")
            return
        }

        updateStatus("Syncing scores...")
        apiService.uploadScanResults(ip, serverPort, accessPoints, object : ApiService.BatchCallback {
            override fun onSuccess(response: ScanResponse) {
                handler.post {
                    if (!isRunning) return@post
                    updateScoresFromResponse(response)
                    scheduleNextScan("Ready")
                }
            }
            override fun onError(error: String) {
                handler.post {
                    if (!isRunning) return@post
                    if (autoRoamingEnabled) evaluateAndTriggerRoaming()
                    scheduleNextScan("Sync failed")
                }
            }
        })
    }

    private fun scheduleNextScan(status: String) {
        if (!isRunning) return
        val msg = "$status (scan in ${scanInterval / 1000}s)"
        updateStatus(msg)
        Log.d(TAG, ">>> Next scan in ${scanInterval / 1000}s")

        // AlarmManager ensures wake up even during Doze mode
        scheduleNextCycleAlarm(scanInterval)
        releaseWakeLock()
    }

    /**
     * Restore cached scores from ApSelectionManager to newly scanned AccessPoint objects
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

    /**
     * Merge scan results: implements weak signal AP protection mechanism
     * - Newly scanned APs: add to list, record current time
     * - Existing APs: update info (signal strength, etc.), refresh time
     * - APs not scanned: check if beyond lifetime, remove if so, otherwise keep
     */
    private fun mergeScanResults(newAps: List<AccessPoint>): List<AccessPoint> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<AccessPoint>()

        // 1. Process newly scanned APs
        newAps.forEach { newAp ->
            val existing = currentAccessPoints.find { it.ssid == newAp.ssid }
            if (existing != null) {
                // Already exists: create updated object, preserve score
                result.add(existing.copy(
                    bssid = newAp.bssid,
                    rssi = newAp.rssi,
                    frequency = newAp.frequency,
                    capabilities = newAp.capabilities,
                    lastSeenTime = now
                ))
            } else {
                // New AP: set timestamp
                newAp.lastSeenTime = now
                result.add(newAp)
            }
        }

        // 2. Check APs not scanned: keep if within protection period
        currentAccessPoints.forEach { oldAp ->
            if (result.none { it.ssid == oldAp.ssid }) {
                val age = now - oldAp.lastSeenTime
                if (age < AP_LIFETIME_MS) {
                    // Within protection period, keep
                    result.add(oldAp)
                    Log.d(TAG, "AP protected: ${oldAp.ssid} not scanned for ${age}ms, kept")
                } else {
                    // Beyond lifetime, remove
                    Log.d(TAG, "AP removed: ${oldAp.ssid} not scanned for ${age}ms, exceeded lifetime")
                }
            }
        }

        return result.sortedByDescending { it.rssi }
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
     * Evaluate and trigger roaming switch
     */
    private fun evaluateAndTriggerRoaming() {
        // Skip roaming when user is manually connecting
        if (isUserConnecting) {
            roamingLogManager.phase("Roaming skipped", "#FF9800", "User is manually connecting, waiting for next evaluation")
            return
        }

        // Cooldown check
        val now = System.currentTimeMillis()
        val timeSinceLastSwitch = now - lastRoamingSwitchTime
        if (lastRoamingSwitchTime > 0 && timeSinceLastSwitch < roamingCooldown) {
            val remaining = (roamingCooldown - timeSinceLastSwitch) / 1000
            roamingLogManager.phase("In cooldown", "#FF9800",
                "Switch cooldown remaining ${remaining}s, skipping this evaluation")
            return
        }

        if (currentAccessPoints.isEmpty()) return

        // Filter out encrypted APs without saved passwords
        val connectableAps = currentAccessPoints.filter { ap ->
            !ap.isSecured() || !PasswordStore.get(this, ap.ssid).isNullOrEmpty()
        }
        if (connectableAps.isEmpty()) return

        val currentAp = connectableAps.find { it.ssid == currentConnectedSsid }
        roamingLogManager.phase("Evaluation started", "#1565C0",
            "Current: ${currentAp?.ssid ?: "none"} (${currentAp?.rssi ?: "--"}dBm), Candidates: ${connectableAps.size}")

        val bestAp = when (roamingMode) {
            RoamingMode.ML    -> apSelectionManager.selectBestAp(connectableAps)
            RoamingMode.SCORE -> apSelectionManager.selectBestApByScore(connectableAps)
        }
        if (bestAp == null) {
            roamingLogManager.phase("Evaluation ended", "#757575", "No available AP found")
            return
        }

        if (bestAp.ssid == currentConnectedSsid) {
            roamingLogManager.phase("Evaluation ended", "#4CAF50", "Current is optimal (${bestAp.ssid}), no switch needed")
            return
        }

        roamingLogManager.phase("Switch triggered", "#E65100",
            "${currentAp?.ssid ?: "none"} → ${bestAp.ssid} (${bestAp.rssi}dBm)")
        triggerRoamingConnection(bestAp)
    }

    /**
     * Trigger roaming connection (pure accessibility service, implements real system switch).
     */
    private fun triggerRoamingConnection(targetAp: AccessPoint) {
        val password = PasswordStore.get(this, targetAp.ssid) ?: ""
        val isOpen = !targetAp.isSecured()

        if (!isOpen && password.isEmpty()) {
            roamingLogManager.w("[Switch Failed] ${targetAp.ssid} requires password but none saved")
            return
        }

        roamingLogManager.i("[Switch Started] Target AP: ${targetAp.ssid}, RSSI: ${targetAp.rssi}dBm, Type: ${if (isOpen) "open" else "secured"}")

        val a11y = WifiAccessibilityService.getInstance()
        if (a11y == null) {
            roamingLogManager.w("[Switch Failed] Accessibility service not enabled, please enable in system settings")
            return
        }

        a11y.cancel()

        roamingLogManager.i("[Switching] Using accessibility service (background roaming, service opens WiFi settings)")
        a11y.connectFromBackground(targetAp.ssid, password, isOpen, object : WifiAccessibilityService.ConnectionCallback {
            override fun onConnected(connectedSsid: String) {
                currentConnectedSsid = connectedSsid
                pinnedSsid = connectedSsid
                lastRoamingSwitchTime = System.currentTimeMillis()  // Record successful switch time
                roamingLogManager.phase("Evaluation ended", "#4CAF50", "Switch successful → $connectedSsid")
                callback?.onConnectionChanged(connectedSsid, true)
            }
            override fun onFailed(failedSsid: String, reason: String) {
                roamingLogManager.phase("Evaluation ended", "#D32F2F", "Switch failed: ${targetAp.ssid} ($reason)")
                callback?.onConnectionChanged(null, false, reason)
            }
        })
    }

    fun setAutoRoamingEnabled(enabled: Boolean) {
        autoRoamingEnabled = enabled
        roamingLogManager.i("[Auto Roaming] Status: ${if (enabled) "enabled" else "disabled"}")
    }

    fun setRoamingMode(mode: RoamingMode) {
        roamingMode = mode
        roamingLogManager.i("[Roaming Mode] Switched to: ${if (mode == RoamingMode.ML) "ML model" else "Crowdsourced score"}")
    }

    fun setRoamingCooldown(cooldown: Long) {
        roamingCooldown = cooldown
        roamingLogManager.i("[Switch Cooldown] Set to ${cooldown / 1000} seconds")
    }

    /**
     * Update currently connected SSID
     */
    fun updateConnectedSsid(ssid: String?) {
        if (currentConnectedSsid != ssid) {
            if (ssid != null) {
                roamingLogManager.i("[Connection State] Connected to: $ssid")
            } else {
                roamingLogManager.i("[Connection State] Disconnected")
            }
        }
        currentConnectedSsid = ssid
    }

    // ── WakeLock Management (acquired on demand, reduces power consumption) ─────────────────────────────────

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(60_000L) // Hold for max 60s to prevent leakage
            Log.d(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.d(TAG, "WakeLock released")
        }
    }

    // ── AlarmManager Periodic Scheduling (can wake CPU even in Doze low-power state) ───────────

    private fun scheduleNextCycleAlarm(delayMs: Long) {
        val pi = buildCycleAlarmPendingIntent(PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            ?: return
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        val am = getSystemService(AlarmManager::class.java)
        // setAndAllowWhileIdle: triggers even during Doze, no SCHEDULE_EXACT_ALARM permission needed
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        Log.d(TAG, "AlarmManager set, triggering in ${delayMs / 1000}s")
    }

    private fun cancelNextCycleAlarm() {
        val pi = buildCycleAlarmPendingIntent(PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
            ?: return
        getSystemService(AlarmManager::class.java).cancel(pi)
        Log.d(TAG, "AlarmManager cancelled")
    }

    private fun buildCycleAlarmPendingIntent(flags: Int): PendingIntent? {
        val intent = Intent(this, ScanForegroundService::class.java).apply {
            action = ACTION_NEXT_CYCLE
        }
        return PendingIntent.getService(this, 0, intent, flags)
    }

    // ── Notifications ────────────────────────────────────────────────────────────────

    private fun updateStatus(status: String) {
        currentStatus = status
        callback?.onStatusUpdate(status)
        updateNotification(status)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "WiFi Scan Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keep WiFi scanning running in background" }
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
            .setContentTitle("WiFi Scanning Running")
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
        Log.d(TAG, "Service destroyed")

        roamingLogManager.i("[Service Stopped] ScanForegroundService destroyed")

        isRunning = false
        handler.removeCallbacksAndMessages(null)
        cancelNextCycleAlarm()
        handlerThread.quit()
        wifiScanner.stopScan()
        apSelectionManager.close()
        releaseWakeLock()
    }

}
