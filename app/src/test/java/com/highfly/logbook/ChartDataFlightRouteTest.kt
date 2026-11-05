package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ChartDataFlightRouteTest {

    private fun entry(
        from: String,
        to: String,
        distanceKm: Int? = null,
        date: LocalDate = LocalDate.of(2026, 1, 1)
    ) = LogbookEntry(
        date = date,
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
        km: Int,
        date: LocalDate = LocalDate.of(2026, 1, 1)
    ) = LogbookEntry(
        date = date,
        fromAirport = from,
        toAirport = to,
        distanceKm = km,
        flightMinutes = minutes
    )

    @Test
    fun flightRouteBars_byDistanceMergesEqualRoundTrips() {
        // Hin- und Rueckflug mit denselben Daten (Distanz und Dauer gleich):
        // ein Balken "MUC-LAX-MUC" mit der Summe beider Richtungen.
        val entries = listOf(
            timed("MUC", "LAX", minutes = 660, km = 8900, date = LocalDate.of(2026, 1, 1)),
            timed("LAX", "MUC", minutes = 660, km = 8900, date = LocalDate.of(2026, 1, 5))
        )

        val bars = ChartData.flightRouteBars(entries, ChartData.FlightRouteSort.ENTFERNUNG)

        assertEquals(listOf("MUC-LAX-MUC"), bars.map { it.label })
        assertEquals(listOf(17800), bars.map { it.count })
    }

    @Test
    fun flightRouteBars_byDurationMergesEqualRoundTrips() {
        // Dieselbe Zusammenfassung im Dauer-Modus.
        val entries = listOf(
            timed("MUC", "LAX", minutes = 660, km = 8900, date = LocalDate.of(2026, 1, 1)),
            timed("LAX", "MUC", minutes = 660, km = 8900, date = LocalDate.of(2026, 1, 5))
        )

        val bars = ChartData.flightRouteBars(entries, ChartData.FlightRouteSort.DAUER)

        assertEquals(listOf("MUC-LAX-MUC"), bars.map { it.label })
        assertEquals(listOf(1320), bars.map { it.count })
    }

    @Test
    fun flightRouteBars_mergedLabelFollowsTheEarliestFlight() {
        // Fliegt die Gegenrichtung zuerst, dreht sich die Beschriftung:
        // "LAX-MUC-LAX" statt "MUC-LAX-MUC".
        val entries = listOf(
            timed("LAX", "MUC", minutes = 660, km = 8900, date = LocalDate.of(2026, 1, 1)),
            timed("MUC", "LAX", minutes = 660, km = 8900, date = LocalDate.of(2026, 1, 5))
        )

        val bars = ChartData.flightRouteBars(entries, ChartData.FlightRouteSort.ENTFERNUNG)

        assertEquals(listOf("LAX-MUC-LAX"), bars.map { it.label })
        assertEquals(listOf(17800), bars.map { it.count })
    }

    @Test
    fun flightRouteBars_differentDataStaysSeparate() {
        // Unterscheiden sich Distanz oder Dauer der beiden Richtungen,
        // bleiben Hin- und Rueckflug eigene Balken.
        val entries = listOf(
            timed("MUC", "LAX", minutes = 660, km = 8900),
            timed("LAX", "MUC", minutes = 700, km = 8900),
            timed("MUC", "ZRH", minutes = 60, km = 265)
        )

        val bars = ChartData.flightRouteBars(entries, ChartData.FlightRouteSort.DAUER)

        assertEquals(listOf("LAX-MUC", "MUC-LAX", "MUC-ZRH"), bars.map { it.label })
        assertEquals(listOf(700, 660, 60), bars.map { it.count })
    }

    @Test
    fun flightRouteBars_byCountDoesNotMerge() {
        // Nach Anzahl zaehlen Hin- und Rueckflug getrennt, auch bei gleichen Daten.
        val entries = listOf(
            timed("MUC", "LAX", minutes = 660, km = 8900),
            timed("LAX", "MUC", minutes = 660, km = 8900)
        )

        val bars = ChartData.flightRouteBars(entries, ChartData.FlightRouteSort.ANZAHL)

        assertEquals(listOf("LAX-MUC", "MUC-LAX"), bars.map { it.label })
        assertEquals(listOf(1, 1), bars.map { it.count })
    }

    @Test
    fun flightRouteStats_mergedRouteCountsBothDirections() {
        val entries = listOf(
            timed("MUC", "LAX", minutes = 660, km = 8900, date = LocalDate.of(2026, 1, 1)),
            timed("LAX", "MUC", minutes = 660, km = 8900, date = LocalDate.of(2026, 1, 5))
        )

        val stats = ChartData.flightRouteStats(entries, "MUC-LAX-MUC")

        assertEquals(2, stats.flights)
        assertEquals(1320, stats.minutes)
        assertEquals(17800, stats.km)
    }

    @Test
    fun flightRouteRank_mergedRouteUsesTheCombinedValue() {
        // Der zusammengefasste Balken MUC-LAX-MUC (17800 km) steht vor FRA-JFK.
        val entries = listOf(
            timed("MUC", "LAX", minutes = 660, km = 8900, date = LocalDate.of(2026, 1, 1)),
            timed("LAX", "MUC", minutes = 660, km = 8900, date = LocalDate.of(2026, 1, 5)),
            timed("FRA", "JFK", minutes = 540, km = 6200)
        )

        assertEquals(
            1,
            ChartData.flightRouteRank(entries, "MUC-LAX-MUC", ChartData.FlightRouteSort.ENTFERNUNG)
        )
        assertEquals(
            2,
            ChartData.flightRouteRank(entries, "FRA-JFK", ChartData.FlightRouteSort.ENTFERNUNG)
        )
        // Nach Anzahl zaehlt der Hin- und Rueckflug getrennt: je ein Flug.
        assertEquals(
            1,
            ChartData.flightRouteRank(entries, "MUC-LAX", ChartData.FlightRouteSort.ANZAHL)
        )
        assertEquals(
            1,
            ChartData.flightRouteRank(entries, "LAX-MUC", ChartData.FlightRouteSort.ANZAHL)
        )
    }
}
