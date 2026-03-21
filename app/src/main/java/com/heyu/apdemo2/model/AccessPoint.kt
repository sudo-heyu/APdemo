package com.heyu.apdemo2.model

/**
 * Access Point 数据模型类
 * 用于存储WiFi热点的基本信息及服务器返回的评价
 */
data class AccessPoint(
    val ssid: String,                   // 网络名称
    val bssid: String,                  // MAC地址
    val rssi: Int,                      // 信号强度 (dBm)
    val frequency: Int,                 // 频率 (MHz)
    val capabilities: String = "",      // 安全能力字符串，如 [WPA2-PSK-CCMP]
    var score: Int? = null,             // 服务器打分 (0-100)
    var reason: String? = null,         // 推荐/不推荐理由
    var isExpanded: Boolean = false     // UI状态：是否展开详情
) {
    /** 是否为加密网络（需要密码） */
    fun isSecured(): Boolean =
        capabilities.contains("WPA") || capabilities.contains("WEP") || capabilities.contains("EAP")

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
