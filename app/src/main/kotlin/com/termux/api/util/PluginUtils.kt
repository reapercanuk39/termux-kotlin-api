package com.termux.api.util

import android.app.PendingIntent
import android.content.Context
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_API_APP
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences

/**
 * Utility object for plugin operations.
 */
object PluginUtils {

    /**
     * Try to get the next unique [PendingIntent] request code that isn't already being used by
     * the app and which would create a unique [PendingIntent] that doesn't conflict with that
     * of any other execution commands.
     *
     * @param context The [Context] for operations.
     * @return Returns the request code that should be safe to use.
     */
    @JvmStatic
    @Synchronized
    fun getLastPendingIntentRequestCode(context: Context?): Int {
        if (context == null) return TERMUX_API_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE

        val preferences = TermuxAPIAppSharedPreferences.build(context)
            ?: return TERMUX_API_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE

        val lastPendingIntentRequestCode = preferences.lastPendingIntentRequestCode

        var nextPendingIntentRequestCode = lastPendingIntentRequestCode + 1

        if (nextPendingIntentRequestCode == Int.MAX_VALUE || nextPendingIntentRequestCode < 0) {
            nextPendingIntentRequestCode = TERMUX_API_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE
        }

        preferences.setLastPendingIntentRequestCode(nextPendingIntentRequestCode)
        return nextPendingIntentRequestCode
    }
}
