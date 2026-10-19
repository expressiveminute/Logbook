package com.highfly.logbook

/**
 * Die Reihenfolge der Kacheln auf der Seite "Einträge" innerhalb eines Tages.
 *
 * Die Liste kommt absteigend nach Datum, jeder Tag steht also als Block
 * beieinander. Innerhalb eines Tages steht der erste Flug des Tages unten und
 * der letzte oben ("je weiter oben, desto später am Tag"). Als
 * Grundreihenfolge gilt die Eintragereihenfolge - beim CSV-Import die
 * Reihenfolge der Datei, ein manuell eingetragener Flug landet als Neuestes
 * oben im Tag. Diese Klasse ordnet nur innerhalb eines Tages um:
 *
 * 1. Ein mit Layover markierter Flug ist immer der letzte Flug des Tages und
 *    steht ganz oben - auch wenn er zugleich der Rückflug des Vortags ist.
 * 2. Der erste Flug des Tages steht ganz unten: Endete der Vortag mit einem
 *    Layover, ist es der Rückflug dieser Strecke (die Umkehrung, sofern nicht
 *    selbst wieder ein Layover). Endete der Vortag ohne Layover, ist es ein
 *    Abflug vom meistgenutzten Flughafen (im ganzen Logbuch) - der Ort, an
 *    dem eine Reise beginnt.
 *
 * Zwischen Einträgen derselben Gruppe bleibt die übergebene Reihenfolge
 * erhalten (stabiler Sort), es wird also nichts weiter umgestellt.
 */
object EntryDayOrder {

    /**
     * Flughäfen mit den meisten Flugbewegungen im übergebenen Bestand. Als
     * Bewegung zählt jeder Start und jede Landung, der Heimatflughafen steht
     * damit fast immer oben. Bei Gleichstand gehören alle betroffenen Flughäfen
     * dazu, weil keiner der beiden bevorzugt wäre.
     */
    fun busiestAirports(entries: List<LogbookEntry>): Set<String> {
        if (entries.isEmpty()) return emptySet()
        val movements = HashMap<String, Int>()
        for (entry in entries) {
            val from = normalized(entry.fromAirport)
            if (from.isNotEmpty()) movements[from] = (movements[from] ?: 0) + 1
            val to = normalized(entry.toAirport)
            if (to.isNotEmpty()) movements[to] = (movements[to] ?: 0) + 1
        }
        val most = movements.values.maxOrNull() ?: return emptySet()
        return movements.filterValues { it == most }.keys
    }

    /**
     * Ordnet jeden Tag nach den Regeln oben. [entries] muss bereits absteigend
     * nach Datum vorliegen (so liefert es die Datenbank), [busiest] kommt aus
     * [busiestAirports] und wird über das ganze Logbuch, nicht nur über den
     * gefilterten Ausschnitt gebildet.
     */
    fun reorder(entries: List<LogbookEntry>, busiest: Set<String>): List<LogbookEntry> {
        if (entries.size < 2) return entries
        val byDate = entries.groupBy { it.date }
        return byDate.flatMap { (date, day) ->
            orderDay(day, byDate[date.minusDays(1)].orEmpty(), busiest)
        }
    }

    private fun orderDay(
        day: List<LogbookEntry>,
        previousDay: List<LogbookEntry>,
        busiest: Set<String>,
    ): List<LogbookEntry> = day.sortedBy { rank(it, previousDay, busiest) }

    /**
     * Rang innerhalb des Tages, kleiner steht weiter oben. Die Layover-Regel
     * gilt immer und wird deshalb zuerst abgefragt; erst danach folgt die
     * Regel für den ersten Flug des Tages ganz unten.
     */
    private fun rank(
        entry: LogbookEntry,
        previousDay: List<LogbookEntry>,
        busiest: Set<String>,
    ): Int = when {
        entry.layover -> 0
        isFirstOfDay(entry, previousDay, busiest) -> 2
        else -> 1
    }

    /**
     * Ist der Flug der erste des Tages und gehört damit ganz nach unten?
     *
     * Ein Layover-Flug steht immer oben und ist damit der letzte Flug des
     * Vortags, sobald der Vortag einen hatte. Endete der Vortag so, ist der
     * erste Flug des neuen Tages der Rückflug: die Umkehrung der
     * Layover-Strecke. Endete der Vortag ohne Layover (oder gibt es in der
     * Liste keinen Vortag), ist es stattdessen ein Abflug vom meistgenutzten
     * Flughafen.
     */
    private fun isFirstOfDay(
        entry: LogbookEntry,
        previousDay: List<LogbookEntry>,
        busiest: Set<String>,
    ): Boolean {
        val layovers = previousDay.filter { it.layover }
        return if (layovers.isNotEmpty()) {
            layovers.any { isReverseOf(entry, it) }
        } else {
            normalized(entry.fromAirport) in busiest
        }
    }

    /**
     * Trifft der Flug die umgekehrte Strecke von [other]? Gestartet wird also
     * dort, wo [other] ankam, und umgekehrt.
     */
    private fun isReverseOf(entry: LogbookEntry, other: LogbookEntry): Boolean {
        val from = normalized(entry.fromAirport)
        val to = normalized(entry.toAirport)
        return normalized(other.toAirport) == from && normalized(other.fromAirport) == to
    }

    private fun normalized(code: String): String = code.trim().uppercase()
}
