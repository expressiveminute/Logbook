package com.highfly.logbook

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList

object LocaleUtils {

    /** Faktor der Einstellung "Kompakte Schrift". */
    private const val COMPACT_FONT_SCALE = 0.9f
    private const val MIN_FONT_SCALE = 0.85f

    fun applyLocale(context: Context, languageTag: String): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(languageTag))
        return context.createConfigurationContext(config)
    }

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
