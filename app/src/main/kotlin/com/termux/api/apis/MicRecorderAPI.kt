package com.termux.api.apis

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.ArrayMap
import android.util.SparseArray
import android.util.SparseIntArray
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

object MicRecorderAPI {

    private const val LOG_TAG = "MicRecorderAPI"

    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val recorderService = Intent(context, MicRecorderService::class.java).apply {
            action = intent.action
            putExtras(intent.extras!!)
        }
        context.startService(recorderService)
    }

    class MicRecorderService : Service(), MediaRecorder.OnInfoListener, MediaRecorder.OnErrorListener {

        override fun onCreate() {
            getMediaRecorder(this)
        }

        override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
            Logger.logDebug(LOG_TAG, "onStartCommand")

            val command = intent.action
            val context = applicationContext
            val handler = getRecorderCommandHandler(command)
            val result = handler.handle(context, intent)
            postRecordCommandResult(context, intent, result)

            return START_NOT_STICKY
        }

        override fun onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy")
            cleanupMediaRecorder()
        }

        override fun onBind(intent: Intent): IBinder? = null

        override fun onError(mr: MediaRecorder, what: Int, extra: Int) {
            Logger.logVerbose(LOG_TAG, "onError: what: $what, extra: $extra")
            isRecording = false
            stopSelf()
        }

        override fun onInfo(mr: MediaRecorder, what: Int, extra: Int) {
            Logger.logVerbose(LOG_TAG, "onInfo: what: $what, extra: $extra")

            when (what) {
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED,
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> stopSelf()
            }
        }

        companion object {
            const val MIN_RECORDING_LIMIT = 1000
            const val DEFAULT_RECORDING_LIMIT = 1000 * 60 * 15

            private const val LOG_TAG = "MicRecorderService"

            @JvmStatic
            var mediaRecorder: MediaRecorder? = null

            @JvmStatic
            var isRecording = false

            @JvmStatic
            var file: File? = null

            @JvmStatic
            fun getRecorderCommandHandler(command: String?): RecorderCommandHandler {
                return when (command ?: "") {
                    "info" -> infoHandler
                    "record" -> recordHandler
                    "quit" -> quitHandler
                    else -> RecorderCommandHandler { context, intent ->
                        RecorderCommandResult().apply {
                            error = "Unknown command: $command"
                            if (!isRecording) context.stopService(intent)
                        }
                    }
                }
            }

            @JvmStatic
            fun postRecordCommandResult(context: Context, intent: Intent, result: RecorderCommandResult) {
                ResultReturner.returnData(context, intent) { out ->
                    out.append(result.message).append("\n")
                    result.error?.let { out.append(it).append("\n") }
                    out.flush()
                    out.close()
                }
            }

            @JvmStatic
            fun getMediaRecorder(service: MicRecorderService) {
                mediaRecorder = MediaRecorder().apply {
                    setOnErrorListener(service)
                    setOnInfoListener(service)
                }
            }

            @JvmStatic
            fun cleanupMediaRecorder() {
                mediaRecorder?.let { recorder ->
                    if (isRecording) {
                        recorder.stop()
                        isRecording = false
                    }
                    recorder.reset()
                    recorder.release()
                }
            }

            @JvmStatic
            fun getDefaultRecordingFilename(): String {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
                val date = Date()
                return "${Environment.getExternalStorageDirectory().absolutePath}/TermuxAudioRecording_${dateFormat.format(date)}"
            }

            @JvmStatic
            fun getRecordingInfoJSONString(): String {
                return try {
                    JSONObject().apply {
                        put("isRecording", isRecording)
                        if (isRecording) put("outputFile", file?.absolutePath)
                    }.toString(2)
                } catch (e: JSONException) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "infoHandler json error", e)
                    ""
                }
            }

            @JvmStatic
            val infoHandler = RecorderCommandHandler { context, intent ->
                RecorderCommandResult().apply {
                    message = getRecordingInfoJSONString()
                    if (!isRecording) context.stopService(intent)
                }
            }

            @JvmStatic
            val recordHandler = RecorderCommandHandler { context, intent ->
                RecorderCommandResult().apply {
                    var duration = intent.getIntExtra("limit", DEFAULT_RECORDING_LIMIT)
                    if (duration > 0 && duration < MIN_RECORDING_LIMIT) {
                        duration = MIN_RECORDING_LIMIT
                    }

                    val sencoder = if (intent.hasExtra("encoder")) intent.getStringExtra("encoder") ?: "" else ""
                    val encoderMap = ArrayMap<String, Int>(4).apply {
                        put("aac", MediaRecorder.AudioEncoder.AAC)
                        put("amr_nb", MediaRecorder.AudioEncoder.AMR_NB)
                        put("amr_wb", MediaRecorder.AudioEncoder.AMR_WB)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put("opus", MediaRecorder.AudioEncoder.OPUS)
                        }
                    }

                    val encoder = encoderMap[sencoder.lowercase()] ?: MediaRecorder.AudioEncoder.AAC

                    var format = intent.getIntExtra("format", MediaRecorder.OutputFormat.DEFAULT)
                    if (format == MediaRecorder.OutputFormat.DEFAULT) {
                        val formatMap = SparseIntArray(4).apply {
                            put(MediaRecorder.AudioEncoder.AAC, MediaRecorder.OutputFormat.MPEG_4)
                            put(MediaRecorder.AudioEncoder.AMR_NB, MediaRecorder.OutputFormat.THREE_GPP)
                            put(MediaRecorder.AudioEncoder.AMR_WB, MediaRecorder.OutputFormat.THREE_GPP)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaRecorder.AudioEncoder.OPUS, MediaRecorder.OutputFormat.OGG)
                            }
                        }
                        format = formatMap.get(encoder, MediaRecorder.OutputFormat.DEFAULT)
                    }

                    val extensionMap = SparseArray<String>(3).apply {
                        put(MediaRecorder.OutputFormat.MPEG_4, ".m4a")
                        put(MediaRecorder.OutputFormat.THREE_GPP, ".3gp")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaRecorder.OutputFormat.OGG, ".ogg")
                        }
                    }
                    val extension = extensionMap.get(format)

                    val filename = if (intent.hasExtra("file")) {
                        intent.getStringExtra("file") ?: ""
                    } else {
                        getDefaultRecordingFilename() + (extension ?: "")
                    }

                    val source = intent.getIntExtra("source", MediaRecorder.AudioSource.MIC)
                    val bitrate = intent.getIntExtra("bitrate", 0)
                    val srate = intent.getIntExtra("srate", 0)
                    val channels = intent.getIntExtra("channels", 0)

                    file = File(filename)

                    Logger.logInfo(LOG_TAG, "MediaRecording file is: ${file?.absolutePath}")

                    when {
                        file!!.exists() -> {
                            error = "File: ${file!!.name} already exists! Please specify a different filename"
                        }
                        isRecording -> {
                            error = "Recording already in progress!"
                        }
                        else -> {
                            try {
                                mediaRecorder?.apply {
                                    setAudioSource(source)
                                    setOutputFormat(format)
                                    setAudioEncoder(encoder)
                                    setOutputFile(filename)
                                    setMaxDuration(duration)
                                    if (bitrate > 0) setAudioEncodingBitRate(bitrate)
                                    if (srate > 0) setAudioSamplingRate(srate)
                                    if (channels > 0) setAudioChannels(channels)
                                    prepare()
                                    start()
                                }
                                isRecording = true
                                message = "Recording started: ${file?.absolutePath} \nMax Duration: ${
                                    if (duration <= 0) "unlimited" else MediaPlayerAPI.getTimeString(duration / 1000)
                                }"
                            } catch (e: Exception) {
                                when (e) {
                                    is IllegalStateException, is IOException -> {
                                        Logger.logStackTraceWithMessage(LOG_TAG, "MediaRecorder error", e)
                                        error = "Recording error: ${e.message}"
                                    }
                                    else -> throw e
                                }
                            }
                        }
                    }
                    if (!isRecording) context.stopService(intent)
                }
            }

            @JvmStatic
            val quitHandler = RecorderCommandHandler { context, intent ->
                RecorderCommandResult().apply {
                    message = if (isRecording) {
                        "Recording finished: ${file?.absolutePath}"
                    } else {
                        "No recording to stop"
                    }
                    context.stopService(intent)
                }
            }
        }
    }

    fun interface RecorderCommandHandler {
        fun handle(context: Context, intent: Intent): RecorderCommandResult
    }

    class RecorderCommandResult {
        var message: String = ""
        var error: String? = null
    }
}
