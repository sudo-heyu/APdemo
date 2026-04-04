package com.heyu.apdemo2.roaming

import android.content.Context
import com.heyu.apdemo2.model.AccessPoint
import java.util.Locale

private fun String.htmlEscape(): String = this
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
        private const val BORDA_TOP_K = 5  // Borda 排名保留前 K 个
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
        if (candidates.isEmpty()) {
            logManager.w("候选列表为空，无法选择")
            return null
        }

        logManager.phase("AP选择", "#6A1B9A",
            "${candidates.size}个候选${if (isGameMode) ", 游戏模式" else ""}")

        // 1. Borda 排名过滤
        val filteredAps = filterByBorda(candidates, BORDA_TOP_K)

        if (filteredAps.isEmpty()) {
            logManager.w("Borda 过滤后无可用 AP")
            return null
        }

        if (filteredAps.size == 1) {
            val best = filteredAps.first()
            logManager.phase("最佳AP", "#2E7D32",
                "★ ${best.ssid} (${best.rssi}dBm) — 唯一候选")
            return best
        }

        // 2. Pairwise 比较选出最佳
        val bestAp = selectByPairwiseComparison(filteredAps, isGameMode)

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
        if (candidates.size <= keepTopK) return candidates

        val n = candidates.size
        val points = mutableMapOf<String, Int>()
        val rssiRank = mutableMapOf<String, Int>()
        val scoreRank = mutableMapOf<String, Int>()

        candidates.sortedByDescending { it.rssi }.forEachIndexed { index, ap ->
            points[ap.ssid] = (points[ap.ssid] ?: 0) + (n - index)
            rssiRank[ap.ssid] = index + 1
        }

        candidates.sortedByDescending { apScores[it.ssid] ?: 0f }.forEachIndexed { index, ap ->
            points[ap.ssid] = (points[ap.ssid] ?: 0) + (n - index)
            scoreRank[ap.ssid] = index + 1
        }

        val sortedByBorda = candidates.sortedByDescending { points[it.ssid] ?: 0 }

        // HTML 表格日志
        val sb = StringBuilder()
        sb.append("<table>")
        sb.append("<tr><th>#</th><th>SSID</th><th>RSSI</th><th>R排名</th><th>S排名</th><th>Borda</th></tr>")
        sortedByBorda.forEachIndexed { idx, ap ->
            sb.append("<tr>")
            sb.append("<td>${idx + 1}</td>")
            sb.append("<td>${ap.ssid.htmlEscape()}</td>")
            sb.append("<td>${ap.rssi}dBm</td>")
            sb.append("<td>#${rssiRank[ap.ssid]}</td>")
            sb.append("<td>#${scoreRank[ap.ssid]}</td>")
            sb.append("<td>${points[ap.ssid]}</td>")
            sb.append("</tr>")
        }
        sb.append("</table>")
        logManager.phase("Borda筛选", "#00838F", "${candidates.size}个候选\n$sb")

        val keepCount = if (sortedByBorda.size > 3) sortedByBorda.size - 3 else sortedByBorda.size
        val result = sortedByBorda.take(keepCount.coerceAtLeast(keepTopK))
        logManager.i("Borda: ${candidates.size}个 → 保留Top${result.size}")
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

        val apList = candidates.map { it.ssid }
        val apMap = candidates.associateBy { it.ssid }

        val probSums = mutableMapOf<String, Float>().apply { apList.forEach { put(it, 0f) } }
        val winCounts = mutableMapOf<String, Int>().apply { apList.forEach { put(it, 0) } }

        // 每对只比一次（i < j），胜负互斥
        for (i in apList.indices) {
            for (j in i + 1 until apList.size) {
                val ssidA = apList[i]
                val ssidB = apList[j]
                val apA = apMap[ssidA]!!
                val apB = apMap[ssidB]!!
                val connA = apA.rssi > -90
                val connB = apB.rssi > -90

                val probAWin = model.predict(
                    rssiA = apA.rssi.toFloat(),
                    scoreA = apScores[ssidA] ?: 50f,
                    rssiB = apB.rssi.toFloat(),
                    scoreB = apScores[ssidB] ?: 50f,
                    connDownA = connA, connDownB = connB,
                    connUpA = connA, connUpB = connB,
                    isGame = isGameMode,
                    ssidA = ssidA, ssidB = ssidB
                )

                // A 得 prob，B 得 1-prob，胜负互斥
                probSums[ssidA] = probSums[ssidA]!! + probAWin
                probSums[ssidB] = probSums[ssidB]!! + (1f - probAWin)
                if (probAWin >= 0.5f) {
                    winCounts[ssidA] = winCounts[ssidA]!! + 1
                } else {
                    winCounts[ssidB] = winCounts[ssidB]!! + 1
                }
            }
        }

        val matchesPerAp = apList.size - 1

        // 排序: 1.平均胜率 2.获胜次数 3.众包评分
        val ranked = apList.sortedWith(compareByDescending<String> { ssid ->
            probSums[ssid]!! / matchesPerAp
        }.thenByDescending { ssid ->
            winCounts[ssid]!!
        }.thenByDescending { ssid ->
            apScores[ssid] ?: 0f
        })

        // HTML 表格日志
        val sb = StringBuilder()
        sb.append("<table>")
        sb.append("<tr><th>#</th><th>SSID</th><th>胜率</th><th>胜负</th><th>RSSI</th><th></th></tr>")
        ranked.forEachIndexed { idx, ssid ->
            val ap = apMap[ssid]!!
            val avgP = String.format(Locale.US, "%.2f", probSums[ssid]!! / matchesPerAp)
            val wins = winCounts[ssid]!!
            val losses = matchesPerAp - wins
            val star = if (idx == 0) "★" else ""
            sb.append("<tr>")
            sb.append("<td>${idx + 1}</td>")
            sb.append("<td>${ssid.htmlEscape()}</td>")
            sb.append("<td>$avgP</td>")
            sb.append("<td>${wins}W${losses}L</td>")
            sb.append("<td>${ap.rssi}dBm</td>")
            sb.append("<td>$star</td>")
            sb.append("</tr>")
        }
        sb.append("</table>")
        logManager.phase("Pairwise对战", "#AD1457", "${apList.size}个AP两两比较\n$sb")

        val bestSsid = ranked.firstOrNull()
        val bestAp = bestSsid?.let { apMap[it] }
        if (bestAp != null) {
            logManager.phase("最佳AP", "#2E7D32",
                "★ ${bestAp.ssid} (${bestAp.rssi}dBm)")
        }
        return bestAp
    }

    /**
     * 释放资源
     */
    fun close() {
        model.close()
    }
}
