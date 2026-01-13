package com.termux.api.apis

import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.JsonWriter
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.core.hardware.fingerprint.FingerprintManagerCompat
import androidx.fragment.app.FragmentActivity
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import java.util.concurrent.Executors

/**
 * This API allows users to use device fingerprint sensor as an authentication mechanism
 */
object FingerprintAPI {

    private const val LOG_TAG = "FingerprintAPI"
    
    @Suppress("unused")
    private const val KEY_NAME = "TermuxFingerprintAPIKey"
    @Suppress("unused")
    private const val KEYSTORE_NAME = "AndroidKeyStore"

    // milliseconds to wait before canceling
    private const val SENSOR_TIMEOUT = 10000L

    // maximum authentication attempts before locked out
    private const val MAX_ATTEMPTS = 5

    // error constants
    private const val ERROR_NO_HARDWARE = "ERROR_NO_HARDWARE"
    private const val ERROR_NO_ENROLLED_FINGERPRINTS = "ERROR_NO_ENROLLED_FINGERPRINTS"
    private const val ERROR_TIMEOUT = "ERROR_TIMEOUT"
    private const val ERROR_TOO_MANY_FAILED_ATTEMPTS = "ERROR_TOO_MANY_FAILED_ATTEMPTS"
    private const val ERROR_LOCKOUT = "ERROR_LOCKOUT"

    // fingerprint authentication result constants
    private const val AUTH_RESULT_SUCCESS = "AUTH_RESULT_SUCCESS"
    private const val AUTH_RESULT_FAILURE = "AUTH_RESULT_FAILURE"
    private const val AUTH_RESULT_UNKNOWN = "AUTH_RESULT_UNKNOWN"

    // store result of fingerprint initialization / authentication
    private var fingerprintResult = FingerprintResult()

    // have we posted our result back?
    private var postedResult = false

    /**
     * Handles setup of fingerprint sensor and writes Fingerprint result to console
     */
    @JvmStatic
    fun onReceive(context: Context, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        resetFingerprintResult()

        val fingerprintManagerCompat = FingerprintManagerCompat.from(context)
        // make sure we have a valid fingerprint sensor before attempting to launch Fingerprint activity
        if (validateFingerprintSensor(context, fingerprintManagerCompat)) {
            val fingerprintIntent = Intent(context, FingerprintActivity::class.java).apply {
                putExtras(intent.extras!!)
                flags = FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fingerprintIntent)
        } else {
            postFingerprintResult(context, intent, fingerprintResult)
        }
    }

    /**
     * Writes the result of our fingerprint result to the console
     */
    private fun postFingerprintResult(context: Context, intent: Intent, result: FingerprintResult) {
        ResultReturner.returnData(context, intent, object : ResultReturner.ResultJsonWriter() {
            override fun writeJson(out: JsonWriter) {
                out.beginObject()

                out.name("errors")
                out.beginArray()
                for (error in result.errors) {
                    out.value(error)
                }
                out.endArray()

                out.name("failed_attempts").value(result.failedAttempts.toLong())
                out.name("auth_result").value(result.authResult)
                out.endObject()

                out.flush()
                out.close()
                postedResult = true
            }
        })
    }

    /**
     * Ensure that we have a fingerprint sensor and that the user has already enrolled fingerprints
     */
    private fun validateFingerprintSensor(context: Context, fingerprintManagerCompat: FingerprintManagerCompat): Boolean {
        var result = true

        if (!fingerprintManagerCompat.isHardwareDetected) {
            Toast.makeText(context, "No fingerprint scanner found!", Toast.LENGTH_SHORT).show()
            appendFingerprintError(ERROR_NO_HARDWARE)
            result = false
        }

        if (!fingerprintManagerCompat.hasEnrolledFingerprints()) {
            Toast.makeText(context, "No fingerprints enrolled", Toast.LENGTH_SHORT).show()
            appendFingerprintError(ERROR_NO_ENROLLED_FINGERPRINTS)
            result = false
        }
        return result
    }

