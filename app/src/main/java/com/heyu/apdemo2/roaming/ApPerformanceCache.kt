package com.heyu.apdemo2.roaming

import android.content.Context
import android.content.SharedPreferences
import android.net.TrafficStats
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * AP Performance History Record
 *
 * Local cache of performance data for APs user has actually connected to,
 * used to assist network selection decisions
 */
@Serializable
data class ApPerformanceRecord(
    val ssid: String,
    val rssi: Int,
    val timestamp: Long,
    val rxSpeedKbps: Float,      // Receive rate KB/s
    val txSpeedKbps: Float,      // Transmit rate KB/s
    val latencyMs: Float?,       // Ping latency ms
    val isConnected: Boolean     // Whether there was actual data transfer at that time
)

/**
 * AP Performance Statistics Summary
 */
data class ApPerformanceSummary(
    val ssid: String,
    val sampleCount: Int,
    val avgRssi: Float,
    val avgRxSpeed: Float,
    val avgTxSpeed: Float,
    val avgLatency: Float?,
    val lastSeen: Long
) {
    /**
     * Comprehensive performance score (0-100)
     * Calculated based on historical throughput, latency, signal strength
     */
    fun calculateScore(): Float {
        // Throughput weight 40%, latency weight 30%, RSSI weight 30%
        val throughputScore = (avgRxSpeed + avgTxSpeed).coerceIn(0f, 2000f) / 20f  // Assume 2000KB/s = 100 points

        val latencyScore = avgLatency?.let { lat ->
            when {
                lat < 20 -> 100f
                lat < 50 -> 80f
                lat < 100 -> 60f
                lat < 200 -> 40f
                else -> 20f
            }
        } ?: 50f  // Medium score for no latency data

        val rssiScore = when {
            avgRssi > -50 -> 100f
            avgRssi > -60 -> 80f
            avgRssi > -70 -> 60f
            avgRssi > -80 -> 40f
            else -> 20f
        }

        return (throughputScore * 0.4f + latencyScore * 0.3f + rssiScore * 0.3f).coerceIn(0f, 100f)
    }
}

/**
 * AP Performance Cache Manager
 *
 * Automatically samples performance of currently connected AP, stores historical data
 */
class ApPerformanceCache private constructor(context: Context) {

