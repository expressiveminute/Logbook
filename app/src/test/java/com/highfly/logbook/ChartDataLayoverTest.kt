package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ChartDataLayoverTest {

    private fun entry(
        to: String,
        date: LocalDate,
        layover: Boolean = true
    ) = LogbookEntry(date = date, fromAirport = "FRA", toAirport = to, layover = layover)

    @Test
    fun newestLayover_picksTheMostRecentOne() {
        val entries = listOf(
            entry("SIN", LocalDate.of(2019, 8, 4)),
            entry("DXB", LocalDate.of(2026, 3, 12)),
            entry("IST", LocalDate.of(2024, 11, 2))
        )

        val newest = ChartData.newestLayover(entries)!!

        assertEquals("DXB", newest.airport)
        assertEquals(LocalDate.of(2026, 3, 12), newest.date)
    }

    @Test
    fun oldestLayover_picksTheOneLongestAgo() {
        val entries = listOf(
            entry("SIN", LocalDate.of(2019, 8, 4)),
            entry("DXB", LocalDate.of(2026, 3, 12)),
            entry("IST", LocalDate.of(2024, 11, 2))
        )

        val oldest = ChartData.oldestLayover(entries)!!

        assertEquals("SIN", oldest.airport)
        assertEquals(LocalDate.of(2019, 8, 4), oldest.date)
    }

    @Test
    fun layoverExtremes_ignoreEntriesWithoutLayover() {
        val entries = listOf(
            entry("SFO", LocalDate.of(2018, 1, 5), layover = false),
            entry("CDG", LocalDate.of(2025, 6, 6))
        )

        assertEquals("CDG", ChartData.newestLayover(entries)?.airport)
        assertEquals("CDG", ChartData.oldestLayover(entries)?.airport)
    }

    @Test
    fun layoverExtremes_ignoreLayoverWithoutAirport() {
        val entries = listOf(
            entry("", LocalDate.of(2018, 1, 5)),
            entry("   ", LocalDate.of(2017, 1, 5)),
            entry("DOH", LocalDate.of(2025, 6, 6))
        )

        assertEquals("DOH", ChartData.newestLayover(entries)?.airport)
        assertEquals("DOH", ChartData.oldestLayover(entries)?.airport)
    }

    @Test
    fun layoverExtreme_normalizesTheAirportCode() {
        val entries = listOf(entry(" dxb ", LocalDate.of(2026, 3, 12)))

        assertEquals("DXB", ChartData.newestLayover(entries)?.airport)
        assertEquals("DXB", ChartData.oldestLayover(entries)?.airport)
    }

    @Test
    fun oldestLayover_ignoresTheFirstVisitOfAnOftenUsedAirport() {
        // SIN war zwar am Anfang das erste Mal dort, war seither aber wieder
        // dort. Das älteste Layover ist deshalb IST, denn dort war man
        // am längsten nicht mehr.
        val entries = listOf(
            entry("SIN", LocalDate.of(2019, 8, 4)),
            entry("SIN", LocalDate.of(2026, 1, 10)),
            entry("IST", LocalDate.of(2024, 11, 2))
        )

        val oldest = ChartData.oldestLayover(entries)!!

        assertEquals("IST", oldest.airport)
        assertEquals(LocalDate.of(2024, 11, 2), oldest.date)
    }

    @Test
    fun newestLayover_takesTheLastVisitOfAnOftenUsedAirport() {
        val entries = listOf(
            entry("SIN", LocalDate.of(2019, 8, 4)),
            entry("IST", LocalDate.of(2024, 11, 2)),
            entry("SIN", LocalDate.of(2026, 1, 10))
        )

        val newest = ChartData.newestLayover(entries)!!

        assertEquals("SIN", newest.airport)
        assertEquals(LocalDate.of(2026, 1, 10), newest.date)
    }

    @Test
    fun layoverExtremes_countAnAirportOnlyOnce() {
        // " dxb " und "DXB" sind derselbe Flughafen. Ohne Zusammenfassung
        // entstünden zwei Kacheln für denselben Ort.
        val entries = listOf(
            entry(" dxb ", LocalDate.of(2026, 3, 12)),
            entry("DXB", LocalDate.of(2025, 1, 5))
        )

        val newest = ChartData.newestLayover(entries)!!
        val oldest = ChartData.oldestLayover(entries)!!

        assertEquals("DXB", newest.airport)
        assertEquals("DXB", oldest.airport)
        assertEquals(LocalDate.of(2026, 3, 12), newest.date)
        assertEquals(LocalDate.of(2026, 3, 12), oldest.date)
    }

    @Test
    fun oldestLayover_usesTheLastVisitEvenForASingleAirport() {
        val entries = listOf(
            entry("DOH", LocalDate.of(2015, 2, 1)),
            entry("DOH", LocalDate.of(2024, 9, 9))
        )

        val oldest = ChartData.oldestLayover(entries)!!

        assertEquals("DOH", oldest.airport)
        assertEquals(LocalDate.of(2024, 9, 9), oldest.date)
    }

    @Test
    fun layoverExtremes_withoutLayoverAreNull() {
        val entries = listOf(entry("SFO", LocalDate.of(2018, 1, 5), layover = false))

        assertNull(ChartData.newestLayover(entries))
        assertNull(ChartData.oldestLayover(entries))
        assertNull(ChartData.newestLayover(emptyList()))
        assertNull(ChartData.oldestLayover(emptyList()))
    }
}
