package com.termux.api

import android.app.Application
import android.content.Context
import android.util.Log
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.crash.TermuxCrashUtils
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences

class TermuxAPIApplication : Application() {

    companion object {
        const val LOG_TAG = "TermuxAPIApplication"

        @JvmStatic
        fun setLogConfig(context: Context, commitToFile: Boolean) {
            Logger.setDefaultLogTag(TermuxConstants.TERMUX_API_APP_NAME.replace("[: ]".toRegex(), ""))

            // Load the log level from shared preferences and set it to the Logger.CURRENT_LOG_LEVEL
            val preferences = TermuxAPIAppSharedPreferences.build(context) ?: return
            preferences.setLogLevel(null, preferences.getLogLevel(true), commitToFile)
        }
    }

    override fun onCreate() {
        super.onCreate()

        Log.i(LOG_TAG, "AppInit")

        val context = applicationContext

        // Set crash handler for the app
        TermuxCrashUtils.setCrashHandler(context)

        ResultReturner.setContext(this)

        // Set log config for the app
        setLogConfig(context, true)

        SocketListener.createSocketListener(this)
    }
}
