package com.heyu.apdemo2.roaming

import android.content.Context
import com.heyu.apdemo2.model.AccessPoint
import java.util.Locale

private fun String.htmlEscape(): String = this
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * AP Selection Manager
 *
 * Provides two network selection strategies:
 * - selectBestAp()        ML Roaming: Uses new_ap_selection video model for pairwise comparison
 * - selectBestApByScore() Score Roaming: Directly selects AP with highest crowdsourced score
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
        private const val RSSI_CONNECTED_THRESHOLD = -80  // Below this value is considered not connectable
    private const val RSSI_CANDIDATE_THRESHOLD = -80  // Score roaming: candidates below this value are filtered out
    private const val SCORE_GAP_THRESHOLD = 10f       // Score gap threshold: ≤10 compares RSSI
    private const val SCORE_DEFAULT = 64f              // Default score when no server (training set mean)
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

    // ── ML Roaming ───────────────────────────────────────────────────────────────

    /**
     * ML Roaming: Uses video-specific model for pairwise comparison to select best AP.
     *
     * Algorithm consistent with Python ml_lgb_test.py's select_best_per_position_generic:
     *   permutations bidirectional comparison → sort by average win rate / wins / crowdsourced score
     */
    fun selectBestAp(candidates: List<AccessPoint>): AccessPoint? {
        if (candidates.isEmpty()) {
            logManager.w("Candidate list empty, cannot select")
            return null
        }

        val scored = candidates.filter { apScores.containsKey(it.ssid) }
        if (scored.isEmpty()) {
            logManager.w("No scored APs, cannot select")
            return null
        }

        logManager.phase("ML Selection", "#6A1B9A", "${scored.size} candidates (filtered ${candidates.size - scored.size} unscored APs)")

        if (scored.size == 1) {
            val best = scored.first()
            logManager.phase("Best AP", "#2E7D32", "★ ${best.ssid} (${best.rssi}dBm) — Only candidate")
            return best
        }

        return selectByModelComparison(scored)
    }

    private fun selectByModelComparison(candidates: List<AccessPoint>): AccessPoint? {
        val apList = candidates.map { it.ssid }
        val apMap  = candidates.associateBy { it.ssid }

        val probSums  = mutableMapOf<String, Float>().apply { apList.forEach { put(it, 0f) } }
        val winCounts = mutableMapOf<String, Int>().apply   { apList.forEach { put(it, 0) } }

        // Record detailed log for each pairwise comparison
        val pairwiseLog = StringBuilder()
        pairwiseLog.append("<table><tr><th>AP A</th><th>AP B</th><th>prob(A>B)</th><th>Result</th></tr>")

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

                // Record this comparison detail
                val result = if (prob >= 0.5f) "<span style='color:#4CAF50;font-weight:bold'>A wins</span>" else "<span style='color:#FF9800;font-weight:bold'>B wins</span>"
                val probColor = when {
                    prob >= 0.7 -> "#4CAF50"  // Green: A clear advantage
                    prob <= 0.3 -> "#FF5722"  // Red: B clear advantage
                    else -> "#FFC107"         // Yellow: Close
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
        logManager.phase("Pairwise Details", "#6A1B9A", pairwiseLog.toString())

        val matchesPerAp = apList.size - 1
        val ranked = apList.sortedWith(
            compareByDescending<String> { probSums[it]!! / matchesPerAp }
                .thenByDescending { winCounts[it]!! }
                .thenByDescending { apScores[it] ?: 0f }
        )

        // HTML table log
        val sb = StringBuilder()
        sb.append("<table><tr><th>#</th><th>SSID</th><th>RSSI</th><th>Conn</th><th>Win%</th><th>W/L</th><th>Score</th></tr>")
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
        logManager.phase("Model Pairwise", "#AD1457",
            "${apList.size} APs bidirectional comparison, conn threshold ${RSSI_CONNECTED_THRESHOLD}dBm\n$sb")

        // Select by RSSI threshold fallback: skip APs with RSSI < -80
        val bestAp = ranked.mapNotNull { apMap[it] }
            .firstOrNull { it.rssi >= RSSI_CONNECTED_THRESHOLD }

        if (bestAp != null) {
            val rank = ranked.indexOf(bestAp.ssid) + 1
            val skipped = ranked.takeWhile { apMap[it]?.rssi?.let { r -> r < RSSI_CONNECTED_THRESHOLD } ?: false }.size
            if (skipped > 0) {
                logManager.phase("RSSI Fallback", "#FF9800",
                    "Skipped $skipped low signal APs, selected rank $rank")
            }
            logManager.phase("Best AP (ML)", "#2E7D32",
                "★ ${bestAp.ssid} (${bestAp.rssi}dBm) Score: ${apScores[bestAp.ssid] ?: "none"}")
        } else {
            logManager.w("All candidate APs have RSSI below ${RSSI_CONNECTED_THRESHOLD}dBm, cannot select")
        }
        return bestAp
    }

    // ── Score Roaming ─────────────────────────────────────────────────────────────

    /**
     * Score Roaming: Select the best AP.
     *
     * Selection strategy:
     * 1. Filter out candidates without score and RSSI < -70 dBm
     * 2. Sort by score, take top two
     * 3. Score gap ≤ 10: select the one with higher RSSI
     * 4. Score gap > 10: select the first one
     */
    fun selectBestApByScore(candidates: List<AccessPoint>): AccessPoint? {
        if (candidates.isEmpty()) {
            logManager.w("Candidate list empty, cannot select")
            return null
        }

        // Step 1: Filter APs without score
        val withScore = candidates.filter { apScores.containsKey(it.ssid) }
        if (withScore.isEmpty()) {
            logManager.w("No scored APs, cannot select")
            return null
        }

        // Step 2: Filter candidates with RSSI < -70 dBm
        val filtered = withScore.filter { it.rssi >= RSSI_CANDIDATE_THRESHOLD }
        if (filtered.isEmpty()) {
            logManager.w("All candidates have RSSI below ${RSSI_CANDIDATE_THRESHOLD}dBm, cannot select")
            // Fallback: return highest scored (even if RSSI is low)
            val fallback = withScore.maxByOrNull { apScores[it.ssid]!! }
            if (fallback != null) {
                logManager.phase("Fallback Selection", "#FF9800",
                    "★ ${fallback.ssid} (${fallback.rssi}dBm) Score: ${apScores[fallback.ssid]}")
            }
            return fallback
        }

        logManager.phase("Score Selection", "#0277BD",
            "${filtered.size} candidates (filtered ${candidates.size - withScore.size} unscored, ${withScore.size - filtered.size} low RSSI)")

        if (filtered.size == 1) {
            val best = filtered.first()
            logManager.phase("Best AP", "#2E7D32", "★ ${best.ssid} (${best.rssi}dBm) — Only candidate")
            return best
        }

        // Sort by score descending
        val sorted = filtered.sortedByDescending { apScores[it.ssid]!! }

        // HTML table log
        val sb = StringBuilder()
        sb.append("<table><tr><th>#</th><th>SSID</th><th>RSSI</th><th>Score</th></tr>")
        sorted.forEachIndexed { idx, ap ->
            val score = apScores[ap.ssid]?.let { String.format(Locale.US, "%.0f", it) } ?: "none"
            sb.append("<tr><td>${idx + 1}</td><td>${ap.ssid.htmlEscape()}</td>")
            sb.append("<td>${ap.rssi}dBm</td><td>$score</td></tr>")
        }
        sb.append("</table>")
        logManager.phase("Score Ranking", "#0288D1", sb.toString())

        // Compare top two
        val first = sorted[0]
        val second = sorted[1]
        val firstScore = apScores[first.ssid]!!
        val secondScore = apScores[second.ssid]!!
        val scoreGap = firstScore - secondScore

        val bestAp = if (scoreGap <= SCORE_GAP_THRESHOLD) {
            // Score gap ≤ 10: select the one with higher RSSI
            val chosen = if (first.rssi >= second.rssi) first else second
            logManager.phase("Best AP (Score)", "#2E7D32",
                "★ ${chosen.ssid} (${chosen.rssi}dBm) Score: ${apScores[chosen.ssid]} — Gap ${String.format(Locale.US, "%.0f", scoreGap)}≤${String.format(Locale.US, "%.0f", SCORE_GAP_THRESHOLD)}, selected higher RSSI")
            chosen
        } else {
            // Score gap > 10: select first place
            logManager.phase("Best AP (Score)", "#2E7D32",
                "★ ${first.ssid} (${first.rssi}dBm) Score: $firstScore — Gap ${String.format(Locale.US, "%.0f", scoreGap)}>${String.format(Locale.US, "%.0f", SCORE_GAP_THRESHOLD)}, selected first place")
            first
        }
        return bestAp
    }

    fun close() {
        model.close()
    }
}
