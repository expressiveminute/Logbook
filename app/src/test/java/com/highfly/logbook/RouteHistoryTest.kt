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

    @Test
    fun routePrefillAction_fillsEmptyFieldsOnExactMatch() {
        val entries = listOf(entry(flightNumber = "16", from = "FRA", to = "HAM"))
        assertEquals(
            RoutePrefillAction.Fill(Route("FRA", "HAM")),
            routePrefillAction(entries, "LH", "16", current = null, suggested = null)
        )
    }

    @Test
    fun routePrefillAction_keepsEmptyFieldsWithoutMatch() {
        val entries = listOf(entry(flightNumber = "16", from = "FRA", to = "HAM"))
        assertEquals(
            RoutePrefillAction.Keep,
            routePrefillAction(entries, "LH", "1672", current = null, suggested = null)
        )
    }

    @Test
    fun routePrefillAction_clearsSuggestionWhenNumberGrowsToOtherFlight() {
        // "LH 16" passt noch, "LH 1672" nicht mehr – der Vorschlag räumt auf.
        val entries = listOf(entry(flightNumber = "16", from = "FRA", to = "HAM"))
        val suggested = Route("FRA", "HAM")
        assertEquals(
            RoutePrefillAction.Clear,
            routePrefillAction(entries, "LH", "1672", current = suggested, suggested = suggested)
        )
    }

    @Test
    fun routePrefillAction_clearsSuggestionWhenNumberIsRemoved() {
        val entries = listOf(entry(flightNumber = "16", from = "FRA", to = "HAM"))
        val suggested = Route("FRA", "HAM")
        assertEquals(
            RoutePrefillAction.Clear,
            routePrefillAction(entries, "", "", current = suggested, suggested = suggested)
        )
    }

    @Test
    fun routePrefillAction_keepsOwnRoute() {
        val entries = listOf(
            entry(flightNumber = "16", from = "FRA", to = "HAM"),
            entry(flightNumber = "1672", from = "MUC", to = "DUS")
        )
        val own = Route("BER", "CGN")
        assertEquals(
            RoutePrefillAction.Keep,
            routePrefillAction(entries, "LH", "16", current = own, suggested = null)
        )
        assertEquals(
            RoutePrefillAction.Keep,
            routePrefillAction(entries, "LH", "1672", current = own, suggested = null)
        )
    }

    @Test
    fun routePrefillAction_keepsSuggestionThatStillMatches() {
        val entries = listOf(entry(flightNumber = "16", from = "FRA", to = "HAM"))
        val suggested = Route("FRA", "HAM")
        assertEquals(
            RoutePrefillAction.Keep,
            routePrefillAction(entries, "LH", "16", current = suggested, suggested = suggested)
        )
    }

    @Test
    fun routePrefillAction_replacesOwnSuggestionOnNewMatch() {
        val entries = listOf(
            entry(flightNumber = "16", from = "FRA", to = "HAM"),
            entry(flightNumber = "1672", from = "MUC", to = "DUS")
        )
        val suggested = Route("FRA", "HAM")
        assertEquals(
            RoutePrefillAction.Fill(Route("MUC", "DUS")),
            routePrefillAction(entries, "LH", "1672", current = suggested, suggested = suggested)
        )
    }
}