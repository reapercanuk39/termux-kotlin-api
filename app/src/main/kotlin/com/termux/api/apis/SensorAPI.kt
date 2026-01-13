package com.termux.api.apis

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.LocalSocket
import android.os.IBinder
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.PrintWriter
import java.util.concurrent.Semaphore

object SensorAPI {

    private const val LOG_TAG = "SensorAPI"

    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val serviceIntent = Intent(context, SensorReaderService::class.java).apply {
            action = intent.action
            putExtras(intent.extras!!)
        }
        context.startService(serviceIntent)
    }

    class SensorReaderService : Service() {

        override fun onCreate() {
            Logger.logDebug(LOG_TAG, "onCreate")
            super.onCreate()
            sensorReadout = JSONObject()
            semaphore = Semaphore(1)
        }

        override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
            Logger.logDebug(LOG_TAG, "onStartCommand")

            val command = intent.action
            val context = applicationContext
            val sensorMgr = getSensorManager(context)

            val handler = getSensorCommandHandler(command)
            val result = handler.handle(sensorMgr, context, intent)

            if (result.type == ResultType.SINGLE) {
                postSensorCommandResult(context, intent, result)
            }
            return START_NOT_STICKY
        }

        override fun onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy")
            super.onDestroy()
            cleanup()
        }

        override fun onBind(intent: Intent): IBinder? = null

        private fun postSensorCommandResult(context: Context, intent: Intent, result: SensorCommandResult) {
            ResultReturner.returnData(context, intent, ResultReturner.ResultWriter { out ->
                out.append(result.message).append("\n")
                result.error?.let { out.append(it).append("\n") }
                out.flush()
                out.close()
            })
        }

        companion object {
            const val INDENTATION = 2
            private const val LOG_TAG = "SensorReaderService"

            @JvmStatic
            var sensorManager: SensorManager? = null

            @JvmStatic
            var sensorReadout: JSONObject = JSONObject()

            @JvmStatic
            var outputWriter: SensorOutputWriter? = null

            @JvmStatic
            var semaphore: Semaphore = Semaphore(1)

            @JvmStatic
            fun getSensorManager(context: Context): SensorManager {
                if (sensorManager == null) {
                    sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                }
                return sensorManager!!
            }

            @JvmStatic
            fun cleanup() {
                outputWriter?.let {
                    if (it.isRunning) {
                        it.interrupt()
                    }
                }
                outputWriter = null

                sensorManager?.unregisterListener(sensorEventListener)
                sensorManager = null
            }

            @JvmStatic
            val sensorEventListener = object : SensorEventListener {
                override fun onSensorChanged(sensorEvent: SensorEvent) {
                    val sensorValuesArray = JSONArray()
                    try {
                        semaphore.acquire()
                        for (j in sensorEvent.values.indices) {
                            sensorValuesArray.put(j, sensorEvent.values[j])
                        }
                        val sensorInfo = JSONObject()
                        sensorInfo.put("values", sensorValuesArray)
                        sensorReadout.put(sensorEvent.sensor.name, sensorInfo)
                        semaphore.release()
                    } catch (e: JSONException) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "onSensorChanged error", e)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor, i: Int) {}
            }

            @JvmStatic
            fun getSensorCommandHandler(command: String?): SensorCommandHandler {
                return when (command ?: "") {
                    "list" -> listHandler
                    "cleanup" -> cleanupHandler
                    "sensors" -> sensorHandler
                    else -> SensorCommandHandler { _, _, _ ->
                        SensorCommandResult().apply {
                            message = "Unknown command: $command"
                        }
                    }
                }
            }

            @JvmStatic
            val listHandler = SensorCommandHandler { sensorManager, _, _ ->
                SensorCommandResult().apply {
                    val sensorArray = JSONArray()
                    val sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL)

                    try {
                        for (j in sensorList.indices) {
                            sensorArray.put(sensorList[j].name)
                        }
                        val output = JSONObject()
                        output.put("sensors", sensorArray)
                        message = output.toString(INDENTATION)
                    } catch (e: JSONException) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "listHandler JSON error", e)
                    }
                }
            }

            @JvmStatic
            val cleanupHandler = SensorCommandHandler { sensorManager, _, _ ->
                SensorCommandResult().apply {
                    if (outputWriter != null) {
                        outputWriter?.interrupt()
                        outputWriter = null
                        sensorManager.unregisterListener(sensorEventListener)
                        message = "Sensor cleanup successful!"
                        Logger.logInfo(LOG_TAG, "Cleanup()")
                    } else {
                        message = "Sensor cleanup unnecessary"
                    }
                }
            }

            @JvmStatic
            val sensorHandler = SensorCommandHandler { sensorManager, _, intent ->
                SensorCommandResult().apply {
                    type = ResultType.CONTINUOUS

                    clearSensorValues()

                    val requestedSensors = getUserRequestedSensors(intent)
                    val sensorsToListenTo = getSensorsToListenTo(sensorManager, requestedSensors, intent)

                    if (sensorsToListenTo.isEmpty()) {
                        message = "No valid sensors were registered!"
                        type = ResultType.SINGLE
                    } else {
                        if (outputWriter == null) {
                            outputWriter = createSensorOutputWriter(intent)
                            outputWriter?.start()
                        }
                    }
                }
            }

            @JvmStatic
            fun getUserRequestedSensors(intent: Intent): Array<String> {
                val sensorListString = if (intent.hasExtra("sensors")) intent.getStringExtra("sensors") ?: "" else ""
                return sensorListString.split(",").toTypedArray()
            }

            @JvmStatic
            fun getSensorsToListenTo(
                sensorManager: SensorManager,
                requestedSensors: Array<String>,
                intent: Intent
            ): List<Sensor> {
                val availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL).sortedBy { it.name }
                val sensorsToListenTo = mutableListOf<Sensor>()

                val listenToAll = intent.getBooleanExtra("all", false)

                if (listenToAll) {
                    for (sensor in availableSensors) {
                        sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_UI)
                    }
                    sensorsToListenTo.addAll(availableSensors)
                    Logger.logInfo(LOG_TAG, "Listening to ALL sensors")
                } else {
                    for (requestedSensor in requestedSensors) {
                        val requestedUpper = requestedSensor.uppercase()

                        var shortestMatchSensor: Sensor? = null
                        var shortestMatchSensorLength = Int.MAX_VALUE

                        for (availableSensor in availableSensors) {
                            val sensorName = availableSensor.name.uppercase()
                            if (sensorName.contains(requestedUpper) && sensorName.length < shortestMatchSensorLength) {
                                shortestMatchSensor = availableSensor
                                shortestMatchSensorLength = sensorName.length
                            }
                        }

                        shortestMatchSensor?.let {
                            sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_UI)
                            sensorsToListenTo.add(it)
                        }
                    }
                }
                return sensorsToListenTo
            }

            @JvmStatic
            fun clearSensorValues() {
                sensorManager?.unregisterListener(sensorEventListener)
                sensorReadout = JSONObject()
            }

            @JvmStatic
            fun createSensorOutputWriter(intent: Intent): SensorOutputWriter {
                val socketAddress = intent.getStringExtra("socket_output")

                val writer = SensorOutputWriter(socketAddress ?: "")
                writer.setOnErrorListener { e ->
                    outputWriter = null
                    Logger.logStackTraceWithMessage(LOG_TAG, "SensorOutputWriter error", e)
                }

                val delay = intent.getIntExtra("delay", SensorOutputWriter.DEFAULT_DELAY)
                Logger.logInfo(LOG_TAG, "Delay set to: $delay")
                writer.delay = delay

                val limit = intent.getIntExtra("limit", SensorOutputWriter.DEFAULT_LIMIT)
                Logger.logInfo(LOG_TAG, "SensorOutput limit set to: $limit")
                writer.limit = limit

                return writer
            }
        }

        class SensorOutputWriter(
            private val outputSocketAddress: String,
            var delay: Int = DEFAULT_DELAY
        ) : Thread() {

            var isRunning = false
                private set

            private var counter = 0
            var limit = DEFAULT_LIMIT
            private var errorListener: SocketWriterErrorListener? = null

            fun setOnErrorListener(errorListener: SocketWriterErrorListener) {
                this.errorListener = errorListener
            }

            override fun run() {
                isRunning = true
                counter = 0

                try {
                    LocalSocket().use { outputSocket ->
                        outputSocket.connect(
                            ResultReturner.getApiLocalSocketAddress(
                                ResultReturner.context, "output", outputSocketAddress
                            )
                        )

                        PrintWriter(outputSocket.outputStream).use { writer ->
                            while (isRunning) {
                                try {
                                    sleep(delay.toLong())
                                } catch (e: InterruptedException) {
                                    Logger.logInfo(LOG_TAG, "SensorOutputWriter interrupted: ${e.message}")
                                }
                                semaphore.acquire()
                                writer.write(sensorReadout.toString(INDENTATION) + "\n")
                                writer.flush()
                                semaphore.release()

                                if (++counter >= limit) {
                                    Logger.logInfo(LOG_TAG, "SensorOutput limit reached! Performing cleanup")
                                    cleanup()
                                }
                            }
                            Logger.logInfo(LOG_TAG, "SensorOutputWriter finished")
                        }
                    }
                } catch (e: Exception) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "SensorOutputWriter error", e)
                    errorListener?.onError(e)
                }
            }

            override fun interrupt() {
                super.interrupt()
                isRunning = false
            }

            companion object {
                const val DEFAULT_DELAY = 1000
                const val DEFAULT_LIMIT = Int.MAX_VALUE
            }
        }
    }

    fun interface SocketWriterErrorListener {
        fun onError(e: Exception)
    }

    fun interface SensorCommandHandler {
        fun handle(sensorManager: SensorManager, context: Context, intent: Intent): SensorCommandResult
    }

    class SensorCommandResult {
        var message: String = ""
        var type: ResultType = ResultType.SINGLE
        var error: String? = null
    }

    enum class ResultType {
        SINGLE,
        CONTINUOUS
    }
}
