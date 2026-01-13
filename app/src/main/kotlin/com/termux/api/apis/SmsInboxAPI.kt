package com.termux.api.apis

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import android.provider.Telephony.Sms
import android.provider.Telephony.Sms.Conversations
import android.provider.Telephony.TextBasedSmsColumns
import android.provider.Telephony.TextBasedSmsColumns.*
import android.util.JsonWriter
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.api.util.ResultReturner.ResultJsonWriter
import com.termux.shared.logger.Logger
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

/**
 * **See Also:**
 * - https://developer.android.com/reference/android/provider/Telephony
 * - https://developer.android.com/reference/android/provider/Telephony.Sms.Conversations
 * - https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns
 * - https://developer.android.com/reference/android/provider/BaseColumns
 */
object SmsInboxAPI {

    private val DISPLAY_NAME_PROJECTION = arrayOf(PhoneLookup.DISPLAY_NAME)
    private const val LOG_TAG = "SmsInboxAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val conversationList = intent.getBooleanExtra("conversation-list", false)
        val conversationReturnMultipleMessages = intent.getBooleanExtra("conversation-return-multiple-messages", false)
        val conversationReturnNestedView = intent.getBooleanExtra("conversation-return-nested-view", false)
        val conversationReturnNoOrderReverse = intent.getBooleanExtra("conversation-return-no-order-reverse", false)

        val conversationOffset = intent.getIntExtra("conversation-offset", -1)
        val conversationLimit = intent.getIntExtra("conversation-limit", -1)
        val conversationSelection = intent.getStringExtra("conversation-selection")

        val conversationSortOrder = intent.getStringExtra("conversation-sort-order").let {
            if (it.isNullOrEmpty()) "date DESC" else it
        }

        val messageOffset = intent.getIntExtra("offset", 0)
        val messageLimit = intent.getIntExtra("limit", 10)
        val messageTypeColumn = intent.getIntExtra("type", TextBasedSmsColumns.MESSAGE_TYPE_INBOX)
        val messageSelection = intent.getStringExtra("message-selection")

        val messageAddress = intent.getStringExtra("from").let {
            if (it.isNullOrEmpty()) null else it
        }

        val messageSortOrder = intent.getStringExtra("message-sort-order").let {
            if (it.isNullOrEmpty()) "date DESC" else it
        }

        val messageReturnNoOrderReverse = intent.getBooleanExtra("message-return-no-order-reverse", false)

        val contentURI = if (conversationList) {
            typeToContentURI(TextBasedSmsColumns.MESSAGE_TYPE_ALL)
        } else {
            typeToContentURI(
                if (messageAddress == null) messageTypeColumn
                else TextBasedSmsColumns.MESSAGE_TYPE_ALL
            )
        }

