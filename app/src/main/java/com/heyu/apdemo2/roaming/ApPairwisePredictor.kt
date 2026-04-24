package com.heyu.apdemo2.roaming

/**
 * AP Pairwise Comparison Predictor Interface (new_ap_selection video-specific model)
 *
 * Feature set: rssi_a/b, rssi_diff, score_a/b, score_diff, prod_a/b, a_conn, b_conn (10 total)
 * Does not include biz/game features, nor uplink/downlink separated connectivity fields.
 */
interface ApPairwisePredictor {

    /**
     * Predict probability that AP A is better than AP B
     *
     * @param rssiA  Raw RSSI of AP A (dBm)
     * @param scoreA Crowdsourced score of AP A (0–100)
     * @param rssiB  Raw RSSI of AP B (dBm)
     * @param scoreB Crowdsourced score of AP B (0–100)
     * @param connA  Whether AP A is connectable (RSSI reaches threshold)
     * @param connB  Whether AP B is connectable
     * @param ssidA  Only for logging (optional)
     * @param ssidB  Only for logging (optional)
     * @return Probability [0.0, 1.0], >0.5 means A is better
     */
    fun predict(
        rssiA: Float, scoreA: Float,
        rssiB: Float, scoreB: Float,
        connA: Boolean, connB: Boolean,
        ssidA: String? = null,
        ssidB: String? = null
    ): Float

    /** Release model resources */
    fun close()
}
