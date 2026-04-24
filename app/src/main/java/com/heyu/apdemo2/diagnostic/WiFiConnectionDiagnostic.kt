package com.heyu.apdemo2.diagnostic

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * WiFi Connection Method Diagnostic Tool
 *
 * Used to diagnose WiFi connection capabilities for specific device models/system versions
 */
class WiFiConnectionDiagnostic(private val context: Context) {

    companion object {
        private const val TAG = "[WIFI_DIAGNOSTIC]"
    }

    data class DiagnosticResult(
        val method: String,
        val available: Boolean,
        val reason: String = ""
    )

    /**
     * Run full diagnostic
     */
    fun runFullDiagnostic(): List<DiagnosticResult> {
        val results = mutableListOf<DiagnosticResult>()

        Log.d(TAG, "=".repeat(60))
        Log.d(TAG, "Starting WiFi connection method diagnostic")
        Log.d(TAG, "Device info: ${Build.BRAND} ${Build.MODEL}")
        Log.d(TAG, "System version: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Log.d(TAG, "=".repeat(60))

        // Diagnose method 1
        results.add(diagnoseMethod1())

        // Diagnose method 2
        results.add(diagnoseMethod2())

        // Diagnose method 3
        results.add(diagnoseMethod3())

        // Diagnose OEM specifics
        results.add(diagnoseOEMSpecific())

        // Print result summary
        Log.d(TAG, "=".repeat(60))
        Log.d(TAG, "Diagnostic result summary:")
        results.forEach { result ->
            val status = if (result.available) "✅ Available" else "❌ Unavailable"
            Log.d(TAG, "${result.method}: $status - ${result.reason}")
        }
        Log.d(TAG, "=".repeat(60))

        return results
    }

    /**
     * Diagnose method 1: WifiConfiguration (Android 9-)
     */
    private fun diagnoseMethod1(): DiagnosticResult {
        val available = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        val reason = if (available) {
            "API ${Build.VERSION.SDK_INT} <= 28, supports addNetwork"
        } else {
            "API ${Build.VERSION.SDK_INT} > 28, addNetwork deprecated and restricted"
        }
        return DiagnosticResult("Method 1: WifiConfiguration.addNetwork", available, reason)
    }

    /**
     * Diagnose method 2: ACTION_WIFI_ADD_NETWORKS
     */
    private fun diagnoseMethod2(): DiagnosticResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return DiagnosticResult(
                "Method 2: ACTION_WIFI_ADD_NETWORKS",
                false,
                "API ${Build.VERSION.SDK_INT} < 30, not supported"
            )
        }

        // Check if Intent is available
        val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
        val resolveInfo = context.packageManager.queryIntentActivities(intent, 0)

        val available = resolveInfo.isNotEmpty()
        val reason = if (available) {
            if (isVivoOrIQOO()) {
                "System supported, but vivo/iQOO may silently handle or restrict third-party apps"
            } else if (isXiaomi()) {
                "System supported, but Xiaomi MIUI may have custom behaviors"
            } else if (isOppo()) {
                "System supported, but OPPO ColorOS may have custom behaviors"
            } else {
                "System natively supported, should display popup normally"
            }
        } else {
            "System has no registered Intent handler"
        }

        return DiagnosticResult("Method 2: ACTION_WIFI_ADD_NETWORKS", available, reason)
    }

    /**
     * Diagnose method 3: WifiNetworkSpecifier
     */
    private fun diagnoseMethod3(): DiagnosticResult {
        val available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val reason = if (available) {
            "API ${Build.VERSION.SDK_INT} >= 29, supports requestNetwork, but creates local connection instead of system switch"
        } else {
            "API ${Build.VERSION.SDK_INT} < 29, not supported"
        }
        return DiagnosticResult("Method 3: WifiNetworkSpecifier (pseudo-connection)", available, reason)
    }

    /**
     * Diagnose OEM specifics
     */
    private fun diagnoseOEMSpecific(): DiagnosticResult {
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()

        return when {
            brand.contains("vivo") || model.contains("vivo") ->
                DiagnosticResult(
                    "OEM Characteristics",
                    false,
                    "vivo device: OriginOS deeply customized, ACTION_WIFI_ADD_NETWORKS may be disabled or require special permissions"
                )
            brand.contains("iqoo") || model.contains("iqoo") ->
                DiagnosticResult(
                    "OEM Characteristics",
                    false,
                    "iQOO device: Same as vivo OriginOS, WiFi management strictly restricted for third-party apps"
                )
            brand.contains("xiaomi") || model.contains("xiaomi") ||
            brand.contains("redmi") || model.contains("redmi") ->
                DiagnosticResult(
                    "OEM Characteristics",
                    true,
                    "Xiaomi/Redmi device: MIUI customized, but usually supports standard API"
                )
            brand.contains("oppo") || model.contains("oppo") ||
            brand.contains("realme") || model.contains("realme") ->
                DiagnosticResult(
                    "OEM Characteristics",
                    true,
                    "OPPO/realme device: ColorOS customized, but usually supports standard API"
                )
            brand.contains("samsung") || model.contains("samsung") ->
                DiagnosticResult(
                    "OEM Characteristics",
                    true,
                    "Samsung device: OneUI relatively open, standard API well supported"
                )
            else ->
                DiagnosticResult(
                    "OEM Characteristics",
                    true,
                    "$brand device: Not recognized as specially customized system"
                )
        }
    }

    /**
     * Check if device is vivo/iQOO
     */
    fun isVivoOrIQOO(): Boolean {
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        return brand.contains("vivo") || brand.contains("iqoo") ||
               model.contains("vivo") || model.contains("iqoo")
    }

    /**
     * Check if device is Xiaomi
     */
    fun isXiaomi(): Boolean {
        val brand = Build.BRAND.lowercase()
        return brand.contains("xiaomi") || brand.contains("redmi")
    }

    /**
     * Check if device is OPPO
     */
    fun isOppo(): Boolean {
        val brand = Build.BRAND.lowercase()
        return brand.contains("oppo") || brand.contains("realme")
    }

    /**
     * Get recommended connection method
     */
    fun getRecommendedMethod(): String {
        return when {
            isVivoOrIQOO() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                "Method 5/6: Jump to settings page + manual/auto operation (vivo/iQOO restricts standard API)"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                "Method 2: ACTION_WIFI_ADD_NETWORKS"
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                "Method 5/6: Android 10 restrictions, need to use settings page approach"
            else ->
                "Method 1: WifiConfiguration (Android 9-)"
        }
    }
}
