package com.highfly.logbook

import android.content.Context
import android.content.SharedPreferences

object Settings {

    const val LANG_DE = "de"

    const val CITY_LANG_NATIVE = "native"
    const val CITY_LANG_EN = "en"
    const val CITY_LANG_DE = "de"

    const val ACCENT_BROWN = "brown"
    const val ACCENT_PURPLE = "purple"
    const val ACCENT_BLUE = "blue"

    val ACCENT_OPTIONS = listOf(ACCENT_BROWN, ACCENT_PURPLE, ACCENT_BLUE)

    private const val LEGACY_ACCENT_LIGHT_BLUE = "light_blue"

    const val ROLE_CREW = "crew"
    const val ROLE_PASSENGER = "passenger"

    const val CLASS_SCHEME_LUFTHANSA = "lufthansa"

    const val CREW_FUNCTION_PURSER_II = "purser_ii"
    const val CREW_FUNCTION_PURSER_I = "purser_i"
    const val CREW_FUNCTION_FLIGHT_ATTENDANT = "flight_attendant"

    const val AVATAR_PERSON = "avatar:person"
    const val AVATAR_FLIGHT = "avatar:flight"
    const val AVATAR_WORLD = "avatar:world"
    const val AVATAR_FILE = "file"

    const val MAP_ORIENTATION_LANDSCAPE = "landscape"
    const val MAP_ORIENTATION_PORTRAIT = "portrait"

    private const val PREF_NAME = "logbook_settings"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_DEFAULT_PERIOD = "default_period"
    private const val KEY_ACCENT = "accent_color"
    private const val KEY_ROLE = "role"
    private const val KEY_CREW_FUNCTION = "crew_function"
    private const val KEY_PREFERRED_CLASS = "preferred_class"
    private const val KEY_CLASS_SCHEME = "class_scheme"
    private const val KEY_AVATAR = "profile_avatar"
    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_AIRLINE = "airline"
    private const val KEY_DEMO_DATA = "demo_data_enabled"
    private const val KEY_CITY_LABEL_LANGUAGE = "city_label_language"
    private const val KEY_MAP_ORIENTATION = "map_orientation"

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
        normalizeAccentColor(prefs(context).getString(KEY_ACCENT, ACCENT_BROWN))

    fun setAccentColor(context: Context, value: String) {
        require(value in ACCENT_OPTIONS)
        prefs(context).edit().putString(KEY_ACCENT, value).apply()
    }

    fun accentThemeResId(context: Context): Int = when (getAccentColor(context)) {
        ACCENT_PURPLE -> R.style.Theme_Logbook_Purple
        ACCENT_BLUE -> R.style.Theme_Logbook_Blue
        else -> R.style.Theme_Logbook_Brown
    }

    private fun normalizeAccentColor(value: String?): String = when (value) {
        ACCENT_PURPLE -> ACCENT_PURPLE
        ACCENT_BLUE, LEGACY_ACCENT_LIGHT_BLUE -> ACCENT_BLUE
        else -> ACCENT_BROWN
    }

    fun getRole(context: Context): String =
        prefs(context).getString(KEY_ROLE, ROLE_CREW) ?: ROLE_CREW

    fun setRole(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ROLE, value).apply()
    }

    fun getCrewFunction(context: Context): String =
        prefs(context).getString(KEY_CREW_FUNCTION, CREW_FUNCTION_PURSER_I)
            ?: CREW_FUNCTION_PURSER_I

    fun setCrewFunction(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CREW_FUNCTION, value).apply()
    }

    fun crewFunctionLabel(context: Context, key: String): String = when (key) {
        CREW_FUNCTION_PURSER_II -> context.getString(R.string.crew_function_purser_ii)
        CREW_FUNCTION_FLIGHT_ATTENDANT -> context.getString(R.string.crew_function_flight_attendant)
        else -> context.getString(R.string.crew_function_purser_i)
    }

    fun selectedCrewFunctionLabel(context: Context): String =
        crewFunctionLabel(context, getCrewFunction(context))

    /**
     * Bevorzugte Arbeitsposition (Reiseklasse) der Crew. Liefert den Index der
     * Reiseklasse (0 Economy, 1 Premium Economy, 2 Business, 3 First) oder -1,
     * wenn keine hinterlegt ist.
     */
    fun getPreferredClassIndex(context: Context): Int =
        prefs(context).getInt(KEY_PREFERRED_CLASS, -1)

    fun setPreferredClassIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_PREFERRED_CLASS, index).apply()
    }

    fun getClassScheme(context: Context): String =
        prefs(context).getString(KEY_CLASS_SCHEME, CLASS_SCHEME_LUFTHANSA) ?: CLASS_SCHEME_LUFTHANSA

    fun setClassScheme(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CLASS_SCHEME, value).apply()
    }

    fun getAvatar(context: Context): String =
        prefs(context).getString(KEY_AVATAR, AVATAR_PERSON) ?: AVATAR_PERSON

    fun setAvatar(context: Context, value: String) {
        prefs(context).edit().putString(KEY_AVATAR, value).apply()
    }

    fun getProfileName(context: Context): String =
        prefs(context).getString(KEY_PROFILE_NAME, "") ?: ""

    fun setProfileName(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PROFILE_NAME, value).apply()
    }

    fun getAirline(context: Context): String =
        prefs(context).getString(KEY_AIRLINE, "") ?: ""

    fun setAirline(context: Context, value: String) {
        prefs(context).edit().putString(KEY_AIRLINE, value).apply()
    }

    fun isDemoDataEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEMO_DATA, false)

    fun setDemoDataEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEMO_DATA, value).apply()
    }

    private const val KEY_DEMO_MIGRATION = "demo_disabled_migration_v1"

    fun isDemoMigrationDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEMO_MIGRATION, false)

    fun setDemoMigrationDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_DEMO_MIGRATION, true).apply()
    }

    fun getCityLabelLanguage(context: Context): String =
        prefs(context).getString(KEY_CITY_LABEL_LANGUAGE, CITY_LANG_NATIVE) ?: CITY_LANG_NATIVE

    fun setCityLabelLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CITY_LABEL_LANGUAGE, value).apply()
    }

    fun getMapOrientation(context: Context): String =
        prefs(context).getString(KEY_MAP_ORIENTATION, MAP_ORIENTATION_LANDSCAPE)
            ?: MAP_ORIENTATION_LANDSCAPE

    fun setMapOrientation(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MAP_ORIENTATION, value).apply()
    }
}