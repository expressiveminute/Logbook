package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ChartDataTimeTest {

    private fun entry(
        from: String,
        to: String,
        minutes: Int?,
        date: LocalDate = LocalDate.of(2026, 9, 7)
    ) = LogbookEntry(date = date, fromAirport = from, toAirport = to, flightMinutes = minutes)

    @Test
    fun histogram_countsFlightsPerFullHour() {
        val bars = ChartData.durationHistogram(
            listOf(
                entry("FRA", "JFK", 180),
                entry("FRA", "JFK", 185),
                entry("FRA", "JFK", 170),
                entry("MUC", "CDG", 90)
            )
        )

        assertEquals(20, bars.size)
        assertEquals(3, bars[2].count)
        assertEquals("3", bars[2].label)
        assertEquals(1, bars[1].count)
        assertEquals(0, bars[0].count)
        assertEquals("20+", bars[19].label)
    }

    @Test
    fun histogram_roundsAndCollectsEverythingFromTwentyHours() {
        val bars = ChartData.durationHistogram(
            listOf(
                entry("FRA", "JFK", 20),
                entry("FRA", "JFK", 40),
                entry("FRA", "JFK", 1170),
                entry("FRA", "JFK", 1500)
            )
        )

        assertEquals(2, bars[0].count)
        assertEquals(0, bars[1].count)
        assertEquals(2, bars[19].count)
    }

    @Test
    fun histogram_ignoresFlightsWithoutDuration() {
        val bars = ChartData.durationHistogram(
            listOf(
                entry("FRA", "JFK", null),
                entry("FRA", "JFK", 0),
                entry("FRA", "JFK", 120)
            )
        )

        assertEquals(1, bars.sumOf { it.count })
        assertEquals(1, bars[1].count)
    }

    @Test
    fun longestFlightBar_picksTheLongestFlightWithRouteAndDate() {
        val entries = listOf(
            entry("MUC", "CDG", 90, LocalDate.of(2026, 1, 2)),
            entry("FRA", "JFK", 480, LocalDate.of(2026, 3, 4)),
            entry("FRA", "CDG", 95, LocalDate.of(2026, 3, 5))
        )

        val bar = ChartData.longestFlightBar(entries)

        assertEquals("FRA-JFK", bar?.label)
        assertEquals(480, bar?.count)
        assertEquals("8:00 h", bar?.countLabel)
        assertEquals("04.03.2026", bar?.subLabel)
    }

    @Test
    fun shortestFlightBar_picksTheShortestFlight() {
        val entries = listOf(
            entry("FRA", "JFK", 480),
            entry("MUC", "CDG", 45),
            entry("FRA", "CDG", 95)
        )

        val bar = ChartData.shortestFlightBar(entries)

        assertEquals("MUC-CDG", bar?.label)
        assertEquals(45, bar?.count)
        assertEquals("45 min", bar?.countLabel)
    }

    @Test
    fun extremeFlightBar_prefersTheNewerFlightOnATie() {
        val entries = listOf(
            entry("FRA", "JFK", 300, LocalDate.of(2026, 1, 2)),
            entry("BER", "MUC", 300, LocalDate.of(2026, 5, 6))
        )

        assertEquals("BER-MUC", ChartData.longestFlightBar(entries)?.label)
        assertEquals("FRA-JFK", ChartData.shortestFlightBar(entries)?.label)
    }

    @Test
    fun extremeFlightBar_isNullWithoutAnyDuration() {
        val entries = listOf(entry("FRA", "JFK", null), entry("FRA", "JFK", 0))

        assertEquals(null, ChartData.longestFlightBar(entries))
        assertEquals(null, ChartData.shortestFlightBar(entries))
    }

    @Test
    fun averageDurationBar_averagesEverySingleFlight() {
        val entries = listOf(
            entry("FRA", "JFK", 420),
            entry("FRA", "JFK", 480),
            entry("MUC", "CDG", 60),
            entry("BER", "HAM", null)
        )

        val bar = ChartData.averageDurationBar(entries, "Alle Flüge") { "$it" }

        assertEquals("Alle Flüge", bar?.label)
        assertEquals(320, bar?.count)
        assertEquals("5:20 h", bar?.countLabel)
        assertEquals("3", bar?.subLabel)
    }

    @Test
    fun averageDurationBar_isNullWithoutAnyDuration() {
        assertEquals(null, ChartData.averageDurationBar(listOf(entry("FRA", "JFK", null)), "x") { "$it" })
    }

    @Test
    fun durationText_usesMinutesBelowOneHour() {
        assertEquals("45 min", ChartData.durationText(45))
        assertEquals("1:00 h", ChartData.durationText(60))
        assertEquals("12:05 h", ChartData.durationText(725))
    }
}
