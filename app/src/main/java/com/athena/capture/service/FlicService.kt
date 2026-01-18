package com.athena.capture.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.athena.capture.AthenaApp
import com.athena.capture.MainActivity
import com.athena.capture.R
import io.flic.flic2libandroid.Flic2Button
import io.flic.flic2libandroid.Flic2ButtonListener
import io.flic.flic2libandroid.Flic2Manager

/**
 * Foreground service that monitors Flic button events and triggers voice recording.
 *
 * Press button = start recording
 * Release button = stop recording
 *
 * Runs persistently to support background/headless PTT operation.
 */
class FlicService : Service() {

    companion object {
        private const val TAG = "FlicService"
        const val NOTIFICATION_ID = 1002  // Different from VoiceRecordingService (1001)

        const val ACTION_START = "com.athena.capture.action.START_FLIC_SERVICE"
        const val ACTION_STOP = "com.athena.capture.action.STOP_FLIC_SERVICE"
    }

    // Map of button address to listener - prevents duplicate listeners
    private val buttonListeners = mutableMapOf<String, Flic2ButtonListener>()

    // Track connection status for UI updates
    var onConnectionStateChanged: ((String, Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "FlicService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "FlicService onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stopping FlicService")
                disconnectAllButtons()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Start or restart - go foreground immediately then connect
                startForegroundWithNotification("Listening for Flic button...")
                connectToAllButtons()
            }
        }

        // START_STICKY: Service respawns after system termination
        return START_STICKY
    }

    /**
     * Start as foreground service with appropriate service type.
     * Must be called within 5 seconds of startForegroundService().
     */
    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Connect to all paired Flic buttons.
     */
    private fun connectToAllButtons() {
        try {
            val manager = Flic2Manager.getInstance()
            val buttons = manager.buttons

            if (buttons.isEmpty()) {
                Log.i(TAG, "No paired Flic buttons found")
                updateNotification("No Flic button paired")
                return
            }

            for (button in buttons) {
                connectButton(button)
            }

            Log.i(TAG, "Connected to ${buttons.size} Flic button(s)")
            updateNotification("Flic button connected")
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to buttons", e)
            updateNotification("Flic error - check permissions")
        }
    }

    /**
     * Connect to a single button and register listener.
     */
    private fun connectButton(button: Flic2Button) {
        val bdAddr = button.bdAddr

        // Prevent duplicate listeners
        if (buttonListeners.containsKey(bdAddr)) {
            Log.d(TAG, "Button $bdAddr already has a listener")
            return
        }

        val listener = createButtonListener(bdAddr)
        buttonListeners[bdAddr] = listener

        button.addListener(listener)
        button.connect()

        Log.i(TAG, "Connected to button: $bdAddr")
    }

    /**
     * Create button listener with proper event handling.
     */
    private fun createButtonListener(bdAddr: String): Flic2ButtonListener {
        return object : Flic2ButtonListener() {

            override fun onButtonUpOrDown(
                button: Flic2Button,
                wasQueued: Boolean,
                lastQueued: Boolean,
                timestamp: Long,
                isUp: Boolean,
                isDown: Boolean
            ) {
                // CRITICAL: Ignore queued events from reconnection
                // Without this check, stale events cause duplicate recordings
                if (wasQueued) {
                    Log.d(TAG, "Ignoring queued event from $bdAddr (timestamp: $timestamp)")
                    return
                }

                if (isDown) {
                    Log.i(TAG, "Button DOWN: $bdAddr -> Starting recording")
                    startVoiceRecording()
                }

                if (isUp) {
                    Log.i(TAG, "Button UP: $bdAddr -> Stopping recording")
                    stopVoiceRecording()
                }
            }

            override fun onConnect(button: Flic2Button) {
                Log.i(TAG, "Button connected: $bdAddr")
                updateNotification("Flic button connected")
                onConnectionStateChanged?.invoke(bdAddr, true)
            }

            override fun onReady(button: Flic2Button, timestamp: Long) {
                Log.i(TAG, "Button ready: $bdAddr")
            }

            override fun onDisconnect(button: Flic2Button) {
                Log.w(TAG, "Button disconnected: $bdAddr - attempting reconnect")
                updateNotification("Flic reconnecting...")
                onConnectionStateChanged?.invoke(bdAddr, false)

                // Auto-reconnect (Flic SDK handles retry internally)
                button.connect()
            }

            override fun onFailure(button: Flic2Button, errorCode: Int, subCode: Int) {
                Log.e(TAG, "Button failure: $bdAddr, error=$errorCode, sub=$subCode")
                updateNotification("Flic error ($errorCode)")
            }

            override fun onUnpaired(button: Flic2Button) {
                Log.w(TAG, "Button unpaired: $bdAddr")
                buttonListeners.remove(bdAddr)
                updateNotification("Flic button unpaired")
            }
        }
    }

    /**
     * Start voice recording via VoiceRecordingService.
     * Checks for RECORD_AUDIO permission first - required for microphone FGS on Android 14+.
     *
     * Background operation requires Accessibility Service to be enabled (bypasses
     * Android 14+ while-in-use permission restrictions).
     */
    private fun startVoiceRecording() {
        // Check RECORD_AUDIO permission before starting microphone foreground service
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start recording - RECORD_AUDIO permission not granted")
            updateNotification("Mic permission needed - open app")
            return
        }

        // Log accessibility service status for debugging background PTT issues
        val accessibilityEnabled = AthenaAccessibilityService.isRunning()
        Log.i(TAG, "Starting recording, accessibility service enabled: $accessibilityEnabled")

        val intent = Intent(this, VoiceRecordingService::class.java).apply {
            action = VoiceRecordingService.ACTION_START
        }
        startForegroundService(intent)
    }

    /**
     * Stop voice recording via VoiceRecordingService.
     */
    private fun stopVoiceRecording() {
        val intent = Intent(this, VoiceRecordingService::class.java).apply {
            action = VoiceRecordingService.ACTION_STOP
        }
        startService(intent)
    }

    /**
     * Disconnect all buttons and remove listeners.
     */
    private fun disconnectAllButtons() {
        try {
            val manager = Flic2Manager.getInstance()
            for (button in manager.buttons) {
                buttonListeners[button.bdAddr]?.let { listener ->
                    button.removeListener(listener)
                }
                button.disconnectOrAbortPendingConnection()
            }
            buttonListeners.clear()
            Log.i(TAG, "Disconnected all buttons")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting buttons", e)
        }
    }

    /**
     * Update the ongoing notification text.
     */
    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Build foreground service notification.
     */
    private fun buildNotification(text: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AthenaApp.FLIC_CHANNEL_ID)
            .setContentTitle("Athena Flic")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(mainPendingIntent)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "FlicService destroyed")
        disconnectAllButtons()
        super.onDestroy()
    }
}
