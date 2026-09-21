package com.highfly.logbook

import java.util.Locale
import kotlin.math.ceil

/**
 * Schätzt die CO₂-Emissionen der erfassten Flüge.
 *
 * Grundlage sind die durchschnittlichen Emissionsfaktoren pro Passagier-
 * kilometer des ifeu-Instituts (2026) gegliedert nach Distanzklassen. Diese
 * Werte enthalten neben CO₂ auch die Klimawirkung der Nicht-CO₂-Emissionen
 * (ca. Faktor 2–3 gegenüber reinem CO₂), wie es bei Verbraucherrechnern üblich
 * ist. Der Flugzeugtyp wird über einen Effizienz-Multiplikator berücksichtigt:
 * moderne Muster (neo/MAX/787/A350/A220) verbrauchen spürbar weniger Treibstoff
 * als die vorherige Generation.
 */
object Co2Calculator {

    /** Ein ausgewachsener Baum bindet grob 10 kg CO₂ pro Jahr (One Tree Planted). */
    const val KG_CO2_PER_TREE_YEAR = 10.0

    /** Fallback-Reisegeschwindigkeit, falls nur die Flugzeit erfasst wurde. */
    private const val FALLBACK_SPEED_KMH = 800.0

    data class Details(
        val totalKg: Double,
        val tonnes: Double,
        val treesPerYear: Int
    )

    private val MODERN_TYPES = listOf(
        "NEO", "MAX", "A19N", "A20N", "A21N",
        "A220", "A221", "A223", "BCS1", "BCS3",
        "A350", "A359", "A35K", "A338", "A339",
        "B787", "B788", "B789", "B78X", "B38M", "B39M", "B3XM",
        "E290", "E295", "E2", "C919"
    )

    private val REGIONAL_TYPES = listOf(
        "CRJ", "E170", "E175", "E190", "E195", "ERJ",
        "F70", "F100", "FOKKER", "BAE", "AVRO", "RJ85", "RJ100", "RJ1H",
        "ATR", "DASH", "DH8", "Q400", "SF34", "SAAB"
    )

    fun details(entries: List<LogbookEntry>): Details {
        val totalKg = entries.sumOf { emissionsKg(it) }
        return Details(
            totalKg = totalKg,
            tonnes = totalKg / 1000.0,
            treesPerYear = ceil(totalKg / KG_CO2_PER_TREE_YEAR).toInt()
        )
    }

    fun emissionsKg(entry: LogbookEntry): Double {
        if (ChartData.normalizeFlightType(entry.flightType) == "Ground Transfer") return 0.0
        val distance = distanceKm(entry) ?: return 0.0
        if (distance <= 0.0) return 0.0
        return distance * factorPerKm(distance) * aircraftMultiplier(entry.aircraftType)
    }

    fun tonnesText(tonnes: Double): String = when {
        tonnes < 10 -> String.format(Locale.GERMANY, "%,.2f", tonnes)
        tonnes < 1000 -> String.format(Locale.GERMANY, "%,.1f", tonnes)
        else -> String.format(Locale.GERMANY, "%,.0f", tonnes)
    }

    private fun distanceKm(entry: LogbookEntry): Double? {
        entry.distanceKm?.let { if (it > 0) return it.toDouble() }
        entry.flightMinutes?.let {
            if (it > 0) return it / 60.0 * FALLBACK_SPEED_KMH
        }
        return null
    }

    private fun factorPerKm(km: Double): Double = when {
        km < 500 -> 0.284
        km < 1500 -> 0.195
        km < 4000 -> 0.156
        else -> 0.207
    }

    private fun aircraftMultiplier(type: String?): Double {
        val t = type?.uppercase()?.trim().orEmpty()
        if (t.isEmpty()) return 1.0
        if (MODERN_TYPES.any { t.contains(it) }) return 0.80
        if (REGIONAL_TYPES.any { t.contains(it) }) return 1.15
        return 1.0
    }
}
