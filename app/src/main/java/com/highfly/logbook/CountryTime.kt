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
 *
 * [start] ist null, wenn nur das Verlassen in den Daten ist (z. B. ein Flug
 * ALA-FRA, die Einreise per Bus aber nicht im Logbuch): Der Aufenthalt taucht
 * dann mit unbekannter Einreise auf, [days] ist 0, bis die Einreise händisch
 * nachtragen wird.
 */
data class StayPeriod(
    val start: LocalDate?,
    val end: LocalDate?,
    val days: Long,
    /** Verbrachte Zeit in Stunden: bei Flug-Aufenthalten Tage*24, bei einem
     *  Layover-Dienstflug die Layover-Stunden ([LogbookEntry.layoverHours]). */
    val hours: Long,
    val closedByFlight: Boolean,
    /** True, wenn dieser Aufenthalt ein Layover-Dienstflug ist (Stunden statt tageweisem Flugpaar). */
    val layover: Boolean = false,
    /** Flug, mit dem das Land betreten wurde; null bei manuellem Landweg oder unbekannter Einreise. */
    val enterEntry: LogbookEntry? = null,
    /** Flug, mit dem das Land verlassen wurde; null bei manuell/offen. */
    val exitEntry: LogbookEntry? = null
) {
    val open: Boolean get() = end == null

    /** True, wenn die Einreise fehlt (nur das Verlassen ist in den Daten). */
    val enterUnknown: Boolean get() = start == null
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

    /** Gesamte Zeit im Land in Stunden (Layover-Stunden eingeschlossen). */
    val totalHours: Long get() = periods.sumOf { it.hours }

    /**
     * True, wenn ein Zeitraum offen ist: der Aufenthalt laeuft noch - oder
     * nur eine Richtung (Hin- bzw. Rueckreise) ist in den Daten.
     */
    val hasOpenPeriods: Boolean
        get() = open || periods.any { it.enterUnknown }
}

/**
 * Auswertung der Aufenthaltsdauer in Laendern aus dem Logbuch: Ein Flug von
 * Land A nach Land B (A != B) ist ein Grenzübertritt - Verlassen von A und
 * Betreten von B am Flugdatum. Die Zeit in einem Land laeuft vom Betreten bis
 * zum naechsten Verlassen; ein Aufenthalt, der noch nicht verlassen wurde,
 * zaehlt bis [today]. Ein Verlassen ohne bekanntes Betreten (nur Hin- oder nur
 * Rueckflug in den Daten) erzeugt einen Aufenthalt mit unbekannter Einreise
 * ([StayPeriod.start] = null). Liegt am selben Tag noch ein Betreten - etwa
 * ein manueller Landweg-Uebertritt, der wegen der Sortierung hinter einem
 * Flug desselben Tages liegt -, werden Verlassen und Betreten zu einem
 * 0-Tage-Aufenthalt (Tagesausflug) gepaart statt zwei getrennte Zeitraeume zu
 * erzeugen. Das Land der Homebase wird nicht gezaehlt.
 *
 * **Privat-Fluege** ([isPrivateFlight]) erzeugen die Grenzübertritte: Ein Flug
 * ins Land ist das Betreten, einer hinaus das Verlassen. Gibt es am selben Tag
 * einen Flug in die Gegenrichtung (Hin- und Rueckflug), bekommen beide
 * dieselbe Sequenznummer, damit das Betreten vor dem Verlassen einsortiert
 * wird - unabhaengig davon, welcher der beiden Fluge die kleinere
 * Datenbank-id hat.
 *
 * **Dienstliche Fluege** (On Duty, Deadhead, Ferry, Dienstreise) zaehlen nur,
 * wenn sie als Layover markiert sind ([layoverHoursOf]): Dann zaehlt nicht das
 * Datumspaar, sondern die Layover-Stundenzahl als Zeit im Zielland
 * ([StayPeriod.hours]). Ein nicht als Layover markierter Dienstflug erzeugt
 * kein Ereignis - auch kein offenes.
 *
 * Landweg-Uebertritte ([manual]) werden als zusaetzliche Ereignisse
 * einsortiert. Ohne Android-Abhaengigkeiten: Das Land eines Flughafens kommt
 * als Funktion [countryOf] herein (in der App [AirportData.country]), damit
 * [CountryTimeTest] die Rechnung pruefen kann.
 */
