package com.termux.api

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

object SocketListener {

    val LISTEN_ADDRESS: String = "${TermuxConstants.TERMUX_API_PACKAGE_NAME}://listen"
    
    private val EXTRA_STRING = Pattern.compile("(-e|--es|--esa) +([^ ]+) +\"(.*?)(?<!\\\\)\"", Pattern.DOTALL)
    private val EXTRA_BOOLEAN = Pattern.compile("--ez +([^ ]+) +([^ ]+)")
    private val EXTRA_INT = Pattern.compile("--ei +([^ ]+) +(-?[0-9]+)")
    private val EXTRA_FLOAT = Pattern.compile("--ef +([^ ]+) +(-?[0-9]+(?:\\.[0-9]+))")
    private val EXTRA_INT_LIST = Pattern.compile("--eia +([^ ]+) +(-?[0-9]+(?:,-?[0-9]+)*)")
    private val EXTRA_LONG_LIST = Pattern.compile("--ela +([^ ]+) +(-?[0-9]+(?:,-?[0-9]+)*)")
    private val EXTRA_UNSUPPORTED = Pattern.compile("--e[^izs ] +[^ ]+ +[^ ]+")
    private val ACTION = Pattern.compile("-a *([^ ]+)")

    private var listener: Thread? = null
    private const val LOG_TAG = "SocketListener"

