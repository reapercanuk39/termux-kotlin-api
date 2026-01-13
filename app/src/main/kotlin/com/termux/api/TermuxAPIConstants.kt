package com.termux.api

import com.termux.shared.termux.TermuxConstants.TERMUX_API_PACKAGE_NAME
import com.termux.shared.termux.TermuxConstants.TERMUX_PACKAGE_NAME

object TermuxAPIConstants {
    /**
     * Termux:API Receiver name.
     */
    const val TERMUX_API_RECEIVER_NAME = "$TERMUX_API_PACKAGE_NAME.TermuxApiReceiver" // Default to "com.termux.api.TermuxApiReceiver"

    /** The Uri authority for Termux:API app file shares */
    const val TERMUX_API_FILE_SHARE_URI_AUTHORITY = "$TERMUX_PACKAGE_NAME.sharedfiles" // Default: "com.termux.sharedfiles"
}
