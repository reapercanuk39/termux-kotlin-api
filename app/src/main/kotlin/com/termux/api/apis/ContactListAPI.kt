package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.JsonWriter
import android.util.SparseArray

import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

object ContactListAPI {

    private const val LOG_TAG = "ContactListAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultJsonWriter { out ->
            listContacts(context, out)
        })
    }

    internal fun listContacts(context: Context, out: JsonWriter) {
        val cr = context.contentResolver

        val contactIdToNumberMap = SparseArray<String>()
        val projection = arrayOf(Phone.NUMBER, Phone.CONTACT_ID)
        val selection = "${Phone.CONTACT_ID} IS NOT NULL AND ${Phone.NUMBER} IS NOT NULL"

        cr.query(Phone.CONTENT_URI, projection, selection, null, null)?.use { phones ->
            val phoneNumberIdx = phones.getColumnIndexOrThrow(Phone.NUMBER)
            val phoneContactIdIdx = phones.getColumnIndexOrThrow(Phone.CONTACT_ID)
            while (phones.moveToNext()) {
                val number = phones.getString(phoneNumberIdx)
                val contactId = phones.getInt(phoneContactIdIdx)
                contactIdToNumberMap.put(contactId, number)
            }
        }

        out.beginArray()
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            null, null, null,
            ContactsContract.Contacts.DISPLAY_NAME
        )?.use { cursor ->
            val contactDisplayNameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            val contactIdIdx = cursor.getColumnIndex(BaseColumns._ID)

            while (cursor.moveToNext()) {
                val contactId = cursor.getInt(contactIdIdx)
                val number = contactIdToNumberMap.get(contactId)
                if (number != null) {
                    val contactName = cursor.getString(contactDisplayNameIdx)
                    out.beginObject()
                        .name("name").value(contactName)
                        .name("number").value(number)
                        .endObject()
                }
            }
        }
        out.endArray()
    }
}
