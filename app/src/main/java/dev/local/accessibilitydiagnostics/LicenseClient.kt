
package dev.local.accessibilitydiagnostics

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.TimeUnit

class LicenseApiException(
    val statusCode: Int,
    val errorCode: String
) : Exception(errorCode)

object LicenseClient {

    private val SUPABASE_URL = BuildConfig.SUPABASE_LICNENSE_SERVER_URL

    private  val KEY_ALIAS = BuildConfig.LICENSE_KEY

    private val jsonMediaType =
        "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

    private fun loadOrCreateKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val existingPrivateKey = keyStore.getKey(KEY_ALIAS, null)
        val existingPublicKey =
            keyStore.getCertificate(KEY_ALIAS)?.publicKey

        if (existingPrivateKey != null && existingPublicKey != null) {
            return KeyPair(
                existingPublicKey,
                existingPrivateKey as java.security.PrivateKey
            )
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore"
        )

        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(
                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1
                )
                .build()
        )

        return generator.generateKeyPair()
    }

    private fun post(
        endpoint: String,
        payload: JSONObject
    ): JSONObject {
        val request = Request.Builder()
            .url("$SUPABASE_URL/functions/v1/$endpoint")
            .post(
                payload.toString().toRequestBody(jsonMediaType)
            )
            .build()

        http.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorCode = try {
                    JSONObject(responseText).optString(
                        "error",
                        "HTTP_${response.code}"
                    )
                } catch (_: Exception) {
                    "HTTP_${response.code}"
                }

                throw LicenseApiException(
                    response.code,
                    errorCode
                )
            }

            return JSONObject(responseText)
        }
    }

    private fun publicKeyBase64(keyPair: KeyPair): String =
        Base64.encodeToString(
            keyPair.public.encoded,
            Base64.NO_WRAP
        )

    private fun fingerprint(keyPair: KeyPair): String =
        sha256Hex(hex(keyPair.public.encoded))

    private fun sign(
        keyPair: KeyPair,
        message: String
    ): String {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(message.toByteArray(Charsets.UTF_8))

        return Base64.encodeToString(
            signer.sign(),
            Base64.NO_WRAP
        )
    }

    private fun getChallenge(
        keyPair: KeyPair,
        purpose: String
    ): JSONObject {
        return post(
            "challenge",
            JSONObject()
                .put("public_key", publicKeyBase64(keyPair))
                .put("purpose", purpose)
        )
    }

    suspend fun activate(licenseCode: String): JSONObject =
        withContext(Dispatchers.IO) {
            require(
                Regex("^LIC-[0-9a-f]{64}$").matches(licenseCode)
            ) {
                "Invalid distribution license configuration"
            }

            val keyPair = loadOrCreateKeyPair()
            val publicKey = publicKeyBase64(keyPair)
            val keyFingerprint = fingerprint(keyPair)

            val challengeResponse = getChallenge(
                keyPair,
                "ACTIVATE"
            )

            val challengeId =
                challengeResponse.getString("challenge_id")
            val challenge =
                challengeResponse.getString("challenge")

            check(
                challengeResponse.getString(
                    "public_key_fingerprint"
                ) == keyFingerprint
            ) {
                "Device key fingerprint mismatch"
            }

            val message = listOf(
                "ACTIVATE",
                challengeId,
                challenge,
                keyFingerprint
            ).joinToString("\n")

            val signature = sign(keyPair, message)

            post(
                "activate",
                JSONObject()
                    .put("license_code", licenseCode)
                    .put("public_key", publicKey)
                    .put("challenge_id", challengeId)
                    .put("challenge", challenge)
                    .put("signature", signature)
            )
        }

    suspend fun authorize(licenseCode: String): JSONObject =
        withContext(Dispatchers.IO) {
            require(
                Regex("^LIC-[0-9a-f]{64}$").matches(licenseCode)
            ) {
                "Invalid distribution license configuration"
            }

            val keyPair = loadOrCreateKeyPair()
            val publicKey = publicKeyBase64(keyPair)
            val keyFingerprint = fingerprint(keyPair)

            val challengeResponse = getChallenge(
                keyPair,
                "AUTHORIZE"
            )

            val challengeId =
                challengeResponse.getString("challenge_id")
            val challenge =
                challengeResponse.getString("challenge")

            check(
                challengeResponse.getString(
                    "public_key_fingerprint"
                ) == keyFingerprint
            ) {
                "Device key fingerprint mismatch"
            }

            val message = listOf(
                "AUTHORIZE",
                challengeId,
                challenge,
                keyFingerprint
            ).joinToString("\n")

            val signature = sign(keyPair, message)

            post(
                "authorize",
                JSONObject()
                    .put("license_code", licenseCode)
                    .put("public_key", publicKey)
                    .put("challenge_id", challengeId)
                    .put("challenge", challenge)
                    .put("signature", signature)
            )
        }

    /**
     * Authorize first. If the server says this device is not registered,
     * activate it and retry authorization once.
     */
    suspend fun ensureAuthorized(
        licenseCode: String
    ): JSONObject {
        return try {
            authorize(licenseCode)
        } catch (e: LicenseApiException) {
            if (e.errorCode != "DEVICE_NOT_AUTHORIZED") {
                throw e
            }

            activate(licenseCode)
            authorize(licenseCode)
        }
    }
}
