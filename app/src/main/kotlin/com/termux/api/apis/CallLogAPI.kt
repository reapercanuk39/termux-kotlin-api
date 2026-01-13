package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.util.JsonWriter

import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * API that allows you to get call log history information
 */
object CallLogAPI {

    private const val LOG_TAG = "CallLogAPI"

    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val offset = intent.getIntExtra("offset", 0)
        val limit = intent.getIntExtra("limit", 50)

        ResultReturner.returnData(context, intent, object : ResultReturner.ResultJsonWriter() {
            override fun writeJson(out: JsonWriter) {
                getCallLogs(context, out, offset, limit)
            }
        })
    }

    private fun getCallLogs(context: Context, out: JsonWriter, offset: Int, limit: Int) {
        val contentResolver = context.contentResolver

        contentResolver.query(
            CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
                .appendQueryParameter(CallLog.Calls.OFFSET_PARAM_KEY, offset.toString())
                .build(),
            null, null, null, "date DESC"
        )?.use { cur ->
            cur.moveToLast()

            val nameIndex = cur.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val numberIndex = cur.getColumnIndex(CallLog.Calls.NUMBER)
            val dateIndex = cur.getColumnIndex(CallLog.Calls.DATE)
            val durationIndex = cur.getColumnIndex(CallLog.Calls.DURATION)
            val callTypeIndex = cur.getColumnIndex(CallLog.Calls.TYPE)
            val simTypeIndex = cur.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            out.beginArray()

            for (j in 0 until cur.count) {
                out.beginObject()

                out.name("name").value(getCallerNameString(cur.getString(nameIndex)))
                out.name("phone_number").value(cur.getString(numberIndex))
                out.name("type").value(getCallTypeString(cur.getInt(callTypeIndex)))
                out.name("date").value(getDateString(cur.getLong(dateIndex), dateFormat))
                out.name("duration").value(getTimeString(cur.getInt(durationIndex)))
                out.name("sim_id").value(cur.getString(simTypeIndex))

                cur.moveToPrevious()
                out.endObject()
            }
            out.endArray()
        }
    }

    private fun getCallTypeString(type: Int): String = when (type) {
        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
        CallLog.Calls.MISSED_TYPE -> "MISSED"
        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
        CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
        else -> "UNKNOWN_TYPE"
    }

    private fun getCallerNameString(name: String?): String = name ?: "UNKNOWN_CALLER"

    private fun getDateString(date: Long, dateFormat: SimpleDateFormat): String =
        dateFormat.format(Date(date))

    private fun getTimeString(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
        }
    }
}
