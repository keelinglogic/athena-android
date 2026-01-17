package com.athena.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class CaptureRequest(
    val captureType: String,
    val content: String,
    val deviceId: String,
    val filePath: String? = null,
    val fileSize: Long? = null,
    val metadata: Map<String, Any>? = null
)

data class CaptureResponse(
    val success: Boolean,
    val id: Int? = null,
    val error: String? = null
)

class CaptureApi {
    companion object {
        private const val WEBHOOK_URL = "http://10.100.0.1:5678/webhook/FLrclK4Xo2BPDGoq/webhook/capture"
        private const val TIMEOUT_SECONDS = 30L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun sendCapture(request: CaptureRequest): CaptureResponse {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("capture_type", request.captureType)
                    put("content", request.content)
                    put("device_id", request.deviceId)
                    put("created_at", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                    request.filePath?.let { put("file_path", it) }
                    request.fileSize?.let { put("file_size", it) }
                    request.metadata?.let { put("metadata", JSONObject(it)) }
                }

                val body = json.toString().toRequestBody(jsonMediaType)
                val httpRequest = Request.Builder()
                    .url(WEBHOOK_URL)
                    .post(body)
                    .build()

                client.newCall(httpRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        val responseJson = responseBody?.let { JSONObject(it) }
                        CaptureResponse(
                            success = responseJson?.optBoolean("success", false) ?: false,
                            id = responseJson?.optInt("id")
                        )
                    } else {
                        CaptureResponse(
                            success = false,
                            error = "HTTP ${response.code}: ${response.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                CaptureResponse(
                    success = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }
}
