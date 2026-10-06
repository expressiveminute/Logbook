package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UpcomingEntriesTest {

    private val today = LocalDate.of(2026, 10, 3)

    private fun entry(date: LocalDate, id: Long = 0L) = LogbookEntry(
        id = id,
        date = date,
        flightType = "On Duty",
        fromAirport = "FRA",
        toAirport = "JFK"
    )

    /** Absteigend nach Datum, so wie die Liste sie liefert. */
    private val entries = listOf(
        entry(LocalDate.of(2026, 12, 24), 1L),
        entry(LocalDate.of(2026, 11, 2), 2L),
        entry(today, 3L),
        entry(LocalDate.of(2026, 9, 30), 4L)
    )

    @Test
    fun upcomingEntries_sindNurDieNachHeute() {
        val upcoming = UpcomingEntries.upcoming(entries, today)

        assertEquals(
            listOf(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 11, 2)),
            upcoming.map { it.date }
        )
    }

    @Test
    fun upcomingEntries_hoechstesDatumZuerst() {
        // In "Upcoming" steht der am weitesten entfernte Flug oben, genau wie
        // in der Monatsgliederung darunter - die Liste springt beim Scrollen
        // nicht zwischen den Gruppen hin und her.
        val upcoming = UpcomingEntries.upcoming(entries.reversed(), today)

        assertEquals(
            listOf(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 11, 2)),
            upcoming.map { it.date }
        )
    }

    @Test
    fun heutigerFlugGehoertZuDenGeflogenen() {
        assertFalse(UpcomingEntries.isUpcoming(entries[2], today))
        assertTrue(UpcomingEntries.isUpcoming(entries[1], today))
    }

    @Test
    fun geflogeneEintraege_steigenAbsteigendNachDatum() {
        val geflogen = listOf(
            entry(LocalDate.of(2026, 3, 8), 5L),
            entry(LocalDate.of(2026, 7, 19), 6L),
            entry(LocalDate.of(2026, 1, 5), 7L)
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 7, 19),
                LocalDate.of(2026, 3, 8),
                LocalDate.of(2026, 1, 5)
            ),
            UpcomingEntries.flown(geflogen, today).map { it.date }
        )
    }

    @Test
    fun aufteilungDecktAlleEintraegeAb() {
        // Die Liste zeigt beide Gruppen, die Auswertung nur die geflogenen:
        // Ein Eintrag darf weder verloren gehen noch doppelt auftauchen.
        val upcoming = UpcomingEntries.upcoming(entries, today)
        val flown = UpcomingEntries.flown(entries, today)

        assertEquals(entries.size, upcoming.size + flown.size)
        assertTrue(upcoming.none { it in flown })
    }

    @Test
    fun leererBestandErzeugtKeineGruppe() {
        assertTrue(UpcomingEntries.upcoming(emptyList(), today).isEmpty())
        assertTrue(UpcomingEntries.flown(emptyList(), today).isEmpty())
    }
}
