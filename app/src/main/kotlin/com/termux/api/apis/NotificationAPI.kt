package com.termux.api.apis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.util.Pair
import com.termux.api.R
import com.termux.api.TermuxAPIConstants
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.PendingIntentUtils
import com.termux.api.util.PluginUtils
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import java.io.File

object NotificationAPI {

    private const val LOG_TAG = "NotificationAPI"

    const val BIN_SH: String = "${TermuxConstants.TERMUX_PREFIX_DIR_PATH}/bin/sh"
    private const val CHANNEL_ID = "termux-notification"
    private const val CHANNEL_TITLE = "Termux API notification channel"
    private const val KEY_TEXT_REPLY = "TERMUX_TEXT_REPLY"

    @JvmStatic
    fun onReceiveShowNotification(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceiveShowNotification")

        val pair = buildNotification(context, intent)
        val notification = pair.first
        val notificationId = pair.second

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.WithStringInput() {
            override fun writeResult(out: java.io.PrintWriter) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                if (!TextUtils.isEmpty(inputString)) {
                    if (inputString.contains("\n")) {
                        val style = NotificationCompat.BigTextStyle()
                        style.bigText(inputString)
                        notification.setStyle(style)
                    } else {
                        notification.setContentText(inputString)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_TITLE,
                        priorityFromIntent(intent)
                    )
                    manager.createNotificationChannel(channel)
                }

                manager.notify(notificationId, 0, notification.build())
            }
        })
    }

    @JvmStatic
    fun onReceiveChannel(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceiveChannel")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val m = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = intent.getStringExtra("id")
                val channelName = intent.getStringExtra("name")

                if (channelId.isNullOrEmpty()) {
                    ResultReturner.returnData(apiReceiver, intent) { out ->
                        out.println("Channel id not specified.")
                    }
                    return
                }

                if (intent.getBooleanExtra("delete", false)) {
                    m.deleteNotificationChannel(channelId)
                    ResultReturner.returnData(apiReceiver, intent) { out ->
                        out.println("Deleted channel with id \"$channelId\".")
                    }
                    return
                }

                if (channelName.isNullOrEmpty()) {
                    ResultReturner.returnData(apiReceiver, intent) { out ->
                        out.println("Cannot create a channel without a name.")
                    }
                    return
                }

                val c = NotificationChannel(channelId, channelName, priorityFromIntent(intent))
                m.createNotificationChannel(c)
                ResultReturner.returnData(apiReceiver, intent) { out ->
                    out.println("Created channel with id \"$channelId\" and name \"$channelName\".")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                ResultReturner.returnData(apiReceiver, intent) { out ->
                    out.println("Could not create/delete channel.")
                }
            }
        } else {
            ResultReturner.returnData(apiReceiver, intent) { out ->
                out.println("Notification channels are only available on Android 8.0 and higher, use the options for termux-notification instead.")
            }
        }
    }

    private fun priorityFromIntent(intent: Intent): Int {
        val priorityExtra = intent.getStringExtra("priority") ?: "default"
        return when (priorityExtra) {
            "high", "max" -> NotificationManager.IMPORTANCE_HIGH
            "low" -> NotificationManager.IMPORTANCE_LOW
            "min" -> NotificationManager.IMPORTANCE_MIN
            else -> NotificationManager.IMPORTANCE_DEFAULT
        }
    }

    @JvmStatic
    fun buildNotification(context: Context, intent: Intent): Pair<NotificationCompat.Builder, String> {
        val priorityExtra = intent.getStringExtra("priority") ?: "default"
        val priority = when (priorityExtra) {
            "high" -> Notification.PRIORITY_HIGH
            "low" -> Notification.PRIORITY_LOW
            "max" -> Notification.PRIORITY_MAX
            "min" -> Notification.PRIORITY_MIN
            else -> Notification.PRIORITY_DEFAULT
        }

        val title = intent.getStringExtra("title")
        val lightsArgbExtra = intent.getStringExtra("led-color")

        var ledColor = 0
        if (lightsArgbExtra != null) {
            try {
                ledColor = lightsArgbExtra.toInt(16) or 0xff000000.toInt()
            } catch (e: NumberFormatException) {
                Logger.logError(LOG_TAG, "Invalid LED color format! Ignoring!")
            }
        }

        val ledOnMs = intent.getIntExtra("led-on", 800)
        val ledOffMs = intent.getIntExtra("led-off", 800)

        var vibratePattern = intent.getLongArrayExtra("vibrate")
        val useSound = intent.getBooleanExtra("sound", false)
        val ongoing = intent.getBooleanExtra("ongoing", false)
        val alertOnce = intent.getBooleanExtra("alert-once", false)

        val actionExtra = intent.getStringExtra("action")
        val notificationId = getNotificationId(intent)
        val groupKey = intent.getStringExtra("group")
        val channel = intent.getStringExtra("channel") ?: CHANNEL_ID

        val notification = NotificationCompat.Builder(context, channel).apply {
            setSmallIcon(R.drawable.ic_event_note_black_24dp)
            color = 0xFF000000.toInt()
            setContentTitle(title)
            setPriority(priority)
            setOngoing(ongoing)
            setOnlyAlertOnce(alertOnce)
            setWhen(System.currentTimeMillis())
            setShowWhen(true)
        }

        val smallIconName = intent.getStringExtra("icon")
        if (smallIconName != null) {
            val smallIconResourceName = "ic_${smallIconName}_black_24dp"
            var smallIconResourceId: Int? = null
            try {
                @Suppress("DiscouragedApi")
                smallIconResourceId = context.resources.getIdentifier(
                    smallIconResourceName,
                    "drawable",
                    context.packageName
                )
                if (smallIconResourceId == 0) {
                    smallIconResourceId = null
                    Logger.logError(LOG_TAG, "Failed to find \"$smallIconResourceName\" icon")
                }
            } catch (e: Exception) {
                Logger.logError(LOG_TAG, "Failed to find \"$smallIconResourceName\" icon: ${e.message}")
            }

            smallIconResourceId?.let { notification.setSmallIcon(it) }
        }

        val imagePath = intent.getStringExtra("image-path")
        if (imagePath != null) {
            val imgFile = File(imagePath)
            if (imgFile.exists()) {
                val myBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                notification.setLargeIcon(myBitmap)
                    .setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(myBitmap)
                    )
            }
        }

        val styleType = intent.getStringExtra("type")
        if (styleType == "media") {
            val mediaPrevious = intent.getStringExtra("media-previous")
            val mediaPause = intent.getStringExtra("media-pause")
            val mediaPlay = intent.getStringExtra("media-play")
            val mediaNext = intent.getStringExtra("media-next")

            if (mediaPrevious != null && mediaPause != null && mediaPlay != null && mediaNext != null) {
                if (smallIconName == null) {
                    notification.setSmallIcon(android.R.drawable.ic_media_play)
                }

                val previousIntent = createAction(context, mediaPrevious)
                val pauseIntent = createAction(context, mediaPause)
                val playIntent = createAction(context, mediaPlay)
                val nextIntent = createAction(context, mediaNext)

                notification.addAction(NotificationCompat.Action(android.R.drawable.ic_media_previous, "previous", previousIntent))
                notification.addAction(NotificationCompat.Action(android.R.drawable.ic_media_pause, "pause", pauseIntent))
                notification.addAction(NotificationCompat.Action(android.R.drawable.ic_media_play, "play", playIntent))
                notification.addAction(NotificationCompat.Action(android.R.drawable.ic_media_next, "next", nextIntent))

                notification.setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 3)
                )
            }
        }

        groupKey?.let { notification.setGroup(it) }

        if (ledColor != 0) {
            notification.setLights(ledColor, ledOnMs, ledOffMs)
            if (vibratePattern == null) {
                vibratePattern = longArrayOf(0)
            }
        }

        vibratePattern?.let {
            val vibrateArg = LongArray(it.size + 1)
            System.arraycopy(it, 0, vibrateArg, 1, it.size)
            notification.setVibrate(vibrateArg)
        }

        if (useSound) notification.setSound(Settings.System.DEFAULT_NOTIFICATION_URI)

        notification.setAutoCancel(true)

        actionExtra?.let {
            val pi = createAction(context, it)
            notification.setContentIntent(pi)
        }

        for (button in 1..3) {
            val buttonText = intent.getStringExtra("button_text_$button")
            val buttonAction = intent.getStringExtra("button_action_$button")

            if (buttonText != null && buttonAction != null) {
                if (buttonAction.contains("\$REPLY")) {
                    val action = createReplyAction(
                        context, intent,
                        button,
                        buttonText, buttonAction, notificationId
                    )
                    notification.addAction(action)
                } else {
                    val pi = createAction(context, buttonAction)
                    notification.addAction(NotificationCompat.Action(android.R.drawable.ic_input_add, buttonText, pi))
                }
            }
        }

        val onDeleteActionExtra = intent.getStringExtra("on_delete_action")
        onDeleteActionExtra?.let {
            val pi = createAction(context, it)
            notification.setDeleteIntent(pi)
        }

        return Pair(notification, notificationId)
    }

    private fun getNotificationId(intent: Intent): String {
        return intent.getStringExtra("id") ?: java.util.UUID.randomUUID().toString()
    }

    @JvmStatic
    fun onReceiveRemoveNotification(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceiveRemoveNotification")

        ResultReturner.noteDone(apiReceiver, intent)
        val notificationId = intent.getStringExtra("id")
        if (notificationId != null) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId, 0)
        }
    }

    @JvmStatic
    fun createReplyAction(
        context: Context, intent: Intent,
        buttonNum: Int,
        buttonText: String,
        buttonAction: String, notificationId: String
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(buttonText)
            .build()

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            buttonNum,
            getMessageReplyIntent(intent.clone() as Intent, buttonText, buttonAction, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_event_note_black_24dp,
            buttonText,
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun getMessageReplyIntent(
        oldIntent: Intent,
        buttonText: String, buttonAction: String,
        notificationId: String
    ): Intent {
        return oldIntent
            .setClassName(TermuxConstants.TERMUX_API_PACKAGE_NAME, TermuxAPIConstants.TERMUX_API_RECEIVER_NAME)
            .putExtra("api_method", "NotificationReply")
            .putExtra("id", notificationId)
            .putExtra("action", buttonAction)
    }

    private fun getMessageText(intent: Intent): CharSequence? {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        return remoteInput?.getCharSequence(KEY_TEXT_REPLY)
    }

    @JvmStatic
    fun shellEscape(input: CharSequence): CharSequence {
        return "\"${input.toString().replace("\"", "\\\"")}\""
    }

    @JvmStatic
    fun onReceiveReplyToNotification(
        termuxApiReceiver: TermuxApiReceiver,
        context: Context, intent: Intent
    ) {
        Logger.logDebug(LOG_TAG, "onReceiveReplyToNotification")

        val reply = getMessageText(intent)
        var action = intent.getStringExtra("action")

        if (action != null && reply != null) {
            action = action.replace("\$REPLY", shellEscape(reply).toString())
        }

        try {
            createAction(context, action)?.send()
        } catch (e: PendingIntent.CanceledException) {
            Logger.logError(LOG_TAG, "CanceledException when performing action: $action")
        }

        val notificationId = intent.getStringExtra("id")
        val ongoing = intent.getBooleanExtra("ongoing", false)
        val notificationManager = NotificationManagerCompat.from(context)
        
        if (ongoing) {
            val repliedNotification = buildNotification(context, intent).first.build()
            notificationManager.notify(notificationId, 0, repliedNotification)
        } else {
            notificationManager.cancel(notificationId, 0)
        }
    }

    @JvmStatic
    fun createExecuteIntent(action: String): Intent {
        val executionCommand = ExecutionCommand().apply {
            executableUri = Uri.Builder()
                .scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE)
                .path(BIN_SH)
                .build()
            arguments = arrayOf("-c", action)
            runner = ExecutionCommand.Runner.APP_SHELL.name
        }

        return Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, executionCommand.executableUri).apply {
            setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_APP.TERMUX_SERVICE_NAME)
            putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, executionCommand.arguments)
            putExtra(TERMUX_SERVICE.EXTRA_RUNNER, executionCommand.runner)
            putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, true)
        }
    }

    @JvmStatic
    fun createAction(context: Context, action: String?): PendingIntent {
        val executeIntent = createExecuteIntent(action ?: "")
        return PendingIntent.getService(
            context,
            PluginUtils.getLastPendingIntentRequestCode(context),
            executeIntent,
            PendingIntentUtils.pendingIntentImmutableFlag
        )
    }
}
