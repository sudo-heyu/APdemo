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
 * AP 漫游选网模型推理类
 *
 * 加载 assets/ap_roaming_model.onnx 模型，提供 pairwise AP 比较预测功能
 * 特征顺序必须与训练时一致：
 * [rssi_a, rssi_b, rssi_diff, score_a, score_b, score_diff, prod_a, prod_b,
 *  a_conn_down, b_conn_down, a_conn_up, b_conn_up, biz]
 */
class ApRoamingModel(context: Context) : ApPairwisePredictor {

    companion object {
        private const val TAG = "[ApRoamingModel]"
        private const val MODEL_NAME = "ap_roaming_model.onnx"
        private const val NUM_FEATURES = 13

        private const val SCORE_MAX = 100f

        // 特征名称（用于日志）
        private val FEATURE_NAMES = arrayOf(
            "rssi_a", "rssi_b", "rssi_diff", "score_a", "score_b", "score_diff",
            "prod_a", "prod_b", "a_conn_down", "b_conn_down", "a_conn_up", "b_conn_up", "biz"
        )

        // StandardScaler 参数（来自 convert_to_onnx.py 输出，2026-04-09 更新，三场景合并训练）
        private val SCALER_MEAN = doubleArrayOf(
            0.22762376135092258, 0.22762376135092266, 2.633337126242885e-19,  // rssi_a, rssi_b, rssi_diff
            0.6414023968026435, 0.6414023968026435, -6.682809454818691e-19,   // score_a, score_b, score_diff
            0.1460065424011401, 0.14600654240114008,                          // prod_a, prod_b
            0.6192904346558469, 0.6192904346558469,                           // a_conn_down, b_conn_down
            0.30432286984620616, 0.30432286984620616,                         // a_conn_up, b_conn_up
            0.4946791710197041                                                 // biz
        )
        private val SCALER_SCALE = doubleArrayOf(
            0.23154702157004162, 0.23154702157004162, 0.39632876942171,      // rssi_a, rssi_b, rssi_diff
            0.22979533454765186, 0.22979533454765183, 0.33240536836753387,   // score_a, score_b, score_diff
            0.16769044841382172, 0.16769044841382175,                        // prod_a, prod_b
            0.4855613166219268, 0.485561316621927,                           // a_conn_down, b_conn_down
            0.4601200503507484, 0.4601200503507484,                          // a_conn_up, b_conn_up
            0.4999716879773919                                                // biz
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

    /**
     * 从 assets 加载 ONNX 模型
     */
    private fun initModel(context: Context) {
        // 将模型从 assets 复制到缓存目录
        val modelFile = File(context.cacheDir, MODEL_NAME)
        if (!modelFile.exists()) {
            context.assets.open(MODEL_NAME).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "模型已复制到缓存: ${modelFile.absolutePath}")
        }

        // 初始化 ONNX Runtime
        ortEnvironment = OrtEnvironment.getEnvironment()
        ortSession = ortEnvironment?.createSession(modelFile.absolutePath)
        isInitialized = true
        Log.d(TAG, "ONNX 模型加载成功")
    }

    override fun predict(
        rssiA: Float, scoreA: Float,
        rssiB: Float, scoreB: Float,
        connDownA: Boolean, connDownB: Boolean,
        connUpA: Boolean, connUpB: Boolean,
        isGame: Boolean,
        rssiMin: Float,
        rssiMax: Float,
        ssidA: String?,
        ssidB: String?
    ): Float {
        val scoreMax = SCORE_MAX
        if (!isInitialized || ortSession == null) {
            Log.w(TAG, "模型未初始化，返回默认概率 0.5")
            return 0.5f
        }

        try {
            // RSSI 归一化到 [0, 1]
            val rssiDenom = (rssiMax - rssiMin).coerceAtLeast(1f)
            val ra = ((rssiA - rssiMin) / rssiDenom).coerceIn(0f, 1f)
            val rb = ((rssiB - rssiMin) / rssiDenom).coerceIn(0f, 1f)

            // 评分归一化到 [0, 1]
            val sa = (scoreA / scoreMax).coerceIn(0f, 1f)
            val sb = (scoreB / scoreMax).coerceIn(0f, 1f)

            // 构建特征向量
            val rawFeatures = FloatArray(NUM_FEATURES)
            val features = FloatArray(NUM_FEATURES) { i ->
                rawFeatures[i] = when (i) {
                    0 -> ra
                    1 -> rb
                    2 -> ra - rb
                    3 -> sa
                    4 -> sb
                    5 -> sa - sb
                    6 -> ra * sa
                    7 -> rb * sb
                    8 -> if (connDownA) 1f else 0f
                    9 -> if (connDownB) 1f else 0f
                    10 -> if (connUpA) 1f else 0f
                    11 -> if (connUpB) 1f else 0f
                    12 -> if (isGame) 1f else 0f
                    else -> 0f
                }
                // StandardScaler 变换: (x - mean) / scale
                ((rawFeatures[i] - SCALER_MEAN[i]) / SCALER_SCALE[i]).toFloat()
            }

            // 记录特征输入（HTML 表格）
            val featureLog = StringBuilder()
            featureLog.append("<span style='background:#5D4037;color:#fff;padding:1px 6px;border-radius:3px;font-weight:bold'>模型输入</span> ${ssidA ?: "A"} vs ${ssidB ?: "B"}")
            featureLog.append("<table>")
            featureLog.append("<tr><th>特征</th><th>原始值</th><th>标准化</th></tr>")
            for (i in 0 until NUM_FEATURES) {
                featureLog.append("<tr>")
                featureLog.append("<td>${FEATURE_NAMES[i]}</td>")
                featureLog.append("<td>${String.format(Locale.US, "%.4f", rawFeatures[i])}</td>")
                featureLog.append("<td>${String.format(Locale.US, "%.4f", features[i])}</td>")
                featureLog.append("</tr>")
            }
            featureLog.append("</table>")
            logManager.d(featureLog.toString())

            // 创建输入张量
            val inputBuffer = FloatBuffer.wrap(features)
            val inputShape = longArrayOf(1, NUM_FEATURES.toLong())
            val inputTensor = OnnxTensor.createTensor(ortEnvironment, inputBuffer, inputShape)

            // 运行推理
            inputTensor.use { tensor ->
                val results = ortSession?.run(mapOf("features" to tensor))
                results?.use { output ->
                    // output[0] = label (int64)
                    // output[1] = probabilities: OnnxSequence -> List<OnnxMap> -> Map<Long, Float>
                    val onnxSeq = (output[1] as? OnnxSequence)?.getValue() as? List<*>
                    val onnxMap = onnxSeq?.firstOrNull() as? OnnxMap
                    @Suppress("UNCHECKED_CAST")
                    val probMap = onnxMap?.getValue() as? Map<Long, Float>
                    val prob = probMap?.get(1L) ?: 0.5f
                    logManager.phase("模型输出", "#283593",
                        "prob(A>B)=${String.format(Locale.US, "%.4f", prob)}, prob(B>A)=${String.format(Locale.US, "%.4f", 1 - prob)}")
                    return prob
                }
            }

            return 0.5f
        } catch (e: Exception) {
            Log.e(TAG, "推理失败: ${e.message}", e)
            logManager.e("模型推理失败: ${e.message}")
            return 0.5f
        }
    }

    /**
     * 释放模型资源
     */
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
