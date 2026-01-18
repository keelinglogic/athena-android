package com.athena.capture

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class VoiceCaptureResponse(
    val success: Boolean,
    val id: Int? = null,
    val uuid: String? = null,
    val transcript: String? = null,
    val isNew: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val duplicate: Boolean = false,
    val contentMismatch: Boolean = false,
    val retryAfter: Int? = null
)

class VoiceCaptureException(
    val errorCode: String,
    override val message: String
) : Exception(message)

class VoiceCaptureApi(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCaptureApi"
        private const val WEBHOOK_URL = "http://10.100.0.1:5678/webhook/voice-capture"
        // Note: No auth token needed - VPN provides security layer
        private const val BASE_TIMEOUT_SECONDS = 30L
        private const val MAX_RETRIES = 3

        private var cachedDeviceId: String? = null
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(BASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)  // Allow for larger uploads
        .readTimeout(90, TimeUnit.SECONDS)   // Allow for transcription time
        .build()

    fun getDeviceId(): String {
        cachedDeviceId?.let { return it }

        val prefs = context.getSharedPreferences("voice_capture", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: run {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            val shortId = androidId.takeLast(8)
            val deviceId = "${Build.MANUFACTURER}-${Build.MODEL}-$shortId"
                .lowercase()
                .replace(Regex("[^a-z0-9-]"), "-")
                .take(100)  // Enforce max length
            prefs.edit().putString("device_id", deviceId).apply()
            cachedDeviceId = deviceId
            deviceId
        }
    }

    suspend fun uploadVoiceCapture(
        audioFile: File,
        uuid: UUID,
        durationSeconds: Float
    ): Result<VoiceCaptureResponse> = withContext(Dispatchers.IO) {
        val audioBody = audioFile.asRequestBody("audio/mp4".toMediaType())
        val audioPart = MultipartBody.Part.createFormData("audio", audioFile.name, audioBody)

        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addPart(audioPart)
                    .addFormDataPart("uuid", uuid.toString())
                    .addFormDataPart("device_id", getDeviceId())
                    .addFormDataPart("timestamp", Instant.now().toString())
                    .addFormDataPart("duration_seconds", durationSeconds.toString())
                    .build()

                // Dynamic timeout based on audio duration
                val dynamicTimeoutMs = (BASE_TIMEOUT_SECONDS * 1000 + (durationSeconds * 12000)).toLong()
                val timeoutClient = client.newBuilder()
                    .readTimeout(dynamicTimeoutMs, TimeUnit.MILLISECONDS)
                    .build()

                val request = Request.Builder()
                    .url(WEBHOOK_URL)
                    .post(requestBody)
                    .build()

                Log.d(TAG, "Uploading voice capture: uuid=$uuid, attempt=${attempt + 1}")

                timeoutClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()

                    // Handle empty response body (prevents JSONException crash)
                    val responseJson: JSONObject? = if (responseBody.isNullOrBlank()) {
                        Log.w(TAG, "Empty response body from server (HTTP ${response.code})")
                        null
                    } else {
                        try {
                            JSONObject(responseBody)
                        } catch (e: JSONException) {
                            Log.e(TAG, "Failed to parse response JSON: ${e.message}")
                            null
                        }
                    }

                    if (response.isSuccessful) {
                        // If we got a 200 but couldn't parse JSON, treat as error
                        if (responseJson == null) {
                            return@withContext Result.failure(
                                VoiceCaptureException(
                                    "parse_error",
                                    "Server returned invalid response"
                                )
                            )
                        }

                        val captureResponse = VoiceCaptureResponse(
                            success = responseJson.optBoolean("success", false),
                            id = if (responseJson.has("id")) responseJson.optInt("id") else null,
                            uuid = responseJson.optString("uuid", null),
                            transcript = responseJson.optString("transcript", null),
                            isNew = responseJson.optBoolean("is_new", false),
                            error = responseJson.optString("error", null),
                            message = responseJson.optString("message", null),
                            duplicate = responseJson.optBoolean("duplicate", false),
                            contentMismatch = responseJson.optBoolean("content_mismatch", false)
                        )

                        if (captureResponse.success) {
                            // Success - delete local file
                            audioFile.delete()
                            Log.i(TAG, "Voice capture uploaded successfully: id=${captureResponse.id}")

                            if (captureResponse.contentMismatch) {
                                Log.w(TAG, "Server detected content mismatch for UUID $uuid - original kept")
                            }

                            return@withContext Result.success(captureResponse)
                        } else {
                            // Server returned error in response body
                            return@withContext Result.failure(
                                VoiceCaptureException(
                                    captureResponse.error ?: "unknown",
                                    captureResponse.message ?: "Unknown error"
                                )
                            )
                        }
                    } else if (response.code == 429) {
                        // Rate limited - wait with jitter and retry
                        val retryAfter = responseJson?.optInt("retry_after") ?: 1
                        val baseDelay = retryAfter * 1000L
                        val jitter = Random.nextLong(0, 500)
                        Log.w(TAG, "Rate limited, waiting ${baseDelay + jitter}ms before retry")
                        delay(baseDelay * (attempt + 1) + jitter)
                    } else {
                        // HTTP error - try to get error details from response
                        val errorMessage = responseJson?.optString("message")
                            ?: responseJson?.optString("error")
                            ?: "Server error: ${response.code} ${response.message}"
                        return@withContext Result.failure(
                            VoiceCaptureException(
                                "http_error",
                                errorMessage
                            )
                        )
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Upload timeout, attempt ${attempt + 1}", e)
                lastException = e
                val jitter = Random.nextLong(0, 500)
                delay(1000L * (attempt + 1) + jitter)
            } catch (e: IOException) {
                Log.w(TAG, "Upload failed, attempt ${attempt + 1}", e)
                lastException = e
                val jitter = Random.nextLong(0, 500)
                delay(1000L * (attempt + 1) + jitter)
            }
        }

        // All retries exhausted
        Log.e(TAG, "Upload failed after $MAX_RETRIES attempts", lastException)
        Result.failure(lastException ?: IOException("Upload failed after $MAX_RETRIES attempts"))
    }

    fun cleanupStaleRecordings() {
        val oneHourAgo = System.currentTimeMillis() - 3600000
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("voice_") && it.name.endsWith(".m4a") }
            ?.filter { it.lastModified() < oneHourAgo }
            ?.forEach {
                Log.d(TAG, "Cleaning stale recording: ${it.name}")
                it.delete()
            }
    }

    fun findPendingUpload(): PendingUpload? {
        return context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("voice_") && it.name.endsWith(".m4a") }
            ?.filter { it.length() > 1000 }  // Ignore empty/corrupt files
            ?.maxByOrNull { it.lastModified() }
            ?.let { file ->
                try {
                    val uuidString = file.name
                        .removePrefix("voice_")
                        .removeSuffix(".m4a")
                    val uuid = UUID.fromString(uuidString)
                    PendingUpload(file, uuid)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Invalid UUID in filename: ${file.name}, deleting")
                    file.delete()
                    null
                }
            }
    }
}

data class PendingUpload(
    val file: File,
    val uuid: UUID
)
