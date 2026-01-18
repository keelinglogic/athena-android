package com.athena.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.athena.capture.databinding.ActivityMainBinding
import com.athena.capture.service.RecordingResult
import com.athena.capture.service.RecordingState
import com.athena.capture.service.UploadState
import com.athena.capture.service.VoiceRecordingService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val captureApi = CaptureApi()
    private lateinit var voiceCaptureApi: VoiceCaptureApi
    private val recentCaptures = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Voice recording
    private var voiceService: VoiceRecordingService? = null
    private var isServiceBound = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    // Track pending runnables for cleanup
    private val pendingRunnables = mutableListOf<Runnable>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceRecordingService.LocalBinder
            voiceService = binder.getService()
            isServiceBound = true

            voiceService?.onRecordingStateChanged = { state ->
                if (!isDestroyed && !isFinishing) {
                    runOnUiThread { handleRecordingState(state) }
                }
            }

            voiceService?.onMaxDurationReached = {
                if (!isDestroyed && !isFinishing) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, R.string.voice_success, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            voiceService?.onWarningDurationReached = {
                if (!isDestroyed && !isFinishing) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, R.string.voice_max_duration_warning, Toast.LENGTH_LONG).show()
                    }
                }
            }

            voiceService?.onUploadStateChanged = { state ->
                if (!isDestroyed && !isFinishing) {
                    runOnUiThread { handleUploadState(state) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            isServiceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            startRecording()
        } else {
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                showPermissionSettingsDialog()
            } else {
                Toast.makeText(this, R.string.voice_mic_permission, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceCaptureApi = VoiceCaptureApi(this)
        voiceCaptureApi.cleanupStaleRecordings()

        setupSubmitButton()
        setupVoiceButton()
        bindVoiceService()
        checkPendingUpload()
    }

    override fun onDestroy() {
        // Clear callbacks FIRST to prevent crash from callback during destruction
        voiceService?.onRecordingStateChanged = null
        voiceService?.onMaxDurationReached = null
        voiceService?.onWarningDurationReached = null
        voiceService?.onUploadStateChanged = null

        // Cancel all pending postDelayed callbacks
        pendingRunnables.forEach { binding.root.removeCallbacks(it) }
        pendingRunnables.clear()

        // Stop timer
        stopTimer()

        // Unbind service
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }

        super.onDestroy()
    }

    private fun bindVoiceService() {
        val intent = Intent(this, VoiceRecordingService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupVoiceButton() {
        binding.btnVoice.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (hasRecordingPermission()) {
                        startRecording()
                    } else {
                        requestRecordingPermission()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (voiceService?.isCurrentlyRecording() == true) {
                        stopRecording()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun hasRecordingPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRecordingPermission() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Microphone permission is required for voice capture. Please enable it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRecording() {
        // Use bound service only - it handles foreground service promotion internally
        // This avoids race condition where Intent and bound service generate different UUIDs
        if (!isServiceBound || voiceService == null) {
            Toast.makeText(this, "Recording service not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val started = voiceService?.startRecording() ?: false
        if (started) {
            updateVoiceButtonRecording(true)
            startTimer()
        }
    }

    private fun stopRecording() {
        stopTimer()
        val result = voiceService?.stopRecording()
        updateVoiceButtonRecording(false)

        result?.let { uploadRecording(it) }
    }

    private fun uploadRecording(result: RecordingResult) {
        showUploadingState()
        // Upload via service (survives app backgrounding)
        voiceService?.uploadRecording(result)
    }

    private fun handleUploadState(state: UploadState) {
        when (state) {
            is UploadState.Uploading -> {
                showUploadingState()
            }
            is UploadState.Success -> {
                val transcript = state.response.transcript ?: ""
                showTranscript(transcript)
                addToHistory("voice", transcript.ifEmpty { "(no transcript)" })
            }
            is UploadState.Error -> {
                showVoiceError(state.message)
            }
        }
    }

    private fun checkPendingUpload() {
        val pending = voiceCaptureApi.findPendingUpload()
        if (pending != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.voice_pending_title)
                .setPositiveButton(R.string.voice_pending_retry) { _, _ ->
                    // Create a RecordingResult for the pending upload
                    val result = RecordingResult(
                        file = pending.file,
                        uuid = pending.uuid,
                        durationMs = 0,  // Unknown duration for recovered files
                        fileSize = pending.file.length()
                    )
                    uploadRecording(result)
                }
                .setNegativeButton(R.string.voice_pending_discard) { _, _ ->
                    pending.file.delete()
                }
                .show()
        }
    }

    private fun handleRecordingState(state: RecordingState) {
        when (state) {
            is RecordingState.Idle -> {
                updateVoiceButtonRecording(false)
                hideVoiceStatus()
            }
            is RecordingState.Recording -> {
                // Timer handles this
            }
            is RecordingState.Completed -> {
                // uploadRecording handles this
            }
            is RecordingState.Cancelled -> {
                updateVoiceButtonRecording(false)
                Toast.makeText(this, R.string.voice_cancelled, Toast.LENGTH_SHORT).show()
            }
            is RecordingState.TooShort -> {
                updateVoiceButtonRecording(false)
                Toast.makeText(this, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
            }
            is RecordingState.Error -> {
                updateVoiceButtonRecording(false)
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateVoiceButtonRecording(recording: Boolean) {
        if (recording) {
            binding.btnVoice.text = getString(R.string.voice_release_to_send)
            binding.btnVoice.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            binding.voiceTimer.visibility = View.VISIBLE
            binding.voiceTranscript.visibility = View.GONE
        } else {
            binding.btnVoice.text = getString(R.string.voice_hold_to_talk)
            binding.btnVoice.setBackgroundColor(getColor(android.R.color.darker_gray))
            binding.voiceTimer.visibility = View.GONE
        }
    }

    private fun showUploadingState() {
        binding.voiceStatus.text = getString(R.string.voice_transcribing)
        binding.voiceStatus.visibility = View.VISIBLE
        binding.voiceProgress.visibility = View.VISIBLE
        binding.voiceTranscript.visibility = View.GONE
    }

    private fun showTranscript(transcript: String) {
        binding.voiceStatus.visibility = View.GONE
        binding.voiceProgress.visibility = View.GONE
        binding.voiceTranscript.text = transcript
        binding.voiceTranscript.visibility = View.VISIBLE

        // Auto-hide after 5 seconds (tracked for cleanup)
        val runnable = Runnable {
            if (!isDestroyed && !isFinishing) {
                binding.voiceTranscript.visibility = View.GONE
            }
        }
        pendingRunnables.add(runnable)
        binding.root.postDelayed(runnable, 5000)
    }

    private fun showVoiceError(message: String) {
        binding.voiceProgress.visibility = View.GONE
        binding.voiceStatus.text = message
        binding.voiceStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        binding.voiceStatus.visibility = View.VISIBLE

        val runnable = Runnable {
            if (!isDestroyed && !isFinishing) {
                binding.voiceStatus.visibility = View.GONE
                binding.voiceStatus.setTextColor(getColor(android.R.color.darker_gray))
            }
        }
        pendingRunnables.add(runnable)
        binding.root.postDelayed(runnable, 3000)
    }

    private fun hideVoiceStatus() {
        binding.voiceStatus.visibility = View.GONE
        binding.voiceProgress.visibility = View.GONE
    }

    private fun startTimer() {
        timerRunnable = object : Runnable {
            override fun run() {
                val durationMs = voiceService?.getCurrentDurationMs() ?: 0
                val seconds = (durationMs / 1000).toInt()
                val minutes = seconds / 60
                val secs = seconds % 60
                binding.voiceTimer.text = String.format("%02d:%02d", minutes, secs)

                // Warning at 4:30
                if (durationMs >= VoiceRecordingService.WARNING_DURATION_MS &&
                    durationMs < VoiceRecordingService.WARNING_DURATION_MS + 1000) {
                    Toast.makeText(this@MainActivity, R.string.voice_max_duration_warning, Toast.LENGTH_LONG).show()
                }

                timerHandler.postDelayed(this, 100)
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun addToHistory(type: String, content: String) {
        val time = dateFormat.format(Date())
        val icon = if (type == "voice") "\uD83C\uDFA4" else "\u2705"
        val preview = if (content.length > 35) content.take(35) + "..." else content
        recentCaptures.add(0, "$time $icon $preview")
        if (recentCaptures.size > 5) recentCaptures.removeLast()
        updateCaptureHistory()
    }

    // ========== Text Capture (existing) ==========

    private fun setupSubmitButton() {
        binding.btnSubmit.setOnClickListener {
            val content = binding.inputText.text?.toString()?.trim() ?: ""

            if (content.isEmpty()) {
                Toast.makeText(this, R.string.error_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sendCapture(content)
        }
    }

    private fun sendCapture(content: String) {
        setLoading(true)
        hideKeyboard()

        lifecycleScope.launch {
            val request = CaptureRequest(
                captureType = "task",
                content = content,
                deviceId = voiceCaptureApi.getDeviceId()
            )

            val response = captureApi.sendCapture(request)

            setLoading(false)

            if (response.success) {
                onCaptureSuccess(content)
            } else {
                onCaptureError(response.error ?: "Unknown error")
            }
        }
    }

    private fun onCaptureSuccess(content: String) {
        binding.statusText.text = getString(R.string.status_success)
        binding.statusText.setTextColor(getColor(android.R.color.holo_green_dark))
        binding.inputText.text?.clear()

        addToHistory("task", content)

        Toast.makeText(this, R.string.status_success, Toast.LENGTH_SHORT).show()

        val runnable = Runnable {
            if (!isDestroyed && !isFinishing) {
                binding.statusText.text = getString(R.string.status_ready)
                binding.statusText.setTextColor(getColor(android.R.color.darker_gray))
            }
        }
        pendingRunnables.add(runnable)
        binding.root.postDelayed(runnable, 2000)
    }

    private fun onCaptureError(error: String) {
        binding.statusText.text = getString(R.string.status_error)
        binding.statusText.setTextColor(getColor(android.R.color.holo_red_dark))
        Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()

        val runnable = Runnable {
            if (!isDestroyed && !isFinishing) {
                binding.statusText.text = getString(R.string.status_ready)
                binding.statusText.setTextColor(getColor(android.R.color.darker_gray))
            }
        }
        pendingRunnables.add(runnable)
        binding.root.postDelayed(runnable, 3000)
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !loading
        binding.inputText.isEnabled = !loading
        if (loading) {
            binding.statusText.text = getString(R.string.status_sending)
            binding.statusText.setTextColor(getColor(android.R.color.darker_gray))
        }
    }

    private fun updateCaptureHistory() {
        binding.captureHistory.text = if (recentCaptures.isEmpty()) {
            "No captures yet"
        } else {
            recentCaptures.joinToString("\n")
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}
