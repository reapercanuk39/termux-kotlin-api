package com.termux.api.apis

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.JsonWriter
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import java.text.SimpleDateFormat
import java.util.Date

object NotificationListAPI {

    private const val LOG_TAG = "NotificationListAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            @Throws(Exception::class)
            override fun writeJson(out: JsonWriter) {
                listNotifications(context, out)
            }
        })
    }

    @JvmStatic
    @Throws(Exception::class)
    fun listNotifications(context: Context, out: JsonWriter) {
        val notificationService = NotificationService.get()
        val notifications = notificationService?.activeNotifications ?: arrayOf()

        out.beginArray()
        for (n in notifications) {
            val id = n.id
            var key = ""
            var title = ""
            var text = ""
            var lines: Array<CharSequence>? = null
            var packageName = ""
            var tag = ""
            var group = ""
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            val `when` = dateFormat.format(Date(n.notification.`when`))

            n.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.let {
                title = it.toString()
            }
            n.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let {
                text = it.toString()
            } ?: n.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.let {
                text = it.toString()
            }
            n.notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let {
                lines = it
            }
            n.tag?.let { tag = it }
            n.notification.group?.let { group = it }
            n.key?.let { key = it }
            n.packageName?.let { packageName = it }

            out.beginObject()
                .name("id").value(id.toLong())
                .name("tag").value(tag)
                .name("key").value(key)
                .name("group").value(group)
                .name("packageName").value(packageName)
                .name("title").value(title)
                .name("content").value(text)
                .name("when").value(`when`)
            
            lines?.let {
                out.name("lines").beginArray()
                for (line in it) {
                    out.value(line.toString())
                }
                out.endArray()
            }
            out.endObject()
        }
        out.endArray()
    }

    class NotificationService : NotificationListenerService() {

        override fun onListenerConnected() {
            _this = this
        }

        override fun onListenerDisconnected() {
            _this = null
        }

        companion object {
            private var _this: NotificationService? = null

            @JvmStatic
            fun get(): NotificationService? = _this
        }
    }
}
