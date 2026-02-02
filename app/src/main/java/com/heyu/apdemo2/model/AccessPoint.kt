package com.heyu.apdemo2.model

/**
 * Access Point 数据模型类
 * 用于存储WiFi热点的基本信息
 */
data class AccessPoint(
    val ssid: String,           // 网络名称
    val bssid: String,          // MAC地址
    val rssi: Int,              // 信号强度 (dBm)
    val frequency: Int          // 频率 (MHz)
) {
    /**
     * 获取信号强度等级
     * @return 0-4级，数值越大信号越好
     */
    fun getSignalLevel(): Int {
        return when {
            rssi >= -50 -> 4  // 信号很强
            rssi >= -60 -> 3  // 信号良好
            rssi >= -70 -> 2  // 信号一般
            rssi >= -80 -> 1  // 信号较弱
            else -> 0         // 信号很差
        }
    }
}