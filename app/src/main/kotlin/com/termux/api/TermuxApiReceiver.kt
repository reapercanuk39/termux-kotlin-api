package com.termux.api

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.termux.api.apis.*
import com.termux.api.activities.TermuxApiPermissionActivity
import com.termux.api.util.ResultReturner
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.plugins.TermuxPluginUtils

class TermuxApiReceiver : BroadcastReceiver() {

    companion object {
        private const val LOG_TAG = "TermuxApiReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        TermuxAPIApplication.setLogConfig(context, false)
        Logger.logDebug(LOG_TAG, "Intent Received:\n${IntentUtils.getIntentString(intent)}")

        try {
            doWork(context, intent)
        } catch (t: Throwable) {
            val message = "Error in $LOG_TAG"
            // Make sure never to throw exception from BroadCastReceiver to avoid "process is bad"
            // behaviour from the Android system.
            Logger.logStackTraceWithMessage(LOG_TAG, message, t)

            TermuxPluginUtils.sendPluginCommandErrorNotification(
                context, LOG_TAG,
                TermuxConstants.TERMUX_API_APP_NAME + " Error", message, t
            )

            ResultReturner.noteDone(this, intent)
        }
    }

    private fun doWork(context: Context, intent: Intent) {
        val apiMethod = intent.getStringExtra("api_method")
        if (apiMethod == null) {
            Logger.logError(LOG_TAG, "Missing 'api_method' extra")
            return
        }

        when (apiMethod) {
            "AudioInfo" -> AudioAPI.onReceive(this, context, intent)
            "BatteryStatus" -> BatteryStatusAPI.onReceive(this, context, intent)
            "Brightness" -> {
                if (!Settings.System.canWrite(context)) {
                    TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.WRITE_SETTINGS)
                    Toast.makeText(context, "Please enable permission for Termux:API", Toast.LENGTH_LONG).show()
                    // user must enable WRITE_SETTINGS permission this special way
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    context.startActivity(settingsIntent)
                    return
                }
                BrightnessAPI.onReceive(this, context, intent)
            }
            "CameraInfo" -> CameraInfoAPI.onReceive(this, context, intent)
            "CameraPhoto" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.CAMERA)) {
                    CameraPhotoAPI.onReceive(this, context, intent)
                }
            }
            "CallLog" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_CALL_LOG)) {
                    CallLogAPI.onReceive(context, intent)
                }
            }
            "Clipboard" -> ClipboardAPI.onReceive(this, context, intent)
            "ContactList" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_CONTACTS)) {
                    ContactListAPI.onReceive(this, context, intent)
                }
            }
            "Dialog" -> DialogAPI.onReceive(context, intent)
            "Download" -> DownloadAPI.onReceive(this, context, intent)
            "Fingerprint" -> FingerprintAPI.onReceive(context, intent)
            "InfraredFrequencies" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.TRANSMIT_IR)) {
                    InfraredAPI.onReceiveCarrierFrequency(this, context, intent)
                }
            }
            "InfraredTransmit" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.TRANSMIT_IR)) {
                    InfraredAPI.onReceiveTransmit(this, context, intent)
                }
            }
            "JobScheduler" -> JobSchedulerAPI.onReceive(this, context, intent)
            "Keystore" -> KeystoreAPI.onReceive(this, intent)
            "Location" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    LocationAPI.onReceive(this, context, intent)
                }
            }
            "MediaPlayer" -> MediaPlayerAPI.onReceive(context, intent)
            "MediaScanner" -> MediaScannerAPI.onReceive(this, context, intent)
            "MicRecorder" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.RECORD_AUDIO)) {
                    MicRecorderAPI.onReceive(context, intent)
                }
            }
            "Nfc" -> NfcAPI.onReceive(context, intent)
            "NotificationList" -> {
                val cn = ComponentName(context, NotificationListAPI.NotificationService::class.java)
                val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                val notificationServiceEnabled = flat != null && flat.contains(cn.flattenToString())
                if (!notificationServiceEnabled) {
                    Toast.makeText(context, "Please give Termux:API Notification Access", Toast.LENGTH_LONG).show()
                    context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    NotificationListAPI.onReceive(this, context, intent)
                }
            }
            "Notification" -> NotificationAPI.onReceiveShowNotification(this, context, intent)
            "NotificationChannel" -> NotificationAPI.onReceiveChannel(this, context, intent)
            "NotificationRemove" -> NotificationAPI.onReceiveRemoveNotification(this, context, intent)
            "NotificationReply" -> NotificationAPI.onReceiveReplyToNotification(this, context, intent)
            "SAF" -> SAFAPI.onReceive(this, context, intent)
            "Sensor" -> SensorAPI.onReceive(context, intent)
            "Share" -> ShareAPI.onReceive(this, context, intent)
            "SmsInbox" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)) {
                    SmsInboxAPI.onReceive(this, context, intent)
                }
            }
            "SmsSend" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS)) {
                    SmsSendAPI.onReceive(this, context, intent)
                }
            }
            "StorageGet" -> StorageGetAPI.onReceive(this, context, intent)
            "SpeechToText" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.RECORD_AUDIO)) {
                    SpeechToTextAPI.onReceive(context, intent)
                }
            }
            "TelephonyCall" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.CALL_PHONE)) {
                    TelephonyAPI.onReceiveTelephonyCall(this, context, intent)
                }
            }
            "TelephonyCellInfo" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    TelephonyAPI.onReceiveTelephonyCellInfo(this, context, intent)
                }
            }
            "TelephonyDeviceInfo" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_PHONE_STATE)) {
                    TelephonyAPI.onReceiveTelephonyDeviceInfo(this, context, intent)
                }
            }
            "TextToSpeech" -> TextToSpeechAPI.onReceive(context, intent)
            "Toast" -> ToastAPI.onReceive(context, intent)
            "Torch" -> TorchAPI.onReceive(this, context, intent)
            "Usb" -> UsbAPI.onReceive(context, intent)
            "Vibrate" -> VibrateAPI.onReceive(this, context, intent)
            "Volume" -> VolumeAPI.onReceive(this, context, intent)
            "Wallpaper" -> WallpaperAPI.onReceive(context, intent)
            "WifiConnectionInfo" -> WifiAPI.onReceiveWifiConnectionInfo(this, context, intent)
            "WifiScanInfo" -> {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    WifiAPI.onReceiveWifiScanInfo(this, context, intent)
                }
            }
            "WifiEnable" -> WifiAPI.onReceiveWifiEnable(this, context, intent)
            else -> Logger.logError(LOG_TAG, "Unrecognized 'api_method' extra: '$apiMethod'")
        }
    }
}
