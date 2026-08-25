package com.ming.focusplan.focus

import android.content.Context
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
        return packageName == context.packageName ||
            packageName == defaultInput ||
            packageName in whitelist(context) ||
            packageName in ALWAYS_ALLOWED
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
