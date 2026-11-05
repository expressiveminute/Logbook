package com.highfly.logbook

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Ein Grenzübertritt eines Landes, manuell erfasst - fuer Landweg-Reisen
 * (Auto, Bus, Faehre), die nicht als Flug im Logbuch stehen. [enter] ist true,
 * wenn das Land betreten wurde, false beim Verlassen. Als Datum genuegt der
 * Tag, weil auch die Flugeintraege nur ein Datum haben (keine Uhrzeit).
 *
 * Serialisiert als "ISO2|yyyy-MM-dd|enter|exit" - eine Zeile pro Uebertritt,
 * siehe [Settings.getManualBorderCrossings]. Ohne Android-Abhaengigkeiten,
 * damit [CountryTimeTest] die Logik pruefen kann.
 */
data class BorderCrossing(
    val iso2: String,
    val date: LocalDate,
    val enter: Boolean,
    /** Transportmittel bei Landweg: "car"/"train"/"bus"/"ferry"/"bike"/"foot", sonst null. */
    val transport: String? = null
) {
    fun toLine(): String {
        val base = "${iso2.trim().uppercase()}|${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}|${if (enter) "enter" else "exit"}"
        return if (transport != null) "$base|$transport" else base
    }

    companion object {
        /** Alle bekannten Transportmittel-Kürzel. */
        val TRANSPORTS = setOf("car", "train", "bus", "ferry", "bike", "foot")

        fun parse(line: String): BorderCrossing? {
            val parts = line.split('|')
            if (parts.size < 3 || parts.size > 4) return null
            val iso2 = parts[0].trim().uppercase()
            if (iso2.length != 2 || iso2.any { it !in 'A'..'Z' }) return null
            val date = try {
                LocalDate.parse(parts[1].trim(), DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: Exception) {
                return null
            }
            val enter = when (parts[2].trim()) {
                "enter" -> true
                "exit" -> false
                else -> return null
            }
            val transport = parts.getOrNull(3)?.trim()?.lowercase()?.takeIf { it in TRANSPORTS }
            return BorderCrossing(iso2, date, enter, transport)
        }
    }
}

/**
 * Ein Aufenthalt in einem Land: vom Betreten ([start]) bis zum Verlassen
 * ([end], null = noch offen). [days] ist die Dauer in Tagen (bei offen bis
 * heute gerechnet). [closedByFlight] ist false, wenn das Verlassen nicht per
 * Flug, sondern manuell (Landweg) erfasst wurde - oder der Aufenthalt noch
 * offen ist; dann kann der Nutzer das Verlassen händisch nachtragen.
 */
data class StayPeriod(
    val start: LocalDate,
    val end: LocalDate?,
    val days: Long,
    val closedByFlight: Boolean,
    /** Flug, mit dem das Land betreten wurde; null bei manuellem Landweg. */
    val enterEntry: LogbookEntry? = null,
    /** Flug, mit dem das Land verlassen wurde; null bei manuell/offen. */
    val exitEntry: LogbookEntry? = null
) {
    val open: Boolean get() = end == null
}

/** Aufenthaltszeit in einem Land: Gesamttage, Aufenthalte, noch offen. */
data class CountryTime(
    val iso2: String,
    val totalDays: Long,
    val visits: Int,
    /** Alle Aufenthalte, chronologisch. */
    val periods: List<StayPeriod> = emptyList(),
    /** Datum des letzten Betretens, wenn der Aufenthalt noch laeuft (bis heute gerechnet). */
    val openSince: LocalDate? = null
) {
    /** True, wenn der letzte Aufenthalt noch laeuft. */
    val open: Boolean get() = openSince != null
}

/**
 * Auswertung der Aufenthaltsdauer in Laendern aus dem Logbuch: Ein Flug von
 * Land A nach Land B (A != B) ist ein Grenzübertritt - Verlassen von A und
 * Betreten von B am Flugdatum. Die Zeit in einem Land laeuft vom Betreten bis
 * zum naechsten Verlassen; ein Aufenthalt, der noch nicht verlassen wurde,
 * zaehlt bis [today]. Das Land der Homebase wird nicht gezaehlt.
 *
 * Landweg-Uebertritte ([manual]) werden als zusaetzliche Ereignisse
 * einsortiert. Ohne Android-Abhaengigkeiten: Das Land eines Flughafens kommt
 * als Funktion [countryOf] herein (in der App [AirportData.country]), damit
 * [CountryTimeTest] die Rechnung pruefen kann.
 */
object CountryTimeStats {

    /** Ein Grenzübertritt als Ereignis: Land, Datum, Richtung, Sortierindex, manuell erfasst, Quelleintrag. */
    private data class Event(
        val iso2: String,
        val date: LocalDate,
        val enter: Boolean,
        val seq: Long,
        val manual: Boolean,
        val entry: LogbookEntry?
    )

    /**
     * Aufenthaltszeiten pro Land, absteigend nach Gesamttagen. [entries] in
     * beliebiger Reihenfolge (wird hier chronologisch sortiert), [countryOf]
     * loest einen IATA-Code in ISO-2 auf, [homeIso2] ist das Land der
     * Homebase (wird ausgeschlossen, null = keins), [manual] sind manuell
     * erfasste Landweg-Uebertritte, [today] der Referenztag fuer offene
     * Aufenthalte.
     */
    fun evaluate(
        entries: List<LogbookEntry>,
        countryOf: (String) -> String?,
        homeIso2: String?,
        manual: List<BorderCrossing>,
        today: LocalDate
    ): List<CountryTime> {
        val events = ArrayList<Event>()
        entries.forEachIndexed { idx, e ->
            val fromC = countryOf(e.fromAirport)?.trim()?.uppercase()
            val toC = countryOf(e.toAirport)?.trim()?.uppercase()
            if (fromC.isNullOrEmpty() || toC.isNullOrEmpty() || fromC == toC) return@forEachIndexed
            // seq: Datenbank-id (Erfassungsreihenfolge innerhalb eines Tages),
            // ersatzweise der Listenindex.
            val seq = e.id ?: (idx + 1).toLong()
            events += Event(fromC, e.date, enter = false, seq, manual = false, entry = e)
            events += Event(toC, e.date, enter = true, seq, manual = false, entry = e)
        }
        manual.forEachIndexed { i, c ->
            // Manuelle Uebertritte sortieren sich am selben Tag hinter die
            // Fluege (seq nahe Long.MAX_VALUE).
            events += Event(
                c.iso2.trim().uppercase(), c.date, c.enter,
                Long.MAX_VALUE - i.toLong(), manual = true, entry = null
            )
        }
        val home = homeIso2?.trim()?.uppercase()
        val sorted = events
            .filter { it.iso2 != home }
            // Chronologisch; bei gleichem Datum und gleicher seq das Betreten
            // vor dem Verlassen (Tagesausflug zaehlt 0 Tage, bleibt nicht offen).
            .sortedWith(compareBy({ it.date }, { it.seq }, { if (it.enter) 0 else 1 }))

        val result = ArrayList<CountryTime>()
        for ((iso, evs) in sorted.groupBy { it.iso2 }) {
            val periods = ArrayList<StayPeriod>()
            var openSince: LocalDate? = null
            var openEntry: LogbookEntry? = null
            for (ev in evs) {
                if (ev.enter) {
                    if (openSince == null) {
                        openSince = ev.date
                        openEntry = ev.entry
                    }
                } else {
                    val since = openSince
                    if (since != null) {
                        periods += StayPeriod(
                            start = since,
                            end = ev.date,
                            days = ChronoUnit.DAYS.between(since, ev.date).coerceAtLeast(0),
                            closedByFlight = !ev.manual,
                            enterEntry = openEntry,
                            exitEntry = ev.entry
                        )
                        openSince = null
                        openEntry = null
                    }
                }
            }
            val since = openSince
            if (since != null) {
                periods += StayPeriod(
                    start = since,
                    end = null,
                    days = ChronoUnit.DAYS.between(since, today).coerceAtLeast(0),
                    closedByFlight = false,
                    enterEntry = openEntry,
                    exitEntry = null
                )
            }
            val total = periods.sumOf { it.days }
            result += CountryTime(
                iso2 = iso,
                totalDays = total,
                visits = periods.size,
                periods = periods,
                openSince = since
            )
        }
        return result.sortedWith(
            compareByDescending<CountryTime> { it.totalDays }.thenBy { it.iso2 }
        )
    }
}
