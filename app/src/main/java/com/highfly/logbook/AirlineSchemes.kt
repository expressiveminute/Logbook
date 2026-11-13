package com.highfly.logbook

import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat

/**
 * Airline-Farbschemas, zusätzlich zu den drei klassischen Akzentfarben
 * ([Settings.ACCENT_OPTIONS]). Jede Airline bringt drei Markenfarben mit:
 * Primär (Kachelhintergrund auf dem Dashboard), Sekundär (Kachelschrift)
 * und Akzent (Akzentverhebungen, also die colorPrimary-Familie im Theme).
 *
 * Die Farben und Theme-Overlays liegen generiert in
 * `res/values/airline_schemes.xml` bzw. `res/values-night/airline_schemes.xml`
 * (erzeugt von `tools/build_airline_schemes.py` - nicht von Hand ändern).
 * Der Schlüssel landet wie die klassischen Akzenten im Einstellungs-Schlüssel
 * `accent_color`, damit alle bestehenden Stellen (Theme-Wechsel, Dashboard,
 * Export/Import) unverändert weiterlaufen.
 */
object AirlineSchemes {

    data class Scheme(
        /** Schlüssel in den Einstellungen, z. B. "airline_lh". */
        val key: String,
        /** IATA-Code der Airline, als Beschriftung der Zelle im Regler. */
        val code: String,
        /** Anzeigename der Airline (contentDescription für TalkBack). */
        val nameRes: Int,
        /** Kachelhintergrund auf dem Dashboard (Primärfarbe). */
        val tileBackgroundRes: Int,
        /** Kachelschrift/-icon auf dem Dashboard (Sekundärfarbe). */
        val tileContentRes: Int,
        /** Marken-Akzentfarbe (unverändert, Tag und Nacht gleich). */
        val accentRes: Int,
        /** Theme-Overlay der App für dieses Schema. */
        val themeRes: Int
    )

    val ALL = listOf(
        Scheme("airline_lh", "LH", R.string.scheme_lh,
            R.color.airline_lh_tile_bg, R.color.airline_lh_tile_text, R.color.airline_lh_brand_accent, R.style.Theme_Logbook_AirlineLH),
        Scheme("airline_lx", "LX", R.string.scheme_lx,
            R.color.airline_lx_tile_bg, R.color.airline_lx_tile_text, R.color.airline_lx_brand_accent, R.style.Theme_Logbook_AirlineLX),
        Scheme("airline_wk", "WK", R.string.scheme_wk,
            R.color.airline_wk_tile_bg, R.color.airline_wk_tile_text, R.color.airline_wk_brand_accent, R.style.Theme_Logbook_AirlineWK),
        Scheme("airline_os", "OS", R.string.scheme_os,
            R.color.airline_os_tile_bg, R.color.airline_os_tile_text, R.color.airline_os_brand_accent, R.style.Theme_Logbook_AirlineOS),
        Scheme("airline_ew", "EW", R.string.scheme_ew,
            R.color.airline_ew_tile_bg, R.color.airline_ew_tile_text, R.color.airline_ew_brand_accent, R.style.Theme_Logbook_AirlineEW),
        Scheme("airline_vl", "VL", R.string.scheme_vl,
            R.color.airline_vl_tile_bg, R.color.airline_vl_tile_text, R.color.airline_vl_brand_accent, R.style.Theme_Logbook_AirlineVL),
        Scheme("airline_4y", "4Y", R.string.scheme_4y,
            R.color.airline_4y_tile_bg, R.color.airline_4y_tile_text, R.color.airline_4y_brand_accent, R.style.Theme_Logbook_Airline4Y),
        Scheme("airline_en", "EN", R.string.scheme_en,
            R.color.airline_en_tile_bg, R.color.airline_en_tile_text, R.color.airline_en_brand_accent, R.style.Theme_Logbook_AirlineEN),
        Scheme("airline_az", "AZ", R.string.scheme_az,
            R.color.airline_az_tile_bg, R.color.airline_az_tile_text, R.color.airline_az_brand_accent, R.style.Theme_Logbook_AirlineAZ),
        Scheme("airline_sn", "SN", R.string.scheme_sn,
            R.color.airline_sn_tile_bg, R.color.airline_sn_tile_text, R.color.airline_sn_brand_accent, R.style.Theme_Logbook_AirlineSN),
        Scheme("airline_tp", "TP", R.string.scheme_tp,
            R.color.airline_tp_tile_bg, R.color.airline_tp_tile_text, R.color.airline_tp_brand_accent, R.style.Theme_Logbook_AirlineTP)
    )

    val KEYS: List<String> = ALL.map { it.key }

    fun byKey(key: String): Scheme? = ALL.firstOrNull { it.key == key }

    /**
     * Aktive Primärfarbe des Schemas: bei Airline-Schemas die Marken-Primärfarbe
     * ([Scheme.tileBackgroundRes], z. B. das LH-Blau - so wie die Dashboard-
     * Kacheln), bei den klassischen Akzentfarben das Theme-Primär (dort ist die
     * Akzentfarbe selbst die Primärfarbe).
     */
    fun primaryColor(context: Context): Int {
        val scheme = byKey(Settings.getAccentColor(context))
        if (scheme != null) {
            return ContextCompat.getColor(context, scheme.tileBackgroundRes)
        }
        return themePrimary(context)
    }

    /**
     * Aktive Akzentfarbe des Schemas: bei Airline-Schemas die Marken-Akzentfarbe
     * ([Scheme.accentRes], z. B. das LH-Gold), bei den klassischen Akzentfarben
     * ebenfalls das Theme-Primär.
     */
    fun accentColor(context: Context): Int {
        val scheme = byKey(Settings.getAccentColor(context))
        if (scheme != null) {
            return ContextCompat.getColor(context, scheme.accentRes)
        }
        return themePrimary(context)
    }

    /** Theme-Primärfarbe (bei klassischen Akzentfarben die Akzentfarbe selbst). */
    private fun themePrimary(context: Context): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorPrimary, tv, true
        )
        return if (tv.resourceId != 0) {
            ContextCompat.getColor(context, tv.resourceId)
        } else {
            tv.data
        }
    }
}
