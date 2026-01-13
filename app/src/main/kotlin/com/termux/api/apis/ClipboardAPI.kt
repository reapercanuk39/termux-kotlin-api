package com.termux.api.apis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.TextUtils

import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

object ClipboardAPI {

    private const val LOG_TAG = "ClipboardAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip

        val version2 = "2" == intent.getStringExtra("api_version")
        if (version2) {
            val set = intent.getBooleanExtra("set", false)
            if (set) {
                ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.WithStringInput() {
                    override fun trimInput(): Boolean = false

                    override fun writeResult(out: java.io.PrintWriter) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", inputString))
                    }
                })
            } else {
                ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultWriter { out ->
                    if (clipData == null) {
                        out.print("")
                    } else {
                        for (i in 0 until clipData.itemCount) {
                            val item = clipData.getItemAt(i)
                            val text = item.coerceToText(context)
                            if (!TextUtils.isEmpty(text)) {
                                out.print(text)
                            }
                        }
                    }
                })
            }
        } else {
            val newClipText = intent.getStringExtra("text")
            if (newClipText != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", newClipText))
            }

            ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultWriter { out ->
                if (newClipText == null) {
                    if (clipData == null) {
                        out.print("")
                    } else {
                        for (i in 0 until clipData.itemCount) {
                            val item = clipData.getItemAt(i)
                            val text = item.coerceToText(context)
                            if (!TextUtils.isEmpty(text)) {
                                out.print(text)
                            }
                        }
                    }
                }
            })
        }
    }
}
