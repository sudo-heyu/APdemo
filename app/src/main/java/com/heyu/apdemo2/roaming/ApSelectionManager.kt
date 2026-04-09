package com.heyu.apdemo2.roaming

import android.content.Context
import com.heyu.apdemo2.model.AccessPoint
import java.util.Locale

private fun String.htmlEscape(): String = this
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * AP 选择管理器
 *
 * 实现与 Python 端 ml_lgb_test.py 一致的漫游选网算法：
 * 1. 动态计算 RSSI 归一化范围（从候选集非零 RSSI 计算 min/max）
 * 2. Pairwise 双向比较（permutations）：使用 ONNX 模型比较每对 AP 的两个方向
 * 3. 按平均胜率 → 获胜次数 → 众包评分排序，选出最佳 AP
 */
class ApSelectionManager(
    private val model: ApPairwisePredictor,
    private val logManager: RoamingLogManager
) {

    constructor(context: Context) : this(
        model = ApRoamingModel(context),
        logManager = RoamingLogManager.getInstance(context)
    )

    companion object {
        private const val TAG = "[ApSelectionManager]"
        private const val SCORE_MAX = 100f
        private const val RSSI_CONNECTED_THRESHOLD = -85  // RSSI 低于此值视为不可连通

        // 固定 RSSI 归一化范围，与训练数据分布一致
        // 训练数据中 RSSI 通常分布在 -85 到 -40 dBm 之间
        private const val RSSI_MIN = -85f
        private const val RSSI_MAX = -40f
        private const val SCORE_DEFAULT = 64f  // 训练集均值（归一化后 0.641×100），无服务器模式下使用
    }

    // AP 众包评分映射（SSID -> 评分），与 Python 端 ap_scores_video / ap_scores_game 对应
    private val apScores: MutableMap<String, Float> = mutableMapOf()

    fun setApScore(ssid: String, score: Float) {
        apScores[ssid] = score.coerceIn(0f, SCORE_MAX)
    }

    fun setApScores(scores: Map<String, Float>) {
        apScores.clear()
        scores.forEach { (ssid, score) ->
            apScores[ssid] = score.coerceIn(0f, SCORE_MAX)
        }
    }

    /**
     * 从候选 AP 中选择最佳 AP
     *
     * 算法流程与 Python ml_lgb_test.py 的 select_best_per_position_generic 一致：
     * 1. 从候选集动态计算 RSSI 归一化范围
     * 2. 使用 permutations 进行双向 pairwise 比较
     * 3. 按 prob_sums/matches → win_counts → ap_scores 排序
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

        if (candidates.size == 1) {
            val best = candidates.first()
            logManager.phase("最佳AP", "#2E7D32",
                "★ ${best.ssid} (${best.rssi}dBm) — 唯一候选")
            return best
        }

        // 动态计算 RSSI 归一化范围（与 Python calc_test_rssi_range 一致）
        val (rssiMin, rssiMax) = calcRssiRange(candidates)
        logManager.i("RSSI 归一化范围: min=${rssiMin}, max=${rssiMax}")

        // Pairwise 比较选出最佳（与 Python select_best_per_position_generic 一致）
        return selectByModelComparison(candidates, isGameMode, rssiMin, rssiMax)
    }

    /**
     * 返回固定的 RSSI 归一化范围
     * 与训练数据分布一致，避免候选集范围过窄导致归一化失真
     */
    private fun calcRssiRange(candidates: List<AccessPoint>): Pair<Float, Float> {
        return Pair(RSSI_MIN, RSSI_MAX)
    }

    /**
     * 使用 LightGBM 模型进行 Pairwise 比较
     *
     * 与 Python ml_lgb_test.py 的核心逻辑对齐：
     * - 使用 permutations（双向比较），而非单向 + 1-prob
     * - 使用众包评分 apScores 作为模型输入
     * - 扫描可见的 AP 均视为已连接（connDown=true, connUp=true），
     *   与训练数据中 rssi!=0 即为 connected 的语义一致
     */
    private fun selectByModelComparison(
        candidates: List<AccessPoint>,
        isGameMode: Boolean,
        rssiMin: Float,
        rssiMax: Float
    ): AccessPoint? {
        val apList = candidates.map { it.ssid }
        val apMap = candidates.associateBy { it.ssid }

        val probSums = mutableMapOf<String, Float>().apply { apList.forEach { put(it, 0f) } }
        val winCounts = mutableMapOf<String, Int>().apply { apList.forEach { put(it, 0) } }

        val bizFlag = isGameMode

        // permutations：双向比较，与 Python itertools.permutations 一致
        for (i in apList.indices) {
            for (j in apList.indices) {
                if (i == j) continue
                val ssidA = apList[i]
                val ssidB = apList[j]
                val apA = apMap[ssidA]!!
                val apB = apMap[ssidB]!!

                // 众包评分（与 Python ap_scores.get(ap_id, 0) 一致）
                val scoreA = apScores[ssidA] ?: SCORE_DEFAULT
                val scoreB = apScores[ssidB] ?: SCORE_DEFAULT

                // 下行连通性：RSSI >= -80 dBm 视为连通
                // 上行连通性：仅游戏模式（biz=1）有上行测量数据，视频模式训练时恒为 0
                val connA = apA.rssi >= RSSI_CONNECTED_THRESHOLD
                val connB = apB.rssi >= RSSI_CONNECTED_THRESHOLD

                val prob = model.predict(
                    rssiA = apA.rssi.toFloat(),
                    scoreA = scoreA,
                    rssiB = apB.rssi.toFloat(),
                    scoreB = scoreB,
                    connDownA = connA,
                    connDownB = connB,
                    connUpA = if (isGameMode) connA else false,
                    connUpB = if (isGameMode) connB else false,
                    isGame = bizFlag,
                    rssiMin = rssiMin,
                    rssiMax = rssiMax,
                    ssidA = ssidA,
                    ssidB = ssidB
                )

                probSums[ssidA] = probSums[ssidA]!! + prob
                if (prob >= 0.5f) {
                    winCounts[ssidA] = winCounts[ssidA]!! + 1
                }
            }
        }

        val matchesPerAp = apList.size - 1

        // 排序：与 Python max(key=lambda: (prob_sums/matches, win_counts, ap_scores)) 一致
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
        sb.append("<tr><th>#</th><th>SSID</th><th>RSSI</th><th>连通</th><th>胜率</th><th>胜负</th><th>众包评分</th></tr>")
        ranked.forEachIndexed { idx, ssid ->
            val ap = apMap[ssid]!!
            val avgP = String.format(Locale.US, "%.2f", probSums[ssid]!! / matchesPerAp)
            val wins = winCounts[ssid]!!
            val losses = matchesPerAp - wins
            val score = apScores[ssid]?.let { String.format(Locale.US, "%.0f", it) } ?: "0"
            val conn = if (ap.rssi >= RSSI_CONNECTED_THRESHOLD) "✓" else "✗"
            sb.append("<tr>")
            sb.append("<td>${idx + 1}</td>")
            sb.append("<td>${ssid.htmlEscape()}</td>")
            sb.append("<td>${ap.rssi}dBm</td>")
            sb.append("<td>$conn</td>")
            sb.append("<td>$avgP</td>")
            sb.append("<td>${wins}W${losses}L</td>")
            sb.append("<td>$score</td>")
            sb.append("</tr>")
        }
        sb.append("</table>")
        logManager.phase("模型Pairwise", "#AD1457",
            "${apList.size}个AP双向比较, RSSI范围[${rssiMin.toInt()},${rssiMax.toInt()}], 连通阈值${RSSI_CONNECTED_THRESHOLD}dBm\n$sb")

        val bestSsid = ranked.firstOrNull()
        val bestAp = bestSsid?.let { apMap[it] }
        if (bestAp != null) {
            logManager.phase("最佳AP", "#2E7D32",
                "★ ${bestAp.ssid} (${bestAp.rssi}dBm) 众包评分: ${apScores[bestAp.ssid] ?: "无"}")
        }
        return bestAp
    }

    fun close() {
        model.close()
    }
}
