package com.athena.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.athena.capture.databinding.ActivitySettingsBinding
import com.athena.capture.databinding.ItemFlicButtonBinding
import com.athena.capture.service.AthenaAccessibilityService
import com.athena.capture.service.FlicService
import io.flic.flic2libandroid.Flic2Button
import io.flic.flic2libandroid.Flic2Manager
import io.flic.flic2libandroid.Flic2ScanCallback

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    private lateinit var binding: ActivitySettingsBinding
    private val buttonAdapter = FlicButtonAdapter()
    private var isScanning = false

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startFlicScan()
        } else {
            Toast.makeText(this, "Bluetooth permissions required for Flic button", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        refreshButtonList()
    }

    override fun onResume() {
        super.onResume()
        refreshButtonList()
        updateAccessibilityStatus()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.flicButtonList.adapter = buttonAdapter
        buttonAdapter.onRemoveClick = { button -> confirmRemoveButton(button) }

        binding.btnScanFlic.setOnClickListener {
            if (isScanning) {
                stopFlicScan()
            } else {
                if (hasBluetoothPermissions()) {
                    startFlicScan()
                } else {
                    requestBluetoothPermissions()
                }
            }
        }

        // Accessibility Service
        binding.btnEnableAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private fun updateAccessibilityStatus() {
        val enabled = AthenaAccessibilityService.isRunning()
        binding.accessibilityStatus.text = if (enabled) {
            getString(R.string.accessibility_status_enabled)
        } else {
            getString(R.string.accessibility_status_disabled)
        }
        binding.accessibilityStatus.setTextColor(
            if (enabled) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.darker_gray)
        )
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun refreshButtonList() {
        try {
            val buttons = Flic2Manager.getInstance().buttons
            buttonAdapter.updateButtons(buttons)

            if (buttons.isEmpty()) {
                binding.flicButtonList.visibility = View.GONE
                binding.noButtonsText.visibility = View.VISIBLE
            } else {
                binding.flicButtonList.visibility = View.VISIBLE
                binding.noButtonsText.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing button list", e)
            binding.flicButtonList.visibility = View.GONE
            binding.noButtonsText.visibility = View.VISIBLE
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        bluetoothPermissionLauncher.launch(permissions)
    }

    private fun startFlicScan() {
        if (isScanning) return

        try {
            isScanning = true
            binding.btnScanFlic.text = "Stop Scanning"
            binding.scanStatus.text = "Click your Flic button to pair..."
            binding.scanStatus.visibility = View.VISIBLE

            Flic2Manager.getInstance().startScan(object : Flic2ScanCallback {
                override fun onDiscoveredAlreadyPairedButton(button: Flic2Button) {
                    runOnUiThread {
                        Log.i(TAG, "Found already paired button: ${button.bdAddr}")
                        Toast.makeText(this@SettingsActivity, "Button already paired", Toast.LENGTH_SHORT).show()
                        stopFlicScan()
                        refreshButtonList()
                        startFlicService()
                    }
                }

                override fun onDiscovered(bdAddr: String) {
                    Log.i(TAG, "Discovered button: $bdAddr")
                    runOnUiThread {
                        binding.scanStatus.text = "Found button $bdAddr, connecting..."
                    }
                }

                override fun onConnected() {
                    Log.i(TAG, "Scan: button connected")
                    runOnUiThread {
                        binding.scanStatus.text = "Connected, verifying..."
                    }
                }

                override fun onComplete(result: Int, subCode: Int, button: Flic2Button?) {
                    runOnUiThread {
                        isScanning = false
                        binding.btnScanFlic.text = "Scan for Flic Button"

                        if (result == Flic2ScanCallback.RESULT_SUCCESS) {
                            Log.i(TAG, "Button paired successfully: ${button?.bdAddr}")
                            Toast.makeText(this@SettingsActivity, "Flic button paired!", Toast.LENGTH_SHORT).show()
                            binding.scanStatus.visibility = View.GONE
                            refreshButtonList()
                            startFlicService()
                        } else {
                            Log.w(TAG, "Scan failed: result=$result, subCode=$subCode")
                            binding.scanStatus.text = "Pairing failed (code: $result). Try again."
                        }
                    }
                }
            })

            Log.i(TAG, "Flic scan started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Flic scan", e)
            isScanning = false
            binding.btnScanFlic.text = "Scan for Flic Button"
            binding.scanStatus.text = "Error: ${e.message}"
            binding.scanStatus.visibility = View.VISIBLE
        }
    }

    private fun stopFlicScan() {
        try {
            Flic2Manager.getInstance().stopScan()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan", e)
        }
        isScanning = false
        binding.btnScanFlic.text = "Scan for Flic Button"
        binding.scanStatus.visibility = View.GONE
    }

    private fun confirmRemoveButton(button: Flic2Button) {
        AlertDialog.Builder(this)
            .setTitle("Remove Flic Button")
            .setMessage("Remove ${button.bdAddr} from paired buttons?")
            .setPositiveButton("Remove") { _, _ ->
                removeButton(button)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeButton(button: Flic2Button) {
        try {
            Flic2Manager.getInstance().forgetButton(button)
            Toast.makeText(this, "Button removed", Toast.LENGTH_SHORT).show()
            refreshButtonList()

            // Stop service if no buttons left
            val buttons = Flic2Manager.getInstance().buttons
            if (buttons.isEmpty()) {
                stopFlicService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing button", e)
            Toast.makeText(this, "Failed to remove button", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startFlicService() {
        val intent = Intent(this, FlicService::class.java).apply {
            action = FlicService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FlicService", e)
        }
    }

    private fun stopFlicService() {
        val intent = Intent(this, FlicService::class.java).apply {
            action = FlicService.ACTION_STOP
        }
        startService(intent)
    }

    // ========== RecyclerView Adapter ==========

    inner class FlicButtonAdapter : RecyclerView.Adapter<FlicButtonAdapter.ViewHolder>() {

        private val buttons = mutableListOf<Flic2Button>()
        var onRemoveClick: ((Flic2Button) -> Unit)? = null

        fun updateButtons(newButtons: List<Flic2Button>) {
            buttons.clear()
            buttons.addAll(newButtons)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemFlicButtonBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(buttons[position])
        }

        override fun getItemCount() = buttons.size

        inner class ViewHolder(private val binding: ItemFlicButtonBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(button: Flic2Button) {
                binding.buttonName.text = button.name ?: "Flic Button"
                binding.buttonAddress.text = button.bdAddr

                // Connection indicator
                val isConnected = button.connectionState == Flic2Button.CONNECTION_STATE_CONNECTED_READY
                val indicator = binding.connectionIndicator.background as? GradientDrawable
                    ?: GradientDrawable().also { binding.connectionIndicator.background = it }

                val color = if (isConnected) {
                    binding.root.context.getColor(android.R.color.holo_green_dark)
                } else {
                    binding.root.context.getColor(android.R.color.darker_gray)
                }
                indicator.setColor(color)

                binding.btnRemove.setOnClickListener {
                    onRemoveClick?.invoke(button)
                }
            }
        }
    }
}
