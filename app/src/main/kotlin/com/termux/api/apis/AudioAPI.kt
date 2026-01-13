package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger

object AudioAPI {

    private const val LOG_TAG = "AudioAPI"

    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val framesPerBuffer = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        val bluetoothA2dp = am.isBluetoothA2dpOn
        val wiredHs = am.isWiredHeadsetOn

        var at = AudioTrack.Builder()
            .setBufferSizeInBytes(4) // one 16bit 2ch frame
            .build()
        val sr = at.sampleRate
        val bs = at.bufferSizeInFrames
        at.release()

        at = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder()
                .setBufferSizeInBytes(4) // one 16bit 2ch frame
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } else {
            val aa = AudioAttributes.Builder()
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build()
            AudioTrack.Builder()
                .setAudioAttributes(aa)
                .setBufferSizeInBytes(4) // one 16bit 2ch frame
                .build()
        }
        val srLl = at.sampleRate
        val bsLl = at.bufferSizeInFrames
        at.release()

        val srPs: Int
        val bsPs: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            at = AudioTrack.Builder()
                .setBufferSizeInBytes(4) // one 16bit 2ch frame
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                .build()
            srPs = at.sampleRate
            bsPs = at.bufferSizeInFrames
            at.release()
        } else {
            srPs = sr
            bsPs = bs
        }

        ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultJsonWriter { out ->
            out.beginObject()
            out.name("PROPERTY_OUTPUT_SAMPLE_RATE").value(sampleRate)
            out.name("PROPERTY_OUTPUT_FRAMES_PER_BUFFER").value(framesPerBuffer)
            out.name("AUDIOTRACK_SAMPLE_RATE").value(sr)
            out.name("AUDIOTRACK_BUFFER_SIZE_IN_FRAMES").value(bs)
            if (srLl != sr || bsLl != bs) {
                out.name("AUDIOTRACK_SAMPLE_RATE_LOW_LATENCY").value(srLl)
                out.name("AUDIOTRACK_BUFFER_SIZE_IN_FRAMES_LOW_LATENCY").value(bsLl)
            }
            if (srPs != sr || bsPs != bs) {
                out.name("AUDIOTRACK_SAMPLE_RATE_POWER_SAVING").value(srPs)
                out.name("AUDIOTRACK_BUFFER_SIZE_IN_FRAMES_POWER_SAVING").value(bsPs)
            }
            out.name("BLUETOOTH_A2DP_IS_ON").value(bluetoothA2dp)
            out.name("WIREDHEADSET_IS_CONNECTED").value(wiredHs)
            out.endObject()
        })
    }
}
