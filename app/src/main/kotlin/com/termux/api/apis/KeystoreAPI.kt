package com.termux.api.apis

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.JsonWriter
import androidx.annotation.RequiresApi
import com.termux.api.TermuxApiReceiver
import com.termux.api.util.ResultReturner
import com.termux.shared.logger.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec

object KeystoreAPI {

    private const val LOG_TAG = "KeystoreAPI"

    // this is the only provider name that is supported by Android
    private const val PROVIDER = "AndroidKeyStore"

    @SuppressLint("NewApi")
    @JvmStatic
    fun onReceive(apiReceiver: TermuxApiReceiver, intent: Intent) {
        Logger.logDebug(LOG_TAG, "onReceive")

        when (intent.getStringExtra("command")) {
            "list" -> listKeys(apiReceiver, intent)
            "generate" -> generateKey(apiReceiver, intent)
            "delete" -> deleteKey(apiReceiver, intent)
            "sign" -> signData(apiReceiver, intent)
            "verify" -> verifyData(apiReceiver, intent)
        }
    }

    /**
     * List the keys inside the keystore.
     * Optional intent extras:
     * - detailed: if set, key parameters (modulus etc.) are included in the response
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun listKeys(apiReceiver: TermuxApiReceiver, intent: Intent) {
        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.ResultJsonWriter() {
            @Throws(GeneralSecurityException::class, IOException::class)
            override fun writeJson(out: JsonWriter) {
                val keyStore = getKeyStore()
                val aliases = keyStore.aliases()
                val detailed = intent.getBooleanExtra("detailed", false)

                out.beginArray()
                while (aliases.hasMoreElements()) {
                    out.beginObject()

                    val alias = aliases.nextElement()
                    out.name("alias").value(alias)

                    val entry = keyStore.getEntry(alias, null)
                    if (entry is KeyStore.PrivateKeyEntry) {
                        printPrivateKey(out, entry, detailed)
                    }

                    out.endObject()
                }
                out.endArray()
            }
        })
    }

    /**
     * Helper function for printing the parameters of a given key.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @Throws(GeneralSecurityException::class, IOException::class)
    private fun printPrivateKey(out: JsonWriter, entry: KeyStore.PrivateKeyEntry, detailed: Boolean) {
        val privateKey = entry.privateKey
        val algorithm = privateKey.algorithm
        val keyInfo = KeyFactory.getInstance(algorithm).getKeySpec(privateKey, KeyInfo::class.java)

        val publicKey = entry.certificate.publicKey

        out.name("algorithm").value(algorithm)
        out.name("size").value(keyInfo.keySize.toLong())

        if (detailed && publicKey is RSAPublicKey) {
            // convert to hex
            out.name("modulus").value(publicKey.modulus.toString(16))
            out.name("exponent").value(publicKey.publicExponent.toString(16))
        }
        if (detailed && publicKey is ECPublicKey) {
            // convert to hex
            out.name("x").value(publicKey.w.affineX.toString(16))
            out.name("y").value(publicKey.w.affineY.toString(16))
        }

        out.name("inside_secure_hardware").value(keyInfo.isInsideSecureHardware)

        out.name("user_authentication")

        out.beginObject()
        out.name("required").value(keyInfo.isUserAuthenticationRequired)

        out.name("enforced_by_secure_hardware")
        out.value(keyInfo.isUserAuthenticationRequirementEnforcedBySecureHardware)

        val validityDuration = keyInfo.userAuthenticationValidityDurationSeconds
        if (validityDuration >= 0) {
            out.name("validity_duration_seconds").value(validityDuration.toLong())
        }
        out.endObject()
    }

    /**
     * Permanently delete a key from the keystore.
     * Required intent extras:
     * - alias: key alias
     */
    private fun deleteKey(apiReceiver: TermuxApiReceiver, intent: Intent) {
        ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultWriter { _ ->
            val alias = intent.getStringExtra("alias")
            // unfortunately this statement does not return anything
            // nor does it throw an exception if the alias does not exist
            getKeyStore().deleteEntry(alias)
        })
    }

    /**
     * Create a new key inside the keystore.
     * Required intent extras:
     * - alias: key alias
     * - algorithm: key algorithm, should be one of the KeyProperties.KEY_ALGORITHM_*
     *   values, for example [KeyProperties.KEY_ALGORITHM_RSA] or [KeyProperties.KEY_ALGORITHM_EC].
     * - purposes: purposes of this key, should be a combination of KeyProperties.PURPOSE_*,
     *   for example 12 for [KeyProperties.PURPOSE_SIGN]+[KeyProperties.PURPOSE_VERIFY]
     * - digests: set of hashes this key can be used with, should be an array of
     *   KeyProperties.DIGEST_* values, for example [KeyProperties.DIGEST_SHA256] and
     *   [KeyProperties.DIGEST_SHA512]
     * - size: key size, only used for RSA keys
     * - curve: elliptic curve name, only used for EC keys
     * - userValidity: number of seconds where it is allowed to use this key for signing
     *   after unlocking the device (re-locking and unlocking restarts the timer), if set to 0
     *   this feature is disabled (i.e. the key can be used anytime)
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @SuppressLint("WrongConstant")
    private fun generateKey(apiReceiver: TermuxApiReceiver, intent: Intent) {
        ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultWriter { _ ->
            val alias = intent.getStringExtra("alias")!!
            val algorithm = intent.getStringExtra("algorithm")!!
            val purposes = intent.getIntExtra("purposes", 0)
            val digests = intent.getStringArrayExtra("digests")!!
            val size = intent.getIntExtra("size", 2048)
            val curve = intent.getStringExtra("curve")
            val userValidity = intent.getIntExtra("validity", 0)

            val builder = KeyGenParameterSpec.Builder(alias, purposes).apply {
                setDigests(*digests)
                
                if (algorithm == KeyProperties.KEY_ALGORITHM_RSA) {
                    // only the exponent 65537 is supported for now
                    setAlgorithmParameterSpec(RSAKeyGenParameterSpec(size, RSAKeyGenParameterSpec.F4))
                    setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                }

                if (algorithm == KeyProperties.KEY_ALGORITHM_EC) {
                    setAlgorithmParameterSpec(ECGenParameterSpec(curve))
                }

                if (userValidity > 0) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationValidityDurationSeconds(userValidity)
                }
            }

            val generator = KeyPairGenerator.getInstance(algorithm, PROVIDER)
            generator.initialize(builder.build())
            generator.generateKeyPair()
        })
    }

    /**
     * Sign a given byte stream. The file is read from stdin and the signature is output to stdout.
     * The output is encoded using base64.
     * Required intent extras:
     * - alias: key alias
     * - algorithm: key algorithm and hash combination to use, e.g. SHA512withRSA
     *   (the full list can be found at
     *   [the Android documentation](https://developer.android.com/training/articles/keystore#SupportedSignatures))
     */
    private fun signData(apiReceiver: TermuxApiReceiver, intent: Intent) {
        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.WithInput() {
            @Throws(Exception::class)
            override fun writeResult(out: java.io.PrintWriter) {
                val alias = intent.getStringExtra("alias")
                val algorithm = intent.getStringExtra("algorithm")
                val input = readStream(`in`!!)

                val key = getKeyStore().getEntry(alias, null) as KeyStore.PrivateKeyEntry
                val signature = java.security.Signature.getInstance(algorithm)
                signature.initSign(key.privateKey)
                signature.update(input)
                val outputData = signature.sign()

                // we are not allowed to output bytes in this function
                // one option is to encode using base64 which is a plain string
                out.write(Base64.encodeToString(outputData, Base64.NO_WRAP))
            }
        })
    }

    /**
     * Verify a given byte stream along with a signature file.
     * The file is read from stdin, and a "true" or "false" message is printed to the stdout.
     * Required intent extras:
     * - alias: key alias
     * - algorithm: key algorithm and hash combination that was used to create this signature,
     *   e.g. SHA512withRSA (the full list can be found at
     *   [the Android documentation](https://developer.android.com/training/articles/keystore#SupportedSignatures))
     * - signature: path of the signature file
     */
    private fun verifyData(apiReceiver: TermuxApiReceiver, intent: Intent) {
        ResultReturner.returnData(apiReceiver, intent, object : ResultReturner.WithInput() {
            @Throws(GeneralSecurityException::class, IOException::class)
            override fun writeResult(out: java.io.PrintWriter) {
                val alias = intent.getStringExtra("alias")
                val algorithm = intent.getStringExtra("algorithm")
                val input = readStream(`in`!!)
                val signatureFile = File(intent.getStringExtra("signature")!!)

                val signatureData = ByteArray(signatureFile.length().toInt())
                val read = FileInputStream(signatureFile).read(signatureData)
                if (signatureFile.length().toInt() != read) {
                    out.println(false)
                    return
                }

                val signature = java.security.Signature.getInstance(algorithm)
                signature.initVerify(getKeyStore().getCertificate(alias).publicKey)
                signature.update(input)
                val verified = signature.verify(signatureData)

                out.println(verified)
            }
        })
    }

    /**
     * Set up and return the keystore.
     */
    @Throws(GeneralSecurityException::class, IOException::class)
    private fun getKeyStore(): KeyStore {
        return KeyStore.getInstance(PROVIDER).apply {
            load(null)
        }
    }

    /**
     * Read a given stream to a byte array. Should not be used with large streams.
     */
    @Throws(IOException::class)
    private fun readStream(stream: InputStream): ByteArray {
        val byteStream = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var read: Int
        while (stream.read(buffer).also { read = it } > 0) {
            byteStream.write(buffer, 0, read)
        }
        return byteStream.toByteArray()
    }

    @Suppress("unused")
    private fun printErrorMessage(apiReceiver: TermuxApiReceiver, intent: Intent) {
        ResultReturner.returnData(apiReceiver, intent, ResultReturner.ResultWriter { out ->
            out.println("termux-keystore requires at least Android 6.0 (Marshmallow).")
        })
    }
}
