package com.heyu.apdemo2.roaming

/**
 * 漫游策略模式
 *
 * ML    - 使用 new_ap_selection 下的视频专用模型（pairwise 比较）
 * SCORE - 仅依据后端返回的众包评分，直接选最高分 AP
 */
enum class RoamingMode {
    ML,
    SCORE
}
