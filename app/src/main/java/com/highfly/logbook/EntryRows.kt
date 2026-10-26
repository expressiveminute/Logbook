package com.highfly.logbook

import java.time.LocalDate
import java.time.YearMonth

/**
 * Eine Zeile der Eintraege-Liste: entweder eine Monatsüberschrift, die
 * "Upcoming"-Überschrift über den geplanten Flügen oder eine Kachel.
 */
internal sealed interface EntryRow {
    data class MonthHeader(val month: YearMonth) : EntryRow
    object UpcomingHeader : EntryRow
    data class Item(val entry: LogbookEntry, val upcoming: Boolean, val dayStart: Boolean) :
        EntryRow
}

/**
 * Aufbau der Zeilen der Eintraege-Liste, rein ohne Android (geprueft von
 * [EntryRowsTest]): Ein Flug mit Datum in der Zukunft ist ein geplanter Flug
 * und steht ganz oben unter der "Upcoming"-Ueberschrift, ohne eigene
 * Monatsueberschrift. Alles bis einschliesslich heute wird nach Monaten
 * gruppiert: Vor der ersten Kachel eines Monats steht eine Ueberschrift, alle
 * Kacheln desselben Monats folgen direkt darunter. Die Eintraege kommen bereits
 * absteigend nach Datum, deshalb genuegt es, die Monate in dieser Reihenfolge
 * der Reihe nach zu durchlaufen.
 *
 * [EntryRow.Item.dayStart] markiert die erste Kachel eines neuen Tages, aber
 * nur wenn direkt davor schon eine Kachel steht - nach einer Ueberschrift
 * (Monat oder "Upcoming") steht der Abstand der Ueberschrift allein.
 */
internal object EntryRows {

    fun build(list: List<LogbookEntry>, today: LocalDate = LocalDate.now()): List<EntryRow> {
        val result = mutableListOf<EntryRow>()
        var lastMonth: YearMonth? = null
        var upcomingStarted = false
        var previousItemDate: LocalDate? = null
        for (entry in list) {
            val upcoming = UpcomingEntries.isUpcoming(entry, today)
            when {
                upcoming && !upcomingStarted -> {
                    result += EntryRow.UpcomingHeader
                    upcomingStarted = true
                    previousItemDate = null
                }
                !upcoming -> {
                    val month = YearMonth.from(entry.date)
                    if (month != lastMonth) {
                        result += EntryRow.MonthHeader(month)
                        lastMonth = month
                        previousItemDate = null
                    }
                }
            }
            val dayStart = previousItemDate != null && entry.date != previousItemDate
            previousItemDate = entry.date
            result += EntryRow.Item(entry, upcoming, dayStart)
        }
        return result
    }

    /**
     * Position der ersten Zeile der geflogenen Eintraege: die Monatsüberschrift
     * über der ersten Kachel, die nicht "Upcoming" ist. Die geplanten Flüge
     * stehen in der Liste darüber und werden erst sichtbar, wenn man selbst
     * hochscrollt. 0, wenn es gar keine geplanten Flüge gibt (die Liste beginnt
     * dann direkt mit einer Monatsüberschrift), -1, wenn es keine geflogenen
     * Eintraege gibt.
     */
    fun firstPastPosition(rows: List<EntryRow>): Int =
        rows.indexOfFirst { it is EntryRow.MonthHeader || (it is EntryRow.Item && !it.upcoming) }
}
