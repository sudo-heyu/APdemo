package com.heyu.apdemo2.model

/**
 * Access Point Data Model Class
 * Used to store basic information of WiFi hotspot and server-returned ratings
 */
data class AccessPoint(
    val ssid: String,                   // Network name
    val bssid: String,                  // MAC address
    val rssi: Int,                      // Signal strength (dBm)
    val frequency: Int,                 // Frequency (MHz)
    val capabilities: String = "",      // Security capability string, e.g. [WPA2-PSK-CCMP]
    var score: Int? = null,             // Server score (0-100)
    var reason: String? = null,         // Recommendation reason
    var isExpanded: Boolean = false,    // UI state: whether details are expanded
    var lastSeenTime: Long = 0L         // Last scanned timestamp (used for weak signal AP protection)
) {
    /** Whether it's a secured network (requires password) */
    fun isSecured(): Boolean =
        capabilities.contains("WPA") || capabilities.contains("WEP") || capabilities.contains("EAP")

    /**
     * Get signal strength level
     * @return 0-4 level, higher value means better signal
     */
    fun getSignalLevel(): Int {
        return when {
            rssi >= -50 -> 4  // Excellent signal
            rssi >= -60 -> 3  // Good signal
            rssi >= -70 -> 2  // Fair signal
            rssi >= -80 -> 1  // Weak signal
            else -> 0         // Poor signal
        }
    }
}
