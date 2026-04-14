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
 * AP 性能历史记录
 *
 * 本地缓存用户实际连接过的 AP 的性能数据，用于辅助选网决策
 */
@Serializable
data class ApPerformanceRecord(
    val ssid: String,
    val rssi: Int,
    val timestamp: Long,
    val rxSpeedKbps: Float,      // 接收速率 KB/s
    val txSpeedKbps: Float,      // 发送速率 KB/s
    val latencyMs: Float?,       // ping 延迟 ms
    val isConnected: Boolean     // 当时是否真正有数据传输
)

/**
 * AP 性能统计摘要
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
     * 综合性能评分 (0-100)
     * 基于历史吞吐量、延迟、信号强度计算
     */
    fun calculateScore(): Float {
        // 吞吐量权重 40%，延迟权重 30%，RSSI 权重 30%
        val throughputScore = (avgRxSpeed + avgTxSpeed).coerceIn(0f, 2000f) / 20f  // 假设 2000KB/s = 100分

        val latencyScore = avgLatency?.let { lat ->
            when {
                lat < 20 -> 100f
                lat < 50 -> 80f
                lat < 100 -> 60f
                lat < 200 -> 40f
                else -> 20f
            }
        } ?: 50f  // 无延迟数据给中等分

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
 * AP 性能缓存管理器
 *
 * 自动采样当前连接 AP 的性能，存储历史数据
 */
class ApPerformanceCache private constructor(context: Context) {

    companion object {
        private const val TAG = "[ApPerformanceCache]"
        private const val PREFS_NAME = "ap_performance_cache"
        private const val MAX_RECORDS_PER_AP = 100     // 每个 AP 最多保留记录数
        private const val RECORD_EXPIRY_DAYS = 30      // 记录过期时间（天）
        private const val SAMPLE_INTERVAL_MS = 5000L   // 采样间隔 5 秒
        private const val MIN_DATA_THRESHOLD = 1024L   // 最小数据变化阈值 1KB

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

    // 内存缓存
    private val memoryCache = ConcurrentHashMap<String, MutableList<ApPerformanceRecord>>()

    // 采样状态
    private var lastSampleTime = 0L
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var currentSsid: String? = null
    private var currentRssi = 0

    init {
        loadFromDisk()
    }

    /**
     * 开始监控指定 AP 的性能
     */
    fun startMonitoring(ssid: String, rssi: Int) {
        currentSsid = ssid
        currentRssi = rssi
        lastSampleTime = System.currentTimeMillis()
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        Log.d(TAG, "开始监控 AP: $ssid, RSSI: $rssi")
    }

    /**
     * 停止监控
     */
    fun stopMonitoring() {
        currentSsid?.let {
            Log.d(TAG, "停止监控 AP: $it")
        }
        currentSsid = null
        lastSampleTime = 0L
    }

    /**
     * 更新当前 RSSI（AP 切换或信号变化时调用）
     */
    fun updateRssi(rssi: Int) {
        currentRssi = rssi
    }

    /**
     * 采样当前性能（应在后台线程定期调用）
     */
    fun sample(): ApPerformanceRecord? {
        val ssid = currentSsid ?: return null
        val now = System.currentTimeMillis()

        // 检查采样间隔
        if (now - lastSampleTime < SAMPLE_INTERVAL_MS) {
            return null
        }

        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val elapsedSec = (now - lastSampleTime) / 1000f

        // 计算速率
        val rxDiff = currentRx - lastRxBytes
        val txDiff = currentTx - lastTxBytes
        val rxSpeed = if (elapsedSec > 0) (rxDiff / 1024f / elapsedSec) else 0f  // KB/s
        val txSpeed = if (elapsedSec > 0) (txDiff / 1024f / elapsedSec) else 0f

        // 判断是否有有效数据传输
        val isEffectivelyConnected = rxDiff > MIN_DATA_THRESHOLD || txDiff > MIN_DATA_THRESHOLD

        // 测量延迟（可选，异步执行）
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

        // 保存记录
        addRecord(ssid, record)

        // 更新状态
        lastSampleTime = now
        lastRxBytes = currentRx
        lastTxBytes = currentTx

        Log.v(TAG, "采样 $ssid: rx=${rxSpeed.toInt()}KB/s, tx=${txSpeed.toInt()}KB/s, " +
                "latency=${latency?.toInt() ?: "N/A"}ms, connected=$isEffectivelyConnected")

        return record
    }

    /**
     * 获取指定 AP 的性能统计
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
     * 获取所有已知 AP 的性能评分
     */
    fun getAllPerformanceScores(): Map<String, Float> {
        return memoryCache.keys.mapNotNull { ssid ->
            getPerformanceSummary(ssid)?.let { summary ->
                ssid to summary.calculateScore()
            }
        }.toMap()
    }

    /**
     * 检查是否有某个 AP 的历史性能数据
     */
    fun hasPerformanceData(ssid: String): Boolean {
        return memoryCache[ssid]?.any { !isExpired(it.timestamp) } ?: false
    }

    /**
     * 获取 AP 的连接状态（基于历史数据）
     */
    fun isLikelyConnectable(ssid: String): Boolean {
        // 当前正在监控的 AP（已连接）直接返回 true
        if (ssid == currentSsid) return true

        val records = memoryCache[ssid] ?: return false
        val recentRecords = records.filter { !isExpired(it.timestamp) }
        if (recentRecords.isEmpty()) return false

        // 如果最近 50% 的采样显示有数据传输，则认为可连接
        val connectedRatio = recentRecords.count { it.isConnected }.toFloat() / recentRecords.size
        return connectedRatio > 0.5f
    }

    /**
     * 清理过期数据
     */
    fun cleanupExpired() {
        val expiryTime = System.currentTimeMillis() - (RECORD_EXPIRY_DAYS * 24 * 60 * 60 * 1000)

        memoryCache.forEach { (ssid, records) ->
            val valid = records.filter { it.timestamp > expiryTime }
            if (valid.size != records.size) {
                memoryCache[ssid] = valid.toMutableList()
            }
        }

        // 移除空列表
        memoryCache.entries.removeIf { it.value.isEmpty() }

        saveToDisk()
        Log.d(TAG, "清理过期数据完成")
    }

    /**
     * 清除所有缓存
     */
    fun clearAll() {
        memoryCache.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "清除所有缓存")
    }

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): String {
        val totalAps = memoryCache.size
        val totalRecords = memoryCache.values.sumOf { it.size }
        return "AP数量: $totalAps, 总记录: $totalRecords"
    }

    // ---------- 私有方法 ----------

    private fun addRecord(ssid: String, record: ApPerformanceRecord) {
        val list = memoryCache.getOrPut(ssid) { mutableListOf() }
        list.add(record)

        // 限制每个 AP 的记录数，保留最新的
        if (list.size > MAX_RECORDS_PER_AP) {
            list.removeAt(0)
        }

        // 定期保存到磁盘（每 10 条记录保存一次）
        if (list.size % 10 == 0) {
            saveToDisk()
        }
    }

    private fun measureLatencyAsync(): Float? {
        // 简单实现：ping 网关（可能不准确，需要异步优化）
        // 实际生产环境建议使用更好的延迟测量方式
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
            Log.d(TAG, "从磁盘加载 ${memoryCache.size} 个 AP 的数据")
        } catch (e: Exception) {
            Log.e(TAG, "加载缓存失败: ${e.message}")
        }
    }

    private fun saveToDisk() {
        try {
            val toSave = memoryCache.mapValues { it.value.toList() }
            val jsonStr = json.encodeToString(toSave)
            prefs.edit().putString("performance_cache", jsonStr).apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存缓存失败: ${e.message}")
        }
    }
}
