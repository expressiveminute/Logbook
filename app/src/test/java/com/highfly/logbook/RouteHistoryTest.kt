package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RouteHistoryTest {

    private fun entry(
        date: LocalDate = LocalDate.of(2026, 1, 1),
        airline: String = "LH",
        flightNumber: String = "480",
        from: String = "MUC",
        to: String = "DEN"
    ) = LogbookEntry(
        date = date,
        fromAirport = from,
        toAirport = to,
        airline = airline,
        flightNumber = flightNumber
    )

    @Test
    fun mostFrequentRoute_picksMostCommonRoute() {
        val entries = listOf(
            entry(from = "MUC", to = "DEN"),
            entry(from = "MUC", to = "DEN"),
            entry(from = "FRA", to = "JFK"),
            entry(from = "BER", to = "CGN")
        )
        assertEquals(Route("MUC", "DEN"), mostFrequentRoute(entries, "LH", "480"))
    }

    @Test
    fun mostFrequentRoute_tieBreak_usesMostRecentRoute() {
        val entries = listOf(
            entry(from = "MUC", to = "DEN", date = LocalDate.of(2025, 6, 1)),
            entry(from = "FRA", to = "JFK", date = LocalDate.of(2026, 9, 1)),
            entry(from = "FRA", to = "JFK", date = LocalDate.of(2026, 9, 7))
        )
        assertEquals(Route("FRA", "JFK"), mostFrequentRoute(entries, "LH", "480"))
    }

    @Test
    fun mostFrequentRoute_returnsNullWhenNoMatch() {
        val entries = listOf(entry(airline = "LH", flightNumber = "400"))
        assertNull(mostFrequentRoute(entries, "LH", "480"))
        assertNull(mostFrequentRoute(entries, "UA", "480"))
    }

    @Test
    fun mostFrequentRoute_onlyConsidersMatchingAirlineAndFlightNumber() {
        val entries = listOf(
            entry(airline = "LH", flightNumber = "480", from = "MUC", to = "DEN"),
            entry(airline = "UA", flightNumber = "480", from = "EWR", to = "SFO")
        )
        assertEquals(Route("MUC", "DEN"), mostFrequentRoute(entries, "LH", "480"))
        assertEquals(Route("EWR", "SFO"), mostFrequentRoute(entries, "UA", "480"))
    }

    @Test
    fun mostFrequentRoute_matchesAirlineCaseInsensitive() {
        val entries = listOf(entry(from = "MUC", to = "DEN", date = LocalDate.of(2025, 1, 1)))
        assertEquals(Route("MUC", "DEN"), mostFrequentRoute(entries, "lh", "480"))
    }

    @Test
    fun mostFrequentRoute_ignoresLeadingZerosInFlightNumber() {
        val entries = listOf(
            entry(flightNumber = "0480", from = "MUC", to = "DEN"),
            entry(flightNumber = "000480", from = "MUC", to = "DEN"),
            entry(flightNumber = "0480", from = "FRA", to = "JFK")
        )
        assertEquals(Route("MUC", "DEN"), mostFrequentRoute(entries, "LH", "480"))
        assertEquals(Route("MUC", "DEN"), mostFrequentRoute(entries, "LH", "000480"))
    }

    @Test
    fun mostFrequentRoute_returnsNullForBlankInputs() {
        val entries = listOf(entry())
        assertNull(mostFrequentRoute(entries, "", ""))
        assertNull(mostFrequentRoute(entries, "LH", ""))
        assertNull(mostFrequentRoute(emptyList(), "LH", "480"))
    }

    @Test
    fun normalizeFlightNumber_stripsLeadingZeros() {
        assertEquals("480", normalizeFlightNumber("0480"))
        assertEquals("480", normalizeFlightNumber("  480  "))
        assertEquals("0", normalizeFlightNumber("000"))
        assertEquals("", normalizeFlightNumber(""))
    }
}