object CountryTimeStats {

    /** True, wenn der Flug als Privat gilt und damit Grenzübertritte erzeugt. */
    fun isPrivateFlight(entry: LogbookEntry): Boolean =
        ChartData.normalizeFlightType(entry.flightType) == ChartData.PRIVATE_CANONICAL

    /**
     * Layover-Stunden eines Dienstflugs (On Duty, Deadhead, Ferry,
     * Dienstreise - nicht Ground Transfer und nicht Privat), wenn er als
     * Layover markiert ist; sonst null. Ohne eingetragene Stundenzahl 0.
     */
    fun layoverHoursOf(entry: LogbookEntry): Int? {
        if (!entry.layover) return null
        val type = ChartData.normalizeFlightType(entry.flightType)
        if (type == ChartData.PRIVATE_CANONICAL ||
            type == ChartData.GROUND_TRANSFER_CANONICAL
        ) return null
        return entry.layoverHours ?: 0
    }

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
        // Nur Privat-Fluege erzeugen Ereignisse.
        // seq: Datenbank-id (Erfassungsreihenfolge innerhalb eines Tages),
        // ersatzweise der Listenindex.
        val relevant = entries.mapIndexedNotNull { idx, e ->
            if (isPrivateFlight(e)) Triple(e, e.id ?: (idx + 1).toLong(), idx) else null
        }
        // Hin- und Rueckflug am selben Tag (Gegenrichtung auf derselben
        // Strecke) bekommen dieselbe seq, damit das Betreten des Ziellandes
        // vor dem Verlassen einsortiert wird - auch wenn der Rueckflug die
        // kleinere id hat und sonst zuerst liefe.
        val seqByIndex = HashMap<Int, Long>()
        val routeGroups = relevant.groupBy { (e, _, _) ->
            val a = e.fromAirport.trim().uppercase()
            val b = e.toAirport.trim().uppercase()
            Triple(e.date, minOf(a, b), maxOf(a, b))
        }
        for ((_, group) in routeGroups) {
            val first = group.first().first
            val a = first.fromAirport.trim().uppercase()
            val b = first.toAirport.trim().uppercase()
            val bothDirections = group.any {
                it.first.fromAirport.trim().uppercase() == b &&
                    it.first.toAirport.trim().uppercase() == a
            }
            val pairedSeq = group.minOf { it.second }
            for ((_, id, idx) in group) {
                seqByIndex[idx] = if (bothDirections) pairedSeq else id
            }
        }
        val events = ArrayList<Event>()
        relevant.forEach { (e, _, idx) ->
            val fromC = countryOf(e.fromAirport)?.trim()?.uppercase()
            val toC = countryOf(e.toAirport)?.trim()?.uppercase()
            if (fromC.isNullOrEmpty() || toC.isNullOrEmpty() || fromC == toC) return@forEach
            val seq = seqByIndex[idx] ?: 0L
            events += Event(fromC, e.date, enter = false, seq, manual = false, entry = e)
            events += Event(toC, e.date, enter = true, seq, manual = false, entry = e)
        }
        // Layover-Dienstfluege: die Stundenzahl zaehlt als Aufenthalt im
        // Zielland (eigener Zeitraum am Flugdatum, kein Grenzübertritt).
        val layoverPeriods = HashMap<String, MutableList<StayPeriod>>()
        entries.forEach { e ->
            val hours = layoverHoursOf(e) ?: return@forEach
            val toC = countryOf(e.toAirport)?.trim()?.uppercase() ?: return@forEach
            if (toC.isEmpty()) return@forEach
            layoverPeriods.getOrPut(toC) { ArrayList() }.add(
                StayPeriod(
                    start = e.date,
                    end = e.date,
                    days = hours / 24L,
                    hours = hours.toLong(),
                    closedByFlight = true,
                    layover = true,
                    enterEntry = e,
                    exitEntry = e
                )
            )
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
            // Indizes von Betreten-Ereignissen, die ein Verlassen am selben
            // Tag per Vorgriff gepaart hat (siehe unten).
            val consumed = HashSet<Int>()
            for ((index, ev) in evs.withIndex()) {
                if (index in consumed) continue
                if (ev.enter) {
                    if (openSince == null) {
                        openSince = ev.date
                        openEntry = ev.entry
                    }
                } else {
                    val since = openSince
                    if (since != null) {
                        val days = ChronoUnit.DAYS.between(since, ev.date).coerceAtLeast(0)
                        periods += StayPeriod(
                            start = since,
                            end = ev.date,
                            days = days,
                            hours = days * 24L,
                            closedByFlight = !ev.manual,
                            enterEntry = openEntry,
                            exitEntry = ev.entry
                        )
                        openSince = null
                        openEntry = null
                    } else {
                        // Verlassen ohne offenes Betreten: Liegt am selben Tag
                        // ein Betreten dahinter (z. B. Landweg FRA->BSL vor dem
                        // Flug BSL->NAP am selben Tag - manuelle Übertritte
                        // sortieren hinter die Flüge), wird gepaart: ein
                        // Tagesausflug mit 0 Tagen statt eines Zeitraums mit
                        // unbekannter Einreise plus einem offenen danach.
                        val sameDayEnter = evs.withIndex().firstOrNull { (i, e) ->
                            i > index && i !in consumed && e.enter && e.date == ev.date
                        }
                        if (sameDayEnter != null) {
                            val (enterIndex, enterEv) = sameDayEnter
                            consumed += enterIndex
                            val days = ChronoUnit.DAYS.between(enterEv.date, ev.date)
                                .coerceAtLeast(0)
                            periods += StayPeriod(
                                start = enterEv.date,
                                end = ev.date,
                                days = days,
                                hours = days * 24L,
                                closedByFlight = !ev.manual,
                                enterEntry = enterEv.entry,
                                exitEntry = ev.entry
                            )
                        } else {
                            // Verlassen ohne bekanntes Betreten (z. B. Flug ALA-FRA,
                            // Einreise per Bus nicht im Logbuch): Das Land taucht
                            // mit unbekannter Einreise auf, damit sie händisch
                            // nachtragen kann.
                            periods += StayPeriod(
                                start = null,
                                end = ev.date,
                                days = 0L,
                                hours = 0L,
                                closedByFlight = !ev.manual,
                                enterEntry = null,
                                exitEntry = ev.entry
                            )
                        }
                    }
                }
            }
            val since = openSince
            if (since != null) {
                val days = ChronoUnit.DAYS.between(since, today).coerceAtLeast(0)
                periods += StayPeriod(
                    start = since,
                    end = null,
                    days = days,
                    hours = days * 24L,
                    closedByFlight = false,
                    enterEntry = openEntry,
                    exitEntry = null
                )
            }
            // Layover-Dienstfluege einsortieren und alles chronologisch.
            layoverPeriods.remove(iso)?.let { periods += it }
            periods.sortBy { it.start ?: it.end ?: LocalDate.MIN }
            val total = periods.sumOf { it.days }
            result += CountryTime(
                iso2 = iso,
                totalDays = total,
                visits = periods.size,
                periods = periods,
                openSince = since
            )
        }
        // Laender, die nur Layover-Dienstfluege haben (keine Privat-Ereignisse).
        for ((iso, laps) in layoverPeriods) {
            if (iso == home) continue
            laps.sortBy { it.start ?: it.end ?: LocalDate.MIN }
            result += CountryTime(
                iso2 = iso,
                totalDays = laps.sumOf { it.days },
                visits = laps.size,
                periods = laps,
                openSince = null
            )
        }
        return result.sortedWith(
            compareByDescending<CountryTime> { it.totalHours }.thenByDescending { it.totalDays }.thenBy { it.iso2 }
        )
    }
}
