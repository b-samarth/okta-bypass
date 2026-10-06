package dev.local.accessibilitydiagnostics

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var filterInput: EditText
    private lateinit var scrollView: ScrollView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isResumed = false
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshLog()
            if (isResumed) mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        refreshLog()
        mainHandler.removeCallbacks(refreshTask)
        mainHandler.postDelayed(refreshTask, REFRESH_MS)
    }

    override fun onPause() {
        isResumed = false
        mainHandler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 20, 16, 12)
        }
        val title = TextView(this).apply {
            text = "Accessibility Diagnostics"
            textSize = 22f
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "Local, in-memory event and node log. Sensitive UI text may appear here."
            textSize = 13f
            setPadding(0, 6, 0, 10)
        })
        val instructions = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(instructions, matchWrap())

        filterInput = EditText(this).apply {
            hint = "Filter log text (package, class, event… )"
            setSingleLine(true)
            addTextChangedListener(SimpleTextWatcher { refreshLog() })
        }
        root.addView(filterInput, matchWrap())

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener { DiagnosticLog.clear(); refreshLog() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = "Export"
            setOnClickListener { createExport() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)

        scrollView = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(8, 8, 8, 8)
        }
        scrollView.addView(logView)
        root.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.gravity = Gravity.TOP
        setContentView(root)
    }

    private fun refreshLog() {
        if (!::logView.isInitialized) return
        val query = if (::filterInput.isInitialized) filterInput.text.toString().trim() else ""
        val filtered = DiagnosticLog.snapshot().filter {
            query.isEmpty() || it.contains(query, ignoreCase = true)
        }
        logView.text = if (filtered.isEmpty()) "No matching entries yet." else filtered.joinToString("\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun createExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "accessibility-diagnostics.txt")
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    @Deprecated("Use the activity result API in a larger app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(DiagnosticLog.snapshot().joinToString("\n"))
            }
        } catch (e: Exception) {
            DiagnosticLog.add("EXPORT failed: ${e.javaClass.simpleName}: ${e.message}")
            refreshLog()
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val EXPORT_REQUEST = 1001
        private const val REFRESH_MS = 500L
    }
}
