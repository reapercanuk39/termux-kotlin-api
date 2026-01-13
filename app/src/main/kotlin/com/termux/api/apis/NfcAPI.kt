package com.termux.api.apis

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.Parcelable
import android.util.JsonWriter
import androidx.appcompat.app.AppCompatActivity
import com.termux.api.util.PendingIntentUtils
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

object NfcAPI {

    private const val LOG_TAG = "NfcAPI"

    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        context.startActivity(
            Intent(context, NfcActivity::class.java)
                .putExtras(intent.extras!!)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    class NfcActivity : AppCompatActivity() {

        private var mIntent: Intent? = null
        private var mAdapter: NfcAdapter? = null
        private var mode: String = "noData"
        private var param: String = "noData"
        private var value: String? = null

        private fun errorNfc(context: Context, intent: Intent, error: String) {
            ResultReturner.returnData(context, intent, object : ResultReturner.ResultJsonWriter() {
                override fun writeJson(out: JsonWriter) {
                    val adapter = NfcAdapter.getDefaultAdapter(context)
                    out.beginObject()
                    if (error.isNotEmpty()) out.name("error").value(error)
                    out.name("nfcPresent").value(adapter != null)
                    adapter?.let { out.name("nfcActive").value(it.isEnabled) }
                    out.endObject()
                }
            })
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            Logger.logDebug(LOG_TAG, "onCreate")

            super.onCreate(savedInstanceState)
            intent?.let { intent ->
                mIntent = intent
                mode = intent.getStringExtra("mode") ?: "noData"
                param = intent.getStringExtra("param") ?: "noData"
                value = intent.getStringExtra("value")
                if (socket_input == null) socket_input = intent.getStringExtra("socket_input")
                if (socket_output == null) socket_output = intent.getStringExtra("socket_output")
                if (mode == "noData") {
                    errorNfc(this, intent, "")
                    finish()
                    return
                }
            }

            val adapter = NfcAdapter.getDefaultAdapter(this)
            if (adapter == null || !adapter.isEnabled) {
                intent?.let { errorNfc(this, it, "") }
                finish()
            }
        }

        override fun onResume() {
            Logger.logVerbose(LOG_TAG, "onResume")

            super.onResume()

            mAdapter = NfcAdapter.getDefaultAdapter(this)
            if (mAdapter == null || mAdapter?.isEnabled != true) {
                mIntent?.let { errorNfc(this, it, "") }
                finish()
                return
            }

            val intentNew = Intent(this, NfcActivity::class.java)
                .addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intentNew,
                PendingIntentUtils.pendingIntentMutableFlag
            )
            val intentFilter = arrayOf(
                IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
                IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
                IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
            )
            mAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilter, null)
        }

