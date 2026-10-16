package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ChartDataFlightRouteTest {

    private fun entry(
        from: String,
        to: String,
        distanceKm: Int? = null
    ) = LogbookEntry(
        date = LocalDate.of(2026, 1, 1),
        fromAirport = from,
        toAirport = to,
        distanceKm = distanceKm
    )

    @Test
    fun flightRouteBars_byCountOrdersByNumberOfFlights() {
        // FRA-JFK wird oefter geflogen, ist aber kuerzer: nach Anzahl steht die
        // Strecke vorne, obwohl FRA-SIN weiter fliegt.
        val entries = listOf(
            entry("FRA", "JFK", distanceKm = 2000),
            entry("FRA", "JFK", distanceKm = 2000),
            entry("FRA", "JFK", distanceKm = 2000),
            entry("FRA", "SIN", distanceKm = 10200)
        )

        val bars = ChartData.flightRouteBars(entries, ChartData.FlightRouteSort.ANZAHL)

        assertEquals(listOf("FRA-JFK", "FRA-SIN"), bars.map { it.label })
        assertEquals(listOf(3, 1), bars.map { it.count })
        assertEquals(listOf(null, null), bars.map { it.countLabel })
    }

    @Test
    fun flightRouteBars_byDistanceOrdersByKilometres() {
        // Nach Distanz dreht sich die Reihenfolge: die seltenere, aber weiter
        // fliegende Strecke steht vorne.
        val entries = listOf(
            entry("FRA", "JFK", distanceKm = 2000),
            entry("FRA", "JFK", distanceKm = 2000),
            entry("FRA", "JFK", distanceKm = 2000),
            entry("FRA", "SIN", distanceKm = 10200)
        )

        val bars = ChartData.flightRouteBars(entries, ChartData.FlightRouteSort.ENTFERNUNG)

        assertEquals(listOf("FRA-SIN", "FRA-JFK"), bars.map { it.label })
        assertEquals(listOf(10200, 6000), bars.map { it.count })
        assertNull(bars[0].countLabel)
    }

    @Test
    fun flightRouteBars_byDistanceKeepsRoutesWithoutDistance() {
        // Ohne erfasste Entfernung zaehlt die Strecke mit null Kilometern,
        // bleibt aber sichtbar - sie wurde ja geflogen.
        val entries = listOf(
            entry("FRA", "HAM", distanceKm = null),
            entry("FRA", "JFK", distanceKm = 6200)
        )

        val bars = ChartData.flightRouteBars(entries, ChartData.FlightRouteSort.ENTFERNUNG)

        assertEquals(listOf("FRA-JFK", "FRA-HAM"), bars.map { it.label })
        assertEquals(listOf(6200, 0), bars.map { it.count })
    }

    @Test
    fun flightRouteBars_byDurationOrdersBySummedMinutes() {
        // Nach Dauer steht die Strecke vorne, deren Fluege zusammen am
        // laengsten dauern: der eine lange Flug schlaegt drei kurze.
        val entries = listOf(
            timed("FRA", "JFK", minutes = 200, km = 2000),
            timed("FRA", "JFK", minutes = 200, km = 2000),
            timed("FRA", "JFK", minutes = 200, km = 2000),
            timed("FRA", "SIN", minutes = 720, km = 10200)
        )

        val bars = ChartData.flightRouteBars(entries, ChartData.FlightRouteSort.DAUER)

        assertEquals(listOf("FRA-SIN", "FRA-JFK"), bars.map { it.label })
        assertEquals(listOf(720, 600), bars.map { it.count })
    }

    @Test
    fun flightRouteBars_byDurationKeepsRoutesWithoutDuration() {
        // Ohne erfasste Flugzeit zaehlt die Strecke mit null Minuten, bleibt
        // aber sichtbar - sie wurde ja geflogen.
        val entries = listOf(
            entry("FRA", "HAM"),
            timed("FRA", "JFK", minutes = 120, km = 2000)
        )

        val bars = ChartData.flightRouteBars(entries, ChartData.FlightRouteSort.DAUER)

        assertEquals(listOf("FRA-JFK", "FRA-HAM"), bars.map { it.label })
        assertEquals(listOf(120, 0), bars.map { it.count })
    }

    @Test
    fun flightRouteStats_sumsFlightsMinutesAndKilometres() {
        val entries = listOf(
            timed("FRA", "JFK", minutes = 540, km = 6200),
            timed("FRA", "JFK", minutes = 555, km = 6250),
            timed("FRA", "SIN", minutes = 720, km = 10200)
        )

        val stats = ChartData.flightRouteStats(entries, "FRA-JFK")

        assertEquals(2, stats.flights)
        assertEquals(1095, stats.minutes)
        assertEquals(12450, stats.km)
    }

    @Test
    fun flightRouteStats_missingValuesCountAsZero() {
        // Ohne Dauer und Entfernung bleibt die Strecke mit null stehen; die
        // Zahl der Fluege zaehlt trotzdem.
        val entries = listOf(
            entry("FRA", "HAM"),
            entry("FRA", "HAM", distanceKm = 400)
        )

        val stats = ChartData.flightRouteStats(entries, "FRA-HAM")

        assertEquals(2, stats.flights)
        assertEquals(0, stats.minutes)
        assertEquals(400, stats.km)
    }

    @Test
    fun flightRouteStats_unknownRouteIsEmpty() {
        val entries = listOf(entry("FRA", "JFK", distanceKm = 6200))

        val stats = ChartData.flightRouteStats(entries, "FRA-SIN")

        assertEquals(0, stats.flights)
        assertEquals(0, stats.minutes)
        assertEquals(0, stats.km)
    }

    @Test
    fun flightRouteRank_byCountSharesPlaceForEqualValues() {
        val entries = listOf(
            entry("FRA", "JFK"),
            entry("FRA", "JFK"),
            entry("FRA", "SIN"),
            entry("FRA", "HAM")
        )

        assertEquals(1, ChartData.flightRouteRank(entries, "FRA-JFK", ChartData.FlightRouteSort.ANZAHL))
        // SIN und HAM teilen sich den zweiten Platz.
        assertEquals(2, ChartData.flightRouteRank(entries, "FRA-SIN", ChartData.FlightRouteSort.ANZAHL))
        assertEquals(2, ChartData.flightRouteRank(entries, "FRA-HAM", ChartData.FlightRouteSort.ANZAHL))
    }

    @Test
    fun flightRouteRank_byDistanceFollowsTheKilometres() {
        val entries = listOf(
            entry("FRA", "JFK", distanceKm = 2000),
            entry("FRA", "JFK", distanceKm = 2000),
            entry("FRA", "JFK", distanceKm = 2000),
            entry("FRA", "SIN", distanceKm = 10200)
        )

        // Nach Distanz steht die seltenere, aber weitere Strecke vorne.
        assertEquals(1, ChartData.flightRouteRank(entries, "FRA-SIN", ChartData.FlightRouteSort.ENTFERNUNG))
        assertEquals(2, ChartData.flightRouteRank(entries, "FRA-JFK", ChartData.FlightRouteSort.ENTFERNUNG))
        // Nach Anzahl dreht sich die Reihenfolge.
        assertEquals(1, ChartData.flightRouteRank(entries, "FRA-JFK", ChartData.FlightRouteSort.ANZAHL))
        assertEquals(2, ChartData.flightRouteRank(entries, "FRA-SIN", ChartData.FlightRouteSort.ANZAHL))
    }

    @Test
    fun flightRouteRank_unknownRouteIsZero() {
        val entries = listOf(entry("FRA", "JFK", distanceKm = 6200))

        assertEquals(0, ChartData.flightRouteRank(entries, "FRA-SIN", ChartData.FlightRouteSort.ANZAHL))
    }

    @Test
    fun flightRouteRank_byDurationFollowsTheFlightTime() {
        val entries = listOf(
            timed("FRA", "JFK", minutes = 200, km = 2000),
            timed("FRA", "JFK", minutes = 200, km = 2000),
            timed("FRA", "JFK", minutes = 200, km = 2000),
            timed("FRA", "SIN", minutes = 720, km = 10200)
        )

        // Nach Dauer steht die seltene, aber lange Strecke vorne; nach Anzahl
        // dreht sich die Reihenfolge.
        assertEquals(1, ChartData.flightRouteRank(entries, "FRA-SIN", ChartData.FlightRouteSort.DAUER))
        assertEquals(2, ChartData.flightRouteRank(entries, "FRA-JFK", ChartData.FlightRouteSort.DAUER))
        assertEquals(1, ChartData.flightRouteRank(entries, "FRA-JFK", ChartData.FlightRouteSort.ANZAHL))
    }

    private fun timed(
        from: String,
        to: String,
        minutes: Int,
        km: Int
    ) = LogbookEntry(
        date = LocalDate.of(2026, 1, 1),
        fromAirport = from,
        toAirport = to,
        distanceKm = km,
        flightMinutes = minutes
    )
}
