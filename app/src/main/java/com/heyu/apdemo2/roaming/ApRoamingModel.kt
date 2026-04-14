package com.heyu.apdemo2.roaming

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxMap
import ai.onnxruntime.OnnxSequence
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.Locale

/**
 * AP 漫游选网模型推理类（new_ap_selection 视频专用模型）
 *
 * 模型文件：assets/ap_roaming_model_video.onnx
 * 转换方式：运行 docs/new_ap_selection/convert_to_onnx.py 生成 ONNX 并复制到 assets/
 *
 * 特征顺序（10 个，与训练脚本 feature_cols 一致）：
 *   rssi_a, rssi_b, rssi_diff, score_a, score_b, score_diff, prod_a, prod_b, a_conn, b_conn
 *
 * RSSI 归一化范围：[-90, -30]（与 infer.py 默认值一致）
 */
class ApRoamingModel(context: Context) : ApPairwisePredictor {

    companion object {
        private const val TAG = "[ApRoamingModel]"
        private const val MODEL_NAME = "ap_roaming_model_video.onnx"
        private const val INPUT_NAME = "input"   // onnxmltools 转换时指定的输入名称
        private const val NUM_FEATURES = 10

        private const val RSSI_MIN  = -90f
        private const val RSSI_MAX  = -30f
        private const val SCORE_MAX = 100f

        private val FEATURE_NAMES = arrayOf(
            "rssi_a", "rssi_b", "rssi_diff",
            "score_a", "score_b", "score_diff",
            "prod_a",  "prod_b",
            "a_conn",  "b_conn"
        )

        // 运行 docs/new_ap_selection/convert_to_onnx.py 生成，特征顺序与训练一致
        private val SCALER_MEAN = doubleArrayOf(
            0.49083859067711855,  // rssi_a
            0.4908385906771184,   // rssi_b
            -3.5723122770303585e-19,  // rssi_diff
            0.6425999703372633,   // score_a
            0.6425999703372629,   // score_b
            -4.1081591185849125e-19,  // score_diff
            0.31202489710403214,  // prod_a
            0.3120248971040321,   // prod_b
            0.9678209577293426,   // a_conn
            0.9678209577293426    // b_conn
        )
        private val SCALER_SCALE = doubleArrayOf(
            0.21116770088905168,  // rssi_a
            0.21116770088905168,  // rssi_b
            0.294505171203483,    // rssi_diff
            0.23394000569251036,  // score_a
            0.23394000569251028,  // score_b
            0.33366565945121557,  // score_diff
            0.17740493072407978,  // prod_a
            0.17740493072407978,  // prod_b
            0.1764753566626248,   // a_conn
            0.17647535666262476   // b_conn
        )
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false
    private val logManager: RoamingLogManager = RoamingLogManager.getInstance(context)

    init {
        try {
            initModel(context)
        } catch (e: Exception) {
            Log.e(TAG, "模型初始化失败: ${e.message}", e)
        }
    }

    private fun initModel(context: Context) {
        val modelFile = File(context.cacheDir, MODEL_NAME)
        if (!modelFile.exists()) {
            context.assets.open(MODEL_NAME).use { input ->
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "模型已复制到缓存: ${modelFile.absolutePath}")
        }
        ortEnvironment = OrtEnvironment.getEnvironment()
        ortSession = ortEnvironment?.createSession(modelFile.absolutePath)
        isInitialized = true
        Log.d(TAG, "视频专用 ONNX 模型加载成功")
    }

    override fun predict(
        rssiA: Float, scoreA: Float,
        rssiB: Float, scoreB: Float,
        connA: Boolean, connB: Boolean,
        ssidA: String?,
        ssidB: String?
    ): Float {
        // ── 特征构造（无论模型是否加载都先算好并打日志）────────────────────
        val rssiDenom = (RSSI_MAX - RSSI_MIN).coerceAtLeast(1f)
        val ra = ((rssiA - RSSI_MIN) / rssiDenom).coerceIn(0f, 1f)
        val rb = ((rssiB - RSSI_MIN) / rssiDenom).coerceIn(0f, 1f)
        val sa = (scoreA / SCORE_MAX).coerceIn(0f, 1f)
        val sb = (scoreB / SCORE_MAX).coerceIn(0f, 1f)

        val rawFeatures = FloatArray(NUM_FEATURES).also { f ->
            f[0] = ra;        f[1] = rb;        f[2] = ra - rb
            f[3] = sa;        f[4] = sb;        f[5] = sa - sb
            f[6] = ra * sa;   f[7] = rb * sb
            f[8] = if (connA) 1f else 0f
            f[9] = if (connB) 1f else 0f
        }
        val features = FloatArray(NUM_FEATURES) { i ->
            ((rawFeatures[i] - SCALER_MEAN[i]) / SCALER_SCALE[i]).toFloat()
        }

        val featureLog = StringBuilder()
        featureLog.append("<span style='background:#5D4037;color:#fff;padding:1px 6px;border-radius:3px;font-weight:bold'>模型输入</span> ")
        featureLog.append("${ssidA ?: "A"} vs ${ssidB ?: "B"}")
        featureLog.append("<table><tr><th>特征</th><th>原始</th><th>标准化</th></tr>")
        for (i in 0 until NUM_FEATURES) {
            featureLog.append("<tr><td>${FEATURE_NAMES[i]}</td>")
            featureLog.append("<td>${String.format(Locale.US, "%.4f", rawFeatures[i])}</td>")
            featureLog.append("<td>${String.format(Locale.US, "%.4f", features[i])}</td></tr>")
        }
        featureLog.append("</table>")
        logManager.d(featureLog.toString())

        // ── 模型推理 ─────────────────────────────────────────────────────────
        if (!isInitialized || ortSession == null) {
            logManager.w("模型未加载（请运行 convert_to_onnx.py 并将 onnx 放入 assets/），返回 0.5")
            return 0.5f
        }

        return try {
            val inputBuffer = FloatBuffer.wrap(features)
            val inputShape  = longArrayOf(1, NUM_FEATURES.toLong())
            val inputTensor = OnnxTensor.createTensor(ortEnvironment, inputBuffer, inputShape)

            var prob = 0.5f
            inputTensor.use { tensor ->
                val results = ortSession?.run(mapOf(INPUT_NAME to tensor))
                results?.use { output ->
                    val onnxSeq = (output[1] as? OnnxSequence)?.getValue() as? List<*>
                    val onnxMap = onnxSeq?.firstOrNull() as? OnnxMap
                    @Suppress("UNCHECKED_CAST")
                    val probMap = onnxMap?.getValue() as? Map<Long, Float>
                    prob = probMap?.get(1L) ?: 0.5f
                }
            }
            logManager.phase("模型输出", "#283593",
                "prob(A>B)=${String.format(Locale.US, "%.4f", prob)}, " +
                "prob(B>A)=${String.format(Locale.US, "%.4f", 1 - prob)}")
            prob
        } catch (e: Exception) {
            Log.e(TAG, "推理失败: ${e.message}", e)
            logManager.e("模型推理失败: ${e.message}")
            0.5f
        }
    }

    override fun close() {
        try {
            ortSession?.close()
            ortEnvironment?.close()
            isInitialized = false
            Log.d(TAG, "模型资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败: ${e.message}", e)
        }
    }
}
