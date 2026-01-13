package com.termux.api.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.IntentService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.util.JsonWriter
import com.termux.shared.android.PackageUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.plugins.TermuxPluginUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

/**
 * Abstract class for returning results to the termux-api client.
 */
abstract class ResultReturner {

    companion object {
        private const val LOG_TAG = "ResultReturner"

        /** An extra intent parameter which specifies a unix socket address where output from the API call should be written. */
        private const val SOCKET_OUTPUT_EXTRA = "socket_output"

        /** An extra intent parameter which specifies a unix socket address where input to the API call can be read from. */
        private const val SOCKET_INPUT_EXTRA = "socket_input"

        @SuppressLint("StaticFieldLeak")
        @JvmStatic
        var context: Context? = null

        /** Just tell termux-api.c that we are done. */
        @JvmStatic
        fun noteDone(receiver: BroadcastReceiver, intent: Intent) {
            returnData(receiver, intent, null)
        }

        @JvmStatic
        fun copyIntentExtras(origIntent: Intent, newIntent: Intent) {
            newIntent.putExtra("api_method", origIntent.getStringExtra("api_method"))
            newIntent.putExtra(SOCKET_OUTPUT_EXTRA, origIntent.getStringExtra(SOCKET_OUTPUT_EXTRA))
            newIntent.putExtra(SOCKET_INPUT_EXTRA, origIntent.getStringExtra(SOCKET_INPUT_EXTRA))
        }

        /**
         * Get [LocalSocketAddress] for a socket address.
         *
         * If socket address starts with a path separator `/`, then a [LocalSocketAddress.Namespace.FILESYSTEM]
         * [LocalSocketAddress] is returned, otherwise an [LocalSocketAddress.Namespace.ABSTRACT].
         */
        @SuppressLint("SdCardPath")
        @JvmStatic
        fun getApiLocalSocketAddress(
            context: Context,
            socketLabel: String,
            socketAddress: String
        ): LocalSocketAddress {
            return if (socketAddress.startsWith("/")) {
                val termuxApplicationInfo = PackageUtils.getApplicationInfoForPackage(
                    context,
                    TermuxConstants.TERMUX_PACKAGE_NAME
                ) ?: throw RuntimeException(
                    "Failed to get ApplicationInfo for the Termux app package: ${TermuxConstants.TERMUX_PACKAGE_NAME}"
                )

                val termuxAppDataDirectories = listOf(
                    termuxApplicationInfo.dataDir,
                    "/data/data/${TermuxConstants.TERMUX_PACKAGE_NAME}"
                )

                if (!FileUtils.isPathInDirPaths(socketAddress, termuxAppDataDirectories, true)) {
                    throw RuntimeException(
                        "The $socketLabel socket address \"$socketAddress\" is not under Termux app data directories: $termuxAppDataDirectories"
                    )
                }

                LocalSocketAddress(socketAddress, LocalSocketAddress.Namespace.FILESYSTEM)
            } else {
                LocalSocketAddress(socketAddress, LocalSocketAddress.Namespace.ABSTRACT)
            }
        }

        @JvmStatic
        fun shouldRunThreadForResultRunnable(context: Any): Boolean {
            return context !is IntentService
        }

        /** Run in a separate thread, unless the context is an IntentService. */
        @JvmStatic
        fun returnData(context: Any, intent: Intent, resultWriter: ResultWriter?) {
            val receiver = context as? BroadcastReceiver
            val activity = context as? Activity
            val asyncResult = receiver?.goAsync()

            // Store caller function stack trace to add to exception messages thrown inside Runnable
            val callerStackTrace: Throwable? = if (shouldRunThreadForResultRunnable(context)) {
                Exception("Called by:")
            } else {
                null
            }

            val runnable = Runnable {
                var writer: PrintWriter? = null
                var outputSocket: LocalSocket? = null
                try {
                    outputSocket = LocalSocket()
                    val outputSocketAddress = intent.getStringExtra(SOCKET_OUTPUT_EXTRA)
                    if (outputSocketAddress.isNullOrEmpty()) {
                        throw IOException("Missing '$SOCKET_OUTPUT_EXTRA' extra")
                    }
                    Logger.logDebug(LOG_TAG, "Connecting to output socket \"$outputSocketAddress\"")
                    outputSocket.connect(
                        getApiLocalSocketAddress(
                            Companion.context!!,
                            "output",
                            outputSocketAddress
                        )
                    )
                    writer = PrintWriter(outputSocket.outputStream)

                    if (resultWriter != null) {
                        if (resultWriter is WithAncillaryFd) {
                            resultWriter.setOutputSocketForFds(outputSocket)
                        }
                        if (resultWriter is BinaryOutput) {
                            resultWriter.setOutput(outputSocket.outputStream)
                        }
                        if (resultWriter is WithInput) {
                            LocalSocket().use { inputSocket ->
                                val inputSocketAddress = intent.getStringExtra(SOCKET_INPUT_EXTRA)
                                if (inputSocketAddress.isNullOrEmpty()) {
                                    throw IOException("Missing '$SOCKET_INPUT_EXTRA' extra")
                                }
                                inputSocket.connect(
                                    getApiLocalSocketAddress(
                                        Companion.context!!,
                                        "input",
                                        inputSocketAddress
                                    )
                                )
                                resultWriter.setInput(inputSocket.inputStream)
                                resultWriter.writeResult(writer)
                            }
                        } else {
                            resultWriter.writeResult(writer)
                        }
                        if (resultWriter is WithAncillaryFd) {
                            resultWriter.cleanupFds()
                        }
                    }

                    if (asyncResult != null && receiver?.isOrderedBroadcast == true) {
                        asyncResult.resultCode = 0
                    } else {
                        activity?.setResult(0)
                    }
                } catch (t: Throwable) {
                    val message = "Error in $LOG_TAG"
                    callerStackTrace?.let { t.addSuppressed(it) }
                    Logger.logStackTraceWithMessage(LOG_TAG, message, t)

                    TermuxPluginUtils.sendPluginCommandErrorNotification(
                        Companion.context,
                        LOG_TAG,
                        "${TermuxConstants.TERMUX_API_APP_NAME} Error",
                        message,
                        t
                    )

                    if (asyncResult != null && receiver?.isOrderedBroadcast == true) {
                        asyncResult.resultCode = 1
                    } else {
                        activity?.setResult(1)
                    }
                } finally {
                    try {
                        writer?.close()
                        outputSocket?.close()
                    } catch (e: Exception) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Failed to close", e)
                    }

                    try {
                        asyncResult?.finish() ?: activity?.finish()
                    } catch (e: Exception) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Failed to finish", e)
                    }
                }
            }