    @SuppressLint("NewApi")
    @JvmStatic
    fun createSocketListener(app: Application) {
        if (listener == null) {
            listener = Thread {
                try {
                    LocalServerSocket(LISTEN_ADDRESS).use { listen ->
                        while (true) {
                            try {
                                val con = listen.accept()
                                con.use {
                                    DataInputStream(con.inputStream).use { input ->
                                        BufferedWriter(OutputStreamWriter(con.outputStream)).use { out ->
                                            // only accept connections from Termux programs
                                            if (con.peerCredentials.uid != app.applicationInfo.uid) {
                                                return@use
                                            }
                                            try {
                                                val length = input.readUnsignedShort()
                                                val b = ByteArray(length)
                                                input.readFully(b)
                                                var cmdline = String(b, StandardCharsets.UTF_8)

                                                val intent = Intent(app.applicationContext, TermuxApiReceiver::class.java)
                                                val stringExtras = HashMap<String, String>()
                                                val stringArrayExtras = HashMap<String, Array<String>>()
                                                val booleanExtras = HashMap<String, Boolean>()
                                                val intExtras = HashMap<String, Int>()
                                                val floatExtras = HashMap<String, Float>()
                                                val intArrayExtras = HashMap<String, IntArray>()
                                                val longArrayExtras = HashMap<String, LongArray>()
                                                var err = false

                                                // extract and remove the string extras first
                                                var m = EXTRA_STRING.matcher(cmdline)
                                                while (m.find()) {
                                                    val option = m.group(1)
                                                    if ("-e" == option || "--es" == option) {
                                                        stringExtras[m.group(2)!!] = m.group(3)!!.replace("\\\"", "\"")
                                                    } else {
                                                        val list = m.group(3)!!.split("(?<!\\\\),".toRegex()).toTypedArray()
                                                        for (i in list.indices) {
                                                            list[i] = list[i].replaceFirst("\\\\,".toRegex(), ",")
                                                        }
                                                        stringArrayExtras[m.group(2)!!] = list
                                                    }
                                                }
                                                cmdline = m.replaceAll("")

                                                m = EXTRA_BOOLEAN.matcher(cmdline)
                                                while (m.find()) {
                                                    val value = m.group(2)?.lowercase()
                                                    var arg: Boolean? = null

                                                    when (value) {
                                                        "true", "t" -> arg = true
                                                        "false", "f" -> arg = false
                                                        else -> {
                                                            try {
                                                                if (value != null) {
                                                                    arg = Integer.decode(value) != 0
                                                                }
                                                            } catch (ex: NumberFormatException) {
                                                                // Ignore
                                                            }
                                                        }
                                                    }
                                                    if (arg == null) {
                                                        val msg = "Invalid boolean extra: ${m.group(0)}\n"
                                                        Logger.logInfo(LOG_TAG, msg)
                                                        out.write(msg)
                                                        err = true
                                                        break
                                                    }
                                                    booleanExtras[m.group(1)!!] = arg
                                                }
                                                cmdline = m.replaceAll("")

                                                m = EXTRA_INT.matcher(cmdline)
                                                while (m.find()) {
                                                    try {
                                                        intExtras[m.group(1)!!] = m.group(2)!!.toInt()
                                                    } catch (e: NumberFormatException) {
                                                        val msg = "Invalid integer extra: ${m.group(0)}\n"
                                                        Logger.logInfo(LOG_TAG, msg)
                                                        out.write(msg)
                                                        err = true
                                                        break
                                                    }
                                                }
                                                cmdline = m.replaceAll("")

                                                m = EXTRA_FLOAT.matcher(cmdline)
                                                while (m.find()) {
                                                    try {
                                                        floatExtras[m.group(1)!!] = m.group(2)!!.toFloat()
                                                    } catch (e: NumberFormatException) {
                                                        val msg = "Invalid float extra: ${m.group(0)}\n"
                                                        Logger.logInfo(LOG_TAG, msg)
                                                        out.write(msg)
                                                        err = true
                                                        break
                                                    }
                                                }
                                                cmdline = m.replaceAll("")

                                                m = EXTRA_INT_LIST.matcher(cmdline)
                                                while (m.find()) {
                                                    try {
                                                        val parts = m.group(2)!!.split(",")
                                                        val ints = IntArray(parts.size)
                                                        for (i in parts.indices) {
                                                            ints[i] = parts[i].toInt()
                                                        }
                                                        intArrayExtras[m.group(1)!!] = ints
                                                    } catch (e: NumberFormatException) {
                                                        val msg = "Invalid int array extra: ${m.group(0)}\n"
                                                        Logger.logInfo(LOG_TAG, msg)
                                                        out.write(msg)
                                                        err = true
                                                        break
                                                    }
                                                }
                                                cmdline = m.replaceAll("")

                                                m = EXTRA_LONG_LIST.matcher(cmdline)
                                                while (m.find()) {
                                                    try {
                                                        val parts = m.group(2)!!.split(",")
                                                        val longs = LongArray(parts.size)
                                                        for (i in parts.indices) {
                                                            longs[i] = parts[i].toLong()
                                                        }
                                                        longArrayExtras[m.group(1)!!] = longs
                                                    } catch (e: NumberFormatException) {
                                                        val msg = "Invalid long array extra: ${m.group(0)}\n"
                                                        Logger.logInfo(LOG_TAG, msg)
                                                        out.write(msg)
                                                        err = true
                                                        break
                                                    }
                                                }
                                                cmdline = m.replaceAll("")

                                                m = ACTION.matcher(cmdline)
                                                while (m.find()) {
                                                    intent.action = m.group(1)
                                                }
                                                cmdline = m.replaceAll("")

                                                m = EXTRA_UNSUPPORTED.matcher(cmdline)
                                                if (m.find()) {
                                                    val msg = "Unsupported argument type: ${m.group(0)}\n"
                                                    Logger.logInfo(LOG_TAG, msg)
                                                    out.write(msg)
                                                    err = true
                                                }
                                                cmdline = m.replaceAll("")

                                                // check if there are any non-whitespace characters left
                                                cmdline = cmdline.replace("\\s".toRegex(), "")
                                                if (cmdline.isNotEmpty()) {
                                                    val msg = "Unsupported options: $cmdline\n"
                                                    Logger.logInfo(LOG_TAG, msg)
                                                    out.write(msg)
                                                    err = true
                                                }

                                                if (err) {
                                                    out.flush()
                                                    return@use
                                                }

                                                // set the intent extras
                                                for ((key, value) in stringExtras) {
                                                    intent.putExtra(key, value)
                                                }
                                                for ((key, value) in stringArrayExtras) {
                                                    intent.putExtra(key, value)
                                                }
                                                for ((key, value) in intExtras) {
                                                    intent.putExtra(key, value)
                                                }
                                                for ((key, value) in booleanExtras) {
                                                    intent.putExtra(key, value)
                                                }
                                                for ((key, value) in floatExtras) {
                                                    intent.putExtra(key, value)
                                                }
                                                for ((key, value) in intArrayExtras) {
                                                    intent.putExtra(key, value)
                                                }
                                                for ((key, value) in longArrayExtras) {
                                                    intent.putExtra(key, value)
                                                }
                                                app.applicationContext.sendOrderedBroadcast(intent, null)
                                                // send a null byte as a sign that the arguments have been successfully received
                                                con.outputStream.write(0)
                                                con.outputStream.flush()
                                            } catch (e: Exception) {
                                                Logger.logStackTraceWithMessage(LOG_TAG, "Error parsing arguments", e)
                                                out.write("Exception in the plugin\n")
                                                out.flush()
                                            }
                                        }
                                    }
                                }
                            } catch (e: java.io.IOException) {
                                Logger.logStackTraceWithMessage(LOG_TAG, "Connection error", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Error listening for connections", e)
                }
            }
            listener?.start()
        }
    }
}
