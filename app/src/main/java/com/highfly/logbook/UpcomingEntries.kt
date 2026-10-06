package com.highfly.logbook

import java.time.LocalDate

/**
 * Ein Flug mit Datum in der Zukunft ist ein geplanter Flug: Er steht auf der
 * Seite Einträge unter "Upcoming" und wird von keiner Auswertung mitgezählt,
 * bis sein Tag erreicht ist. Die Grenze steht hier an einer Stelle, damit die
 * Liste und die Statistik garantiert dieselben Einträge als "geflogen"
 * ansehen - ein Flug, der in der Liste schon gezählt wird, aber in der Kachel
 * noch nicht, wäre nicht nachvollziehbar.
 *
 * Der Schnitt ist der heutige Tag: Ein Flug von heute zählt, morgen ist er
 * Vergangenheit. Dass der Schnitt durchgehend gilt, sagt dem Nutzer das blaue
 * "i" neben der Filterzeile des Dashboards: Es nennt die Zahl der geplanten
 * Flüge und die Kacheln, die sie deshalb noch nicht enthalten.
 *
 * Beide Gruppen stehen absteigend nach Datum, das höchste Datum also ganz
 * oben: Der am weitesten entfernte Flug eröffnet die Liste, darunter folgen
 * die näheren und dann die Monatsgruppen der geflogenen Flüge. Ein
 * Sonderfall für "Upcoming" (etwa den nächsten Flug zuerst) wäre die
 * einzige Stelle, an der die Liste beim Scrollen springen würde.
 */
object UpcomingEntries {

    fun isUpcoming(entry: LogbookEntry, today: LocalDate = LocalDate.now()): Boolean =
        entry.date.isAfter(today)

    /** Einträge, die noch bevorstehen, mit dem höchsten Datum zuerst. */
    fun upcoming(
        entries: List<LogbookEntry>,
        today: LocalDate = LocalDate.now()
    ): List<LogbookEntry> = entries
        .filter { isUpcoming(it, today) }
        .sortedByDescending { it.date }

    /** Einträge bis einschliesslich heute, mit dem höchsten Datum zuerst. */
    fun flown(
        entries: List<LogbookEntry>,
        today: LocalDate = LocalDate.now()
    ): List<LogbookEntry> = entries
        .filterNot { isUpcoming(it, today) }
        .sortedByDescending { it.date }
}