        override fun onNewIntent(intent: Intent) {
            Logger.logDebug(LOG_TAG, "onNewIntent")

            intent.putExtra("socket_input", socket_input)
            intent.putExtra("socket_output", socket_output)

            if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {
                try {
                    postResult(this, intent)
                } catch (e: Exception) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Error posting result", e)
                }
                finish()
            }
            super.onNewIntent(intent)
        }

        override fun onPause() {
            Logger.logDebug(LOG_TAG, "onPause")

            mAdapter?.disableForegroundDispatch(this)
            super.onPause()
        }

        override fun onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy")

            socket_input = null
            socket_output = null
            super.onDestroy()
        }

        private fun postResult(context: Context, intent: Intent) {
            ResultReturner.returnData(context, intent, object : ResultReturner.ResultJsonWriter() {
                override fun writeJson(out: JsonWriter) {
                    Logger.logDebug(LOG_TAG, "postResult")
                    try {
                        when (mode) {
                            "write" -> when (param) {
                                "text" -> {
                                    Logger.logVerbose(LOG_TAG, "Write start")
                                    onReceiveNfcWrite(context, intent)
                                    Logger.logVerbose(LOG_TAG, "Write end")
                                }
                                else -> onUnexpectedAction(out, "Wrong Params", "Should be text for TAG")
                            }
                            "read" -> when (param) {
                                "short", "noData" -> readNDEFTag(intent, out)
                                "full" -> readFullNDEFTag(intent, out)
                                else -> onUnexpectedAction(out, "Wrong Params", "Should be correct param value")
                            }
                            else -> onUnexpectedAction(out, "Wrong Params", "Should be correct mode value ")
                        }
                    } catch (e: Exception) {
                        onUnexpectedAction(out, "exception", e.message ?: "")
                    }
                }
            })
        }

        @Throws(Exception::class)
        fun onReceiveNfcWrite(context: Context, intent: Intent) {
            Logger.logVerbose(LOG_TAG, "onReceiveNfcWrite")

            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            val record = NdefRecord.createTextRecord("en", value)
            val msg = NdefMessage(arrayOf(record))
            val ndef = Ndef.get(tag)
            ndef.connect()
            ndef.writeNdefMessage(msg)
            ndef.close()
        }

        @Throws(Exception::class)
        fun readNDEFTag(intent: Intent, out: JsonWriter) {
            Logger.logVerbose(LOG_TAG, "readNDEFTag")

            val msgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            val strs = tag?.techList ?: arrayOf()
            
            var bNdefPresent = false
            for (s in strs) {
                if (s == "android.nfc.tech.Ndef") {
                    bNdefPresent = true
                    break
                }
            }
            
            if (!bNdefPresent) {
                onUnexpectedAction(out, "Wrong Technology", "termux API support only NFEF Tag")
                return
            }
            
            if (msgs != null && msgs.size == 1) {
                val nmsgs = msgs[0] as NdefMessage
                val records = nmsgs.records
                out.beginObject()
                if (records.isNotEmpty()) {
                    out.name("Record")
                    if (records.size > 1) out.beginArray()
                    for (record in records) {
                        out.beginObject()
                        var pos = if (NdefRecord.TNF_WELL_KNOWN == record.tnf.toShort()) {
                            record.payload[0].toInt() + 1
                        } else {
                            0
                        }
                        val len = record.payload.size - pos
                        val msg = ByteArray(len)
                        System.arraycopy(record.payload, pos, msg, 0, len)
                        out.name("Payload").value(String(msg))
                        out.endObject()
                    }
                    if (records.size > 1) out.endArray()
                }
                out.endObject()
            }
        }

        @Throws(Exception::class)
        fun readFullNDEFTag(intent: Intent, out: JsonWriter) {
            Logger.logVerbose(LOG_TAG, "readFullNDEFTag")

            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
            val ndefTag = Ndef.get(tag)
            val msgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)

            val strs = tag.techList
            var bNdefPresent = false
            for (s in strs) {
                if (s == "android.nfc.tech.Ndef") {
                    bNdefPresent = true
                    break
                }
            }
            
            if (!bNdefPresent) {
                onUnexpectedAction(out, "Wrong Technology", "termux API support only NFEF Tag")
                return
            }
            
            out.beginObject()
            
            val tagID = tag.id
            val sp = StringBuilder()
            for (tagIDpart in tagID) {
                sp.append(String.format("%02x", tagIDpart))
            }
            out.name("id").value(sp.toString())
            out.name("typeTag").value(ndefTag.type)
            out.name("maxSize").value(ndefTag.maxSize.toLong())
            out.name("techList")
            out.beginArray()
            for (str in tag.techList) {
                out.value(str)
            }
            out.endArray()
            
            if (msgs != null && msgs.size == 1) {
                Logger.logInfo(LOG_TAG, "-->> readFullNDEFTag - 06")
                val nmsgs = msgs[0] as NdefMessage
                val records = nmsgs.records
                out.name("record")
                if (records.size > 1) out.beginArray()
                for (record in records) {
                    out.beginObject()
                    out.name("type").value(String(record.type))
                    out.name("tnf").value(record.tnf.toLong())
                    record.toUri()?.let { out.name("URI").value(it.toString()) }
                    out.name("mime").value(record.toMimeType())
                    var pos = if (NdefRecord.TNF_WELL_KNOWN == record.tnf.toShort()) {
                        record.payload[0].toInt() + 1
                    } else {
                        0
                    }
                    val len = record.payload.size - pos
                    val msg = ByteArray(len)
                    System.arraycopy(record.payload, pos, msg, 0, len)
                    out.name("payload").value(String(msg))
                    out.endObject()
                }
                if (records.size > 1) out.endArray()
            }
            
            out.endObject()
        }

        @Throws(Exception::class)
        private fun onUnexpectedAction(out: JsonWriter, error: String, description: String) {
            out.beginObject()
            out.name("error").value(error)
            out.name("description").value(description)
            out.endObject()
            out.flush()
        }

        companion object {
            private const val LOG_TAG = "NfcActivity"

            @JvmStatic
            var socket_input: String? = null

            @JvmStatic
            var socket_output: String? = null
        }
    }
}
