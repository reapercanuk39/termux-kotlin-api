package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileUtils
import android.provider.DocumentsContract
import android.util.JsonWriter
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object SAFAPI {

    private const val LOG_TAG = "SAFAPI"

    class SAFActivity : AppCompatActivity() {

        private var resultReturned = false

        override fun onCreate(savedInstanceState: Bundle?) {
            Logger.logDebug(LOG_TAG, "onCreate")

            super.onCreate(savedInstanceState)
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(i, 0)
        }

        override fun onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy")

            super.onDestroy()
            finishAndRemoveTask()
            if (!resultReturned) {
                ResultReturner.returnData(this, intent, ResultReturner.ResultWriter { out -> out.write("") })
                resultReturned = true
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            Logger.logVerbose(
                LOG_TAG,
                "onActivityResult: requestCode: $requestCode, resultCode: $resultCode, data: ${IntentUtils.getIntentString(data)}"
            )

            super.onActivityResult(requestCode, resultCode, data)
            if (data != null) {
                val uri = data.data
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    resultReturned = true
                    ResultReturner.returnData(this, intent, ResultReturner.ResultWriter { out ->
                        out.println(data.dataString)
                    })
                }
            }
            finish()
        }

        companion object {
            private const val LOG_TAG = "SAFActivity"
        }
    }

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val method = intent.getStringExtra("safmethod")
        if (method == null) {
            Logger.logError(LOG_TAG, "safmethod extra null")
            return
        }
        try {
            when (method) {
                "getManagedDocumentTrees" -> getManagedDocumentTrees(apiReceiver, context, intent)
                "manageDocumentTree" -> manageDocumentTree(context, intent)
                "writeDocument" -> writeDocument(apiReceiver, context, intent)
                "createDocument" -> createDocument(apiReceiver, context, intent)
                "readDocument" -> readDocument(apiReceiver, context, intent)
                "listDirectory" -> listDirectory(apiReceiver, context, intent)
                "removeDocument" -> removeDocument(apiReceiver, context, intent)
                "statURI" -> statURI(apiReceiver, context, intent)
                else -> Logger.logError(LOG_TAG, "Unrecognized safmethod: '$method'")
            }
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in SAFAPI", e)
        }
    }

    private fun getManagedDocumentTrees(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            @Throws(Exception::class)
            override fun writeJson(out: JsonWriter) {
                out.beginArray()
                for (p in context.contentResolver.persistedUriPermissions) {
                    statDocument(out, context, treeUriToDocumentUri(p.uri))
                }
                out.endArray()
            }
        })
    }

    private fun manageDocumentTree(context: Context, intent: Intent) {
        val i = Intent(context, SAFActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ResultReturner.copyIntentExtras(intent, i)
        context.startActivity(i)
    }

    private fun writeDocument(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        val uri = intent.getStringExtra("uri")
        if (uri == null) {
            Logger.logError(LOG_TAG, "uri extra null")
            return
        }
        val f = DocumentFile.fromSingleUri(context, Uri.parse(uri)) ?: return
        writeDocumentFile(apiReceiver, context, intent, f)
    }

    private fun createDocument(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        val treeURIString = intent.getStringExtra("treeuri")
        if (treeURIString == null) {
            Logger.logError(LOG_TAG, "treeuri extra null")
            return
        }
        val name = intent.getStringExtra("filename")
        if (name == null) {
            Logger.logError(LOG_TAG, "filename extra null")
            return
        }
        val mime = intent.getStringExtra("mimetype") ?: "application/octet-stream"
        val treeURI = Uri.parse(treeURIString)
        var id = DocumentsContract.getTreeDocumentId(treeURI)
        try {
            id = DocumentsContract.getDocumentId(Uri.parse(treeURIString))
        } catch (ignored: IllegalArgumentException) {
        }

        val finalId = id
        ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultWriter { out ->
            out.println(
                DocumentsContract.createDocument(
                    context.contentResolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeURI, finalId),
                    mime,
                    name
                ).toString()
            )
        })
    }

    private fun readDocument(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        val uri = intent.getStringExtra("uri")
        if (uri == null) {
            Logger.logError(LOG_TAG, "uri extra null")
            return
        }
        val f = DocumentFile.fromSingleUri(context, Uri.parse(uri)) ?: return
        returnDocumentFile(apiReceiver, context, intent, f)
    }

    private fun listDirectory(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        val treeURIString = intent.getStringExtra("treeuri")
        if (treeURIString == null) {
            Logger.logError(LOG_TAG, "treeuri extra null")
            return
        }
        val treeURI = Uri.parse(treeURIString)
        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            @Throws(Exception::class)
            override fun writeJson(out: JsonWriter) {
                out.beginArray()
                var id = DocumentsContract.getTreeDocumentId(treeURI)
                try {
                    id = DocumentsContract.getDocumentId(Uri.parse(treeURIString))
                } catch (ignored: IllegalArgumentException) {
                }
                try {
                    context.contentResolver.query(
                        DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(treeURIString), id),
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                        null, null, null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val documentId = c.getString(0)
                            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeURI, documentId)
                            statDocument(out, context, documentUri)
                        }
                    }
                } catch (ignored: UnsupportedOperationException) {
                }
                out.endArray()
            }
        })
    }

    private fun statURI(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        val uriString = intent.getStringExtra("uri")
        if (uriString == null) {
            Logger.logError(LOG_TAG, "uri extra null")
            return
        }
        val docUri = treeUriToDocumentUri(Uri.parse(uriString))
        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            @Throws(Exception::class)
            override fun writeJson(out: JsonWriter) {
                statDocument(out, context, Uri.parse(docUri.toString()))
            }
        })
    }

    private fun removeDocument(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        val uri = intent.getStringExtra("uri")
        if (uri == null) {
            Logger.logError(LOG_TAG, "uri extra null")
            return
        }
        ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultWriter { out ->
            try {
                if (DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri))) {
                    out.println(0)
                } else {
                    out.println(1)
                }
            } catch (e: Exception) {
                when (e) {
                    is FileNotFoundException, is IllegalArgumentException -> out.println(2)
                    else -> throw e
                }
            }
        })
    }

    private fun treeUriToDocumentUri(tree: Uri): Uri {
        var id = DocumentsContract.getTreeDocumentId(tree)
        try {
            id = DocumentsContract.getDocumentId(tree)
        } catch (ignored: IllegalArgumentException) {
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    @Throws(Exception::class)
    private fun statDocument(out: JsonWriter, context: Context, uri: Uri) {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.count == 0) return

            var mime: String? = null
            c.moveToNext()
            out.beginObject()

            c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME).let { index ->
                if (index >= 0) {
                    out.name("name")
                    out.value(c.getString(index))
                }
            }

            c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE).let { index ->
                if (index >= 0) {
                    out.name("type")
                    mime = c.getString(index)
                    out.value(mime)
                }
            }

            out.name("uri")
            out.value(uri.toString())

            c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).let { index ->
                if (index >= 0) {
                    out.name("last_modified")
                    out.value(c.getLong(index))
                }
            }

            if (mime != null && DocumentsContract.Document.MIME_TYPE_DIR != mime) {
                c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE).let { index ->
                    if (index >= 0) {
                        out.name("length")
                        out.value(c.getInt(index).toLong())
                    }
                }
            }

            out.endObject()
        }
    }

    private fun returnDocumentFile(
        apiReceiver: TermuxApiReceiver,
        context: Context,
        intent: Intent,
        f: DocumentFile
    ) {
        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.BinaryOutput() {
            @Throws(Exception::class)
            override fun writeResult(out: OutputStream) {
                context.contentResolver.openInputStream(f.uri)?.use { input ->
                    writeInputStreamToOutputStream(input, out)
                }
            }
        })
    }

    private fun writeDocumentFile(
        apiReceiver: TermuxApiReceiver,
        context: Context,
        intent: Intent,
        f: DocumentFile
    ) {
        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.WithInput() {
            @Throws(Exception::class)
            override fun writeResult(unused: java.io.PrintWriter) {
                context.contentResolver.openOutputStream(f.uri, "rwt")?.use { out ->
                    writeInputStreamToOutputStream(`in`!!, out)
                }
            }
        })
    }

    @Throws(IOException::class)
    private fun writeInputStreamToOutputStream(input: InputStream, out: OutputStream) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            FileUtils.copy(input, out)
        } else {
            val buffer = ByteArray(4096)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
            }
        }
    }
}
