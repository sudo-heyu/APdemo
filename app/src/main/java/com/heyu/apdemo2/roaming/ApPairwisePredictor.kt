package com.heyu.apdemo2.roaming

/**
 * AP 两两比较预测器接口（new_ap_selection 视频专用模型）
 *
 * 特征集：rssi_a/b, rssi_diff, score_a/b, score_diff, prod_a/b, a_conn, b_conn（共 10 个）
 * 不含 biz/game 特征，不含上/下行分离的连通性字段。
 */
interface ApPairwisePredictor {

    /**
     * 预测 AP A 优于 AP B 的概率
     *
     * @param rssiA  AP A 的原始 RSSI (dBm)
     * @param scoreA AP A 的众包评分 (0–100)
     * @param rssiB  AP B 的原始 RSSI (dBm)
     * @param scoreB AP B 的众包评分 (0–100)
     * @param connA  AP A 是否连通（RSSI 达到阈值）
     * @param connB  AP B 是否连通
     * @param ssidA  仅用于日志（可选）
     * @param ssidB  仅用于日志（可选）
     * @return 概率 [0.0, 1.0]，>0.5 表示 A 更优
     */
    fun predict(
        rssiA: Float, scoreA: Float,
        rssiB: Float, scoreB: Float,
        connA: Boolean, connB: Boolean,
        ssidA: String? = null,
        ssidB: String? = null
    ): Float

    /** 释放模型资源 */
    fun close()
}
