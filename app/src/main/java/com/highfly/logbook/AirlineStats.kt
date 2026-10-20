package com.highfly.logbook

import java.text.Collator
import java.time.LocalDate

/**
 * Auswertung der Kachel "Fluggesellschaften": mit welcher Airline wie oft
 * geflogen wurde.
 *
 * Das Feld im Formular ist ein Code - meist der IATA-Code ("LH"), manchmal der
 * ICAO-Code ("DLH") oder der ausgeschriebene Name. Erst der Katalog löst diese
 * Schreibweisen auf dieselbe Airline auf, damit "LH" und "DLH" nicht als zwei
 * verschiedene Gesellschaften zählen. Ohne Android-Abhängigkeiten, damit
 * Gruppierung, Sortierung und Suche im Test ohne Datenbankzugriff geprüft
 * werden können.
 *
 * Gezählt wird wie bei den Reisebuddies: die Zahl der Flüge zählt jeden
 * Eintrag, Distanz und Flugzeit dagegen nur echte Flüge (kein Ground
 * Transfer), damit die Kennzahlen zu den Kacheln *Distanz* und *Zeit* passen.
 */
object AirlineStats {

    /** Auflösung eines gespeicherten Airline-Texts aus dem Katalog. */
    data class Resolved(
        /** Kanonischer Schlüssel der Airline: der IATA-Code. */
        val code: String,
        val icao: String?,
        val name: String
    )

    /**
     * @param code Kanonischer Schlüssel: IATA-Code aus dem Katalog, sonst der
     *   gespeicherte Rohtext in Grossbuchstaben
     * @param name Anzeigename: der Katalogname, sonst der Rohtext
     * @param flights Zahl der Einträge mit dieser Airline
     * @param minutes gesamte Flugzeit in Minuten, echte Flüge ohne Ground Transfer
     * @param km gesamte Distanz in Kilometern, echte Flüge ohne Ground Transfer
     * @param destinations verschiedene Ziel-Flughäfen, echte Flüge
     * @param aircraftTypes verschiedene Flugzeugtypen, echte Flüge
     * @param firstFlight ältestes Flugdatum, [lastFlight] jüngstes
     */
    data class Airline(
        val code: String,
        val name: String,
        val icao: String?,
        val flights: Int,
        val minutes: Int,
        val km: Int,
        val destinations: Int,
        val aircraftTypes: Int,
        val firstFlight: LocalDate?,
        val lastFlight: LocalDate?
    )

    /** Score, wenn die Suche nicht trifft; kleinere Scores stehen weiter oben. */
    const val NO_MATCH = 99

    /**
     * Kanonischer Schlüssel eines gespeicherten Airline-Texts: der aufgelöste
     * IATA-Code, sonst der Text selbst in Grossbuchstaben. Leer bleibt null -
     * ein Eintrag ohne Airline gehört zu keiner Gruppe.
     */
    fun keyOf(raw: String?, resolve: (String) -> Resolved? = { null }): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return resolve(text)?.code ?: text.uppercase()
    }

    /**
     * Zählt die Flüge je Airline, absteigend nach Zahl. "LH" und "DLH" landen
     * über [resolve] in derselben Gruppe; bei gleicher Zahl entscheidet
     * [collator] über die Reihenfolge - die Liste soll in jeder Sprache
     * aufsteigend lesen.
     *
     * Anzeigename ist der Katalogname; eine Airline ohne Katalogeintrag
     * behält ihren Rohtext, so wie ihn zuerst jemand geschrieben hat.
     */
    fun build(
        entries: List<LogbookEntry>,
        resolve: (String) -> Resolved?,
        collator: Collator = Collator.getInstance()
    ): List<Airline> {
        val anzeige = HashMap<String, String>()
        val icaoOf = HashMap<String, String?>()
        val counts = HashMap<String, Int>()
        val minutes = HashMap<String, Int>()
        val km = HashMap<String, Int>()
        val destinations = HashMap<String, MutableSet<String>>()
        val types = HashMap<String, MutableSet<String>>()
        val firstDate = HashMap<String, LocalDate>()
        val lastDate = HashMap<String, LocalDate>()
        for (entry in entries) {
            val raw = entry.airline?.takeIf { it.isNotBlank() } ?: continue
            val resolved = resolve(raw)
            val key = resolved?.code ?: raw.trim().uppercase()
            anzeige.putIfAbsent(key, resolved?.name ?: raw.trim())
            icaoOf.putIfAbsent(key, resolved?.icao)
            counts[key] = (counts[key] ?: 0) + 1
            val bisherFirst = firstDate[key]
            if (bisherFirst == null || entry.date < bisherFirst) firstDate[key] = entry.date
            val bisherLast = lastDate[key]
            if (bisherLast == null || entry.date > bisherLast) lastDate[key] = entry.date
            // Distanz, Flugzeit, Ziele und Typen zählen nur echte Flüge, wie
            // die Kacheln "Distanz" und "Zeit"; ein Ground Transfer ist keiner.
            if (!DashboardStats.isFlying(entry)) continue
            minutes[key] = (minutes[key] ?: 0) + (entry.flightMinutes ?: 0)
            km[key] = (km[key] ?: 0) + (entry.distanceKm ?: 0)
            entry.toAirport.trim().uppercase().takeIf { it.isNotEmpty() }
                ?.let { destinations.getOrPut(key) { mutableSetOf() }.add(it) }
            entry.aircraftType?.takeIf { it.isNotBlank() }
                ?.let { types.getOrPut(key) { mutableSetOf() }.add(it) }
        }
        return counts.map { (key, anzahl) ->
            Airline(
                code = key,
                name = anzeige.getValue(key),
                icao = icaoOf[key],
                flights = anzahl,
                minutes = minutes[key] ?: 0,
                km = km[key] ?: 0,
                destinations = destinations[key]?.size ?: 0,
                aircraftTypes = types[key]?.size ?: 0,
                firstFlight = firstDate[key],
                lastFlight = lastDate[key]
            )
        }.sortedWith(
            compareByDescending<Airline> { it.flights }
                .thenBy(collator) { it.name }
        )
    }

    /**
     * Flüge, auf denen überhaupt eine Airline eingetragen ist. Das ist die
     * Zahl in der Überschrift der Seite.
     */
    fun flightsWithAirline(entries: List<LogbookEntry>): Int =
        entries.count { !it.airline.isNullOrBlank() }

    /**
     * Trifft die Suche [query] die Airline - über den IATA-Code, den ICAO-Code
     * oder den Namen, ohne Gross- und Kleinschreibung. "DLH", "LH" und
     * "Lufthansa" treffen dieselbe Airline.
     */
    fun matchesQuery(code: String, icao: String?, name: String, query: String): Boolean =
        searchScore(code, icao, name, query) != NO_MATCH

    /**
     * Wie gut die Airline zur Suche passt; kleiner ist besser: 0 exakter Code,
     * 3 reiner Teiltreffer im Namen, [NO_MATCH] kein Treffer. Exakte und
     * beginnende Treffer auf den Codes stehen oben, danach der Name.
     */
    fun searchScore(code: String, icao: String?, name: String, query: String): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return NO_MATCH
        val c = code.lowercase()
        val i = icao?.lowercase()
        val n = name.lowercase()
        return when {
            c == q || i == q -> 0
            c.startsWith(q) || i?.startsWith(q) == true -> 1
            n.startsWith(q) == true -> 2
            n.contains(q) -> 3
            else -> NO_MATCH
        }
    }
}
