package com.termux.api.apis

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.JsonWriter
import android.util.SparseArray

import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object UsbAPI {

    private const val LOG_TAG = "UsbAPI"

    val openDevices = SparseArray<UsbDeviceConnection>()

    private val ACTION_USB_PERMISSION = "${TermuxConstants.TERMUX_API_PACKAGE_NAME}.USB_PERMISSION"

    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val serviceIntent = Intent(context, UsbService::class.java).apply {
            action = intent.action
            intent.extras?.let { putExtras(it) }
        }
        context.startService(serviceIntent)
    }

    class UsbService : Service() {

        companion object {
            private const val LOG_TAG = "UsbService"
        }

        private val mThreadPoolExecutor = ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        )

        override fun onBind(intent: Intent): IBinder? = null

        override fun onCreate() {
            Logger.logDebug(LOG_TAG, "onCreate")
            super.onCreate()
        }

        override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
            Logger.logDebug(LOG_TAG, "onStartCommand")

            val action = intent.action
            if (action == null) {
                Logger.logError(LOG_TAG, "No action passed")
                ResultReturner.returnData(this, intent) { out -> out.append("Missing action\n") }
            }

            when (action) {
                "list" -> runListAction(intent)
                "permission" -> runPermissionAction(intent)
                "open" -> runOpenAction(intent)
                else -> {
                    Logger.logError(LOG_TAG, "Invalid action: \"$action\"")
                    ResultReturner.returnData(this, intent) { out -> out.append("Invalid action: \"$action\"\n") }
                }
            }

            return START_NOT_STICKY
        }

        override fun onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy")
            super.onDestroy()
        }

        private fun runListAction(intent: Intent) {
            Logger.logVerbose(LOG_TAG, "Running 'list' usb devices action")

            ResultReturner.returnData(this, intent, object : ResultReturner.ResultJsonWriter() {
                @Throws(Exception::class)
                override fun writeJson(out: JsonWriter) {
                    listDevices(out)
                }
            })
        }

        @Throws(IOException::class)
        private fun listDevices(out: JsonWriter) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            out.beginArray()
            for (deviceName in deviceList.keys) {
                out.value(deviceName)
            }
            out.endArray()
        }

        private fun runPermissionAction(intent: Intent) {
            mThreadPoolExecutor.submit {
                val deviceName = intent.getStringExtra("device")

                Logger.logVerbose(LOG_TAG, "Running 'permission' action for device \"$deviceName\"")

                val device = getDevice(intent, deviceName) ?: return@submit

                val status = checkAndRequestUsbDevicePermission(intent, device)
                ResultReturner.returnData(this, intent) { out ->
                    when (status) {
                        0 -> {
                            Logger.logVerbose(LOG_TAG, "Permission granted for device \"${device.deviceName}\"")
                            out.append("Permission granted.\n")
                        }
                        1 -> {
                            Logger.logVerbose(LOG_TAG, "Permission denied for device \"${device.deviceName}\"")
                            out.append("Permission denied.\n")
                        }
                        -1 -> out.append("Permission request timeout.\n")
                    }
                }
            }
        }

        private fun runOpenAction(intent: Intent) {
            mThreadPoolExecutor.submit {
                val deviceName = intent.getStringExtra("device")

                Logger.logVerbose(LOG_TAG, "Running 'open' action for device \"$deviceName\"")

                val device = getDevice(intent, deviceName) ?: return@submit

                val status = checkAndRequestUsbDevicePermission(intent, device)
                ResultReturner.returnData(this, intent, object : ResultReturner.WithAncillaryFd() {
                    override fun writeResult(out: java.io.PrintWriter) {
                        when (status) {
                            0 -> {
                                val fd = open(device)
                                if (fd < 0) {
                                    Logger.logVerbose(LOG_TAG, "Failed to open device \"${device.deviceName}\": $fd")
                                    out.append("Open device failed.\n")
                                } else {
                                    Logger.logVerbose(LOG_TAG, "Open device \"${device.deviceName}\" successful")
                                    this.sendFd(out, fd)
                                }
                            }
                            1 -> {
                                Logger.logVerbose(LOG_TAG, "Permission denied to open device \"${device.deviceName}\"")
                                out.append("Permission denied.\n")
                            }
                            -1 -> out.append("Permission request timeout.\n")
                        }
                    }
                })
            }
        }

        private fun open(device: UsbDevice): Int {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

            val connection = usbManager.openDevice(device) ?: return -2

            val fd = connection.fileDescriptor
            if (fd == -1) {
                connection.close()
                return -1
            }

            openDevices.put(fd, connection)
            return fd
        }

        private fun getDevice(intent: Intent, deviceName: String?): UsbDevice? {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

            val deviceList = usbManager.deviceList
            val device = deviceList[deviceName]
            if (device == null) {
                Logger.logVerbose(LOG_TAG, "Failed to find device \"$deviceName\"")
                ResultReturner.returnData(this, intent) { out -> out.append("No such device.\n") }
            }

            return device
        }

        private fun checkUsbDevicePermission(device: UsbDevice): Boolean {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            return usbManager.hasPermission(device)
        }

        private fun checkAndRequestUsbDevicePermission(intent: Intent, device: UsbDevice): Int {
            val checkResult = checkUsbDevicePermission(device)
            Logger.logVerbose(LOG_TAG, "Permission check result for device \"${device.deviceName}\": $checkResult")
            if (checkResult) {
                return 0
            }

            if (!intent.getBooleanExtra("request", false)) {
                return 1
            }

            Logger.logVerbose(LOG_TAG, "Requesting permission for device \"${device.deviceName}\"")

            val latch = CountDownLatch(1)
            val result = AtomicReference<Boolean>()

            var usbReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
                override fun onReceive(context: Context, usbIntent: Intent) {
                    if (ACTION_USB_PERMISSION == usbIntent.action) {
                        val requestResult = usbIntent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Logger.logVerbose(LOG_TAG, "Permission request result for device \"${device.deviceName}\": $requestResult")
                        result.set(requestResult)
                    }
                    context.unregisterReceiver(this)
                    latch.countDown()
                }
            }

            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

            val usbIntent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(packageName)
            }

            @Suppress("ObsoleteSdkInt")
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(this, 0, usbIntent, pendingIntentFlags)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        usbReceiver,
                        IntentFilter(ACTION_USB_PERMISSION),
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))
                }

                usbManager.requestPermission(device, permissionIntent)

                try {
                    if (!latch.await(30L, TimeUnit.SECONDS)) {
                        Logger.logVerbose(LOG_TAG, "Permission request time out for device \"${device.deviceName}\" after 30s")
                        return -1
                    }
                } catch (e: InterruptedException) {
                    throw RuntimeException(e)
                }

                val requestResult = result.get()
                return if (requestResult != null) {
                    usbReceiver = null
                    if (requestResult) 0 else 1
                } else {
                    1
                }
            } finally {
                try {
                    usbReceiver?.let { unregisterReceiver(it) }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
}
