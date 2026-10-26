package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Aufbau der Zeilen der Eintraege-Liste ([EntryRows]): Die geplanten Fluege
 * ("Upcoming") stehen ganz oben, die geflogenen Eintraege folgen darunter, je
 * Monat gruppiert. [EntryRows.firstPastPosition] nennt die Zeile, ab der die
 * Liste beim ersten Oeffnen startet - die geplanten Fluege darueber werden
 * erst sichtbar, wenn man selbst hochscrollt.
 */
class EntryRowsTest {

    private val today = LocalDate.of(2026, 10, 26)

    private fun entry(date: LocalDate, to: String = "MUC"): LogbookEntry =
        LogbookEntry(date = date, fromAirport = "FRA", toAirport = to)

    @Test
    fun firstPastPosition_mitUpcoming_zeigtAufDieMonatsueberschrift() {
        // Zwei geplante Fluege, dann die geflogenen Eintraege.
        val rows = EntryRows.build(
            listOf(
                entry(today.plusDays(10), "JFK"),
                entry(today.plusDays(2), "LHR"),
                entry(today.minusDays(1), "MUC"),
                entry(today.minusDays(40), "CDG"),
            ),
            today
        )
        // UpcomingHeader, zwei geplante Kacheln, dann die Monatsueberschrift
        // ueber der ersten geflogenen Kachel.
        val firstPast = EntryRows.firstPastPosition(rows)
        assertEquals(3, firstPast)
        assertTrue(rows[firstPast] is EntryRow.MonthHeader)
    }

    @Test
    fun firstPastPosition_ohneUpcoming_beginntBeiNull() {
        val rows = EntryRows.build(
            listOf(entry(today.minusDays(1)), entry(today.minusDays(40))),
            today
        )
        assertEquals(0, EntryRows.firstPastPosition(rows))
    }

    @Test
    fun firstPastPosition_nurUpcoming_istMinusEins() {
        val rows = EntryRows.build(
            listOf(entry(today.plusDays(10)), entry(today.plusDays(2))),
            today
        )
        assertEquals(-1, EntryRows.firstPastPosition(rows))
    }

    @Test
    fun firstPastPosition_leereListe_istMinusEins() {
        assertEquals(-1, EntryRows.firstPastPosition(emptyList()))
    }

    @Test
    fun build_geplanteFluegeStehenOhneMonatsueberschriftOben() {
        val rows = EntryRows.build(
            listOf(
                entry(today.plusDays(10), "JFK"),
                entry(today.minusDays(1), "MUC"),
            ),
            today
        )
        // UpcomingHeader, geplante Kachel, Monatsueberschrift, geflogene Kachel.
        assertEquals(4, rows.size)
        assertTrue(rows[0] is EntryRow.UpcomingHeader)
        assertTrue((rows[1] as EntryRow.Item).upcoming)
        // Die geflogenen Eintraege beginnen mit ihrer Monatsueberschrift.
        assertTrue(rows[2] is EntryRow.MonthHeader)
        assertEquals(
            YearMonth.from(today.minusDays(1)),
            (rows[2] as EntryRow.MonthHeader).month
        )
        assertTrue(rows[3] is EntryRow.Item && !(rows[3] as EntryRow.Item).upcoming)
    }
}
