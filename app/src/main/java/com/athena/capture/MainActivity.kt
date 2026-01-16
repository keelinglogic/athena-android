package com.athena.capture

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.athena.capture.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
    }

    private fun setupButtons() {
        binding.btnCapturePhoto.setOnClickListener {
            // TODO: Implement photo capture
        }

        binding.btnCaptureAudio.setOnClickListener {
            // TODO: Implement audio recording
        }

        binding.btnCaptureText.setOnClickListener {
            // TODO: Implement text input
        }

        binding.btnQuickTask.setOnClickListener {
            // TODO: Implement quick task entry
        }
    }
}
