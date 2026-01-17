package com.athena.capture

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.athena.capture.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val captureApi = CaptureApi()
    private val recentCaptures = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSubmitButton()
    }

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
                deviceId = buildDeviceId()
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

        val time = dateFormat.format(Date())
        val preview = if (content.length > 40) content.take(40) + "..." else content
        recentCaptures.add(0, "$time - $preview")
        if (recentCaptures.size > 5) recentCaptures.removeLast()
        updateCaptureHistory()

        Toast.makeText(this, R.string.status_success, Toast.LENGTH_SHORT).show()

        binding.root.postDelayed({
            binding.statusText.text = getString(R.string.status_ready)
            binding.statusText.setTextColor(getColor(android.R.color.darker_gray))
        }, 2000)
    }

    private fun onCaptureError(error: String) {
        binding.statusText.text = getString(R.string.status_error)
        binding.statusText.setTextColor(getColor(android.R.color.holo_red_dark))
        Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()

        binding.root.postDelayed({
            binding.statusText.text = getString(R.string.status_ready)
            binding.statusText.setTextColor(getColor(android.R.color.darker_gray))
        }, 3000)
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

    private fun buildDeviceId(): String {
        return "${Build.MANUFACTURER}-${Build.MODEL}".lowercase().replace(" ", "-")
    }
}
