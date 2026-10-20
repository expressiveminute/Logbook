package com.highfly.logbook

import android.content.Context
import android.content.SharedPreferences
import java.lang.ref.WeakReference
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object Settings {

    const val LANG_DE = "de"
    const val LANG_EN = "en"

    val LANG_OPTIONS = listOf(LANG_DE, LANG_EN)

    const val CITY_LANG_NATIVE = "native"
    const val CITY_LANG_EN = "en"
    const val CITY_LANG_DE = "de"

    const val ACCENT_BROWN = "brown"
    const val ACCENT_MAGENTA = "magenta"
    const val ACCENT_TURQUOISE = "turquoise"

    const val THEME_MODE_SYSTEM = "system"
    const val THEME_MODE_LIGHT = "light"
    const val THEME_MODE_DARK = "dark"

    val THEME_MODE_OPTIONS =
        listOf(THEME_MODE_SYSTEM, THEME_MODE_LIGHT, THEME_MODE_DARK)

    val ACCENT_OPTIONS = listOf(ACCENT_BROWN, ACCENT_MAGENTA, ACCENT_TURQUOISE)

    private const val LEGACY_ACCENT_LIGHT_BLUE = "light_blue"
    private const val LEGACY_ACCENT_BLUE = "blue"

    const val CLASS_SCHEME_LUFTHANSA = "lufthansa"

    const val CREW_FUNCTION_PURSER_II = "purser_ii"
    const val CREW_FUNCTION_PURSER_I = "purser_i"
    const val CREW_FUNCTION_FLIGHT_ATTENDANT = "flight_attendant"

    const val AVATAR_DEFAULT = "ic_profile"
    const val AVATAR_FILE = "file"

    /**
     * Ausrichtung der Weltkarte, siehe [getWorldMapOrientation]. Die
     * Rubbelkarte hat hier nichts mehr zu wählen: sie zeigt immer die Kugel.
     */
    const val WORLD_MAP_LANDSCAPE = "landscape"
    const val WORLD_MAP_PORTRAIT = "portrait"

    private const val PREF_NAME = "logbook_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_LEGACY_DARK_MODE = "dark_mode"
    private const val KEY_DEFAULT_PERIOD = "default_period"
    private const val KEY_FLIGHT_TYPE_FILTER = "flight_type_filter"
    private const val KEY_CLASS_TRAVEL_TYPE = "class_travel_type"
    private const val KEY_DEFAULT_TIME_UNIT = "default_time_unit"
    private const val KEY_ACCENT = "accent_color"
    private const val KEY_ROLE = "role"
    private const val KEY_CREW_FUNCTION = "crew_function"
    private const val KEY_PREFERRED_CLASS = "preferred_class"
    private const val KEY_PASSENGER_LOAD_FACTOR = "passenger_load_factor"
    private const val KEY_CLASS_SCHEME = "class_scheme"
    private const val KEY_AVATAR = "profile_avatar"
    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_AIRLINE = "airline"
    private const val KEY_DEMO_DATA = "demo_data_enabled"
    private const val KEY_CITY_LABEL_LANGUAGE = "city_label_language"
    private const val KEY_MAP_ORIENTATION = "map_orientation"
    private const val KEY_DISCOVERY_YEAR = "discovery_year"
    private const val KEY_COMPACT_TEXT = "compact_text"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_MANUAL_COUNTRIES = "manual_countries"
    private const val KEY_HIDDEN_COUNTRIES = "hidden_countries"
    private const val KEY_WELCOME_SEEN = "welcome_seen"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Anhängsel für die nutzerbezogenen Schlüssel (Profil, Rolle,
     * Fluggesellschaft, Arbeitsposition, abgehakte Länder). Nutzer 1 behält die
     * alten Schlüssel ohne Anhang, damit eine bestehende Installation ihr
     * Profil unverändert weiterbenutzt.
     */
    private fun suffixFor(userId: Int): String = if (userId <= 1) "" else "_u$userId"

    private fun userSuffix(context: Context): String = suffixFor(Users.activeId(context))

    /**
     * Erscheinungsbild der App. [THEME_MODE_SYSTEM] übernimmt die Einstellung,
     * die auf dem Handy gespeichert ist.
     *
     * Früher stand hier nur ein Ja/Nein-Wert im Schlüssel "dark_mode". Ist er
     * noch gesetzt, weil die Wahl vor dem Umstauf getroffen wurde, gilt er
     * weiter - so behält eine installierte App ihr Aussehen. Fehlt er, folgt
     * eine frische Installation dem Handy.
     */
    fun getThemeMode(context: Context): String {
        val prefs = prefs(context)
        return when {
            prefs.contains(KEY_THEME_MODE) -> normalizeThemeMode(prefs.getString(KEY_THEME_MODE, null))
            prefs.contains(KEY_LEGACY_DARK_MODE) ->
                if (prefs.getBoolean(KEY_LEGACY_DARK_MODE, true)) THEME_MODE_DARK else THEME_MODE_LIGHT
            else -> THEME_MODE_SYSTEM
        }
    }

    fun setThemeMode(context: Context, value: String) {
        require(value in THEME_MODE_OPTIONS)
        prefs(context).edit().putString(KEY_THEME_MODE, value).apply()
    }

    /**
     * Ob der Nutzer das Erscheinungsbild schon einmal bewußt gewählt hat. Die
     * Willkommens-Tour setzt sonst beim ersten Start [THEME_MODE_DARK] als
     * Vorauswahl; eine getroffene Wahl bleibt unangetastet.
     */
    fun isThemeModeChosen(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.contains(KEY_THEME_MODE) || prefs.contains(KEY_LEGACY_DARK_MODE)
    }

    private fun normalizeThemeMode(value: String?): String =
        THEME_MODE_OPTIONS.firstOrNull { it == value } ?: THEME_MODE_SYSTEM

    fun getDefaultPeriodKey(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_PERIOD, "all") ?: "all"

    fun setDefaultPeriodKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_DEFAULT_PERIOD, key).apply()
    }

    /**
     * Welche Fluege die Kacheln des Dashboards zeigen, siehe
     * [FlightTypeOptions]. Der Wert bleibt erhalten, damit die Kachelzeile nach
     * einem Neustart dasselbe anzeigt wie zuletzt.
     */
    fun getFlightTypeFilterKey(context: Context): String =
        FlightTypeOptions.normalize(
            prefs(context).getString(KEY_FLIGHT_TYPE_FILTER, FlightTypeOptions.KEY_ALL)
        )

    fun setFlightTypeFilterKey(context: Context, key: String) {
        prefs(context).edit()
            .putString(KEY_FLIGHT_TYPE_FILTER, FlightTypeOptions.normalize(key))
            .apply()
    }

    /**
     * Reiseart, nach der die Kachel "Reiseklasse" ihre Klassen aufschluesselt,
     * siehe [ClassTravelType]. Standard ist "Privat". Der Wert bleibt erhalten,
     * weil ihn auch die Vorschau auf dem Dashboard braucht.
     */
    fun getClassTravelType(context: Context): String =
        ClassTravelType.normalize(prefs(context).getString(KEY_CLASS_TRAVEL_TYPE, null))

    fun setClassTravelType(context: Context, value: String) {
        prefs(context).edit()
            .putString(KEY_CLASS_TRAVEL_TYPE, ClassTravelType.normalize(value))
            .apply()
    }

    /**
     * Einheit, in der die Kachel "Zeit" ihre Summe anzeigt. Ein unbekannter
     * Schlüssel wird wie Stunden behandelt, siehe [TimeUnitOptions.unit].
     */
    fun getDefaultTimeUnitKey(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_TIME_UNIT, TimeUnitOptions.KEY_HOURS)
            ?: TimeUnitOptions.KEY_HOURS

    fun setDefaultTimeUnitKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_DEFAULT_TIME_UNIT, key).apply()
    }

    fun getAccentColor(context: Context): String =
        normalizeAccentColor(prefs(context).getString(KEY_ACCENT, ACCENT_BROWN))

    fun setAccentColor(context: Context, value: String) {
        require(value in ACCENT_OPTIONS)
        prefs(context).edit().putString(KEY_ACCENT, value).apply()
    }

    fun accentThemeResId(context: Context): Int = when (getAccentColor(context)) {
        ACCENT_MAGENTA -> R.style.Theme_Logbook_Magenta
        ACCENT_TURQUOISE -> R.style.Theme_Logbook_Turquoise
        else -> R.style.Theme_Logbook_Brown
    }

    private fun normalizeAccentColor(value: String?): String = when (value) {
        ACCENT_MAGENTA -> ACCENT_MAGENTA
        ACCENT_TURQUOISE, LEGACY_ACCENT_BLUE -> ACCENT_TURQUOISE
        LEGACY_ACCENT_LIGHT_BLUE -> ACCENT_TURQUOISE
        else -> ACCENT_BROWN
    }

    /**
     * Globale Rolle des Nutzers - Besatzung oder Fluggast, siehe [Role]. Ohne
     * gespeicherte Wahl gilt [Role.DEFAULT].
     */
    fun getRole(context: Context): Role =
        Role.fromKey(prefs(context).getString(KEY_ROLE + userSuffix(context), null))

    fun setRole(context: Context, role: Role) {
        prefs(context).edit().putString(KEY_ROLE + userSuffix(context), role.key).apply()
    }

    /**
     * Auslastung in Prozent, mit der die Kachel "Passagiere" die geschätzte
     * Passagierzahl berechnet (Sitzplätze × Auslastung). Zwischen 50 und 100
     * in 5er-Schritten, Standard 80 - siehe [PassengersCalculator].
     */
    fun getPassengerLoadFactor(context: Context): Int =
        PassengersCalculator.normalizeLoadFactor(
            prefs(context).getInt(
                KEY_PASSENGER_LOAD_FACTOR + userSuffix(context),
                PassengersCalculator.DEFAULT_LOAD_FACTOR_PERCENT
            )
        )

    fun setPassengerLoadFactor(context: Context, percent: Int) {
        prefs(context).edit()
            .putInt(
                KEY_PASSENGER_LOAD_FACTOR + userSuffix(context),
                PassengersCalculator.normalizeLoadFactor(percent)
            )
            .apply()
    }

    fun getCrewFunction(context: Context): String =
        prefs(context).getString(
            KEY_CREW_FUNCTION + userSuffix(context), CREW_FUNCTION_PURSER_I
        ) ?: CREW_FUNCTION_PURSER_I

    fun setCrewFunction(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CREW_FUNCTION + userSuffix(context), value).apply()
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
        prefs(context).getInt(KEY_PREFERRED_CLASS + userSuffix(context), -1)

    fun setPreferredClassIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_PREFERRED_CLASS + userSuffix(context), index).apply()
    }

    fun getClassScheme(context: Context): String =
        prefs(context).getString(KEY_CLASS_SCHEME, CLASS_SCHEME_LUFTHANSA) ?: CLASS_SCHEME_LUFTHANSA

    fun setClassScheme(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CLASS_SCHEME, value).apply()
    }

    fun getAvatar(context: Context): String =
        getAvatar(context, Users.activeId(context))

    fun getAvatar(context: Context, userId: Int): String =
        prefs(context).getString(KEY_AVATAR + suffixFor(userId), AVATAR_DEFAULT)
            ?: AVATAR_DEFAULT

    fun setAvatar(context: Context, value: String) {
        prefs(context).edit().putString(KEY_AVATAR + userSuffix(context), value).apply()
    }

    fun getProfileName(context: Context): String =
        getProfileName(context, Users.activeId(context))

    fun getProfileName(context: Context, userId: Int): String =
        prefs(context).getString(KEY_PROFILE_NAME + suffixFor(userId), "") ?: ""

    fun setProfileName(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PROFILE_NAME + userSuffix(context), value).apply()
    }

    fun getAirline(context: Context): String =
        prefs(context).getString(KEY_AIRLINE + userSuffix(context), "") ?: ""

    fun setAirline(context: Context, value: String) {
        prefs(context).edit().putString(KEY_AIRLINE + userSuffix(context), value).apply()
    }

    fun isDemoDataEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEMO_DATA, false)

    fun setDemoDataEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEMO_DATA, value).apply()
    }

    /**
     * Kompakte Schrift: verkleinert die Schrift global, damit auf schmalen
     * Displays mehr Text in Kacheln und Listen passt. Wird in
     * [LocaleUtils.applyTextScale] ausgewertet.
     */
    fun isCompactText(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPACT_TEXT, false)

    fun setCompactText(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPACT_TEXT, value).apply()
    }

    /**
     * Sprache der App, "de" oder "en". Standard ist Deutsch, damit eine
     * bestehende Installation ihre bisherige Sprache behält.
     */
    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, LANG_DE)
            ?.takeIf { it in LANG_OPTIONS }
            ?: LANG_DE

    fun setLanguage(context: Context, value: String) {
        require(value in LANG_OPTIONS)
        prefs(context).edit().putString(KEY_LANGUAGE, value).apply()
    }

    /** Sprache der App als [Locale] für Zahlen- und Datumsformate. */
    fun language(context: Context): Locale =
        if (getLanguage(context) == LANG_EN) Locale.ENGLISH else Locale.GERMANY

    /**
     * Ob die Willkommens-Tour schon einmal durchlaufen oder übersprungen wurde.
     * Ist der Schlüssel noch nicht gesetzt, zeigt der Start der App die Tour.
     * Ein Abschluss über die Einstellungen ("Tour starten") läßt den Schlüssel
     * unberührt, damit die Tour dort beliebig oft wiederholt werden kann.
     */
    fun isWelcomeSeen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WELCOME_SEEN, false)

    fun setWelcomeSeen(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WELCOME_SEEN, value).apply()
    }

    private const val KEY_DEMO_MIGRATION = "demo_disabled_migration_v1"

    fun isDemoMigrationDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEMO_MIGRATION, false)

    fun setDemoMigrationDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_DEMO_MIGRATION, true).apply()
    }

    /**
     * Fassung des Demodatensatzes, zuletzt in die Demodatenbank geschrieben.
     * Weicht sie von [DemoData.VERSION] ab, baut [LogbookRepository] die Bank
     * neu auf - sonst bliebe der alte Bestand stehen, weil die Bank nur beim
     * ersten Start gefuellt wird.
     */
    fun getDemoDataVersion(context: Context): Int =
        prefs(context).getInt(KEY_DEMO_DATA_VERSION, 0)

    fun setDemoDataVersion(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_DEMO_DATA_VERSION, value).apply()
    }

    private const val KEY_DEMO_DATA_VERSION = "demo_data_version"

    fun getCityLabelLanguage(context: Context): String =
        prefs(context).getString(KEY_CITY_LABEL_LANGUAGE, CITY_LANG_NATIVE) ?: CITY_LANG_NATIVE

    fun setCityLabelLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CITY_LABEL_LANGUAGE, value).apply()
    }

    /**
     * Ausrichtung, in der die Weltkarte (osmdroid) gezeigt wird. Sie wird auf
     * den Schlüssel "map_orientation" gespeichert, den sie schon immer benutzt
     * hat - deshalb behält eine installierte App ihre Wahl. Der Schlüssel
     * gehörte früher auch der Rubbelkarte, die zwischen flacher Karte und Kugel
     * umschaltete; die Kugel ist jetzt die einzige Darstellung, deshalb steuert
     * der Wert nur noch die Bildschirmausrichtung der Weltkarte.
     */
    fun getWorldMapOrientation(context: Context): String =
        prefs(context).getString(KEY_MAP_ORIENTATION, WORLD_MAP_LANDSCAPE)
            ?: WORLD_MAP_LANDSCAPE

    fun setWorldMapOrientation(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MAP_ORIENTATION, value).apply()
    }

    /**
     * Zuletzt in der Jahresauswahl gewähltes Jahr der Discovery-Seite. Die Seite
     * stellt beim Öffnen auf das laufende Jahr um, gespeichert wird nur die
     * Auswahl des Dialogs.
     */
    fun getDiscoveryYear(context: Context): Int =
        prefs(context).getInt(KEY_DISCOVERY_YEAR, LocalDate.now().year)

    fun setDiscoveryYear(context: Context, year: Int) {
        prefs(context).edit().putInt(KEY_DISCOVERY_YEAR, year).apply()
    }

    /**
     * Länder, die der Nutzer auf der Länderliste der Rubbelkarte selbst
     * abgehakt hat, als ISO-2. Sie kommen zusätzlich zu den Ländern aus den
     * Flugeinträgen auf die Karte.
     *
     * getStringSet liefert die Menge der Einstellungen selbst zurück, deshalb
     * wird sie kopiert: Sonst würde ein Aufrufer, der die Menge ändert, auch die
     * gespeicherten Werte ändern, ohne zu speichern.
     */
    fun getManualCountries(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MANUAL_COUNTRIES + userSuffix(context), null)
            ?.map { it.trim().uppercase() }
            ?.toSet()
            .orEmpty()

    fun setManualCountries(context: Context, value: Set<String>) {
        val codes = value.map { it.trim().uppercase() }
            .filter { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            .toSet()
        prefs(context).edit().putStringSet(KEY_MANUAL_COUNTRIES + userSuffix(context), codes).apply()
        notifyManualCountriesChanged()
    }

    /**
     * Länder, die der Nutzer auf der Länderliste abgewählt hat, als ISO-2.
     * Sie stammen aus Flugeinträgen, erscheinen aber deshalb nicht mehr auf
     * der Rubbelkarte und zählen auch nicht mehr mit - bis der Nutzer sie
     * wieder anhakt oder sie erneut anfliegt. Die Flughafenpunkte und das
     * Flugzeugsymbol auf der Länderliste bleiben davon unberührt.
     */
    fun getHiddenCountries(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN_COUNTRIES + userSuffix(context), null)
            ?.map { it.trim().uppercase() }
            ?.toSet()
            .orEmpty()

    fun setHiddenCountries(context: Context, value: Set<String>) {
        val codes = value.map { it.trim().uppercase() }
            .filter { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            .toSet()
        prefs(context).edit().putStringSet(KEY_HIDDEN_COUNTRIES + userSuffix(context), codes).apply()
        notifyManualCountriesChanged()
    }

    /**
     * Löscht alle Profileinstellungen eines Nutzers (Name, Avatar, Rolle,
     * Fluggesellschaft, Arbeitsposition, abgehakte Länder). Der Platz bleibt
     * bestehen. Siehe [clearActiveUserProfile] für den aktiven Nutzer.
     */
    fun clearProfileForUser(context: Context, userId: Int) {
        val s = suffixFor(userId)
        prefs(context).edit()
            .remove(KEY_PROFILE_NAME + s)
            .remove(KEY_AVATAR + s)
            .remove(KEY_AIRLINE + s)
            .remove(KEY_ROLE + s)
            .remove(KEY_CREW_FUNCTION + s)
            .remove(KEY_PREFERRED_CLASS + s)
            .remove(KEY_PASSENGER_LOAD_FACTOR + s)
            .remove(KEY_MANUAL_COUNTRIES + s)
            .remove(KEY_HIDDEN_COUNTRIES + s)
            .apply()
        notifyManualCountriesChanged()
    }

    /** Löscht die Profileinstellungen des aktiven Nutzers, der Platz bleibt. */
    fun clearActiveUserProfile(context: Context) {
        clearProfileForUser(context, Users.activeId(context))
    }

    /**
     * Meldet, dass sich die selbst abgehakten Länder geändert haben.
     *
     * Die Rubbelkarte und ihre Länderlisten zeigen alle dieselbe Liste an, und
     * sie können gleichzeitig offen sein: die Länderliste eines Kontinents und
     * die mit allen Ländern liegen beide auf dem Stapel. Damit ein Tipp in der
     * einen sofort in der anderen sichtbar wird, hängen sich beide an diese
     * Meldung, statt nur beim Wiedererscheinen neu zu laden.
     *
     * Die Zuhörer stehen in einer [WeakReference]-Liste: Eine Seite, die sich
     * abgemeldet hat, darf nicht am Leben bleiben.
     */
    private val manualCountriesListeners =
        CopyOnWriteArrayList<WeakReference<() -> Unit>>()

    /**
     * Meldet Änderungen der Länderliste an [listener], bis [removeListener]
     * aufgerufen wird. Wird von einer Seite im [androidx.fragment.app.Fragment.onStart]
     * angemeldet und im `onStop` wieder abgemeldet.
     */
    fun addManualCountriesListener(listener: () -> Unit) {
        manualCountriesListeners.removeAll { it.get() == null }
        manualCountriesListeners.add(WeakReference(listener))
    }

    fun removeManualCountriesListener(listener: () -> Unit) {
        manualCountriesListeners.removeAll { it.get() == null || it.get() === listener }
    }

    private fun notifyManualCountriesChanged() {
        for (reference in manualCountriesListeners) {
            reference.get()?.invoke()
        }
    }
}