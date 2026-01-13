package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.JsonWriter
import android.util.SparseArray

import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

import java.io.IOException

object VolumeAPI {

    private const val STREAM_UNKNOWN = -1
    private const val LOG_TAG = "VolumeAPI"

    // string representations for each of the available audio streams
    private val streamMap = SparseArray<String>().apply {
        append(AudioManager.STREAM_ALARM, "alarm")
        append(AudioManager.STREAM_MUSIC, "music")
        append(AudioManager.STREAM_NOTIFICATION, "notification")
        append(AudioManager.STREAM_RING, "ring")
        append(AudioManager.STREAM_SYSTEM, "system")
        append(AudioManager.STREAM_VOICE_CALL, "call")
    }

    @JvmStatic
    fun onReceive(receiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val action = intent.action

        if ("set-volume" == action) {
            val streamName = intent.getStringExtra("stream")
            val stream = getAudioStream(streamName)

            if (stream == STREAM_UNKNOWN) {
                val error = "ERROR: Unknown stream: $streamName"
                printError(context, intent, error)
            } else {
                setStreamVolume(intent, audioManager, stream)
                ResultReturner.noteDone(receiver, intent)
            }
        } else {
            printAllStreamInfo(context, intent, audioManager)
        }
    }

    private fun printError(context: Context, intent: Intent, error: String) {
        ResultReturner.returnData(context, intent) { out ->
            out.append(error).append("\n")
            out.flush()
            out.close()
        }
    }

    private fun setStreamVolume(intent: Intent, audioManager: AudioManager, stream: Int) {
        var volume = intent.getIntExtra("volume", audioManager.getStreamVolume(stream))
        val maxVolume = audioManager.getStreamMaxVolume(stream)

        volume = when {
            volume <= 0 -> 0
            volume >= maxVolume -> maxVolume
            else -> volume
        }
        audioManager.setStreamVolume(stream, volume, 0)
    }

    private fun printAllStreamInfo(context: Context, intent: Intent, audioManager: AudioManager) {
        ResultReturner.returnData(context, intent, object : ResultReturner.ResultJsonWriter() {
            @Throws(Exception::class)
            override fun writeJson(out: JsonWriter) {
                getStreamsInfo(audioManager, out)
                out.close()
            }
        })
    }

    @Throws(IOException::class)
    private fun getStreamsInfo(audioManager: AudioManager, out: JsonWriter) {
        out.beginArray()
        for (j in 0 until streamMap.size()) {
            val stream = streamMap.keyAt(j)
            getStreamInfo(audioManager, out, stream)
        }
        out.endArray()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun getStreamInfo(audioManager: AudioManager, out: JsonWriter, stream: Int) {
        out.beginObject()
        out.name("stream").value(streamMap.get(stream))
        out.name("volume").value(audioManager.getStreamVolume(stream).toLong())
        out.name("max_volume").value(audioManager.getStreamMaxVolume(stream).toLong())
        out.endObject()
    }

    @JvmStatic
    fun getAudioStream(type: String?): Int {
        return when (type ?: "") {
            "alarm" -> AudioManager.STREAM_ALARM
            "call" -> AudioManager.STREAM_VOICE_CALL
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "ring" -> AudioManager.STREAM_RING
            "system" -> AudioManager.STREAM_SYSTEM
            "music" -> AudioManager.STREAM_MUSIC
            else -> STREAM_UNKNOWN
        }
    }
}
