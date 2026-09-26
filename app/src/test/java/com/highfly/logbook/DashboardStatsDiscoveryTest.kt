package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Tests fuer die Entdeckungszahl der Dashboard-Kachel. Die Kachel zaehlt
 * immer nur das laufende Jahr, deshalb wird hier bewusst mit festen Jahren
 * statt mit einem Zeitraum-Schluessel gearbeitet.
 */
class DashboardStatsDiscoveryTest {

    private fun entry(
        date: LocalDate,
        from: String = "MUC",
        to: String = "DEN"
    ) = LogbookEntry(
        date = date,
        fromAirport = from,
        toAirport = to
    )

    @Test
    fun discoveryCount_countsOnlyGivenYear() {
        val entries = listOf(
            entry(LocalDate.of(2024, 9, 1), from = "FRA", to = "JFK"),
            entry(LocalDate.of(2026, 3, 12)),
            entry(LocalDate.of(2026, 8, 1), from = "BER", to = "CDG")
        )
        assertEquals(2, DashboardStats.discoveryCount(entries, 2026))
        assertEquals(1, DashboardStats.discoveryCount(entries, 2024))
        assertEquals(0, DashboardStats.discoveryCount(entries, 2025))
    }

    /**
     * Der eigentliche Grund fuer die Auswertung ueber alle Eintraege: Ein
     * Wiederholungsflug in einem spaeteren Jahr ist keine Entdeckung. Wuerde
     * man die Eintraege zuerst auf das Jahr filtern, zaehlte der Flug 2026
     * als neu, obwohl MUC-DEN bereits 2024 geflogen wurde.
     */
    @Test
    fun discoveryCount_repeatFlightOfEarlierYearIsNoDiscovery() {
        val entries = listOf(
            entry(LocalDate.of(2024, 9, 1)),
            entry(LocalDate.of(2026, 2, 1))
        )
        assertEquals(0, DashboardStats.discoveryCount(entries, 2026))
        assertEquals(1, DashboardStats.discoveryCount(entries, 2024))
    }

    @Test
    fun discoveryCount_roundTripWithinSevenDaysCountsAsOne() {
        val entries = listOf(
            entry(LocalDate.of(2026, 3, 12), from = "MUC", to = "DEN"),
            entry(LocalDate.of(2026, 3, 15), from = "DEN", to = "MUC")
        )
        assertEquals(1, DashboardStats.discoveryCount(entries, 2026))
    }

    @Test
    fun discoveryCount_newYearsDayBelongsToItsOwnYearOnly() {
        val entries = listOf(
            entry(LocalDate.of(2025, 12, 31), from = "MUC", to = "DEN"),
            entry(LocalDate.of(2026, 1, 1), from = "BER", to = "CDG")
        )
        assertEquals(1, DashboardStats.discoveryCount(entries, 2025))
        assertEquals(1, DashboardStats.discoveryCount(entries, 2026))
    }

    @Test
    fun discoveryCount_emptyLogCountsZero() {
        assertEquals(0, DashboardStats.discoveryCount(emptyList(), 2026))
    }
}
