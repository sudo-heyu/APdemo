package com.heyu.apdemo2.roaming

/**
 * Roaming Strategy Mode
 *
 * ML    - Uses video-specific model under new_ap_selection (pairwise comparison)
 * SCORE - Selects AP with highest score based solely on backend crowdsourced scores
 */
enum class RoamingMode {
    ML,
    SCORE
}
