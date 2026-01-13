package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import java.io.File
import java.io.PrintWriter
import java.util.Locale
import java.util.Stack

object MediaScannerAPI {

    private const val LOG_TAG = "MediaScannerAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val filePaths = intent.getStringArrayExtra("paths") ?: return
        val recursive = intent.getBooleanExtra("recursive", false)
        val totalScanned = intArrayOf(0)
        val verbose = intent.getBooleanExtra("verbose", false)
        
        for (i in filePaths.indices) {
            filePaths[i] = filePaths[i].replace("\\,", ",")
        }

        ResultReturner.returnData(apiReceiver, intent) { out ->
            scanFiles(out, context, filePaths, totalScanned, verbose)
            if (recursive) scanFilesRecursively(out, context, filePaths, totalScanned, verbose)
            out.println(String.format(Locale.ENGLISH, "Finished scanning %d file(s)", totalScanned[0]))
        }
    }

    private fun scanFiles(
        out: PrintWriter,
        context: Context,
        filePaths: Array<String>,
        totalScanned: IntArray,
        verbose: Boolean
    ) {
        MediaScannerConnection.scanFile(
            context.applicationContext,
            filePaths,
            null
        ) { path, uri ->
            Logger.logInfo(LOG_TAG, "'$path'" + if (uri != null) " -> '$uri'" else "")
        }

        if (verbose) {
            for (path in filePaths) {
                out.println(path)
            }
        }

        totalScanned[0] += filePaths.size
    }

    private fun scanFilesRecursively(
        out: PrintWriter,
        context: Context,
        filePaths: Array<String>,
        totalScanned: IntArray,
        verbose: Boolean
    ) {
        for (filePath in filePaths) {
            val subDirs = Stack<File>()
            var currentPath: File? = File(filePath)
            
            while (currentPath != null && currentPath.isDirectory && currentPath.canRead()) {
                var fileList: Array<File>? = null

                try {
                    fileList = currentPath.listFiles()
                } catch (e: SecurityException) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open '$currentPath'", e)
                }

                if (fileList != null && fileList.isNotEmpty()) {
                    val filesToScan = Array(fileList.size) { i ->
                        fileList[i].toString().also {
                            if (fileList[i].isDirectory) subDirs.push(fileList[i])
                        }
                    }
                    scanFiles(out, context, filesToScan, totalScanned, verbose)
                }

                currentPath = if (subDirs.isNotEmpty()) {
                    File(subDirs.pop().toString())
                } else {
                    null
                }
            }
        }
    }
}
