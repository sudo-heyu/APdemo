package com.heyu.apdemo2.network

import android.util.Log
import com.google.gson.Gson
import com.heyu.apdemo2.model.AccessPoint
import com.heyu.apdemo2.model.ApDetailResponse
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ApiService {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val DEBUG_TAG = "[SCAN_DEBUG]"
    }

    interface Callback {
        fun onSuccess(response: ApDetailResponse)
        fun onError(error: String)
    }

    interface BatchCallback {
        fun onSuccess(message: String)
        fun onError(error: String)
    }

    /**
     * 获取单个 AP 的详细信息
     */
    fun fetchApDetails(ip: String, port: Int, bssid: String, callback: Callback) {
        val url = "http://$ip:$port/api/ap_details"
        val jsonRequest = gson.toJson(mapOf("bssid" to bssid))
        val body = jsonRequest.toRequestBody(JSON)
        
        Log.d(DEBUG_TAG, ">>> [详情请求] URL: $url")
        
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(DEBUG_TAG, "!!! [详情失败]: ${e.message}")
                callback.onError(e.message ?: "网络错误")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyStr = it.body?.string()
                    Log.d(DEBUG_TAG, "<<< [详情响应] 代码: ${it.code}, 内容: $bodyStr")
                    if (it.isSuccessful && bodyStr != null) {
                        try {
                            val apDetail = gson.fromJson(bodyStr, ApDetailResponse::class.java)
                            callback.onSuccess(apDetail)
                        } catch (e: Exception) {
                            callback.onError("解析失败")
                        }
                    } else {
                        callback.onError("错误码: ${it.code}")
                    }
                }
            }
        })
    }

    /**
     * 批量上传 4 次扫描后的结果 (已简化 Payload)
     */
    fun uploadScanResults(ip: String, port: Int, accessPoints: List<AccessPoint>, callback: BatchCallback) {
        val url = "http://$ip:$port/api/upload_scan"
        
        // 简化数据：仅提取 SSID 字符串列表，去掉 bssid, rssi, frequency, timestamp
        val ssidList = accessPoints.map { it.ssid }.filter { it.isNotBlank() }
        
        val payload = mapOf(
            "ssids" to ssidList,
            "count" to ssidList.size,
            "device_model" to android.os.Build.MODEL
        )
        
        val jsonRequest = gson.toJson(payload)
        val body = jsonRequest.toRequestBody(JSON)
        
        Log.d(DEBUG_TAG, "========================================")
        Log.d(DEBUG_TAG, ">>> [HTTP POST] 发起简化版批量上传")
        Log.d(DEBUG_TAG, "目标地址: $url")
        Log.d(DEBUG_TAG, "SSID 数量: ${ssidList.size}")
        Log.d(DEBUG_TAG, "完整载荷: $jsonRequest")
        Log.d(DEBUG_TAG, "========================================")
        
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(DEBUG_TAG, "!!! [上传失败]: 网络错误或策略限制")
                Log.e(DEBUG_TAG, "错误详情: ${e.message}")
                callback.onError(e.message ?: "Unknown Error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val respStr = it.body?.string()
                    Log.d(DEBUG_TAG, "<<< [上传响应] 状态码: ${it.code}")
                    Log.d(DEBUG_TAG, "服务器回应: $respStr")
                    
                    if (it.isSuccessful) {
                        callback.onSuccess(respStr ?: "OK")
                    } else {
                        callback.onError("服务器错误: ${it.code}")
                    }
                }
            }
        })
    }
}
