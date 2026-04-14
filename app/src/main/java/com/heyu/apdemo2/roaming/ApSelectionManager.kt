package com.heyu.apdemo2.roaming

import android.content.Context
import com.heyu.apdemo2.model.AccessPoint
import java.util.Locale

private fun String.htmlEscape(): String = this
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * AP 选择管理器
 *
 * 提供两种选网策略：
 * - selectBestAp()        ML 漫游：使用 new_ap_selection 视频模型进行 pairwise 比较
 * - selectBestApByScore() 评分漫游：直接选众包评分最高的 AP
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
        private const val SCORE_MAX = 100f
        private const val RSSI_CONNECTED_THRESHOLD = -85  // 低于此值视为不可连通
        private const val SCORE_DEFAULT = 64f              // 无服务器时的默认评分（训练集均值）
    }

    private val apScores: MutableMap<String, Float> = mutableMapOf()

    fun setApScore(ssid: String, score: Float) {
        apScores[ssid] = score.coerceIn(0f, SCORE_MAX)
    }

    fun setApScores(scores: Map<String, Float>) {
        scores.forEach { (ssid, score) -> apScores[ssid] = score.coerceIn(0f, SCORE_MAX) }
    }

    fun getApScore(ssid: String): Float? = apScores[ssid]

    // ── ML 漫游 ───────────────────────────────────────────────────────────────

    /**
     * ML 漫游：使用视频专用模型进行 pairwise 比较，选出最优 AP。
     *
     * 算法与 Python ml_lgb_test.py 的 select_best_per_position_generic 一致：
     *   permutations 双向比较 → 按平均胜率 / 胜场 / 众包评分排序
     */
    fun selectBestAp(candidates: List<AccessPoint>): AccessPoint? {
        if (candidates.isEmpty()) {
            logManager.w("候选列表为空，无法选择")
            return null
        }

        logManager.phase("ML选网", "#6A1B9A", "${candidates.size}个候选")

        if (candidates.size == 1) {
            val best = candidates.first()
            logManager.phase("最佳AP", "#2E7D32", "★ ${best.ssid} (${best.rssi}dBm) — 唯一候选")
            return best
        }

        return selectByModelComparison(candidates)
    }

    private fun selectByModelComparison(candidates: List<AccessPoint>): AccessPoint? {
        val apList = candidates.map { it.ssid }
        val apMap  = candidates.associateBy { it.ssid }

        val probSums  = mutableMapOf<String, Float>().apply { apList.forEach { put(it, 0f) } }
        val winCounts = mutableMapOf<String, Int>().apply   { apList.forEach { put(it, 0) } }

        // 记录每次Pairwise比较的详细日志
        val pairwiseLog = StringBuilder()
        pairwiseLog.append("<table><tr><th>AP A</th><th>AP B</th><th>prob(A>B)</th><th>结果</th></tr>")

        for (i in apList.indices) {
            for (j in apList.indices) {
                if (i == j) continue
                val ssidA = apList[i]; val ssidB = apList[j]
                val apA   = apMap[ssidA]!!; val apB = apMap[ssidB]!!

                val scoreA = apScores[ssidA] ?: SCORE_DEFAULT
                val scoreB = apScores[ssidB] ?: SCORE_DEFAULT
                val connA  = apA.rssi >= RSSI_CONNECTED_THRESHOLD
                val connB  = apB.rssi >= RSSI_CONNECTED_THRESHOLD

                val prob = model.predict(
                    rssiA = apA.rssi.toFloat(), scoreA = scoreA,
                    rssiB = apB.rssi.toFloat(), scoreB = scoreB,
                    connA = connA, connB = connB,
                    ssidA = ssidA, ssidB = ssidB
                )

                probSums[ssidA] = probSums[ssidA]!! + prob
                if (prob >= 0.5f) winCounts[ssidA] = winCounts[ssidA]!! + 1

                // 记录本次比较详情
                val result = if (prob >= 0.5f) "<span style='color:#4CAF50;font-weight:bold'>A胜</span>" else "<span style='color:#FF9800;font-weight:bold'>B胜</span>"
                val probColor = when {
                    prob >= 0.7 -> "#4CAF50"  // 绿色：A明显优势
                    prob <= 0.3 -> "#FF5722"  // 红色：B明显优势
                    else -> "#FFC107"         // 黄色：接近
                }
                pairwiseLog.append("<tr>")
                pairwiseLog.append("<td><b>${ssidA.htmlEscape()}</b></td>")
                pairwiseLog.append("<td><b>${ssidB.htmlEscape()}</b></td>")
                pairwiseLog.append("<td style='color:$probColor;font-weight:bold'>${String.format(Locale.US, "%.3f", prob)}</td>")
                pairwiseLog.append("<td>$result</td>")
                pairwiseLog.append("</tr>")
            }
        }
        pairwiseLog.append("</table>")
        logManager.phase("Pairwise明细", "#6A1B9A", pairwiseLog.toString())

        val matchesPerAp = apList.size - 1
        val ranked = apList.sortedWith(
            compareByDescending<String> { probSums[it]!! / matchesPerAp }
                .thenByDescending { winCounts[it]!! }
                .thenByDescending { apScores[it] ?: 0f }
        )

        // HTML 表格日志
        val sb = StringBuilder()
        sb.append("<table><tr><th>#</th><th>SSID</th><th>RSSI</th><th>连通</th><th>胜率</th><th>胜负</th><th>评分</th></tr>")
        ranked.forEachIndexed { idx, ssid ->
            val ap   = apMap[ssid]!!
            val avgP = String.format(Locale.US, "%.2f", probSums[ssid]!! / matchesPerAp)
            val wins = winCounts[ssid]!!
            val losses = matchesPerAp - wins
            val score  = apScores[ssid]?.let { String.format(Locale.US, "%.0f", it) } ?: "—"
            val conn   = if (ap.rssi >= RSSI_CONNECTED_THRESHOLD) "✓" else "✗"
            sb.append("<tr><td>${idx + 1}</td><td>${ssid.htmlEscape()}</td>")
            sb.append("<td>${ap.rssi}dBm</td><td>$conn</td>")
            sb.append("<td>$avgP</td><td>${wins}W${losses}L</td><td>$score</td></tr>")
        }
        sb.append("</table>")
        logManager.phase("模型Pairwise", "#AD1457",
            "${apList.size}个AP双向比较, 连通阈值${RSSI_CONNECTED_THRESHOLD}dBm\n$sb")

        val bestAp = ranked.firstOrNull()?.let { apMap[it] }
        if (bestAp != null) {
            logManager.phase("最佳AP(ML)", "#2E7D32",
                "★ ${bestAp.ssid} (${bestAp.rssi}dBm) 评分: ${apScores[bestAp.ssid] ?: "无"}")
        }
        return bestAp
    }

    // ── 评分漫游 ─────────────────────────────────────────────────────────────

    /**
     * 评分漫游：直接选众包评分最高的 AP，评分相同时按 RSSI 降序。
     * 无评分的 AP 评分视为 -1（排在最后）。
     */
    fun selectBestApByScore(candidates: List<AccessPoint>): AccessPoint? {
        if (candidates.isEmpty()) {
            logManager.w("候选列表为空，无法选择")
            return null
        }

        logManager.phase("评分选网", "#0277BD", "${candidates.size}个候选")

        if (candidates.size == 1) {
            val best = candidates.first()
            logManager.phase("最佳AP", "#2E7D32", "★ ${best.ssid} (${best.rssi}dBm) — 唯一候选")
            return best
        }

        val sorted = candidates.sortedWith(
            compareByDescending<AccessPoint> { apScores[it.ssid] ?: -1f }
                .thenByDescending { it.rssi }
        )

        // HTML 表格日志
        val sb = StringBuilder()
        sb.append("<table><tr><th>#</th><th>SSID</th><th>RSSI</th><th>评分</th></tr>")
        sorted.forEachIndexed { idx, ap ->
            val score = apScores[ap.ssid]?.let { String.format(Locale.US, "%.0f", it) } ?: "无"
            sb.append("<tr><td>${idx + 1}</td><td>${ap.ssid.htmlEscape()}</td>")
            sb.append("<td>${ap.rssi}dBm</td><td>$score</td></tr>")
        }
        sb.append("</table>")
        logManager.phase("评分排名", "#0288D1", sb.toString())

        val bestAp = sorted.first()
        logManager.phase("最佳AP(评分)", "#2E7D32",
            "★ ${bestAp.ssid} (${bestAp.rssi}dBm) 评分: ${apScores[bestAp.ssid] ?: "无"}")
        return bestAp
    }

    fun close() {
        model.close()
    }
}
