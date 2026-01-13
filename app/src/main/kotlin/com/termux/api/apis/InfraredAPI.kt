package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.hardware.ConsumerIrManager
import android.util.JsonWriter
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

/**
 * Exposing [ConsumerIrManager].
 */
object InfraredAPI {

    private const val LOG_TAG = "InfraredAPI"

    @JvmStatic
    fun onReceiveCarrierFrequency(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceiveCarrierFrequency")

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            override fun writeJson(out: JsonWriter) {
                val irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager

                if (irManager.hasIrEmitter()) {
                    val ranges = irManager.carrierFrequencies
                    if (ranges == null) {
                        out.beginObject().name("API_ERROR").value("Error communicating with the Consumer IR Service").endObject()
                    } else {
                        out.beginArray()
                        for (range in ranges) {
                            out.beginObject()
                            out.name("min").value(range.minFrequency.toLong())
                            out.name("max").value(range.maxFrequency.toLong())
                            out.endObject()
                        }
                        out.endArray()
                    }
                } else {
                    out.beginArray().endArray()
                }
            }
        })
    }

    @JvmStatic
    fun onReceiveTransmit(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceiveTransmit")

        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            override fun writeJson(out: JsonWriter) {
                val irManager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager

                val carrierFrequency = intent.getIntExtra("frequency", -1)
                val pattern = intent.getIntArrayExtra("pattern")

                val error: String? = when {
                    !irManager.hasIrEmitter() -> "No infrared emitter available"
                    carrierFrequency == -1 -> "Missing 'frequency' extra"
                    pattern == null || pattern.isEmpty() -> "Missing 'pattern' extra"
                    else -> null
                }

                if (error != null) {
                    out.beginObject().name("API_ERROR").value(error).endObject()
                    return
                }

                irManager.transmit(carrierFrequency, pattern)
            }
        })
    }
}
