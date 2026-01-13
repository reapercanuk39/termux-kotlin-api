package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

object VibrateAPI {

    private const val LOG_TAG = "VibrateAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        Thread {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val milliseconds = intent.getIntExtra("duration_ms", 1000)
            val force = intent.getBooleanExtra("force", false)

            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am == null) {
                Logger.logError(LOG_TAG, "Audio service null")
                return@Thread
            }

            // Do not vibrate if "Silent" ringer mode or "Do Not Disturb" is enabled and -f/--force option is not used.
            if (am.ringerMode != AudioManager.RINGER_MODE_SILENT || force) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(milliseconds.toLong(), VibrationEffect.DEFAULT_AMPLITUDE),
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .build()
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(milliseconds.toLong())
                    }
                } catch (e: Exception) {
                    // Issue on samsung devices on android 8
                    // java.lang.NullPointerException: Attempt to read from field 'android.os.VibrationEffect com.android.server.VibratorService$Vibration.mEffect' on a null object reference
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run vibrator", e)
                }
            }
        }.start()

        ResultReturner.noteDone(apiReceiver, intent)
    }
}
