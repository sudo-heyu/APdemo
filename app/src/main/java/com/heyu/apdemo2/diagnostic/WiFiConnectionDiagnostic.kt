package com.heyu.apdemo2.diagnostic

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * WiFi 连接方式诊断工具
 *
 * 用于诊断特定机型/系统版本的 WiFi 连接能力
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
     * 运行完整诊断
     */
    fun runFullDiagnostic(): List<DiagnosticResult> {
        val results = mutableListOf<DiagnosticResult>()

        Log.d(TAG, "=".repeat(60))
        Log.d(TAG, "开始 WiFi 连接方式诊断")
        Log.d(TAG, "设备信息: ${Build.BRAND} ${Build.MODEL}")
        Log.d(TAG, "系统版本: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Log.d(TAG, "=".repeat(60))

        // 诊断方式一
        results.add(diagnoseMethod1())

        // 诊断方式二
        results.add(diagnoseMethod2())

        // 诊断方式三
        results.add(diagnoseMethod3())

        // 诊断厂商特性
        results.add(diagnoseOEMSpecific())

        // 打印结果汇总
        Log.d(TAG, "=".repeat(60))
        Log.d(TAG, "诊断结果汇总:")
        results.forEach { result ->
            val status = if (result.available) "✅ 可用" else "❌ 不可用"
            Log.d(TAG, "${result.method}: $status - ${result.reason}")
        }
        Log.d(TAG, "=".repeat(60))

        return results
    }

    /**
     * 诊断方式一：WifiConfiguration (Android 9-)
     */
    private fun diagnoseMethod1(): DiagnosticResult {
        val available = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        val reason = if (available) {
            "API ${Build.VERSION.SDK_INT} <= 28，支持 addNetwork"
        } else {
            "API ${Build.VERSION.SDK_INT} > 28，addNetwork 已废弃且受限"
        }
        return DiagnosticResult("方式一: WifiConfiguration.addNetwork", available, reason)
    }

    /**
     * 诊断方式二：ACTION_WIFI_ADD_NETWORKS
     */
    private fun diagnoseMethod2(): DiagnosticResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return DiagnosticResult(
                "方式二: ACTION_WIFI_ADD_NETWORKS",
                false,
                "API ${Build.VERSION.SDK_INT} < 30，不支持"
            )
        }

        // 检查 Intent 是否可用
        val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
        val resolveInfo = context.packageManager.queryIntentActivities(intent, 0)

        val available = resolveInfo.isNotEmpty()
        val reason = if (available) {
            if (isVivoOrIQOO()) {
                "系统支持，但 vivo/iQOO 可能静默处理或限制第三方应用"
            } else if (isXiaomi()) {
                "系统支持，但小米 MIUI 可能有定制行为"
            } else if (isOppo()) {
                "系统支持，但 OPPO ColorOS 可能有定制行为"
            } else {
                "系统原生支持，应该正常弹窗"
            }
        } else {
            "系统未注册此 Intent 处理器"
        }

        return DiagnosticResult("方式二: ACTION_WIFI_ADD_NETWORKS", available, reason)
    }

    /**
     * 诊断方式三：WifiNetworkSpecifier
     */
    private fun diagnoseMethod3(): DiagnosticResult {
        val available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val reason = if (available) {
            "API ${Build.VERSION.SDK_INT} >= 29，支持 requestNetwork，但会产生本地连接而非系统切换"
        } else {
            "API ${Build.VERSION.SDK_INT} < 29，不支持"
        }
        return DiagnosticResult("方式三: WifiNetworkSpecifier (假连接)", available, reason)
    }

    /**
     * 诊断厂商特性
     */
    private fun diagnoseOEMSpecific(): DiagnosticResult {
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()

        return when {
            brand.contains("vivo") || model.contains("vivo") ->
                DiagnosticResult(
                    "厂商特性",
                    false,
                    "vivo 设备：OriginOS 深度定制，ACTION_WIFI_ADD_NETWORKS 可能被禁用或需特殊权限"
                )
            brand.contains("iqoo") || model.contains("iqoo") ->
                DiagnosticResult(
                    "厂商特性",
                    false,
                    "iQOO 设备：同 vivo OriginOS，WiFi 管理严格限制第三方应用"
                )
            brand.contains("xiaomi") || model.contains("xiaomi") ||
            brand.contains("redmi") || model.contains("redmi") ->
                DiagnosticResult(
                    "厂商特性",
                    true,
                    "小米/Redmi 设备：MIUI 有定制，但通常支持标准 API"
                )
            brand.contains("oppo") || model.contains("oppo") ||
            brand.contains("realme") || model.contains("realme") ->
                DiagnosticResult(
                    "厂商特性",
                    true,
                    "OPPO/realme 设备：ColorOS 有定制，但通常支持标准 API"
                )
            brand.contains("samsung") || model.contains("samsung") ->
                DiagnosticResult(
                    "厂商特性",
                    true,
                    "三星设备：OneUI 相对开放，标准 API 支持良好"
                )
            else ->
                DiagnosticResult(
                    "厂商特性",
                    true,
                    "$brand 设备：未识别为特殊定制系统"
                )
        }
    }

    /**
     * 检查是否是 vivo/iQOO 设备
     */
    fun isVivoOrIQOO(): Boolean {
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        return brand.contains("vivo") || brand.contains("iqoo") ||
               model.contains("vivo") || model.contains("iqoo")
    }

    /**
     * 检查是否是小米设备
     */
    fun isXiaomi(): Boolean {
        val brand = Build.BRAND.lowercase()
        return brand.contains("xiaomi") || brand.contains("redmi")
    }

    /**
     * 检查是否是 OPPO 设备
     */
    fun isOppo(): Boolean {
        val brand = Build.BRAND.lowercase()
        return brand.contains("oppo") || brand.contains("realme")
    }

    /**
     * 获取推荐的连接方式
     */
    fun getRecommendedMethod(): String {
        return when {
            isVivoOrIQOO() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                "方式五/六: 跳转设置页 + 手动/自动操作 (vivo/iQOO 限制标准API)"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                "方式二: ACTION_WIFI_ADD_NETWORKS"
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                "方式五/六: Android 10 限制，需使用设置页方案"
            else ->
                "方式一: WifiConfiguration (Android 9-)"
        }
    }
}
