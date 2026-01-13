package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.provider.Settings

import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

object BrightnessAPI {

    private const val LOG_TAG = "BrightnessAPI"

    @JvmStatic
    fun onReceive(receiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val contentResolver = context.contentResolver
        if (intent.hasExtra("auto")) {
            val auto = intent.getBooleanExtra("auto", false)
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
        }

        var brightness = intent.getIntExtra("brightness", 0)
        brightness = brightness.coerceIn(0, 255)

        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
        ResultReturner.noteDone(receiver, intent)
    }
}
