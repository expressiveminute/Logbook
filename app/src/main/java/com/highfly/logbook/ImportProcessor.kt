package com.highfly.logbook

import android.content.Context
import kotlin.math.roundToInt

object ImportProcessor {

    data class ProcessResult(
        val entries: List<LogbookEntry>,
        val conflictIndexes: List<Int>
    )

    fun process(context: Context, entries: List<LogbookEntry>): ProcessResult {
        val processed = entries.map { enrich(context, it) }
        val conflicts = processed.mapIndexedNotNull { index, entry ->
            if (hasConflict(context, entry)) index else null
        }
        return ProcessResult(processed, conflicts)
    }

    fun enrich(context: Context, entry: LogbookEntry): LogbookEntry {
        val from = entry.fromAirport.trim().uppercase()
        val to = entry.toAirport.trim().uppercase()
        var result = entry.copy(fromAirport = from, toAirport = to)
        val a = AirportData.location(context, from)
        val b = AirportData.location(context, to)
        if (a != null && b != null && from != to) {
            val km = GeoMath.distanceKm(a, b).roundToInt()
            result = result.copy(
                distanceKm = km,
                flightMinutes = GeoMath.flightMinutes(km.toDouble()),
                fromCountry = result.fromCountry?.takeIf { it.isNotBlank() }
                    ?: AirportData.country(context, from),
                toCountry = result.toCountry?.takeIf { it.isNotBlank() }
                    ?: AirportData.country(context, to)
            )
        }
        return result
    }

    fun hasConflict(context: Context, entry: LogbookEntry): Boolean {
        val from = entry.fromAirport.trim().uppercase()
        val to = entry.toAirport.trim().uppercase()
        if (from.isBlank() || to.isBlank() || from == to) return true
        if (!isPlausibleCode(from) || !isPlausibleCode(to)) return true
        return AirportData.location(context, from) == null || AirportData.location(context, to) == null
    }

    /**
     * Liefert einen verständlichen Grund, warum der Eintrag als Konflikt gilt,
     * oder null, wenn es (nach aktueller Prüfung) kein Konflikt ist.
     */
    fun conflictReason(context: Context, entry: LogbookEntry): String? {
        val from = entry.fromAirport.trim().uppercase()
        val to = entry.toAirport.trim().uppercase()
        if (from.isBlank() || to.isBlank()) {
            return context.getString(R.string.import_conflict_missing_route)
        }
        if (from == to) {
            return context.getString(R.string.import_conflict_same_airport)
        }
        val invalid = listOfNotNull(
            from.takeIf { !isPlausibleCode(it) || AirportData.location(context, it) == null },
            to.takeIf { !isPlausibleCode(it) || AirportData.location(context, it) == null }
        )
        if (invalid.isNotEmpty()) {
            return context.getString(
                R.string.import_conflict_invalid_iata,
                invalid.joinToString(" / ")
            )
        }
        return null
    }

    /**
     * Sieht der Code wie ein Flughafencode aus? Drei Zeichen, oder vier, wenn
     * das erste "X" ist ("X" + Dreilettercode, geschlossener Flughafen).
     * Reine Formprüfung; ob es den Flughafen gibt, prüft der Aufrufer.
     */
    private fun isPlausibleCode(code: String): Boolean =
        code.length == 3 || AirportData.isClosedCode(code)
}
