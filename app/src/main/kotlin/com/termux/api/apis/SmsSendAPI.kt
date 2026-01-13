package com.termux.api.apis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.annotation.RequiresPermission
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

object SmsSendAPI {

    private const val LOG_TAG = "SmsSendAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.WithStringInput() {
            @RequiresPermission(allOf = [Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS])
            override fun writeResult(out: java.io.PrintWriter) {
                val smsManager = getSmsManager(context, intent) ?: return

                var recipients = intent.getStringArrayExtra("recipients")

                if (recipients == null) {
                    val recipient = intent.getStringExtra("recipient")
                    if (recipient != null) recipients = arrayOf(recipient)
                }

                if (recipients == null || recipients.isEmpty()) {
                    Logger.logError(LOG_TAG, "No recipient given")
                } else {
                    val messages = smsManager.divideMessage(inputString)
                    for (recipient in recipients) {
                        smsManager.sendMultipartTextMessage(recipient, null, messages, null, null)
                    }
                }
            }
        })
    }

    @JvmStatic
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    fun getSmsManager(context: Context, intent: Intent): SmsManager? {
        val slot = intent.getIntExtra("slot", -1)
        return if (slot == -1) {
            SmsManager.getDefault()
        } else {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            if (sm == null) {
                Logger.logError(LOG_TAG, "SubscriptionManager not supported")
                return null
            }
            for (si in sm.activeSubscriptionInfoList ?: emptyList()) {
                if (si.simSlotIndex == slot) {
                    return SmsManager.getSmsManagerForSubscriptionId(si.subscriptionId)
                }
            }
            Logger.logError(LOG_TAG, "Sim slot $slot not found")
            null
        }
    }
}
