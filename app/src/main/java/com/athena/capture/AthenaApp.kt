package com.athena.capture

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.athena.capture.util.AudioFeedback
import io.flic.flic2libandroid.Flic2Manager

/**
 * Application class for Athena Capture.
 * Initializes Flic2Manager and creates notification channels.
 */
class AthenaApp : Application() {

    companion object {
        private const val TAG = "AthenaApp"
        const val FLIC_CHANNEL_ID = "flic_service_channel"
        const val FLIC_CHANNEL_NAME = "Flic Button Service"

        lateinit var instance: AthenaApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createFlicNotificationChannel()
        initFlicManager()
        AudioFeedback.init(this)
    }

    /**
     * Creates notification channel for FlicService.
     * Must be created before startForeground() is called.
     */
    private fun createFlicNotificationChannel() {
        val channel = NotificationChannel(
            FLIC_CHANNEL_ID,
            FLIC_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Flic button monitoring active"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        Log.i(TAG, "Flic notification channel created")
    }

    /**
     * Initializes Flic2Manager singleton.
     * Must be called once in Application.onCreate() before any Flic operations.
     * Handler ensures callbacks are delivered on main thread.
     */
    private fun initFlicManager() {
        try {
            Flic2Manager.initAndGetInstance(this, Handler(Looper.getMainLooper()))
            Log.i(TAG, "Flic2Manager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Flic2Manager", e)
        }
    }
}
