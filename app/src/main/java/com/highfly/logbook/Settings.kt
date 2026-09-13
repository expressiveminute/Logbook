package com.highfly.logbook

import android.content.Context
import android.content.SharedPreferences

object Settings {

    const val LANG_DE = "de"

    const val ACCENT_PURPLE = "purple"
    const val ACCENT_LIGHT_BLUE = "light_blue"
    const val ACCENT_LIGHT_GREEN = "light_green"
    const val ACCENT_BROWN = "brown"

    const val ROLE_CREW = "crew"
    const val ROLE_PASSENGER = "passenger"

    const val CLASS_SCHEME_LUFTHANSA = "lufthansa"

    private const val PREF_NAME = "logbook_settings"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_DEFAULT_PERIOD = "default_period"
    private const val KEY_ACCENT = "accent_color"
    private const val KEY_ROLE = "role"
    private const val KEY_CLASS_SCHEME = "class_scheme"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isDarkMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DARK_MODE, true)

    fun setDarkMode(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK_MODE, value).apply()
    }

    fun getDefaultPeriodKey(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_PERIOD, "all") ?: "all"

    fun setDefaultPeriodKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_DEFAULT_PERIOD, key).apply()
    }

    fun getAccentColor(context: Context): String =
        prefs(context).getString(KEY_ACCENT, ACCENT_PURPLE) ?: ACCENT_PURPLE

    fun setAccentColor(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ACCENT, value).apply()
    }

    fun accentThemeResId(context: Context): Int = when (getAccentColor(context)) {
        ACCENT_LIGHT_BLUE -> R.style.Theme_Logbook_LightBlue
        ACCENT_LIGHT_GREEN -> R.style.Theme_Logbook_LightGreen
        ACCENT_BROWN -> R.style.Theme_Logbook_Brown
        else -> R.style.Theme_Logbook
    }

    fun getRole(context: Context): String =
        prefs(context).getString(KEY_ROLE, ROLE_CREW) ?: ROLE_CREW

    fun setRole(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ROLE, value).apply()
    }

    fun getClassScheme(context: Context): String =
        prefs(context).getString(KEY_CLASS_SCHEME, CLASS_SCHEME_LUFTHANSA) ?: CLASS_SCHEME_LUFTHANSA

    fun setClassScheme(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CLASS_SCHEME, value).apply()
    }
}