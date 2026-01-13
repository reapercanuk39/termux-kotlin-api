package com.termux.api.apis

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.TextUtils
import android.webkit.MimeTypeMap
import com.termux.api.R
import com.termux.api.TermuxAPIConstants
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import com.termux.shared.net.uri.UriUtils
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

object ShareAPI {

    private const val LOG_TAG = "ShareAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val fileExtra = intent.getStringExtra("file")
        val titleExtra = intent.getStringExtra("title")
        val contentTypeExtra = intent.getStringExtra("content-type")
        val defaultReceiverExtra = intent.getBooleanExtra("default-receiver", false)
        val actionExtra = intent.getStringExtra("action")

        val intentAction = when (actionExtra) {
            "edit" -> Intent.ACTION_EDIT
            "send" -> Intent.ACTION_SEND
            "view" -> Intent.ACTION_VIEW
            null -> Intent.ACTION_VIEW
            else -> {
                Logger.logError(LOG_TAG, "Invalid action '$actionExtra', using 'view'")
                Intent.ACTION_VIEW
            }
        }

        if (fileExtra == null) {
            ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.WithStringInput() {
                override fun writeResult(out: java.io.PrintWriter) {
                    if (TextUtils.isEmpty(inputString)) {
                        out.println("Error: Nothing to share")
                        return
                    }

                    val sendIntent = Intent().apply {
                        action = intentAction
                        putExtra(Intent.EXTRA_TEXT, inputString)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        titleExtra?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                        type = contentTypeExtra ?: "text/plain"
                    }

                    context.startActivity(
                        Intent.createChooser(sendIntent, context.resources.getText(R.string.share_file_chooser_title))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            })
        } else {
            ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultWriter { out ->
                val fileToShare = File(fileExtra)
                if (!(fileToShare.isFile && fileToShare.canRead())) {
                    out.println("ERROR: Not a readable file: '${fileToShare.absolutePath}'")
                } else {
                    var sendIntent = Intent().apply {
                        action = intentAction
                    }

                    val uriToShare = UriUtils.getContentUri(
                        TermuxAPIConstants.TERMUX_API_FILE_SHARE_URI_AUTHORITY,
                        fileToShare.absolutePath
                    )
                    sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                    val contentTypeToUse = if (contentTypeExtra == null) {
                        val fileName = fileToShare.name
                        val lastDotIndex = fileName.lastIndexOf('.')
                        val fileExtension = fileName.substring(lastDotIndex + 1)
                        val mimeTypes = MimeTypeMap.getSingleton()
                        mimeTypes.getMimeTypeFromExtension(fileExtension.lowercase()) ?: "application/octet-stream"
                    } else {
                        contentTypeExtra
                    }

                    titleExtra?.let { sendIntent.putExtra(Intent.EXTRA_SUBJECT, it) }

                    if (Intent.ACTION_SEND == intentAction) {
                        sendIntent.putExtra(Intent.EXTRA_STREAM, uriToShare)
                        sendIntent.type = contentTypeToUse
                    } else {
                        sendIntent.setDataAndType(uriToShare, contentTypeToUse)
                    }

                    if (!defaultReceiverExtra) {
                        sendIntent = Intent.createChooser(sendIntent, context.resources.getText(R.string.share_file_chooser_title))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(sendIntent)
                }
            })
        }
    }

    class ContentProvider : android.content.ContentProvider() {

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
        ): Cursor {
            val file = File(uri.path ?: "")

            val proj = projection ?: arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns._ID
            )

            val row = Array<Any?>(proj.size) { i ->
                when (proj[i]) {
                    MediaStore.MediaColumns.DISPLAY_NAME -> file.name
                    MediaStore.MediaColumns.SIZE -> file.length().toInt()
                    MediaStore.MediaColumns._ID -> 1
                    else -> null
                }
            }

            val cursor = MatrixCursor(proj)
            cursor.addRow(row)
            return cursor
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

        @Throws(FileNotFoundException::class)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = File(uri.path ?: "")

            try {
                val path = file.canonicalPath
                val callingPackageName = callingPackage
                Logger.logDebug(LOG_TAG, "Open file request received from $callingPackageName for \"$path\" with mode \"$mode\"")
            } catch (e: IOException) {
                throw IllegalArgumentException(e)
            }

            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        companion object {
            private const val LOG_TAG = "ContentProvider"
        }
    }
}
