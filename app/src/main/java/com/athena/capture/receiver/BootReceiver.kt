package com.athena.capture.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.athena.capture.service.FlicService
import io.flic.flic2libandroid.Flic2Manager

/**
 * Starts FlicService on device boot for headless PTT operation.
 * Allows Flic button to work without manually opening the app.
 * Only starts if Bluetooth permissions are already granted.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.i(TAG, "Boot completed - checking Flic requirements")

            // Check Bluetooth permissions first (required for foreground service type)
            if (!hasBluetoothPermissions(context)) {
                Log.i(TAG, "Bluetooth permissions not granted - skipping FlicService start")
                return
            }

            // Check if we have paired buttons
            try {
                val buttons = Flic2Manager.getInstance().buttons
                if (buttons.isEmpty()) {
                    Log.i(TAG, "No paired Flic buttons - skipping FlicService start")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not check Flic buttons", e)
                return
            }

            val serviceIntent = Intent(context, FlicService::class.java).apply {
                action = FlicService.ACTION_START
            }

            try {
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.i(TAG, "FlicService started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FlicService on boot", e)
            }
        }
    }

    private fun hasBluetoothPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
}
