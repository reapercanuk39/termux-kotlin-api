package com.termux.api.apis

import android.app.DownloadManager
import android.app.DownloadManager.Request
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import java.io.File

object DownloadAPI {

    private const val LOG_TAG = "DownloadAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultWriter { out ->
            val downloadUri: Uri? = intent.data
            if (downloadUri == null) {
                out.println("No download URI specified")
            } else {
                val title = intent.getStringExtra("title")
                val description = intent.getStringExtra("description")
                val path = intent.getStringExtra("path")

                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val req = Request(downloadUri).apply {
                    setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setVisibleInDownloadsUi(true)

                    title?.let { setTitle(it) }
                    description?.let { setDescription(it) }
                    path?.let { setDestinationUri(Uri.fromFile(File(it))) }
                }

                manager.enqueue(req)
            }
        })
    }
}
