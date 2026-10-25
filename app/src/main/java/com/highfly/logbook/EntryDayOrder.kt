package com.highfly.logbook

import java.time.LocalDate

/**
 * Die Reihenfolge der Kacheln auf der Seite "Einträge" innerhalb eines Tages.
 *
 * Die Liste kommt absteigend nach Datum, jeder Tag steht also als Block
 * beieinander. Innerhalb eines Tages steht der erste Flug des Tages unten
 * und der letzte oben ("je weiter oben, desto später am Tag"). Als
 * Grundreihenfolge gilt die Eintragereihenfolge - beim CSV-Import die
 * Reihenfolge der Datei (die erste Zeile ist der erste Flug des Tages), ein
 * manuell eingetragener Flug landet als Neuestes oben im Tag. Diese Klasse
 * ordnet nur innerhalb eines Tages um:
 *
 * 1. Ein mit Layover markierter Flug ist immer der letzte Flug des Tages und
 *    steht ganz oben - auch wenn er zugleich der Rückflug des Vortags ist.
 * 2. Der erste Flug des Tages steht ganz unten: Endete der jüngste Tag mit
 *    Flügen mit einem Layover, ist es der Rückflug dieser Strecke (die
 *    Umkehrung, sofern nicht selbst wieder ein Layover). Liegen zwischen
 *    beiden Tagen Ruhetage ohne Flug, gilt die Regel trotzdem - der
 *    Rückflug steht auch am übernächsten oder einem späteren Tag unten.
 * 3. Dazwischen gruppiert die Klasse Hin- und Rückflüge ab der Homebase zu
 *    Paaren: Der Hinflug (Abflug ab der Homebase) steht unten, der Rückflug
 *    (die Umkehrung der Strecke) direkt darüber. Die Paare sortieren sich nach
 *    der Position des Hinflugs in der Eintragereihenfolge. Flüge ohne
 *    passenden Gegenflug (zum Beispiel ein Anschlussflug an einem anderen
 *    Ort) behalten ihre Position.
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
     * nach Datum vorliegen (so liefert es die Datenbank), [homebases] ist die
     * Homebase (über das ganze Logbuch, nicht nur über den gefilterten
     * Ausschnitt - siehe [Homebase]).
     */
    fun reorder(entries: List<LogbookEntry>, homebases: Set<String>): List<LogbookEntry> {
        if (entries.size < 2) return entries
        val byDate = entries.groupBy { it.date }
        return byDate.flatMap { (date, day) ->
            orderDay(day, previousFlightDay(byDate, date), homebases)
        }
    }

    /**
     * Der jüngste Tag vor [date] mit Flügen: Tag für Tag rückwärts, bis ein
     * Tag mit Einträgen auftaucht. Ruhetage ohne Flug dazwischen werden
     * übersprungen, damit die Rückflug-Regel auch greift, wenn der Rückflug
     * erst am übernächsten oder einem späteren Tag stattfindet. Hat der
     * jüngste Tag mit Flügen kein Layover, endet die Suche dort - Flüge an
     * einem Zwischentag beenden die Layover-Kette.
     */
    private fun previousFlightDay(
        byDate: Map<LocalDate, List<LogbookEntry>>,
        date: LocalDate
    ): List<LogbookEntry> {
        val oldest = byDate.keys.minOrNull() ?: return emptyList()
        var previous = date.minusDays(1)
        while (previous >= oldest) {
            byDate[previous]?.let { return it }
            previous = previous.minusDays(1)
        }
        return emptyList()
    }

    /**
     * Ordnet einen Tag. [day] kommt von oben nach unten (letzter Flug zuerst),
     * die Chronologie des Tages ist also die umgekehrte Reihenfolge. Das
     * Ergebnis steht wieder von oben nach unten. [previousDay] ist der
     * jüngste Tag mit Flügen davor (siehe [previousFlightDay]).
     */
    private fun orderDay(
        day: List<LogbookEntry>,
        previousDay: List<LogbookEntry>,
        homebases: Set<String>,
    ): List<LogbookEntry> {
        val chrono = day.asReversed()
        val layovers = chrono.filter { it.layover }
        val withoutLayover = chrono.filter { !it.layover }
        // Endete der jüngste Tag mit Flügen mit einem Layover, ist der erste
        // Flug des Tages der Rückflug: die Umkehrung der Layover-Strecke.
        val first = if (previousDay.any { it.layover }) {
            val layoverRoutes = previousDay.filter { it.layover }
            withoutLayover.firstOrNull { entry ->
                layoverRoutes.any { isReverseOf(entry, it) }
            }
        } else {
            null
        }
        val rest = if (first != null) withoutLayover.filter { it != first } else withoutLayover
        val result = listOfNotNull(first) + pairUp(rest, homebases) + layovers
        return result.asReversed()
    }

    /**
     * Gruppiert Hin- und Rückflüge ab der Homebase zu Paaren. Ein Hinflug
     * (Abflug ab der Homebase) paart sich mit dem ersten noch freien Flug auf
     * der umgekehrten Strecke - egal ob dieser in der Eintragereihenfolge vor
     * oder nach dem Hinflug steht. Die Gruppen sortieren sich stabil nach der
     * Position des Hinflugs; innerhalb eines Paares steht der Hinflug vor dem
     * Rückflug. Flüge ohne passenden Gegenflug bilden eine eigene Gruppe an
     * ihrer Position.
     */
    private fun pairUp(entries: List<LogbookEntry>, homebases: Set<String>): List<LogbookEntry> {
        if (entries.size < 2) return entries
        val used = BooleanArray(entries.size)
        // Gruppe: Anker (Position des Hinflugs bzw. des einzelnen Flugs) und
        // die Indizes der Gruppe in Anzeigereihenfolge.
        val groups = mutableListOf<Pair<Int, List<Int>>>()
        // Erste Runde: Jeder Hinflug ab der Homebase paart sich mit dem ersten
        // freien Rückflug auf der umgekehrten Strecke - egal ob dieser in der
        // Eintragereihenfolge vor oder nach dem Hinflug steht.
        for (i in entries.indices) {
            if (used[i]) continue
            val entry = entries[i]
            if (normalized(entry.fromAirport) !in homebases) continue
            val partner = entries.indices.firstOrNull {
                it != i && !used[it] && isReverseOf(entries[it], entry)
            } ?: continue
            used[i] = true
            used[partner] = true
            groups += i to listOf(i, partner)
        }
        // Zweite Runde: Übrig bleiben die Flüge ohne passenden Gegenflug; sie
        // bilden eine eigene Gruppe an ihrer Position.
        for (i in entries.indices) {
            if (!used[i]) groups += i to listOf(i)
        }
        return groups.sortedBy { it.first }
            .flatMap { (_, indices) -> indices.map { entries[it] } }
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
