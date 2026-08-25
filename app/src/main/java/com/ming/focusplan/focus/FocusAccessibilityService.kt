package com.ming.focusplan.focus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ming.focusplan.MainActivity

class FocusAccessibilityService : AccessibilityService() {
    private var lastBlockedPackage: String? = null
    private var lastBlockedAt = 0L
    private var warningOverlay: View? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!FocusPreferences.isStrictSessionActive(this)) return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank() || FocusPreferences.isAllowed(this, packageName)) return

        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockedAt < 1_200L) return
        lastBlockedPackage = packageName
        lastBlockedAt = now
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        startService(Intent(this, FocusTimerService::class.java).setAction(FocusTimerService.ACTION_PAUSE))
        showWarningOverlay(appName)
    }

    private fun showWarningOverlay(appName: String) {
        removeWarningOverlay()
        val windowManager = getSystemService(WindowManager::class.java)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(250, 250, 252))
                cornerRadius = dp(8).toFloat()
            }
        }
        panel.addView(TextView(this).apply {
            text = "严格专注进行中"
            textSize = 22f
            setTextColor(Color.rgb(32, 34, 40))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(TextView(this).apply {
            text = "$appName 不在白名单中\n计时器已暂停"
            textSize = 15f
            setTextColor(Color.rgb(85, 88, 96))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(Button(this).apply {
            text = "返回专注"
            isAllCaps = false
            setOnClickListener {
                removeWarningOverlay()
                startService(Intent(this@FocusAccessibilityService, FocusTimerService::class.java).setAction(FocusTimerService.ACTION_RESUME))
                startActivity(Intent(this@FocusAccessibilityService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        panel.addView(Button(this).apply {
            text = "忽略一次"
            isAllCaps = false
            setOnClickListener { removeWarningOverlay() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
        panel.addView(TextView(this).apply {
            text = "忽略后保持暂停，可稍后从专注页继续"
            textSize = 12f
            setTextColor(Color.rgb(105, 108, 116))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(panel, LinearLayout.LayoutParams(dp(340), LinearLayout.LayoutParams.WRAP_CONTENT))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        runCatching {
            windowManager.addView(root, params)
            warningOverlay = root
        }.onFailure {
            startActivity(
                Intent(this, FocusGuardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(FocusGuardActivity.EXTRA_BLOCKED_APP, appName)
            )
        }
    }

    private fun removeWarningOverlay() {
        warningOverlay?.let { view -> runCatching { getSystemService(WindowManager::class.java).removeView(view) } }
        warningOverlay = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onInterrupt() = removeWarningOverlay()
    override fun onDestroy() {
        removeWarningOverlay()
        super.onDestroy()
    }
}