        ResultReturner.returnData(apiReceiver, intent, object : ResultJsonWriter() {
            @Throws(Exception::class)
            override fun writeJson(out: JsonWriter) {
                if (conversationList) {
                    getConversations(
                        context, out,
                        conversationOffset, conversationLimit,
                        conversationSelection,
                        conversationSortOrder,
                        conversationReturnMultipleMessages, conversationReturnNestedView,
                        conversationReturnNoOrderReverse,
                        messageOffset, messageLimit,
                        messageSelection,
                        messageSortOrder,
                        messageReturnNoOrderReverse
                    )
                } else {
                    getAllSms(
                        context, out, contentURI,
                        messageOffset, messageLimit,
                        messageSelection, messageAddress,
                        messageSortOrder,
                        messageReturnNoOrderReverse
                    )
                }
            }
        })
    }

    @SuppressLint("SimpleDateFormat")
    @JvmStatic
    @Throws(IOException::class)
    fun getConversations(
        context: Context, out: JsonWriter,
        conversationOffset: Int, conversationLimit: Int,
        conversationSelection: String?,
        conversationSortOrderParam: String,
        conversationReturnMultipleMessages: Boolean, conversationReturnNestedView: Boolean,
        conversationReturnNoOrderReverse: Boolean,
        messageOffset: Int, messageLimit: Int,
        messageSelectionParam: String?,
        messageSortOrderParam: String,
        messageReturnNoOrderReverse: Boolean
    ) {
        val cr = context.contentResolver

        if (messageSelectionParam != null && messageSelectionParam.matches(Regex("^(.*[ \t\n])?$THREAD_ID[ \t\n].*$"))) {
            throw IllegalArgumentException(
                "The 'conversation-selection' cannot contain '$THREAD_ID': `$messageSelectionParam`"
            )
        }

        val conversationSortOrder = getSortOrder(conversationSortOrderParam, conversationOffset, conversationLimit)
        val messageSortOrder = getSortOrder(messageSortOrderParam, messageOffset, messageLimit)

        cr.query(Conversations.CONTENT_URI, null, conversationSelection, null, conversationSortOrder)?.use { conversationCursor ->
            val conversationCount = conversationCursor.count
            if (conversationReturnNoOrderReverse) {
                conversationCursor.moveToFirst()
            } else {
                conversationCursor.moveToLast()
            }

            val nameCache = mutableMapOf<String, String?>()

            if (conversationReturnNestedView) {
                out.beginObject()
            } else {
                out.beginArray()
            }

            for (i in 0 until conversationCount) {
                val index = conversationCursor.getColumnIndex(THREAD_ID)
                if (index < 0) {
                    conversationCursor.moveToPrevious()
                    continue
                }

                val id = conversationCursor.getInt(index)

                if (conversationReturnNestedView) {
                    out.name(id.toString())
                    out.beginArray()
                }

                var messageSelection = messageSelectionParam ?: ""
                if (messageSelection.isNotEmpty()) {
                    messageSelection += " "
                }

                cr.query(
                    Sms.CONTENT_URI, null,
                    "$messageSelection$THREAD_ID == '$id'", null,
                    messageSortOrder
                )?.use { messageCursor ->
                    val messageCount = messageCursor.count
                    if (messageCount > 0) {
                        if (conversationReturnMultipleMessages) {
                            if (messageReturnNoOrderReverse) {
                                messageCursor.moveToFirst()
                            } else {
                                messageCursor.moveToLast()
                            }

                            for (j in 0 until messageCount) {
                                writeElement(messageCursor, out, nameCache, context)

                                if (messageReturnNoOrderReverse) {
                                    messageCursor.moveToNext()
                                } else {
                                    messageCursor.moveToPrevious()
                                }
                            }
                        } else {
                            messageCursor.moveToFirst()
                            writeElement(messageCursor, out, nameCache, context)
                        }
                    }
                }

                if (conversationReturnNestedView) {
                    out.endArray()
                }

                if (conversationReturnNoOrderReverse) {
                    conversationCursor.moveToNext()
                } else {
                    conversationCursor.moveToPrevious()
                }
            }

            if (conversationReturnNestedView) {
                out.endObject()
            } else {
                out.endArray()
            }
        }
    }

    @SuppressLint("SimpleDateFormat")
    @Throws(IOException::class)
    private fun writeElement(c: Cursor, out: JsonWriter, nameCache: MutableMap<String, String?>, context: Context) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

        val threadID = c.getInt(c.getColumnIndexOrThrow(THREAD_ID))
        val smsAddress = c.getString(c.getColumnIndexOrThrow(ADDRESS))
        val smsBody = c.getString(c.getColumnIndexOrThrow(BODY))
        val smsReceivedDate = c.getLong(c.getColumnIndexOrThrow(DATE))
        val smsID = c.getInt(c.getColumnIndexOrThrow("_id"))

        val smsSenderName = getContactNameFromNumber(nameCache, context, smsAddress)
        val messageType = getMessageType(c.getInt(c.getColumnIndexOrThrow(TYPE)))

        out.beginObject()
        out.name("threadid").value(threadID)
        out.name("type").value(messageType)

        val readIndex = c.getColumnIndex(READ)
        if (readIndex >= 0) {
            out.name("read").value(c.getInt(readIndex) != 0)
        }

        if (smsSenderName != null) {
            if (messageType == "inbox") {
                out.name("sender").value(smsSenderName)
            } else {
                out.name("sender").value("You")
            }
        }

        out.name("address").value(smsAddress)
        out.name("number").value(smsAddress)
        out.name("received").value(dateFormat.format(Date(smsReceivedDate)))
        out.name("body").value(smsBody)
        out.name("_id").value(smsID)

        out.endObject()
    }

    @SuppressLint("SimpleDateFormat")
    @JvmStatic
    @Throws(IOException::class)
    fun getAllSms(
        context: Context, out: JsonWriter,
        contentURI: Uri,
        messageOffset: Int, messageLimit: Int,
        messageSelection: String?, messageAddress: String?,
        messageSortOrderParam: String,
        messageReturnNoOrderReverse: Boolean
    ) {
        val cr = context.contentResolver

        var selection = messageSelection
        var selectionArgs: Array<String>? = null
        if (selection.isNullOrEmpty()) {
            if (!messageAddress.isNullOrEmpty()) {
                selection = "$ADDRESS LIKE ?"
                selectionArgs = arrayOf(messageAddress)
            }
        }

        val messageSortOrder = getSortOrder(messageSortOrderParam, messageOffset, messageLimit)

        cr.query(contentURI, null, selection, selectionArgs, messageSortOrder)?.use { messageCursor ->
            val messageCount = messageCursor.count
            if (messageReturnNoOrderReverse) {
                messageCursor.moveToFirst()
            } else {
                messageCursor.moveToLast()
            }

            val nameCache = mutableMapOf<String, String?>()

            out.beginArray()
            for (i in 0 until messageCount) {
                writeElement(messageCursor, out, nameCache, context)

                if (messageReturnNoOrderReverse) {
                    messageCursor.moveToNext()
                } else {
                    messageCursor.moveToPrevious()
                }
            }
            out.endArray()
        }
    }

    private fun getContactNameFromNumber(cache: MutableMap<String, String?>, context: Context, number: String): String? {
        if (cache.containsKey(number)) {
            return cache[number]
        }

        val contactUri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(contactUri, DISPLAY_NAME_PROJECTION, null, null, null)?.use { c ->
            var name: String? = null
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(PhoneLookup.DISPLAY_NAME)
                if (index >= 0) {
                    name = c.getString(index)
                }
            }
            cache[number] = name
            return name
        }
        return null
    }

    private fun getMessageType(type: Int): String = when (type) {
        TextBasedSmsColumns.MESSAGE_TYPE_INBOX -> "inbox"
        TextBasedSmsColumns.MESSAGE_TYPE_SENT -> "sent"
        TextBasedSmsColumns.MESSAGE_TYPE_DRAFT -> "draft"
        TextBasedSmsColumns.MESSAGE_TYPE_FAILED -> "failed"
        TextBasedSmsColumns.MESSAGE_TYPE_OUTBOX -> "outbox"
        else -> ""
    }

    private fun typeToContentURI(type: Int): Uri = when (type) {
        TextBasedSmsColumns.MESSAGE_TYPE_SENT -> Sms.Sent.CONTENT_URI
        TextBasedSmsColumns.MESSAGE_TYPE_DRAFT -> Sms.Draft.CONTENT_URI
        TextBasedSmsColumns.MESSAGE_TYPE_OUTBOX -> Sms.Outbox.CONTENT_URI
        TextBasedSmsColumns.MESSAGE_TYPE_INBOX -> Sms.Inbox.CONTENT_URI
        else -> Sms.CONTENT_URI
    }

    private fun getSortOrder(sortOrder: String?, offset: Int, limit: Int): String? {
        var result = sortOrder ?: ""
        if (limit >= 0) {
            result += " LIMIT $limit"
        }
        if (offset >= 0) {
            result += " OFFSET $offset"
        }
        return result.ifEmpty { null }
    }
}
