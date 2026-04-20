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
        private const val RSSI_CONNECTED_THRESHOLD = -80  // 低于此值视为不可连通
    private const val RSSI_CANDIDATE_THRESHOLD = -80  // 评分漫游：低于此值的候选直接过滤
    private const val SCORE_GAP_THRESHOLD = 10f       // 分差阈值：≤10时比较RSSI
    private const val SCORE_DEFAULT = 64f              // 无服务器时的默认评分（训练集均值）
}

private val apScores: MutableMap<String, Float> = mutableMapOf()
private val apReasons: MutableMap<String, String> = mutableMapOf()

    fun setApScore(ssid: String, score: Float) {
        apScores[ssid] = score.coerceIn(0f, SCORE_MAX)
    }

    fun setApScore(ssid: String, score: Float, reason: String?) {
        apScores[ssid] = score.coerceIn(0f, SCORE_MAX)
        if (reason != null) apReasons[ssid] = reason
    }

    fun setApScores(scores: Map<String, Float>) {
        scores.forEach { (ssid, score) -> apScores[ssid] = score.coerceIn(0f, SCORE_MAX) }
    }

    fun getApScore(ssid: String): Float? = apScores[ssid]

    fun getApReason(ssid: String): String? = apReasons[ssid]

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

        val scored = candidates.filter { apScores.containsKey(it.ssid) }
        if (scored.isEmpty()) {
            logManager.w("无评分AP，无法选择")
            return null
        }

        logManager.phase("ML选网", "#6A1B9A", "${scored.size}个候选（已过滤${candidates.size - scored.size}个无评分AP）")

        if (scored.size == 1) {
            val best = scored.first()
            logManager.phase("最佳AP", "#2E7D32", "★ ${best.ssid} (${best.rssi}dBm) — 唯一候选")
            return best
        }

        return selectByModelComparison(scored)
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

        // 按 RSSI 阈值顺延选择：跳过 RSSI < -80 的 AP
        val bestAp = ranked.mapNotNull { apMap[it] }
            .firstOrNull { it.rssi >= RSSI_CONNECTED_THRESHOLD }

        if (bestAp != null) {
            val rank = ranked.indexOf(bestAp.ssid) + 1
            val skipped = ranked.takeWhile { apMap[it]?.rssi?.let { r -> r < RSSI_CONNECTED_THRESHOLD } ?: false }.size
            if (skipped > 0) {
                logManager.phase("RSSI顺延", "#FF9800",
                    "跳过${skipped}个低信号AP，选择第${rank}名")
            }
            logManager.phase("最佳AP(ML)", "#2E7D32",
                "★ ${bestAp.ssid} (${bestAp.rssi}dBm) 评分: ${apScores[bestAp.ssid] ?: "无"}")
        } else {
            logManager.w("所有候选AP的RSSI均低于${RSSI_CONNECTED_THRESHOLD}dBm，无法选择")
        }
        return bestAp
    }

    // ── 评分漫游 ─────────────────────────────────────────────────────────────

    /**
     * 评分漫游：选择最优 AP。
     *
     * 选择策略：
     * 1. 过滤掉无评分和 RSSI < -70 dBm 的候选
     * 2. 按评分排序，取前两名
     * 3. 分差 ≤ 10：选 RSSI 更高的
     * 4. 分差 > 10：选第一名
     */
    fun selectBestApByScore(candidates: List<AccessPoint>): AccessPoint? {
        if (candidates.isEmpty()) {
            logManager.w("候选列表为空，无法选择")
            return null
        }

        // 第一步：过滤无评分的AP
        val withScore = candidates.filter { apScores.containsKey(it.ssid) }
        if (withScore.isEmpty()) {
            logManager.w("无评分AP，无法选择")
            return null
        }

        // 第二步：过滤 RSSI < -70 dBm 的候选
        val filtered = withScore.filter { it.rssi >= RSSI_CANDIDATE_THRESHOLD }
        if (filtered.isEmpty()) {
            logManager.w("所有候选RSSI均低于${RSSI_CANDIDATE_THRESHOLD}dBm，无法选择")
            // 降级：返回评分最高的（即使RSSI低）
            val fallback = withScore.maxByOrNull { apScores[it.ssid]!! }
            if (fallback != null) {
                logManager.phase("降级选择", "#FF9800",
                    "★ ${fallback.ssid} (${fallback.rssi}dBm) 评分: ${apScores[fallback.ssid]}")
            }
            return fallback
        }

        logManager.phase("评分选网", "#0277BD",
            "${filtered.size}个候选（已过滤${candidates.size - withScore.size}个无评分, ${withScore.size - filtered.size}个低RSSI）")

        if (filtered.size == 1) {
            val best = filtered.first()
            logManager.phase("最佳AP", "#2E7D32", "★ ${best.ssid} (${best.rssi}dBm) — 唯一候选")
            return best
        }

        // 按评分降序排序
        val sorted = filtered.sortedByDescending { apScores[it.ssid]!! }

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

        // 取前两名比较
        val first = sorted[0]
        val second = sorted[1]
        val firstScore = apScores[first.ssid]!!
        val secondScore = apScores[second.ssid]!!
        val scoreGap = firstScore - secondScore

        val bestAp = if (scoreGap <= SCORE_GAP_THRESHOLD) {
            // 分差 ≤ 10：选 RSSI 更高的
            val chosen = if (first.rssi >= second.rssi) first else second
            logManager.phase("最佳AP(评分)", "#2E7D32",
                "★ ${chosen.ssid} (${chosen.rssi}dBm) 评分: ${apScores[chosen.ssid]} — 分差${String.format(Locale.US, "%.0f", scoreGap)}≤${String.format(Locale.US, "%.0f", SCORE_GAP_THRESHOLD)}，选RSSI高者")
            chosen
        } else {
            // 分差 > 10：选第一名
            logManager.phase("最佳AP(评分)", "#2E7D32",
                "★ ${first.ssid} (${first.rssi}dBm) 评分: $firstScore — 分差${String.format(Locale.US, "%.0f", scoreGap)}>${String.format(Locale.US, "%.0f", SCORE_GAP_THRESHOLD)}，选第一名")
            first
        }
        return bestAp
    }

    fun close() {
        model.close()
    }
}
