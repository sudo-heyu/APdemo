package com.heyu.apdemo2.roaming

/**
 * AP 两两比较预测器接口
 *
 * 选网模块通过此接口调用模型，不关心底层实现（LightGBM / DNN / 规则引擎等）。
 * 切换模型只需提供新的实现类，选网逻辑和连接流程无需改动。
 */
interface ApPairwisePredictor {

    /**
     * 预测 AP A 优于 AP B 的概率
     *
     * @param rssiA AP A 的原始 RSSI (dBm)
     * @param scoreA AP A 的众包评分 (0-100)
     * @param rssiB AP B 的原始 RSSI (dBm)
     * @param scoreB AP B 的众包评分 (0-100)
     * @param connDownA A 的下行是否可用
     * @param connDownB B 的下行是否可用
     * @param connUpA A 的上行是否可用
     * @param connUpB B 的上行是否可用
     * @param isGame 是否为游戏/上行业务
     * @param ssidA 用于日志（可选）
     * @param ssidB 用于日志（可选）
     * @return 概率 [0.0, 1.0]，>0.5 表示 A 更优
     */
    fun predict(
        rssiA: Float, scoreA: Float,
        rssiB: Float, scoreB: Float,
        connDownA: Boolean, connDownB: Boolean,
        connUpA: Boolean, connUpB: Boolean,
        isGame: Boolean = false,
        ssidA: String? = null,
        ssidB: String? = null
    ): Float

    /** 释放模型资源 */
    fun close()
}
