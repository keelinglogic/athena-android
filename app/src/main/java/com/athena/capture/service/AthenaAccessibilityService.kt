package com.athena.capture.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service that enables background PTT operation.
 *
 * This service doesn't process accessibility events - it exists solely to provide
 * an exemption from Android 14+ background foreground service restrictions.
 *
 * When this service is enabled, FlicService can start VoiceRecordingService
 * (microphone foreground service) even when the app is in background.
 *
 * User must enable this service once in Settings → Accessibility → Athena.
 */
class AthenaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AthenaAccessibility"

        @Volatile
        var instance: AthenaAccessibilityService? = null
            private set

        /**
         * Check if the Accessibility Service is currently running.
         * Used by FlicService to verify background PTT capability.
         */
        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service connected - background PTT enabled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used - Flic SDK handles button events directly
        // This service exists only for the background FGS exemption
    }

    override fun onInterrupt() {
        // Required override - nothing to do
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Accessibility Service destroyed - background PTT disabled")
        super.onDestroy()
    }
}
