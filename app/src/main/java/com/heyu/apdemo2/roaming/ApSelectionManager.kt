package com.heyu.apdemo2.roaming

import android.content.Context
import com.heyu.apdemo2.model.AccessPoint
import java.util.Locale

/**
 * AP 选择管理器
 *
 * 实现基于 LightGBM 模型的漫游选网算法：
 * 1. Borda 排名过滤：根据 RSSI 和众包评分进行初步筛选
 * 2. Pairwise 比较：使用 ONNX 模型比较 AP 对
 * 3. 选出最佳 AP
 */
class ApSelectionManager(
    private val model: ApPairwisePredictor,
    private val logManager: RoamingLogManager
) {

    /**
     * 便捷构造：使用默认的 ONNX LightGBM 模型
     */
    constructor(context: Context) : this(
        model = ApRoamingModel(context),
        logManager = RoamingLogManager.getInstance(context)
    )

    companion object {
        private const val TAG = "[ApSelectionManager]"
        private const val BORDA_TOP_K = 3  // Borda 排名保留前 K 个
        private const val SCORE_MAX = 100f
    }

    // AP 众包评分映射（SSID -> 评分）
    // 可以根据实际场景配置不同的评分
    private val apScores: MutableMap<String, Float> = mutableMapOf()

    /**
     * 设置 AP 的众包评分
     */
    fun setApScore(ssid: String, score: Float) {
        apScores[ssid] = score.coerceIn(0f, SCORE_MAX)
    }

    /**
     * 批量设置 AP 评分
     */
    fun setApScores(scores: Map<String, Float>) {
        apScores.clear()
        scores.forEach { (ssid, score) ->
            apScores[ssid] = score.coerceIn(0f, SCORE_MAX)
        }
    }

    /**
     * 从候选 AP 中选择最佳 AP（始终返回绝对最优，不做当前连接过滤）
     *
     * @param candidates 候选 AP 列表
     * @param isGameMode 是否为游戏模式（上行业务）
     * @return 算法选出的最佳 AP，由调用方决定是否切换
     */
    fun selectBestAp(
        candidates: List<AccessPoint>,
        isGameMode: Boolean = false
    ): AccessPoint? {
        logManager.i("【AP选择算法开始】候选AP数量: ${candidates.size}, 游戏模式: $isGameMode")

        if (candidates.isEmpty()) {
            logManager.w("候选列表为空，无法选择")
            return null
        }

        // 打印所有候选AP信息
        logManager.d("【候选AP列表】")
        candidates.forEachIndexed { index, ap ->
            val score = apScores[ap.ssid] ?: 50f
            logManager.d("  [$index] SSID: ${ap.ssid}, BSSID: ${ap.bssid}, RSSI: ${ap.rssi}dBm, 众包评分: $score")
        }

        // 1. Borda 排名过滤
        val filteredAps = filterByBorda(candidates, BORDA_TOP_K)
        logManager.i("【Borda过滤后】保留 ${filteredAps.size} 个 AP: ${filteredAps.map { it.ssid }}")

        if (filteredAps.isEmpty()) {
            logManager.w("Borda 过滤后无可用 AP")
            return null
        }

        // 如果只有一个候选，直接返回
        if (filteredAps.size == 1) {
            val best = filteredAps.first()
            logManager.i("【选择结果】唯一候选AP: ${best.ssid}")
            return best
        }

        // 2. Pairwise 比较选出最佳
        val bestAp = selectByPairwiseComparison(filteredAps, isGameMode)

        logManager.i("【选择结果】最佳AP: ${bestAp?.ssid}, BSSID: ${bestAp?.bssid}, RSSI: ${bestAp?.rssi}dBm")
        return bestAp
    }

    /**
     * Borda 排名过滤
     * 根据 RSSI 和众包评分进行排名，综合得分高的 AP 获得更多点数
     */
    private fun filterByBorda(
        candidates: List<AccessPoint>,
        keepTopK: Int
    ): List<AccessPoint> {
        logManager.d("【Borda排名计算】")

        if (candidates.size <= keepTopK) {
            logManager.d("候选数量(${candidates.size}) <= keepTopK($keepTopK)，全部保留")
            return candidates
        }

        val n = candidates.size
        val points = mutableMapOf<String, Int>()

        // 按 RSSI 排序（降序）
        val sortedByRssi = candidates.sortedByDescending { it.rssi }
        logManager.d("按RSSI排序: ${sortedByRssi.map { "${it.ssid}(${it.rssi}dBm)" }}")
        sortedByRssi.forEachIndexed { index, ap ->
            val point = n - index
            points[ap.ssid] = (points[ap.ssid] ?: 0) + point
            logManager.d("  ${ap.ssid}: RSSI排名#${index + 1}, 获得${point}分, 累计=${points[ap.ssid]}")
        }

        // 按众包评分排序（降序）
        val sortedByScore = candidates.sortedByDescending { ap ->
            apScores[ap.ssid] ?: 0f
        }
        logManager.d("按众包评分排序: ${sortedByScore.map { "${it.ssid}(${apScores[it.ssid] ?: 50f}分)" }}")
        sortedByScore.forEachIndexed { index, ap ->
            val point = n - index
            points[ap.ssid] = (points[ap.ssid] ?: 0) + point
            logManager.d("  ${ap.ssid}: 评分排名#${index + 1}, 获得${point}分, 累计=${points[ap.ssid]}")
        }

        // 按 Borda 点数排序，取前 keepTopK
        val sortedByBorda = candidates.sortedByDescending { points[it.ssid] ?: 0 }
        logManager.d("Borda总得分: ${sortedByBorda.map { "${it.ssid}=${points[it.ssid]}" }}")

        // 去掉排名靠后的（保留前 n-3 或全部）
        val keepCount = if (sortedByBorda.size > 3) sortedByBorda.size - 3 else sortedByBorda.size
        val result = sortedByBorda.take(keepCount.coerceAtLeast(keepTopK))
        logManager.d("Borda过滤结果: ${result.map { it.ssid }}")
        return result
    }

    /**
     * 使用 Pairwise 比较选出最佳 AP
     * 对每对 AP 使用模型预测比较概率，综合得分最高的胜出
     */
    private fun selectByPairwiseComparison(
        candidates: List<AccessPoint>,
        isGameMode: Boolean
    ): AccessPoint? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        logManager.i("【Pairwise比较阶段】候选AP: ${candidates.map { it.ssid }}")

        val apList = candidates.map { it.ssid }
        val apMap = candidates.associateBy { it.ssid }

        // 计算每个 AP 的得分和获胜次数
        val probSums = mutableMapOf<String, Float>().apply {
            apList.forEach { put(it, 0f) }
        }
        val winCounts = mutableMapOf<String, Int>().apply {
            apList.forEach { put(it, 0) }
        }

        // 记录每对比较的详细结果
        val comparisonResults = mutableListOf<String>()

        // 对所有 AP 对进行 pairwise 比较
        logManager.d("【模型推理详情】")
        for (i in apList.indices) {
            for (j in apList.indices) {
                if (i == j) continue

                val ssidA = apList[i]
                val ssidB = apList[j]
                val apA = apMap[ssidA]!!
                val apB = apMap[ssidB]!!

                // 判断连接状态（这里简化为基于 RSSI 的判断）
                val connA = apA.rssi > -90  // RSSI > -90 认为可连接
                val connB = apB.rssi > -90

                val scoreA = apScores[ssidA] ?: 50f
                val scoreB = apScores[ssidB] ?: 50f

                // 使用模型预测
                val prob = model.predict(
                    rssiA = apA.rssi.toFloat(),
                    scoreA = scoreA,
                    rssiB = apB.rssi.toFloat(),
                    scoreB = scoreB,
                    connDownA = connA,
                    connDownB = connB,
                    connUpA = connA,
                    connUpB = connB,
                    isGame = isGameMode,
                    ssidA = ssidA,
                    ssidB = ssidB
                )

                probSums[ssidA] = probSums[ssidA]!! + prob
                if (prob >= 0.5f) {
                    winCounts[ssidA] = winCounts[ssidA]!! + 1
                }

                val resultStr = "${ssidA} vs ${ssidB}: prob=${String.format(Locale.US, "%.4f", prob)}, " +
                        "RSSI(${apA.rssi} vs ${apB.rssi}), " +
                        "Score(${scoreA} vs ${scoreB}), " +
                        "Conn($connA vs $connB), " +
                        "winner=${if (prob >= 0.5f) ssidA else ssidB}"
                comparisonResults.add(resultStr)
                logManager.d("  $resultStr")
            }
        }

        // 打印每个AP的综合统计
        logManager.i("【Pairwise统计结果】")
        val n = apList.size - 1
        apList.forEach { ssid ->
            val avgProb = probSums[ssid]!! / n
            val wins = winCounts[ssid]!!
            val score = apScores[ssid] ?: 50f
            logManager.i("  $ssid: 平均概率=${String.format(Locale.US, "%.4f", avgProb)}, 获胜次数=$wins/$n, 众包评分=$score")
        }

        // 选择综合得分最高的 AP
        // 排序优先级: 1. 平均概率 2. 获胜次数 3. 众包评分
        val bestSsid = apList.maxWithOrNull(compareByDescending<String> { ssid ->
            probSums[ssid]!! / (apList.size - 1)
        }.thenByDescending { ssid ->
            winCounts[ssid]!!
        }.thenByDescending { ssid ->
            apScores[ssid] ?: 0f
        })

        logManager.i("【排序优先级】1.平均概率 2.获胜次数 3.众包评分")
        logManager.i("【最佳AP】SSID: $bestSsid")

        return bestSsid?.let { apMap[it] }
    }

    /**
     * 释放资源
     */
    fun close() {
        model.close()
    }
}
