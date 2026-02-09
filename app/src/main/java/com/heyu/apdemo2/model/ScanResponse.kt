package com.heyu.apdemo2.model

/**
 * 扫描结果上传后的响应模型
 */
data class ScanResponse(
    val results: List<ApScoreResult>?,
    val status: String?
)

/**
 * 每个 SSID 的打分结果
 */
data class ApScoreResult(
    val ssid: String,
    val score: Int?,
    val reason: String?
)
