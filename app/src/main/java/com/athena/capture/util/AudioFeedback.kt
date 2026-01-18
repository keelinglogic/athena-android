package com.athena.capture.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Provides audio and haptic feedback for PTT recording events.
 *
 * Uses ToneGenerator for audio tones (simpler than SoundPool, no audio files needed)
 * and Vibrator for haptic feedback as silent mode fallback.
 *
 * Distinct tones for each action:
 * - Record start: HIGH double-beep (two quick high tones) - "ready!"
 * - Record stop: LOW single tone - "stopped"
 * - Upload success: Rising two-tone chirp - "done!"
 * - Error: Long low warning tone - "failed"
 */
object AudioFeedback {
    private const val TAG = "AudioFeedback"

    // Vibration durations in milliseconds
    private const val VIBRATE_SHORT_MS = 50L
    private const val VIBRATE_MEDIUM_MS = 100L
    private const val VIBRATE_LONG_MS = 200L

    // ToneGenerator volume (0-100)
    private const val TONE_VOLUME = 100

    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var isInitialized = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Initialize audio feedback system.
     * Call once from Application.onCreate().
     */
    fun init(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }

        try {
            // Use STREAM_NOTIFICATION for feedback tones - respects Do Not Disturb
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
            Log.i(TAG, "ToneGenerator initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ToneGenerator", e)
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            Log.i(TAG, "Vibrator initialized, hasVibrator=${vibrator?.hasVibrator()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Vibrator", e)
        }

        isInitialized = true
        Log.i(TAG, "AudioFeedback initialized")
    }

    /**
     * Play feedback for recording start.
     * Single HIGH beep.
     */
    fun playRecordStart() {
        Log.d(TAG, "playRecordStart")
        // Single high beep
        playTone(ToneGenerator.TONE_DTMF_9, 100)  // 1477Hz
        vibrate(VIBRATE_SHORT_MS)
    }

    /**
     * Play feedback for successful upload.
     * Single LOW boop.
     */
    fun playUploadSuccess() {
        Log.d(TAG, "playUploadSuccess")
        // Single low boop
        playTone(ToneGenerator.TONE_DTMF_1, 150)  // 697Hz
        vibrate(VIBRATE_MEDIUM_MS)
    }

    /**
     * Play feedback for error (upload failed, recording error, etc).
     * Triple LOW boop-boop-boop.
     */
    fun playError() {
        Log.d(TAG, "playError")
        // Triple low boop
        playTone(ToneGenerator.TONE_DTMF_1, 100)
        handler.postDelayed({
            playTone(ToneGenerator.TONE_DTMF_1, 100)
        }, 150)
        handler.postDelayed({
            playTone(ToneGenerator.TONE_DTMF_1, 100)
        }, 300)
        vibrate(VIBRATE_LONG_MS)
    }

    /**
     * Play feedback for recording stopped (before upload).
     * Double HIGH beep-beep.
     */
    fun playRecordStop() {
        Log.d(TAG, "playRecordStop")
        // Double high beep
        playTone(ToneGenerator.TONE_DTMF_9, 80)
        handler.postDelayed({
            playTone(ToneGenerator.TONE_DTMF_9, 80)
        }, 120)
        vibrate(VIBRATE_SHORT_MS)
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        try {
            toneGenerator?.startTone(toneType, durationMs)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play tone", e)
        }
    }

    private fun vibrate(durationMs: Long) {
        try {
            vibrator?.let { v ->
                if (!v.hasVibrator()) return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to vibrate", e)
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        try {
            vibrator?.let { v ->
                if (!v.hasVibrator()) return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to vibrate pattern", e)
        }
    }

    /**
     * Release resources.
     * Call from Application.onTerminate() if needed (rarely called on Android).
     */
    fun release() {
        toneGenerator?.release()
        toneGenerator = null
        vibrator = null
        isInitialized = false
        Log.i(TAG, "AudioFeedback released")
    }
}
