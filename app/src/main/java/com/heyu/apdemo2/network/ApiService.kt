package com.heyu.apdemo2.network

import android.util.Log
import com.google.gson.Gson
import com.heyu.apdemo2.model.AccessPoint
import com.heyu.apdemo2.model.ApDetailResponse
import com.heyu.apdemo2.model.ScanResponse
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
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
        fun onSuccess(response: ScanResponse)
        fun onError(error: String)
    }

    interface SimpleCallback {
        fun onSuccess()
        fun onError(error: String)
    }

    /**
     * Get detailed information for a single AP
     */
    fun fetchApDetails(ip: String, port: Int, bssid: String, callback: Callback) {
        val url = "http://$ip:$port/api/ap_details"
        val jsonRequest = gson.toJson(mapOf("bssid" to bssid))
        val body = jsonRequest.toRequestBody(JSON)

        Log.d(DEBUG_TAG, ">>> [Details Request] URL: $url")

        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(DEBUG_TAG, "!!! [Details Failed]: ${e.message}")
                callback.onError(e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyStr = it.body?.string()
                    Log.d(DEBUG_TAG, "<<< [Details Response] Code: ${it.code}, Content: $bodyStr")
                    if (it.isSuccessful && bodyStr != null) {
                        try {
                            val apDetail = gson.fromJson(bodyStr, ApDetailResponse::class.java)
                            callback.onSuccess(apDetail)
                        } catch (e: Exception) {
                            callback.onError("Parse failed")
                        }
                    } else {
                        callback.onError("Error code: ${it.code}")
                    }
                }
            }
        })
    }

    /**
     * Upload roaming algorithm log to backend
     */
    fun uploadRoamingLog(ip: String, port: Int, logText: String, callback: SimpleCallback) {
        val url = "http://$ip:$port/api/roaming_log"
        val payload = mapOf(
            "device_model" to android.os.Build.MODEL,
            "exported_at" to java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()
            ).format(java.util.Date()),
            "logs" to logText
        )
        val body = gson.toJson(payload).toRequestBody(JSON)
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.onError(e.message ?: "Network error")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) callback.onSuccess()
                    else callback.onError("Server error: ${it.code}")
                }
            }
        })
    }

    /**
     * Batch upload results after 4 scans and receive scores
     */
    fun uploadScanResults(ip: String, port: Int, accessPoints: List<AccessPoint>, callback: BatchCallback) {
        val url = "http://$ip:$port/api/upload_scan"

        val ssidList = accessPoints.map { it.ssid }.filter { it.isNotBlank() }

        val payload = mapOf(
            "ssids" to ssidList,
            "count" to ssidList.size,
            "device_model" to android.os.Build.MODEL
        )

        val jsonRequest = gson.toJson(payload)
        val body = jsonRequest.toRequestBody(JSON)

        Log.d(DEBUG_TAG, "========================================")
        Log.d(DEBUG_TAG, ">>> [HTTP POST] Initiating batch upload and score request")
        Log.d(DEBUG_TAG, "Target URL: $url")
        Log.d(DEBUG_TAG, "SSID count: ${ssidList.size}")
        Log.d(DEBUG_TAG, "========================================")

        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(DEBUG_TAG, "!!! [Upload Failed]: ${e.message}")
                callback.onError(e.message ?: "Unknown Error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val respStr = it.body?.string()
                    Log.d(DEBUG_TAG, "<<< [Upload Response] Status: ${it.code}")
                    Log.d(DEBUG_TAG, "Server response: $respStr")

                    if (it.isSuccessful && respStr != null) {
                        try {
                            val scanResponse = gson.fromJson(respStr, ScanResponse::class.java)
                            callback.onSuccess(scanResponse)
                        } catch (e: Exception) {
                            Log.e(DEBUG_TAG, "JSON parse failed: ${e.message}")
                            callback.onError("Data format parse failed")
                        }
                    } else {
                        callback.onError("Server error: ${it.code}")
                    }
                }
            }
        })
    }
}
