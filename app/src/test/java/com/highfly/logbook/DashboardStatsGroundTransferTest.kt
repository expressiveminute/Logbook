package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Tests fuer den Ausschluss von Ground Transfers. Ein Ground Transfer (Auto,
 * Zug, Bus) ist kein Flug und darf weder in die Flugzeit noch in die Distanz
 * eingehen, sonst waeren die Kacheln der Zeit- und der Distanzseite zu hoch.
 */class DashboardStatsGroundTransferTest {

    private fun entry(
        minutes: Int,
        flightType: String? = "On Duty",
        km: Int? = 1000
    ) = LogbookEntry(
        date = LocalDate.of(2026, 5, 4),
        flightType = flightType,
        fromAirport = "FRA",
        toAirport = "JFK",
        distanceKm = km,
        flightMinutes = minutes
    )

    @Test
    fun flightMinutes_ignoresGroundTransfer() {
        val entries = listOf(
            entry(120),
            entry(95, flightType = "Ground Transfer")
        )
        assertEquals(120, DashboardStats.flightMinutes(entries))
    }

    /**
     * Gespeichert werden kann der englische Begriff oder eine der Bezeichnungen,
     * die [ChartData.normalizeFlightType] kennt. Alle Varianten müssen greifen.
     */
    @Test
    fun flightMinutes_ignoresEveryGroundTransferSpelling() {
        val entries = listOf(
            entry(60, flightType = "Ground Transfer"),
            entry(60, flightType = "ground transfer"),
            entry(60, flightType = "ground"),
            entry(60, flightType = "  Ground Transfer  ")
        )
        assertEquals(0, DashboardStats.flightMinutes(entries))
    }

    @Test
    fun flyingEntries_keepsEveryOtherTravelType() {
        val entries = listOf(
            entry(60, flightType = "Privat"),
            entry(60, flightType = "Ferry"),
            entry(60, flightType = "Dienstreise"),
            entry(60, flightType = null)
        )
        assertEquals(4, DashboardStats.flyingEntries(entries).size)
    }
}
