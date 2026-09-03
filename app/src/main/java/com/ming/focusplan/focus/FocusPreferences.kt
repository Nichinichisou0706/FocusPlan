package com.ming.focusplan.focus

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings

object FocusPreferences {
    private const val SETTINGS_PREFS = "focus_settings"
    private const val KEY_STRICT_ENABLED = "strict_enabled"
    private const val KEY_WHITELIST = "whitelist"

    fun isStrictEnabled(context: Context): Boolean = settings(context).getBoolean(KEY_STRICT_ENABLED, false)

    fun setStrictEnabled(context: Context, enabled: Boolean) {
        settings(context).edit().putBoolean(KEY_STRICT_ENABLED, enabled).apply()
    }

    fun whitelist(context: Context): Set<String> = settings(context).getStringSet(KEY_WHITELIST, emptySet()).orEmpty().toSet()

    fun setWhitelisted(context: Context, packageName: String, allowed: Boolean) {
        val updated = whitelist(context).toMutableSet().apply {
            if (allowed) add(packageName) else remove(packageName)
        }
        settings(context).edit().putStringSet(KEY_WHITELIST, updated).apply()
    }

    fun isStrictSessionActive(context: Context): Boolean = timer(context).getBoolean(FocusTimerService.KEY_STRICT_ACTIVE, false)

    fun isAllowed(context: Context, packageName: String): Boolean {
        val defaultInput = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        val homePackage = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0
        )?.activityInfo?.packageName
        return packageName == context.packageName ||
            packageName == defaultInput ||
            packageName == homePackage ||
            packageName in whitelist(context) ||
            packageName in ALWAYS_ALLOWED
    }

    /** Accessibility also reports volume panels, launchers and dialog providers. Only block a real launchable user app. */
    fun isMonitoredApplication(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || isAllowed(context, packageName)) return false
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return false
        if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return false
        return context.packageManager.getLaunchIntentForPackage(packageName) != null
    }

    private fun settings(context: Context) = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private fun timer(context: Context) = context.getSharedPreferences(FocusTimerService.PREFS, Context.MODE_PRIVATE)

    private val ALWAYS_ALLOWED = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.incallui",
        "com.google.android.dialer",
        "com.miui.home",
        "com.miui.securitycenter"
    )
}
