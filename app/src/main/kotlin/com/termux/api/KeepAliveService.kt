package com.termux.api

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.termux.shared.logger.Logger

class KeepAliveService : Service() {

    companion object {
        private const val LOG_TAG = "KeepAliveService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.logDebug(LOG_TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
