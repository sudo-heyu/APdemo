package com.heyu.apdemo2.model

data class ApDetailRequest(
    val bssid: String
)

data class ApDetailResponse(
    val bssid: String,
    val ssid: String,
    val security: String,
    val manufacturer: String,
    val extraDetails: Map<String, String>
)
