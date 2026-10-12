package com.highfly.logbook

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

object LocaleUtils {

    /** Faktor der Einstellung "Kompakte Schrift". */
    private const val COMPACT_FONT_SCALE = 0.9f
    private const val MIN_FONT_SCALE = 0.85f

    fun applyLocale(context: Context, languageTag: String): Context {
        // Auch die Prozess-Standardsprache folgt der App-Sprache, damit
        // überall dieselben Monatsnamen, Dezimaltrennzeichen und Datumsformate
        // gelten. Das geschieht hier beim Anmelden des Contexts und beim
        // Umschalten in den Einstellungen (siehe SettingsSectionFragment).
        Locale.setDefault(localeFor(languageTag))
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(languageTag))
        return context.createConfigurationContext(config)
    }

    /** Sprache zu einem Tag; unbekannte Tags fallen auf Deutsch zurück. */
    private fun localeFor(languageTag: String): Locale =
        if (languageTag == Settings.LANG_EN) Locale.ENGLISH else Locale.GERMANY

    /**
     * Skaliert die Schrift für die gesamte App. Die Systemschrift bleibt
     * Grundlage, wird bei [compact] aber verkleinert und auf 1.0 begrenzt, damit
     * auch bei grosser Systemschrift nichts überlappt. Ohne die Einstellung
     * bleibt die Systemschrift unverändert.
     */
    fun applyTextScale(context: Context, compact: Boolean): Context {
        val base = context.resources.configuration
        val systemScale = base.fontScale
        if (!compact) {
            return context
        }
        val target = (minOf(systemScale, 1f) * COMPACT_FONT_SCALE).coerceAtLeast(MIN_FONT_SCALE)
        if (target >= systemScale) {
            return context
        }
        val config = Configuration(base)
        config.fontScale = target
        return context.createConfigurationContext(config)
    }
}
