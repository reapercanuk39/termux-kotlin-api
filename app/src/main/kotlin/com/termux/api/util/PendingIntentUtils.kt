package com.termux.api.util

import android.app.PendingIntent
import android.os.Build

/**
 * Utility object for PendingIntent flag handling.
 */
object PendingIntentUtils {

    /**
     * Get [PendingIntent.FLAG_IMMUTABLE] flag.
     *
     * - https://developer.android.com/guide/components/intents-filters#DeclareMutabilityPendingIntent
     * - https://developer.android.com/about/versions/12/behavior-changes-12#pending-intent-mutability
     */
    @JvmStatic
    val pendingIntentImmutableFlag: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }

    /**
     * Get [PendingIntent.FLAG_MUTABLE] flag.
     *
     * - https://developer.android.com/guide/components/intents-filters#DeclareMutabilityPendingIntent
     * - https://developer.android.com/about/versions/12/behavior-changes-12#pending-intent-mutability
     */
    @JvmStatic
    val pendingIntentMutableFlag: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
}
