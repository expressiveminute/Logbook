package com.highfly.logbook

/**
 * Die Reihenfolge der Kacheln auf der Seite "Einträge" innerhalb eines Tages.
 *
 * Die Liste kommt absteigend nach Datum, jeder Tag steht also als Block
 * beieinander und ist für sich schon "je weiter unten, desto länger her". Diese
 * Klasse ordnet nur innerhalb eines Tages um:
 *
 * 1. Ein mit Layover markierter Flug steht ganz oben im Tag - er eröffnet die
 *    Reise und ist der wichtigste Eintrag des Tages.
 * 2. Ein Flug, der die Umkehrung eines Flugs vom Vortag ist (also die Rückkehr
 *    einer am Tag zuvor begonnenen Strecke), steht ganz unten. Er schliesst
 *    die Reise ab und gehört damit ans Ende des neuen Tages, auch wenn er mit
 *    Layover markiert ist.
 * 3. Sonst stehen Flüge, die an einem Flughafen mit den meisten Flugbewegungen
 *    (im ganzen Logbuch) starten, unten im Tag: Auch sie beginnen am Heimatort
 *    eine Reise und gehören unter die übrigen Flüge.
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
     * Rang innerhalb des Tages, kleiner steht weiter oben. Die Abfrage der
     * Umkehrung kommt zuerst, damit Regel 2 die Layover-Regel schlägt; die
     * Layover-Regel kommt vor dem Heimatflughafen, damit ein Layover-Flug oben
     * bleibt, auch wenn er am meistgenutzten Flughafen startet.
     */
    private fun rank(
        entry: LogbookEntry,
        previousDay: List<LogbookEntry>,
        busiest: Set<String>,
    ): Int = when {
        isReverseOfPreviousDay(entry, previousDay) -> 3
        entry.layover -> 0
        normalized(entry.fromAirport) in busiest -> 2
        else -> 1
    }

    /**
     * Trifft der Flug die umgekehrte Strecke eines Flugs vom Vortag? Gestartet
     * wird also dort, wo der Vortagsflug ankam, und umgekehrt.
     */
    private fun isReverseOfPreviousDay(
        entry: LogbookEntry,
        previousDay: List<LogbookEntry>,
    ): Boolean {
        val from = normalized(entry.fromAirport)
        val to = normalized(entry.toAirport)
        return previousDay.any {
            normalized(it.toAirport) == from && normalized(it.fromAirport) == to
        }
    }

    private fun normalized(code: String): String = code.trim().uppercase()
}
