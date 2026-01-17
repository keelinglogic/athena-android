package com.athena.capture.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.athena.capture.MainActivity
import com.athena.capture.R
import java.io.File
import java.io.IOException
import java.util.UUID

class VoiceRecordingService : Service() {

    companion object {
        private const val TAG = "VoiceRecordingService"
        const val CHANNEL_ID = "voice_recording_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.athena.capture.action.START_RECORDING"
        const val ACTION_STOP = "com.athena.capture.action.STOP_RECORDING"
        const val EXTRA_UUID = "extra_uuid"

        const val MAX_DURATION_MS = 300_000  // 5 minutes
        const val WARNING_DURATION_MS = 270_000  // 4:30 warning
        const val MIN_DURATION_MS = 500  // 0.5 seconds minimum
    }

    private val binder = LocalBinder()
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var currentCaptureUuid: UUID? = null
    private var recordingStartTime: Long = 0
    private var isRecording = false

    var onRecordingStateChanged: ((RecordingState) -> Unit)? = null
    var onMaxDurationReached: (() -> Unit)? = null
    var onWarningDurationReached: (() -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): VoiceRecordingService = this@VoiceRecordingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uuidString = intent.getStringExtra(EXTRA_UUID)
                val uuid = uuidString?.let { UUID.fromString(it) } ?: UUID.randomUUID()
                // Set UUID and file before calling startRecordingInternal
                currentCaptureUuid = uuid
                audioFile = File(cacheDir, "voice_${uuid}.m4a")
                startRecordingInternal(uuid)
            }
            ACTION_STOP -> {
                stopRecordingInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun startRecording(uuid: UUID = UUID.randomUUID()): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }

        currentCaptureUuid = uuid
        audioFile = File(cacheDir, "voice_${uuid}.m4a")

        val notification = buildNotification("Recording...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return startRecordingInternal(uuid)
    }

    private fun startRecordingInternal(uuid: UUID): Boolean {
        // UUID and audioFile are set by caller (startRecording or onStartCommand)
        // Only set here if not already set (for onStartCommand path)
        if (currentCaptureUuid == null) {
            currentCaptureUuid = uuid
            audioFile = File(cacheDir, "voice_${uuid}.m4a")
        }

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)  // 16kHz sufficient for speech
                setAudioEncodingBitRate(64000)  // 64kbps
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(audioFile?.absolutePath)

                setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> {
                            Log.i(TAG, "Max duration reached")
                            onMaxDurationReached?.invoke()
                            stopRecording()
                        }
                    }
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error: what=$what extra=$extra")
                    releaseRecorder()
                    onRecordingStateChanged?.invoke(RecordingState.Error("Recording failed"))
                }

                prepare()
                start()
            }

            recordingStartTime = System.currentTimeMillis()
            isRecording = true
            onRecordingStateChanged?.invoke(RecordingState.Recording(0))
            Log.i(TAG, "Recording started: ${audioFile?.absolutePath}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "MediaRecorder prepare failed", e)
            releaseRecorder()
            onRecordingStateChanged?.invoke(RecordingState.Error("Microphone unavailable"))
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaRecorder invalid state", e)
            releaseRecorder()
            onRecordingStateChanged?.invoke(RecordingState.Error("Recording failed to start"))
            false
        }
    }

    fun stopRecording(): RecordingResult? {
        return stopRecordingInternal()
    }

    private fun stopRecordingInternal(): RecordingResult? {
        if (!isRecording) {
            Log.w(TAG, "Not currently recording")
            return null
        }

        val duration = System.currentTimeMillis() - recordingStartTime
        isRecording = false

        releaseRecorder()
        stopForeground(STOP_FOREGROUND_REMOVE)

        val file = audioFile
        val uuid = currentCaptureUuid

        if (file == null || uuid == null) {
            onRecordingStateChanged?.invoke(RecordingState.Error("Recording file not found"))
            return null
        }

        if (duration < MIN_DURATION_MS) {
            file.delete()
            onRecordingStateChanged?.invoke(RecordingState.TooShort)
            return null
        }

        if (!file.exists() || file.length() == 0L) {
            onRecordingStateChanged?.invoke(RecordingState.Error("Recording file is empty"))
            return null
        }

        val result = RecordingResult(
            file = file,
            uuid = uuid,
            durationMs = duration,
            fileSize = file.length()
        )

        onRecordingStateChanged?.invoke(RecordingState.Completed(result))
        Log.i(TAG, "Recording stopped: duration=${duration}ms, size=${file.length()} bytes")
        return result
    }

    fun cancelRecording() {
        if (!isRecording) return

        isRecording = false
        releaseRecorder()
        stopForeground(STOP_FOREGROUND_REMOVE)

        audioFile?.delete()
        audioFile = null
        currentCaptureUuid = null

        onRecordingStateChanged?.invoke(RecordingState.Cancelled)
        Log.i(TAG, "Recording cancelled")
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaRecorder stop failed", e)
        }
        try {
            mediaRecorder?.reset()
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder reset failed", e)
        }
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder release failed", e)
        }
        mediaRecorder = null
    }

    fun getCurrentDurationMs(): Long {
        return if (isRecording) {
            System.currentTimeMillis() - recordingStartTime
        } else 0
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    fun getAudioFile(): File? = audioFile

    fun getCurrentUuid(): UUID? = currentCaptureUuid

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when voice recording is active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VoiceRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Athena Capture")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        if (isRecording) {
            stopRecordingInternal()
        }
        super.onDestroy()
    }
}

sealed class RecordingState {
    object Idle : RecordingState()
    data class Recording(val durationMs: Long) : RecordingState()
    data class Completed(val result: RecordingResult) : RecordingState()
    object Cancelled : RecordingState()
    object TooShort : RecordingState()
    data class Error(val message: String) : RecordingState()
}

data class RecordingResult(
    val file: File,
    val uuid: UUID,
    val durationMs: Long,
    val fileSize: Long
)
