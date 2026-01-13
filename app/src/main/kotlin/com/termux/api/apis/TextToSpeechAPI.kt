package com.termux.api.apis

import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.Engine
import android.speech.tts.UtteranceProgressListener
import android.util.JsonWriter
import com.termux.api.util.ResultReturner
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object TextToSpeechAPI {

    private const val LOG_TAG = "TextToSpeechAPI"

    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")
        context.startService(Intent(context, TextToSpeechService::class.java).putExtras(intent.extras!!))
    }

    private fun getLocale(language: String, region: String?, variant: String?): Locale {
        return when {
            region != null && variant != null -> Locale(language, region, variant)
            region != null -> Locale(language, region)
            else -> Locale(language)
        }
    }

    class TextToSpeechService : IntentService(TextToSpeechService::class.java.name) {

        private var mTts: TextToSpeech? = null
        private val mTtsLatch = CountDownLatch(1)

        companion object {
            private const val LOG_TAG = "TextToSpeechService"
        }

        override fun onCreate() {
            Logger.logDebug(LOG_TAG, "onCreate")
            super.onCreate()
        }

        override fun onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy")
            mTts?.shutdown()
            super.onDestroy()
        }

        override fun onHandleIntent(intent: Intent?) {
            Logger.logDebug(LOG_TAG, "onHandleIntent:\n${IntentUtils.getIntentString(intent)}")

            val speechLanguage = intent?.getStringExtra("language")
            val speechRegion = intent?.getStringExtra("region")
            val speechVariant = intent?.getStringExtra("variant")
            val speechEngine = intent?.getStringExtra("engine")
            val speechPitch = intent?.getFloatExtra("pitch", 1.0f) ?: 1.0f

            val streamToUse = when (intent?.getStringExtra("stream")) {
                "NOTIFICATION" -> AudioManager.STREAM_NOTIFICATION
                "ALARM" -> AudioManager.STREAM_ALARM
                "MUSIC" -> AudioManager.STREAM_MUSIC
                "RING" -> AudioManager.STREAM_RING
                "SYSTEM" -> AudioManager.STREAM_SYSTEM
                "VOICE_CALL" -> AudioManager.STREAM_VOICE_CALL
                else -> AudioManager.STREAM_MUSIC
            }

            mTts = TextToSpeech(this, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    mTtsLatch.countDown()
                } else {
                    Logger.logError(LOG_TAG, "Failed tts initialization: status=$status")
                    stopSelf()
                }
            }, speechEngine)

            ResultReturner.returnData(this, intent, object : ResultReturner.WithInput() {
                override fun writeResult(out: PrintWriter) {
                    try {
                        if (!mTtsLatch.await(10, TimeUnit.SECONDS)) {
                            Logger.logError(LOG_TAG, "Timeout waiting for TTS initialization")
                            return
                        }
                    } catch (e: InterruptedException) {
                        Logger.logError(LOG_TAG, "Interrupted awaiting TTS initialization")
                        return
                    }

                    if (speechEngine == "LIST_AVAILABLE") {
                        JsonWriter(out).use { writer ->
                            writer.setIndent("  ")
                            val defaultEngineName = mTts?.defaultEngine
                            writer.beginArray()
                            mTts?.engines?.forEach { info ->
                                writer.beginObject()
                                writer.name("name").value(info.name)
                                writer.name("label").value(info.label)
                                writer.name("default").value(defaultEngineName == info.name)
                                writer.endObject()
                            }
                            writer.endArray()
                        }
                        out.println()
                        return
                    }

                    val ttsDoneUtterancesCount = AtomicInteger()

                    mTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) {}

                        override fun onError(utteranceId: String) {
                            Logger.logError(LOG_TAG, "UtteranceProgressListener.onError() called")
                            synchronized(ttsDoneUtterancesCount) {
                                ttsDoneUtterancesCount.incrementAndGet()
                                (ttsDoneUtterancesCount as Object).notify()
                            }
                        }

                        override fun onDone(utteranceId: String) {
                            synchronized(ttsDoneUtterancesCount) {
                                ttsDoneUtterancesCount.incrementAndGet()
                                (ttsDoneUtterancesCount as Object).notify()
                            }
                        }
                    })

                    if (speechLanguage != null) {
                        val setLanguageResult = mTts?.setLanguage(getLocale(speechLanguage, speechRegion, speechVariant))
                        if (setLanguageResult != TextToSpeech.LANG_AVAILABLE) {
                            Logger.logError(LOG_TAG, "tts.setLanguage('$speechLanguage') returned $setLanguageResult")
                        }
                    }

                    mTts?.setPitch(speechPitch)
                    mTts?.setSpeechRate(intent?.getFloatExtra("rate", 1.0f) ?: 1.0f)

                    val utteranceId = "utterance_id"
                    val params = Bundle().apply {
                        putInt(Engine.KEY_PARAM_STREAM, streamToUse)
                        putString(Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    }

                    var submittedUtterances = 0

                    try {
                        BufferedReader(InputStreamReader(`in`)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (line?.isNotEmpty() == true) {
                                    submittedUtterances++
                                    mTts?.speak(line, TextToSpeech.QUEUE_ADD, params, utteranceId)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "TTS error reading input", e)
                    }

                    synchronized(ttsDoneUtterancesCount) {
                        while (ttsDoneUtterancesCount.get() != submittedUtterances) {
                            (ttsDoneUtterancesCount as Object).wait()
                        }
                    }
                }
            })
        }
    }
}
