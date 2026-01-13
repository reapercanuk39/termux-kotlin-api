package com.termux.api.apis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.data.IntentUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.file.TermuxFileUtils
import java.io.FileOutputStream
import java.io.IOException

object StorageGetAPI {

    private const val FILE_EXTRA = "${TermuxConstants.TERMUX_API_PACKAGE_NAME}.storage.file"
    private const val LOG_TAG = "StorageGetAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        ResultReturner.returnData(apiReceiver, intent) { out ->
            val fileExtra = intent.getStringExtra("file")
            if (fileExtra.isNullOrEmpty()) {
                out.println("ERROR: File path not passed")
                return@returnData
            }

            val filePath = TermuxFileUtils.getCanonicalPath(fileExtra, null, true)
            val fileParentDirPath = FileUtils.getFileDirname(filePath)
            Logger.logVerbose(LOG_TAG, "filePath=\"$filePath\", fileParentDirPath=\"$fileParentDirPath\"")

            val error = FileUtils.checkMissingFilePermissions("file parent directory", fileParentDirPath, "rw-", true)
            if (error != null) {
                out.println("ERROR: ${error.errorLogString}")
                return@returnData
            }

            val intent1 = Intent(context, StorageActivity::class.java).apply {
                putExtra(FILE_EXTRA, filePath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent1)
        }
    }

    class StorageActivity : Activity() {

        private var outputFile: String? = null

        companion object {
            private const val LOG_TAG = "StorageActivity"
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            Logger.logDebug(LOG_TAG, "onCreate")
            super.onCreate(savedInstanceState)
        }

        override fun onResume() {
            Logger.logVerbose(LOG_TAG, "onResume")
            super.onResume()

            outputFile = intent.getStringExtra(FILE_EXTRA)

            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }

            startActivityForResult(intent, 42)
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
            Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: $requestCode, resultCode: $resultCode, data: ${IntentUtils.getIntentString(resultData)}")

            super.onActivityResult(requestCode, resultCode, resultData)
            if (resultCode == RESULT_OK) {
                val data = resultData?.data
                if (data != null) {
                    try {
                        contentResolver.openInputStream(data)?.use { input ->
                            FileOutputStream(outputFile).use { output ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    } catch (e: IOException) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Error copying $data to $outputFile", e)
                    }
                }
            }
            finish()
        }
    }
}
