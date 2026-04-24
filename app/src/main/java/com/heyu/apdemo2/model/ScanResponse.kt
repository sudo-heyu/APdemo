package com.heyu.apdemo2.model

/**
 * Scan result upload response model
 */
data class ScanResponse(
    val results: List<ApScoreResult>?,
    val status: String?
)

/**
 * Score result for each SSID
 */
data class ApScoreResult(
    val ssid: String,
    val score: Int?,
    val reason: String?
)
