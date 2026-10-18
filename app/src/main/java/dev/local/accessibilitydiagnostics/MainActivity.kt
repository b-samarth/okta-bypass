package dev.local.accessibilitydiagnostics

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var cycleEnabled = false

    private val bg = Color.rgb(15, 23, 42)
    private val surface = Color.rgb(30, 41, 59)
    private val accent = Color.rgb(56, 189, 248)
    private val green = Color.rgb(74, 222, 128)
    private val red = Color.rgb(248, 113, 113)
    private val textPrimary = Color.rgb(241, 245, 249)
    private val textSecondary = Color.rgb(148, 163, 184)
    private val yellow = Color.rgb(250, 204, 21)

    private lateinit var cycleButton: Button
    private lateinit var cycleSwitch: Switch
    private lateinit var permissionStatus: TextView
    private lateinit var permissionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        buildUi()
        updateCycleUi()
        updatePermissionUi()
        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            // Kill the process immediately without dumping a crash report
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::permissionStatus.isInitialized) {
            updatePermissionUi()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(bg)
        }

        // Header
        root.addView(label("ACCESSIBILITY", 11f, accent, true))
        root.addView(space(5))
        root.addView(label("Diagnostics", 29f, textPrimary, true))
        root.addView(space(5))
        root.addView(
            label(
                "Control your accessibility service",
                14f,
                textSecondary
            )
        )

        root.addView(space(28))

        // Cycle control card
        val cycleCard = card()

        cycleCard.addView(label("AUTOMATION", 11f, accent, true))
        cycleCard.addView(space(8))
        cycleCard.addView(label("Okta cycle", 22f, textPrimary, true))
        cycleCard.addView(space(6))
        cycleCard.addView(
            label(
                "Enable or disable the configured cycle from here.",
                14f,
                textSecondary
            )
        )
        cycleCard.addView(space(18))

        cycleSwitch = Switch(this).apply {
            text = "Enable Okta cycle"
            textSize = 15f
            setTextColor(textPrimary)

            setOnCheckedChangeListener { _, checked ->
                setCycleEnabled(checked)
            }
        }

        cycleCard.addView(cycleSwitch)
        cycleCard.addView(space(12))

        cycleButton = Button(this).apply {
            isAllCaps = false
            textSize = 15f
            setTextColor(bg)
            setPadding(dp(12), dp(8), dp(12), dp(8))

            setOnClickListener {
                setCycleEnabled(!cycleEnabled)
            }
        }

        cycleCard.addView(cycleButton, matchWrap())
        root.addView(cycleCard)

        root.addView(space(16))

        // Usage Access permission card
        val permissionCard = card()

        permissionCard.addView(
            label("PERMISSIONS", 11f, yellow, true)
        )
        permissionCard.addView(space(8))

        permissionCard.addView(
            label("Usage Access", 20f, textPrimary, true)
        )
        permissionCard.addView(space(6))

        permissionStatus = label("", 14f, textSecondary)
        permissionCard.addView(permissionStatus)

        permissionCard.addView(space(12))

        permissionButton = Button(this).apply {
            text = "Open Usage Access Settings"
            isAllCaps = false
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                )
            }
        }

        permissionCard.addView(permissionButton, matchWrap())
        root.addView(permissionCard)

        root.addView(space(20))

        // Accessibility settings
        val accessibilityButton = Button(this).apply {
            text = "Open Accessibility Settings"
            isAllCaps = false

            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        root.addView(accessibilityButton, matchWrap())

        setContentView(root)
    }

    private fun setCycleEnabled(enabled: Boolean) {
        if (cycleEnabled == enabled) return

        cycleEnabled = enabled

        val intent = Intent(
            this,
            DiagnosticAccessibilityService::class.java
        ).apply {
            action = DiagnosticAccessibilityService.ACTION_TOGGLE_CYCLE
            putExtra(
                DiagnosticAccessibilityService.EXTRA_CYCLE_ENABLED,
                enabled
            )
        }

        try {
            startService(intent)
            updateCycleUi()
        } catch (e: Exception) {
            cycleEnabled = false

            cycleSwitch.setOnCheckedChangeListener(null)
            cycleSwitch.isChecked = false
            cycleSwitch.setOnCheckedChangeListener { _, checked ->
                setCycleEnabled(checked)
            }

            updateCycleUi()

            Toast.makeText(
                this,
                "Unable to control the cycle: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateCycleUi() {
        if (!::cycleButton.isInitialized) return

        cycleButton.text = if (cycleEnabled) {
            "■  Stop cycle"
        } else {
            "▶  Start cycle"
        }

        cycleButton.background = rounded(
            if (cycleEnabled) red else accent,
            12
        )

        if (::cycleSwitch.isInitialized &&
            cycleSwitch.isChecked != cycleEnabled
        ) {
            cycleSwitch.setOnCheckedChangeListener(null)
            cycleSwitch.isChecked = cycleEnabled
            cycleSwitch.setOnCheckedChangeListener { _, checked ->
                setCycleEnabled(checked)
            }
        }
    }

    private fun updatePermissionUi() {
        val granted = hasUsageStatsPermission()

        permissionStatus.text = if (granted) {
            "● Usage Access is enabled"
        } else {
            "● Usage Access permission is required"
        }

        permissionStatus.setTextColor(
            if (granted) green else yellow
        )

        permissionButton.visibility =
            if (granted) android.view.View.GONE
            else android.view.View.VISIBLE
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(
            Context.APP_OPS_SERVICE
        ) as AppOpsManager

        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }

        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(18))
        background = rounded(surface, 18)
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)

        if (bold) {
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun rounded(
        color: Int,
        radius: Int
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun space(height: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            1,
            dp(height)
        )
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