    /**
     * Clear out previous fingerprint result
     */
    private fun resetFingerprintResult() {
        fingerprintResult = FingerprintResult()
        postedResult = false
    }

    /**
     * Increment failed authentication attempts
     */
    private fun addFailedAttempt() {
        fingerprintResult.failedAttempts++
    }

    /**
     * Add an error to our fingerprint result
     */
    private fun appendFingerprintError(error: String) {
        fingerprintResult.errors.add(error)
    }

    /**
     * Set the final result of our authentication
     */
    private fun setAuthResult(authResult: String) {
        fingerprintResult.authResult = authResult
    }

    /**
     * Activity that is necessary for authenticating w/ fingerprint sensor
     */
    class FingerprintActivity : FragmentActivity() {

        override fun onCreate(savedInstanceState: Bundle?) {
            Logger.logDebug(ACTIVITY_LOG_TAG, "onCreate")
            super.onCreate(savedInstanceState)
            handleFingerprint()
        }

        /**
         * Handle setup and listening of fingerprint sensor
         */
        private fun handleFingerprint() {
            val executor = Executors.newSingleThreadExecutor()
            authenticateWithFingerprint(this, intent, executor)
        }

        companion object {
            private const val ACTIVITY_LOG_TAG = "FingerprintActivity"

            /**
             * Handles authentication callback from our fingerprint sensor
             */
            private fun authenticateWithFingerprint(
                context: FragmentActivity,
                intent: Intent,
                executor: java.util.concurrent.Executor
            ) {
                val biometricPrompt = BiometricPrompt(context, executor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode == BiometricPrompt.ERROR_LOCKOUT) {
                            appendFingerprintError(ERROR_LOCKOUT)

                            // first time locked out, subsequent auth attempts will fail immediately for a bit
                            if (fingerprintResult.failedAttempts >= MAX_ATTEMPTS) {
                                appendFingerprintError(ERROR_TOO_MANY_FAILED_ATTEMPTS)
                            }
                        }
                        setAuthResult(AUTH_RESULT_FAILURE)
                        postFingerprintResult(context, intent, fingerprintResult)
                        Logger.logError(ACTIVITY_LOG_TAG, errString.toString())
                    }

                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        setAuthResult(AUTH_RESULT_SUCCESS)
                        postFingerprintResult(context, intent, fingerprintResult)
                    }

                    override fun onAuthenticationFailed() {
                        addFailedAttempt()
                    }
                })

                val builder = BiometricPrompt.PromptInfo.Builder().apply {
                    setTitle(if (intent.hasExtra("title")) intent.getStringExtra("title")!! else "Authenticate")
                    setNegativeButtonText(if (intent.hasExtra("cancel")) intent.getStringExtra("cancel")!! else "Cancel")
                    if (intent.hasExtra("description")) {
                        setDescription(intent.getStringExtra("description"))
                    }
                    if (intent.hasExtra("subtitle")) {
                        setSubtitle(intent.getStringExtra("subtitle"))
                    }
                }

                // listen to fingerprint sensor
                biometricPrompt.authenticate(builder.build())

                addSensorTimeout(context, intent, biometricPrompt)
            }

            /**
             * Adds a timeout for our fingerprint sensor which will force a result return if we
             * haven't already received one
             */
            private fun addSensorTimeout(context: Context, intent: Intent, biometricPrompt: BiometricPrompt) {
                val timeoutHandler = Handler(Looper.getMainLooper())
                timeoutHandler.postDelayed({
                    if (!postedResult) {
                        appendFingerprintError(ERROR_TIMEOUT)
                        biometricPrompt.cancelAuthentication()
                        postFingerprintResult(context, intent, fingerprintResult)
                    }
                }, SENSOR_TIMEOUT)
            }
        }
    }

    /**
     * Simple class to encapsulate information about result of a fingerprint authentication attempt
     */
    internal class FingerprintResult {
        var authResult: String = AUTH_RESULT_UNKNOWN
        var failedAttempts: Int = 0
        var errors: MutableList<String> = mutableListOf()
    }
}