    companion object {
        private const val TAG = "[ApPerformanceCache]"
        private const val PREFS_NAME = "ap_performance_cache"
        private const val MAX_RECORDS_PER_AP = 100     // Max records to keep per AP
        private const val RECORD_EXPIRY_DAYS = 30      // Record expiry time (days)
        private const val SAMPLE_INTERVAL_MS = 5000L   // Sampling interval 5 seconds
        private const val MIN_DATA_THRESHOLD = 1024L   // Minimum data change threshold 1KB

        @Volatile
        private var instance: ApPerformanceCache? = null

        fun getInstance(context: Context): ApPerformanceCache {
            return instance ?: synchronized(this) {
                instance ?: ApPerformanceCache(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // Memory cache
    private val memoryCache = ConcurrentHashMap<String, MutableList<ApPerformanceRecord>>()

    // Sampling state
    private var lastSampleTime = 0L
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var currentSsid: String? = null
    private var currentRssi = 0

    init {
        loadFromDisk()
    }

    /**
     * Start monitoring specified AP's performance
     */
    fun startMonitoring(ssid: String, rssi: Int) {
        currentSsid = ssid
        currentRssi = rssi
        lastSampleTime = System.currentTimeMillis()
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        Log.d(TAG, "Started monitoring AP: $ssid, RSSI: $rssi")
    }

    /**
     * Stop monitoring
     */
    fun stopMonitoring() {
        currentSsid?.let {
            Log.d(TAG, "Stopped monitoring AP: $it")
        }
        currentSsid = null
        lastSampleTime = 0L
    }

    /**
     * Update current RSSI (called on AP switch or signal change)
     */
    fun updateRssi(rssi: Int) {
        currentRssi = rssi
    }

    /**
     * Sample current performance (should be called periodically in background thread)
     */
    fun sample(): ApPerformanceRecord? {
        val ssid = currentSsid ?: return null
        val now = System.currentTimeMillis()

        // Check sampling interval
        if (now - lastSampleTime < SAMPLE_INTERVAL_MS) {
            return null
        }

        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val elapsedSec = (now - lastSampleTime) / 1000f

        // Calculate rates
        val rxDiff = currentRx - lastRxBytes
        val txDiff = currentTx - lastTxBytes
        val rxSpeed = if (elapsedSec > 0) (rxDiff / 1024f / elapsedSec) else 0f  // KB/s
        val txSpeed = if (elapsedSec > 0) (txDiff / 1024f / elapsedSec) else 0f

        // Determine if there's valid data transfer
        val isEffectivelyConnected = rxDiff > MIN_DATA_THRESHOLD || txDiff > MIN_DATA_THRESHOLD

        // Measure latency (optional, executed asynchronously)
        val latency = measureLatencyAsync()

        val record = ApPerformanceRecord(
            ssid = ssid,
            rssi = currentRssi,
            timestamp = now,
            rxSpeedKbps = rxSpeed,
            txSpeedKbps = txSpeed,
            latencyMs = latency,
            isConnected = isEffectivelyConnected
        )

        // Save record
        addRecord(ssid, record)

        // Update state
        lastSampleTime = now
        lastRxBytes = currentRx
        lastTxBytes = currentTx

        Log.v(TAG, "Sampled $ssid: rx=${rxSpeed.toInt()}KB/s, tx=${txSpeed.toInt()}KB/s, " +
                "latency=${latency?.toInt() ?: "N/A"}ms, connected=$isEffectivelyConnected")

        return record
    }

    /**
     * Get performance statistics for specified AP
     */
    fun getPerformanceSummary(ssid: String): ApPerformanceSummary? {
        val records = memoryCache[ssid]?.filter { !isExpired(it.timestamp) }
            ?: return null

        if (records.isEmpty()) return null

        val validRecords = records.filter { it.isConnected }
        if (validRecords.isEmpty()) return null

        return ApPerformanceSummary(
            ssid = ssid,
            sampleCount = validRecords.size,
            avgRssi = validRecords.map { it.rssi }.average().toFloat(),
            avgRxSpeed = validRecords.map { it.rxSpeedKbps }.average().toFloat(),
            avgTxSpeed = validRecords.map { it.txSpeedKbps }.average().toFloat(),
            avgLatency = validRecords.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            lastSeen = validRecords.maxOf { it.timestamp }
        )
    }

    /**
     * Get performance scores for all known APs
     */
    fun getAllPerformanceScores(): Map<String, Float> {
        return memoryCache.keys.mapNotNull { ssid ->
            getPerformanceSummary(ssid)?.let { summary ->
                ssid to summary.calculateScore()
            }
        }.toMap()
    }

    /**
     * Check if there's historical performance data for an AP
     */
    fun hasPerformanceData(ssid: String): Boolean {
        return memoryCache[ssid]?.any { !isExpired(it.timestamp) } ?: false
    }

    /**
     * Get AP connection status (based on historical data)
     */
    fun isLikelyConnectable(ssid: String): Boolean {
        // Currently monitored AP (connected) returns true directly
        if (ssid == currentSsid) return true

        val records = memoryCache[ssid] ?: return false
        val recentRecords = records.filter { !isExpired(it.timestamp) }
        if (recentRecords.isEmpty()) return false

        // If recent 50% of samples show data transfer, consider connectable
        val connectedRatio = recentRecords.count { it.isConnected }.toFloat() / recentRecords.size
        return connectedRatio > 0.5f
    }

    /**
     * Clean up expired data
     */
    fun cleanupExpired() {
        val expiryTime = System.currentTimeMillis() - (RECORD_EXPIRY_DAYS * 24 * 60 * 60 * 1000)

        memoryCache.forEach { (ssid, records) ->
            val valid = records.filter { it.timestamp > expiryTime }
            if (valid.size != records.size) {
                memoryCache[ssid] = valid.toMutableList()
            }
        }

        // Remove empty lists
        memoryCache.entries.removeIf { it.value.isEmpty() }

        saveToDisk()
        Log.d(TAG, "Expired data cleanup complete")
    }

    /**
     * Clear all cache
     */
    fun clearAll() {
        memoryCache.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "All cache cleared")
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): String {
        val totalAps = memoryCache.size
        val totalRecords = memoryCache.values.sumOf { it.size }
        return "AP count: $totalAps, Total records: $totalRecords"
    }

    // ---------- Private Methods ----------

    private fun addRecord(ssid: String, record: ApPerformanceRecord) {
        val list = memoryCache.getOrPut(ssid) { mutableListOf() }
        list.add(record)

        // Limit records per AP, keep newest
        if (list.size > MAX_RECORDS_PER_AP) {
            list.removeAt(0)
        }

        // Periodically save to disk (every 10 records)
        if (list.size % 10 == 0) {
            saveToDisk()
        }
    }

    private fun measureLatencyAsync(): Float? {
        // Simple implementation: ping gateway (may be inaccurate, needs async optimization)
        // Production environments should use better latency measurement
        return try {
            val start = System.currentTimeMillis()
            val reachable = InetAddress.getByName("223.5.5.5").isReachable(1000)
            if (reachable) (System.currentTimeMillis() - start).toFloat() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun isExpired(timestamp: Long): Boolean {
        val expiryTime = System.currentTimeMillis() - (RECORD_EXPIRY_DAYS * 24 * 60 * 60 * 1000)
        return timestamp < expiryTime
    }

    private fun loadFromDisk() {
        try {
            val jsonStr = prefs.getString("performance_cache", null) ?: return
            val loaded: Map<String, List<ApPerformanceRecord>> = json.decodeFromString(jsonStr)
            memoryCache.clear()
            loaded.forEach { (ssid, records) ->
                memoryCache[ssid] = records.toMutableList()
            }
            Log.d(TAG, "Loaded data for ${memoryCache.size} APs from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache: ${e.message}")
        }
    }

    private fun saveToDisk() {
        try {
            val toSave = memoryCache.mapValues { it.value.toList() }
            val jsonStr = json.encodeToString(toSave)
            prefs.edit().putString("performance_cache", jsonStr).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache: ${e.message}")
        }
    }
}
