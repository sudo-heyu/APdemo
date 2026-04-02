package com.heyu.apdemo2.connection

import android.content.Context

/**
 * WiFi 密码本地存储（SharedPreferences）。
 * 以 SSID 为 key，仅存储加密网络密码。
 */
object PasswordStore {

    private const val PREFS_NAME = "wifi_passwords"

    fun save(context: Context, ssid: String, password: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(ssid, password).apply()
    }

    fun get(context: Context, ssid: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(ssid, null)

    fun delete(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(ssid).apply()
    }
}
