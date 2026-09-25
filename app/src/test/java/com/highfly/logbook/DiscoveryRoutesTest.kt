package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class DiscoveryRoutesTest {

    private fun entry(
        date: LocalDate,
        id: Long? = null,
        from: String = "MUC",
        to: String = "DEN",
        aircraftType: String? = null,
        registration: String? = null,
        airline: String? = null
    ) = LogbookEntry(
        id = id,
        date = date,
        fromAirport = from,
        toAirport = to,
        aircraftType = aircraftType,
        registration = registration,
        airline = airline
    )

    @Test
    fun firstFlown_keepsEarliestFlightPerRoute() {
        val entries = listOf(
            entry(LocalDate.of(2026, 3, 12)),
            entry(LocalDate.of(2025, 7, 2), from = "FRA", to = "JFK"),
            entry(LocalDate.of(2026, 8, 1))
        )
        val discoveries = DiscoveryRoutes.firstFlown(entries)
        assertEquals(2, discoveries.size)
        assertEquals(
            RouteDiscovery("FRA", "JFK", LocalDate.of(2025, 7, 2)),
            discoveries.first { it.from == "FRA" }
        )
        assertEquals(
            RouteDiscovery("MUC", "DEN", LocalDate.of(2026, 3, 12)),
            discoveries.first { it.from == "MUC" }
        )
    }

    @Test
    fun firstFlown_routeIsDirectional() {
        val entries = listOf(
            entry(LocalDate.of(2026, 1, 5), from = "MUC", to = "DEN"),
            entry(LocalDate.of(2026, 2, 5), from = "DEN", to = "MUC")
        )
        assertEquals(2, DiscoveryRoutes.firstFlown(entries).size)
    }

    @Test
    fun firstFlown_ignoresBlankAirportsAndNormalizesCodes() {
        val entries = listOf(
            entry(LocalDate.of(2026, 1, 5), from = " ", to = "DEN"),
            entry(LocalDate.of(2026, 1, 6), from = "MUC", to = ""),
            entry(LocalDate.of(2026, 1, 7), from = " muc ", to = "den"),
            entry(LocalDate.of(2026, 2, 7), from = "MUC", to = "DEN")
        )
        val discoveries = DiscoveryRoutes.firstFlown(entries)
        assertEquals(1, discoveries.size)
        assertEquals(RouteDiscovery("MUC", "DEN", LocalDate.of(2026, 1, 7)), discoveries.first())
    }

    @Test
    fun firstFlown_sameDay_usesFirstCreatedEntry() {
        val entries = listOf(
            entry(LocalDate.of(2026, 4, 4), id = 7, from = "FRA", to = "JFK"),
            entry(LocalDate.of(2026, 4, 4), id = 2, from = "MUC", to = "DEN")
        )
        assertEquals(
            RouteDiscovery("MUC", "DEN", LocalDate.of(2026, 4, 4)),
            DiscoveryRoutes.firstFlown(entries).first { it.to == "DEN" }
        )
    }

    @Test
    fun firstFlownInYear_onlyKeepsEntriesOfThatYear() {
        val entries = listOf(
            entry(LocalDate.of(2025, 5, 5), from = "BER", to = "CGN"),
            entry(LocalDate.of(2026, 5, 5), from = "MUC", to = "DEN"),
            entry(LocalDate.of(2026, 6, 5), from = "FRA", to = "JFK")
        )
        val discoveries = DiscoveryRoutes.firstFlownInYear(entries, 2026)
        assertEquals(2, discoveries.size)
        assertTrue(discoveries.all { it.date.year == 2026 })
    }

    @Test
    fun byMonth_ordersMonthsAndEntriesDescending() {
        val entries = listOf(
            entry(LocalDate.of(2026, 1, 20), from = "A", to = "B"),
            entry(LocalDate.of(2026, 3, 2), from = "C", to = "D"),
            entry(LocalDate.of(2026, 3, 18), from = "E", to = "F"),
            entry(LocalDate.of(2026, 3, 9), from = "G", to = "H")
        )
        val months = DiscoveryRoutes.byMonth(entries, 2026)
        assertEquals(
            listOf(YearMonth.of(2026, 3), YearMonth.of(2026, 1)),
            months.map { it.month }
        )
        assertEquals(
            listOf("E", "G", "C"),
            months.first().routes.map { it.from }
        )
    }

    @Test
    fun byMonth_returnsEmptyListForYearWithoutDiscoveries() {
        val entries = listOf(entry(LocalDate.of(2025, 5, 5)))
        assertTrue(DiscoveryRoutes.byMonth(entries, 2026).isEmpty())
    }

    @Test
    fun firstFlown_carriesAircraftOfEarliestFlight() {
        val entries = listOf(
            entry(
                LocalDate.of(2025, 2, 2), from = "MUC", to = "DEN",
                aircraftType = "A320", registration = "D-ABXA"
            ),
            entry(
                LocalDate.of(2026, 2, 2), from = "MUC", to = "DEN",
                aircraftType = "A321", registration = "D-ABXB"
            )
        )
        assertEquals(
            RouteDiscovery("MUC", "DEN", LocalDate.of(2025, 2, 2), "A320", "D-ABXA"),
            DiscoveryRoutes.firstFlown(entries).single()
        )
    }

    @Test
    fun firstFlown_blankAircraftFieldsBecomeNull() {
        val entries = listOf(
            entry(
                LocalDate.of(2026, 2, 2),
                aircraftType = "  ", registration = " D-ABXB "
            )
        )
        val discovery = DiscoveryRoutes.firstFlown(entries).single()
        assertEquals(null, discovery.aircraftType)
        assertEquals("D-ABXB", discovery.registration)
    }

    @Test
    fun firstFlown_carriesAirlineOfEarliestFlight() {
        val entries = listOf(
            entry(
                LocalDate.of(2025, 1, 1), from = "MUC", to = "DEN",
                airline = " LH "
            ),
            entry(
                LocalDate.of(2026, 1, 1), from = "MUC", to = "DEN",
                airline = "AF"
            )
        )
        assertEquals("LH", DiscoveryRoutes.firstFlown(entries).single().airline)
    }

    @Test
    fun firstFlown_blankAirlineBecomesNull() {
        val entries = listOf(entry(LocalDate.of(2026, 2, 2), airline = "   "))
        assertEquals(null, DiscoveryRoutes.firstFlown(entries).single().airline)
    }
}
