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
            result = merged(
                result,
                km,
                GeoMath.flightMinutes(km.toDouble()),
                AirportData.country(context, from),
                AirportData.country(context, to)
            )
        }
        return result
    }

    /**
     * Fuegt die errechneten Werte in den Eintrag ein, ohne vorhandene Werte
     * zu ueberschreiben. Ein Wert aus der CSV-Datei (Entfernung, Flugzeit,
     * Laender) hat Vorrang; nur was fehlt, wird ergaenzt.
     *
     * Reine Funktion ohne Android-Zugriff, damit sie sich im Unit-Test
     * pruefen laesst.
     */
    fun merged(
        entry: LogbookEntry,
        distanceKm: Int?,
        flightMinutes: Int?,
        fromCountry: String?,
        toCountry: String?
    ): LogbookEntry = entry.copy(
        distanceKm = entry.distanceKm ?: distanceKm,
        flightMinutes = entry.flightMinutes ?: flightMinutes,
        fromCountry = entry.fromCountry?.takeIf { it.isNotBlank() } ?: fromCountry,
        toCountry = entry.toCountry?.takeIf { it.isNotBlank() } ?: toCountry
    )

    fun hasConflict(context: Context, entry: LogbookEntry): Boolean =
        conflictReasons(context, entry).isNotEmpty()

    /**
     * Liefert einen verständlichen Grund, warum der Eintrag als Konflikt gilt,
     * oder null, wenn es (nach aktueller Prüfung) kein Konflikt ist.
     */
    fun conflictReason(context: Context, entry: LogbookEntry): String? =
        conflictReasons(context, entry).firstOrNull()

    /**
     * Alle Gründe, warum der Eintrag als Konflikt gilt (leer = kein Konflikt).
     * Pro Feld geprüft, damit der Korrekturdialog die betroffenen Werte einzeln
     * markieren kann: Flughafencodes und die Fluggesellschaft.
     */
    fun conflictReasons(context: Context, entry: LogbookEntry): List<String> {
        val from = entry.fromAirport.trim().uppercase()
        val to = entry.toAirport.trim().uppercase()
        val reasons = mutableListOf<String>()
        if (from.isBlank() || to.isBlank()) {
            reasons += context.getString(R.string.import_conflict_missing_route)
        } else {
            if (from == to) {
                reasons += context.getString(R.string.import_conflict_same_airport)
            }
            val invalid = listOfNotNull(
                from.takeIf { !isKnownAirport(context, it) },
                to.takeIf { !isKnownAirport(context, it) }
            )
            if (invalid.isNotEmpty()) {
                reasons += context.getString(
                    R.string.import_conflict_invalid_iata,
                    invalid.joinToString(" / ")
                )
            }
        }
        val airline = entry.airline.orEmpty().trim()
        if (!isKnownAirline(context, airline)) {
            reasons += context.getString(R.string.import_conflict_invalid_airline, airline)
        }
        return reasons
    }

    /** Ist der Code ein bekannter Flughafen (Form ok und in den Assets)? */
    fun isKnownAirport(context: Context, code: String): Boolean {
        val c = code.trim().uppercase()
        return isPlausibleCode(c) && AirportData.location(context, c) != null
    }

    /** Ist die Fluggesellschaft bekannt? Leer ist erlaubt (optionales Feld). */
    fun isKnownAirline(context: Context, code: String): Boolean {
        val c = code.trim()
        if (c.isEmpty()) return true
        return AirlineCatalog.resolve(context, c) != null
    }

    /**
     * Sieht der Code wie ein Flughafencode aus? Drei Zeichen, oder vier, wenn
     * das erste "X" ist ("X" + Dreilettercode, geschlossener Flughafen).
     * Reine Formprüfung; ob es den Flughafen gibt, prüft der Aufrufer.
     */
    private fun isPlausibleCode(code: String): Boolean =
        code.length == 3 || AirportData.isClosedCode(code)
}
