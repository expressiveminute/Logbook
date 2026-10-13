package com.highfly.logbook

import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Maßband der Karten: wählt zu einer verfügbaren Breite eine runde Entfernung
 * (1, 2 oder 5 mal 10^n Meter) und beschriftet sie je nach Größe in Metern oder
 * Kilometern.
 */
object ScaleBar {

    /** Länge in Metern, die höchstens in die vorgegebene Breite passt. */
    fun niceDistance(maxMeters: Double): Double {
        if (!maxMeters.isFinite() || maxMeters <= 0.0) return 0.0
        val base = 10.0.pow(floor(log10(maxMeters)))
        for (factor in intArrayOf(5, 2, 1)) {
            val candidate = factor * base
            if (candidate <= maxMeters) return candidate
        }
        return base
    }

    /** Beschriftung: unter 1 km in Metern, darüber in Kilometern. */
    fun distanceText(meters: Double, locale: Locale): String = if (meters >= 1000.0) {
        String.format(locale, "%,d km", (meters / 1000.0).roundToInt())
    } else {
        String.format(locale, "%,d m", meters.roundToInt())
    }
}