            if (shouldRunThreadForResultRunnable(context)) {
                Thread(runnable).start()
            } else {
                runnable.run()
            }
        }

        @JvmStatic
        fun setContext(context: Context) {
            Companion.context = context.applicationContext
        }
    }

    interface ResultWriter {
        @Throws(Exception::class)
        fun writeResult(out: PrintWriter)
    }

    /** Possible subclass of [ResultWriter] when input is to be read from [SOCKET_INPUT_EXTRA]. */
    abstract class WithInput : ResultWriter {
        protected var `in`: InputStream? = null

        @Throws(Exception::class)
        open fun setInput(inputStream: InputStream) {
            this.`in` = inputStream
        }
    }

    /** Possible subclass of [ResultWriter] when the output is binary data instead of text. */
    abstract class BinaryOutput : ResultWriter {
        private var out: OutputStream? = null

        fun setOutput(outputStream: OutputStream) {
            this.out = outputStream
        }

        @Throws(Exception::class)
        abstract fun writeResult(out: OutputStream)

        /** writeResult with a PrintWriter is marked as final and overwritten, so you don't accidentally use it */
        @Throws(Exception::class)
        final override fun writeResult(out: PrintWriter) {
            writeResult(this.out!!)
            this.out!!.flush()
        }
    }

    /** Possible marker interface for a [ResultWriter] when input is to be read from [SOCKET_INPUT_EXTRA]. */
    abstract class WithStringInput : WithInput() {
        protected var inputString: String = ""

        protected open fun trimInput(): Boolean = true

        @Throws(Exception::class)
        final override fun setInput(inputStream: InputStream) {
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var l: Int
            while (inputStream.read(buffer).also { l = it } > 0) {
                baos.write(buffer, 0, l)
            }
            inputString = String(baos.toByteArray(), StandardCharsets.UTF_8)
            if (trimInput()) inputString = inputString.trim()
        }
    }

    abstract class WithAncillaryFd : ResultWriter {
        private var outputSocket: LocalSocket? = null
        private val pfds = arrayOfNulls<ParcelFileDescriptor>(1)

        fun setOutputSocketForFds(outputSocket: LocalSocket) {
            this.outputSocket = outputSocket
        }

        fun sendFd(out: PrintWriter, fd: Int) {
            // If fd already sent, then error out as we only support sending one currently.
            if (this.pfds[0] != null) {
                Logger.logStackTraceWithMessage(LOG_TAG, "File descriptor already sent", Exception())
                return
            }

            this.pfds[0] = ParcelFileDescriptor.adoptFd(fd)
            val fds = arrayOf(pfds[0]!!.fileDescriptor)

            // Set fd to be sent
            outputSocket?.setFileDescriptorsForSend(fds)

            // Write the `@` character expected by termux-api command when a fd is sent.
            out.print("@")

            // Actually send the fd by flushing the data
            out.flush()

            // Clear existing fd after it has been sent
            outputSocket?.setFileDescriptorsForSend(null)
        }

        fun cleanupFds() {
            pfds[0]?.let {
                try {
                    it.close()
                } catch (e: IOException) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to close file descriptor", e)
                }
            }
        }
    }

    abstract class ResultJsonWriter : ResultWriter {
        @Throws(Exception::class)
        final override fun writeResult(out: PrintWriter) {
            val writer = JsonWriter(out)
            writer.setIndent("  ")
            writeJson(writer)
            out.println() // To add trailing newline.
        }

        @Throws(Exception::class)
        abstract fun writeJson(out: JsonWriter)
    }
}
