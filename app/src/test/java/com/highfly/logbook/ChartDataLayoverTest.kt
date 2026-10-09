package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ChartDataLayoverTest {

    private fun entry(
        to: String,
        date: LocalDate,
        layover: Boolean = true,
        layoverHours: Int? = null
    ) = LogbookEntry(
        date = date,
        fromAirport = "FRA",
        toAirport = to,
        layover = layover,
        layoverHours = layoverHours
    )

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

    @Test
    fun layoverHistory_ordersOldestFirst() {
        val entries = listOf(
            entry("JFK", LocalDate.of(2026, 3, 12)),
            entry("JFK", LocalDate.of(2021, 6, 11)),
            entry("JFK", LocalDate.of(2023, 5, 17))
        )

        val history = ChartData.layoverHistory(entries, "JFK")

        assertEquals(
            listOf(
                LocalDate.of(2021, 6, 11),
                LocalDate.of(2023, 5, 17),
                LocalDate.of(2026, 3, 12)
            ),
            history.map { it.date }
        )
    }

    @Test
    fun layoverHistory_ignoresOtherAirportsAndFlightsWithoutLayover() {
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11)),
            entry("JFK", LocalDate.of(2020, 1, 1), layover = false),
            entry("DXB", LocalDate.of(2024, 11, 13)),
            entry("", LocalDate.of(2019, 5, 5))
        )

        assertEquals(
            listOf(LocalDate.of(2021, 6, 11)),
            ChartData.layoverHistory(entries, "JFK").map { it.date }
        )
    }

    @Test
    fun layoverHistory_normalizesTheAirportCode() {
        val entries = listOf(
            entry(" jfk ", LocalDate.of(2021, 6, 11)),
            entry("JFK", LocalDate.of(2021, 6, 11))
        )

        assertEquals(2, ChartData.layoverHistory(entries, "jfk").size)
        assertEquals(2, ChartData.layoverHistory(entries, "JFK").size)
    }

    @Test
    fun layoverHistory_unknownAirportAndEmptyListAreEmpty() {
        assertEquals(emptyList<LogbookEntry>(), ChartData.layoverHistory(emptyList(), "JFK"))
        assertEquals(
            emptyList<LogbookEntry>(),
            ChartData.layoverHistory(listOf(entry("SFO", LocalDate.of(2018, 1, 5))), "JFK")
        )
    }

    @Test
    fun layoverHistoryNewestFirst_putsTheMostRecentLayoverOnTop() {
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11)),
            entry("JFK", LocalDate.of(2026, 3, 12)),
            entry("JFK", LocalDate.of(2023, 5, 17))
        )

        val history = ChartData.layoverHistoryNewestFirst(entries, "JFK")

        assertEquals(
            listOf(
                LocalDate.of(2026, 3, 12),
                LocalDate.of(2023, 5, 17),
                LocalDate.of(2021, 6, 11)
            ),
            history.map { it.date }
        )
    }

    @Test
    fun layoverHistoryNewestFirst_ignoresOtherAirportsAndFlightsWithoutLayover() {
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11)),
            entry("JFK", LocalDate.of(2026, 3, 12), layover = false),
            entry("DXB", LocalDate.of(2026, 3, 12))
        )

        assertEquals(
            listOf(LocalDate.of(2021, 6, 11)),
            ChartData.layoverHistoryNewestFirst(entries, "JFK").map { it.date }
        )
    }

    @Test
    fun layoverHoursTotal_sumsTheLengthsOfTheLayovers() {
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11), layoverHours = 48),
            entry("JFK", LocalDate.of(2023, 5, 17), layoverHours = 24),
            entry("JFK", LocalDate.of(2026, 3, 12), layoverHours = 96)
        )

        assertEquals(168, ChartData.layoverHoursTotal(entries, "JFK"))
    }

    @Test
    fun layoverHoursTotal_countsEntriesWithoutLengthAsZero() {
        // Ohne Angabe ist die Zeit nicht erfasst, nicht etwa null Stunden -
        // das Ergebnis ändert sich dadurch nicht.
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11), layoverHours = 48),
            entry("JFK", LocalDate.of(2023, 5, 17))
        )

        assertEquals(48, ChartData.layoverHoursTotal(entries, "JFK"))
    }

    @Test
    fun layoverHoursTotal_ignoresOtherAirportsAndFlightsWithoutLayover() {
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11), layoverHours = 48),
            entry("DXB", LocalDate.of(2024, 11, 13), layoverHours = 72),
            entry("JFK", LocalDate.of(2025, 1, 5), layover = false, layoverHours = 72)
        )

        assertEquals(48, ChartData.layoverHoursTotal(entries, "JFK"))
    }

    @Test
    fun layoverHoursTotal_normalizesTheAirportCode() {
        val entries = listOf(
            entry(" jfk ", LocalDate.of(2021, 6, 11), layoverHours = 48),
            entry("JFK", LocalDate.of(2023, 5, 17), layoverHours = 24)
        )

        assertEquals(72, ChartData.layoverHoursTotal(entries, "jfk"))
        assertEquals(72, ChartData.layoverHoursTotal(entries, "JFK"))
    }

    @Test
    fun layoverHoursTotal_withoutLayoversIsZero() {
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11), layover = false, layoverHours = 48)
        )

        assertEquals(0, ChartData.layoverHoursTotal(entries, "JFK"))
        assertEquals(0, ChartData.layoverHoursTotal(emptyList(), "JFK"))
    }

    @Test
    fun layoverHoursByAirport_sumsTheLengthsPerAirport() {
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11), layoverHours = 48),
            entry("JFK", LocalDate.of(2023, 5, 17), layoverHours = 24),
            entry("DXB", LocalDate.of(2024, 11, 13), layoverHours = 72)
        )

        assertEquals(
            mapOf("JFK" to 72, "DXB" to 72),
            ChartData.layoverHoursByAirport(entries)
        )
    }

    @Test
    fun layoverHoursByAirport_ignoresFlightsWithoutLayoverAndBlankAirports() {
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11), layoverHours = 48),
            entry("SFO", LocalDate.of(2021, 6, 11), layover = false, layoverHours = 48),
            entry("", LocalDate.of(2021, 6, 11), layoverHours = 48)
        )

        assertEquals(mapOf("JFK" to 48), ChartData.layoverHoursByAirport(entries))
    }

    @Test
    fun layoverHoursByAirport_countsEntriesWithoutLengthAsZero() {
        // Ohne Angabe ist die Zeit nicht erfasst. Der Flughafen bleibt trotzdem
        // Teil der Auswertung - mit "0 h", nicht mit einem fehlenden Balken.
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11), layoverHours = 48),
            entry("JFK", LocalDate.of(2023, 5, 17)),
            entry("DOH", LocalDate.of(2023, 5, 17))
        )

        assertEquals(
            mapOf("JFK" to 48, "DOH" to 0),
            ChartData.layoverHoursByAirport(entries)
        )
    }

    @Test
    fun layoverBars_countsTheVisitsPerAirport() {
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11), layoverHours = 48),
            entry("JFK", LocalDate.of(2023, 5, 17), layoverHours = 24),
            entry("DXB", LocalDate.of(2024, 11, 13), layoverHours = 72),
            entry("SFO", LocalDate.of(2020, 1, 5), layover = false)
        )

        val bars = ChartData.layoverBars(entries, ChartData.LayoverSort.ANZAHL)

        assertEquals(listOf("JFK", "DXB"), bars.map { it.label })
        assertEquals(listOf(2, 1), bars.map { it.count })
        assertEquals(listOf(null, null), bars.map { it.countLabel })
    }

    @Test
    fun layoverBars_sortsByDurationOfTheLayovers() {
        // Geordnet wird nach Stunden, die Beschriftung in Tagen haengt an
        // barChart und braucht deshalb hier nicht die Werte selbst.
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11), layoverHours = 48),
            entry("DXB", LocalDate.of(2024, 11, 13), layoverHours = 72),
            entry("DXB", LocalDate.of(2023, 5, 17), layoverHours = 12)
        )

        val bars = ChartData.layoverBars(entries, ChartData.LayoverSort.DAUER)

        assertEquals(listOf("DXB", "JFK"), bars.map { it.label })
        assertEquals(listOf(84, 48), bars.map { it.count })
        assertEquals(listOf(null, null), bars.map { it.countLabel })
    }

    @Test
    fun layoverBars_keepsTheLastVisitSubLabelInBothModes() {
        val entries = listOf(
            entry("DXB", LocalDate.of(2023, 5, 17), layoverHours = 12),
            entry("DXB", LocalDate.of(2024, 11, 13), layoverHours = 72)
        )
        val subLabels = mapOf("DXB" to "Zuletzt: 13.11.2024")

        for (sort in ChartData.LayoverSort.entries) {
            val bars = ChartData.layoverBars(entries, sort, subLabels = subLabels)
            assertEquals(listOf("Zuletzt: 13.11.2024"), bars.map { it.subLabel })
        }
    }

    @Test
    fun layoverBars_withoutLayoversIsEmpty() {
        val entries = listOf(entry("SFO", LocalDate.of(2018, 1, 5), layover = false))

        for (sort in ChartData.LayoverSort.entries) {
            assertEquals(emptyList<ChartData.Bar>(), ChartData.layoverBars(entries, sort))
        }
    }

    @Test
    fun splitLayoverZeroDuration_separatesAirportsWithoutRecordedTime() {
        // Im Dauer-Modus haben Flughäfen ohne erfasste Zeit den Wert 0. Sie
        // gehören nicht in die Rangliste, sondern in die Null-Gruppe.
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11), layoverHours = 48),
            entry("DOH", LocalDate.of(2023, 5, 17)),
            entry("DXB", LocalDate.of(2024, 11, 13), layoverHours = 72)
        )
        val bars = ChartData.layoverBars(entries, ChartData.LayoverSort.DAUER)

        val (ranked, zero) = ChartData.splitLayoverZeroDuration(bars)

        assertEquals(listOf("DXB", "JFK"), ranked.map { it.label })
        assertEquals(listOf("DOH"), zero.map { it.label })
        assertEquals(listOf(0), zero.map { it.count })
    }

    @Test
    fun splitLayoverZeroDuration_keepsCountModeUntouched() {
        // Im Anzahl-Modus gibt es keine Null-Balken: jeder Flughafen wurde
        // mindestens einmal besucht, die Null-Gruppe bleibt leer.
        val entries = listOf(
            entry("JFK", LocalDate.of(2021, 6, 11)),
            entry("DOH", LocalDate.of(2023, 5, 17))
        )
        val bars = ChartData.layoverBars(entries, ChartData.LayoverSort.ANZAHL)

        val (ranked, zero) = ChartData.splitLayoverZeroDuration(bars)

        assertEquals(bars, ranked)
        assertEquals(emptyList<ChartData.Bar>(), zero)
    }
}
