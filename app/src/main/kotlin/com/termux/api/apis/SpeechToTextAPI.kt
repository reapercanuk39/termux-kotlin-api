package com.termux.api.apis

import android.app.Activity
import android.app.AlertDialog
import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.termux.api.util.ResultReturner
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import java.io.PrintWriter
import java.util.concurrent.LinkedBlockingQueue

object SpeechToTextAPI {

    private const val LOG_TAG = "SpeechToTextAPI"

    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")
        context.startService(Intent(context, SpeechToTextService::class.java).putExtras(intent.extras!!))
    }

    @JvmStatic
    fun runFromActivity(context: Activity) {
        val pm = context.packageManager
        val installedList = pm.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
        val speechRecognitionInstalled = installedList.isNotEmpty()

        if (speechRecognitionInstalled) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Select an application")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
        } else {
            AlertDialog.Builder(context)
                .setMessage("For recognition it's necessary to install \"Google Voice Search\"")
                .setTitle("Install Voice Search from Google Play?")
                .setPositiveButton("Install") { _, _ ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.voicesearch")).apply {
                        flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    }
                    context.startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .create()
                .show()
        }
    }

    class SpeechToTextService @JvmOverloads constructor(
        name: String = SpeechToTextService::class.java.simpleName
    ) : IntentService(name) {

        private var mSpeechRecognizer: SpeechRecognizer? = null
        private val queue = LinkedBlockingQueue<String>()

        companion object {
            private const val STOP_ELEMENT = ""
            private const val LOG_TAG = "SpeechToTextService"
        }

        override fun onCreate() {
            Logger.logDebug(LOG_TAG, "onCreate")
            super.onCreate()

            mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            mSpeechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onRmsChanged(rmsdB: Float) {}

                override fun onResults(results: Bundle) {
                    val recognitions = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Logger.logError(LOG_TAG, "RecognitionListener#onResults($recognitions)")
                    recognitions?.let { queue.addAll(it) }
                }

                override fun onReadyForSpeech(params: Bundle) {}

                override fun onPartialResults(partialResults: Bundle) {
                    val strings = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Logger.logError(LOG_TAG, "RecognitionListener#onPartialResults($strings)")
                    strings?.let { queue.addAll(it) }
                }

                override fun onEvent(eventType: Int, params: Bundle) {}

                override fun onError(error: Int) {
                    val description = when (error) {
                        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                        else -> error.toString()
                    }
                    Logger.logError(LOG_TAG, "RecognitionListener#onError($description)")
                    queue.add(STOP_ELEMENT)
                }

                override fun onEndOfSpeech() {
                    Logger.logError(LOG_TAG, "RecognitionListener#onEndOfSpeech()")
                    queue.add(STOP_ELEMENT)
                }

                override fun onBufferReceived(buffer: ByteArray) {}

                override fun onBeginningOfSpeech() {}
            })

            val pm = packageManager
            val installedList = pm.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
            val speechRecognitionInstalled = installedList.isNotEmpty()

            if (!speechRecognitionInstalled) {
                AlertDialog.Builder(this)
                    .setMessage("For recognition it's necessary to install \"Google Voice Search\"")
                    .setTitle("Install Voice Search from Google Play?")
                    .setPositiveButton("Install") { _, _ ->
                        val installIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.voicesearch")).apply {
                            flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        }
                        startActivity(installIntent)
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                    .show()
            }

            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Enter shell command")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            mSpeechRecognizer?.startListening(recognizerIntent)
        }

        override fun onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy")
            super.onDestroy()
            mSpeechRecognizer?.destroy()
        }

        override fun onHandleIntent(intent: Intent?) {
            Logger.logDebug(LOG_TAG, "onHandleIntent:\n${IntentUtils.getIntentString(intent)}")

            ResultReturner.returnData(this, intent!!, object : ResultReturner.WithInput() {
                @Throws(Exception::class)
                override fun writeResult(out: PrintWriter) {
                    while (true) {
                        val s = queue.take()
                        if (s === STOP_ELEMENT) {
                            return
                        } else {
                            out.println(s)
                        }
                    }
                }
            })
        }
    }
}
