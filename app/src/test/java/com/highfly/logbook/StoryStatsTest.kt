package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StoryStatsTest {

    private val today = LocalDate.of(2026, 10, 24)

    private val catalog = AircraftSeats.Catalog(
        seats = mapOf("A320" to 186, "B738" to 189),
        aliases = mapOf("320" to "A320", "738" to "B738")
    )

    private fun entry(
        from: String,
        to: String,
        date: LocalDate,
        flightType: String? = "Privat",
        classType: String? = null,
        aircraftType: String? = null,
        distanceKm: Int? = null,
        flightMinutes: Int? = null,
        layover: Boolean = false,
        layoverHours: Int? = null,
        travelBuddy: String? = null,
        fromCountry: String? = null,
        toCountry: String? = null
    ) = LogbookEntry(
        date = date,
        flightType = flightType,
        classType = classType,
        fromAirport = from,
        toAirport = to,
        aircraftType = aircraftType,
        distanceKm = distanceKm,
        flightMinutes = flightMinutes,
        layover = layover,
        layoverHours = layoverHours,
        travelBuddy = travelBuddy,
        fromCountry = fromCountry,
        toCountry = toCountry
    )

    private fun playStats(
        entries: List<LogbookEntry>,
        homebase: String? = "MUC",
        lookups: StoryStats.Lookups = StoryStats.Lookups()
    ) = StoryStats.playStats(
        entries = entries,
        seatsCatalog = catalog,
        loadFactorPercent = 80,
        homebase = homebase,
        today = today,
        lookups = lookups
    )

    @Test
    fun playStats_countsEveryEntryButOnlyRealFlightsForDistanceAndTime() {
        val entries = listOf(
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10), distanceKm = 950, flightMinutes = 120),
            entry("LHR", "MUC", LocalDate.of(2025, 6, 1), distanceKm = 950, flightMinutes = 115),
            entry("MUC", "HAM", LocalDate.of(2026, 3, 5), flightType = "Ground Transfer")
        )

        val stats = playStats(entries)

        assertEquals(3, stats.totalFlights)
        assertEquals(2, stats.ytdFlights)
        assertEquals(1900, stats.totalKm)
        assertEquals(235, stats.totalMinutes)
    }

    @Test
    fun playStats_topDestinationsExcludeHomebaseAndSplitByTravelType() {
        val entries = listOf(
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10)),
            entry("LHR", "MUC", LocalDate.of(2026, 1, 10)),
            entry("MUC", "LHR", LocalDate.of(2026, 2, 10)),
            entry("MUC", "HAM", LocalDate.of(2026, 3, 10), flightType = "On Duty"),
            entry("MUC", "HAM", LocalDate.of(2026, 4, 10), flightType = "On Duty"),
            entry("MUC", "HAM", LocalDate.of(2026, 5, 10), flightType = "On Duty")
        )

        val stats = playStats(entries)

        // MUC ist Homebase und landet als Ziel nie in der Liste.
        assertEquals(listOf("LHR"), stats.topDestinationsPrivate.map { it.code })
        assertEquals(2, stats.topDestinationsPrivate.first().flights)
        assertEquals(listOf("HAM"), stats.topDestinationsOnDuty.map { it.code })
        assertEquals(3, stats.topDestinationsOnDuty.first().flights)
    }

    @Test
    fun playStats_topCountriesTouchFromAndToButCountDomesticOnce() {
        val lookups = StoryStats.Lookups(
            countryOf = { code -> mapOf("MUC" to "DE", "LHR" to "GB", "HAM" to "DE")[code] },
            countryNameOf = { iso -> mapOf("DE" to "Deutschland", "GB" to "Vereinigtes Königreich")[iso] }
        )
        val entries = listOf(
            // MUC–LHR berührt DE und GB, LHR–MUC ebenso: DE 2, GB 2.
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10)),
            entry("LHR", "MUC", LocalDate.of(2026, 1, 11)),
            // Inlandsflug: zählt für DE nur einmal.
            entry("MUC", "HAM", LocalDate.of(2026, 2, 10))
        )

        // Ohne Homebase zählt jedes berührte Land.
        val stats = playStats(entries, homebase = null, lookups = lookups)

        val de = stats.topCountriesPrivate.first { it.iso2 == "DE" }
        assertEquals(3, de.flights)
        assertEquals("Deutschland", de.name)
        assertEquals(AirportData.flagEmoji("DE"), de.flag)
        val gb = stats.topCountriesPrivate.first { it.iso2 == "GB" }
        assertEquals(2, gb.flights)
    }

    @Test
    fun playStats_topCountriesExcludeHomebaseCountry() {
        val lookups = StoryStats.Lookups(
            countryOf = { code -> mapOf("MUC" to "DE", "LHR" to "GB", "HAM" to "DE")[code] },
            countryNameOf = { iso -> mapOf("DE" to "Deutschland", "GB" to "Vereinigtes Königreich")[iso] }
        )
        val entries = listOf(
            // MUC–LHR und LHR–MUC berühren DE je zweimal, MUC–HAM ist Inland:
            // DE käme mit 3 Flügen auf Platz 1.
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10)),
            entry("LHR", "MUC", LocalDate.of(2026, 1, 11)),
            entry("MUC", "HAM", LocalDate.of(2026, 2, 10))
        )

        val stats = playStats(entries, lookups = lookups) // Homebase MUC

        // Das Heimatland taucht nicht auf, GB führt die Liste an.
        assertTrue(stats.topCountriesPrivate.none { it.iso2 == "DE" })
        assertEquals(listOf("GB"), stats.topCountriesPrivate.map { it.iso2 })
        assertEquals(2, stats.topCountriesPrivate.first().flights)
    }

    @Test
    fun playStats_topCountriesPreferStoredCountryOverLookup() {
        val lookups = StoryStats.Lookups(countryOf = { "XX" })
        val entries = listOf(
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10), toCountry = "GB")
        )

        val stats = playStats(entries, lookups = lookups)

        assertEquals("GB", stats.topCountriesPrivate.first().iso2)
    }

    @Test
    fun playStats_topBuddiesCountEveryEntry() {
        val entries = listOf(
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10), travelBuddy = "Anna"),
            entry("MUC", "HAM", LocalDate.of(2026, 2, 10), travelBuddy = "Anna, Ben"),
            entry("MUC", "SFO", LocalDate.of(2026, 3, 10), travelBuddy = "Ben")
        )

        val stats = playStats(entries)

        // Gleiche Zahl: der zuletzt geflogene Buddy (Ben, März) führt.
        assertEquals(listOf("Ben", "Anna"), stats.topBuddies.map { it.name })
        assertEquals(2, stats.topBuddies.first().flights)
    }

    @Test
    fun playStats_aircraftMostFlownAndFurthest() {
        val entries = listOf(
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10), aircraftType = "A320", distanceKm = 950),
            entry("MUC", "HAM", LocalDate.of(2026, 2, 10), aircraftType = "A320", distanceKm = 600),
            entry("MUC", "JFK", LocalDate.of(2026, 3, 10), aircraftType = "B738", distanceKm = 6300),
            entry("MUC", "SFO", LocalDate.of(2026, 4, 10), aircraftType = null, distanceKm = 9000)
        )

        val stats = playStats(entries)

        assertEquals("A320", stats.mostFlownAircraft?.type)
        assertEquals(2, stats.mostFlownAircraft?.flights)
        assertEquals("B738", stats.furthestAircraft?.type)
        assertEquals(6300, stats.furthestAircraft?.km)
    }

    @Test
    fun playStats_aircraftRanksIgnoreGroundTransfers() {
        val entries = listOf(
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10), aircraftType = "A320", distanceKm = 950),
            entry("MUC", "HAM", LocalDate.of(2026, 2, 10),
                flightType = "Ground Transfer", aircraftType = "B738", distanceKm = 600)
        )

        val stats = playStats(entries)

        assertEquals("A320", stats.mostFlownAircraft?.type)
        assertEquals("A320", stats.furthestAircraft?.type)
        assertEquals(950, stats.furthestAircraft?.km)
    }

    @Test
    fun playStats_topRouteCountsBothDirectionsAsOneRoute() {
        val entries = listOf(
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10)),
            entry("LHR", "MUC", LocalDate.of(2026, 1, 11)),
            entry("MUC", "LHR", LocalDate.of(2026, 2, 10)),
            entry("MUC", "HAM", LocalDate.of(2026, 3, 10))
        )

        val stats = playStats(entries)

        assertEquals("LHR", stats.topRoute?.from)
        assertEquals("MUC", stats.topRoute?.to)
        assertEquals(3, stats.topRoute?.flights)
    }

    @Test
    fun playStats_passengersCountOnlyOnDutyFlights() {
        val entries = listOf(
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10),
                flightType = "On Duty", aircraftType = "A320"),
            entry("MUC", "HAM", LocalDate.of(2026, 2, 10),
                flightType = "Privat", aircraftType = "A320")
        )

        val stats = playStats(entries)

        // 186 Sitzplätze × 80 % = 149 Passagiere, nur der On-Duty-Flug zählt.
        assertEquals(149L, stats.passengersTotal)
    }

    @Test
    fun playStats_classSharesFollowTileOrderAndSkipEmptyClasses() {
        val entries = listOf(
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10), classType = "Economy"),
            entry("MUC", "HAM", LocalDate.of(2026, 2, 10), classType = "economy"),
            entry("MUC", "SFO", LocalDate.of(2026, 3, 10), classType = "Business"),
            entry("MUC", "JFK", LocalDate.of(2026, 4, 10), classType = null)
        )

        val stats = playStats(entries)

        assertEquals(
            listOf("Economy", "Business"),
            stats.classShares.map { it.classCanonical }
        )
        assertEquals(2, stats.classShares.first().flights)
    }

    @Test
    fun playStats_co2MatchesCalculator() {
        val entries = listOf(
            entry("MUC", "LHR", LocalDate.of(2026, 1, 10), distanceKm = 950),
            entry("MUC", "HAM", LocalDate.of(2026, 2, 10),
                flightType = "Ground Transfer", distanceKm = 600)
        )

        val stats = playStats(entries)
        val expected = Co2Calculator.details(DashboardStats.flyingEntries(entries))

        assertEquals(expected.tonnes, stats.co2Tonnes, 0.0001)
        assertEquals(expected.treesPerYear, stats.co2Trees)
    }

    @Test
    fun playStats_emptyLogbookYieldsZerosAndNulls() {
        val stats = playStats(emptyList())

        assertEquals(0, stats.totalFlights)
        assertTrue(stats.topDestinationsPrivate.isEmpty())
        assertTrue(stats.topBuddies.isEmpty())
        assertNull(stats.mostFlownAircraft)
        assertNull(stats.furthestAircraft)
        assertNull(stats.topRoute)
        assertEquals(0L, stats.passengersTotal)
        assertTrue(stats.classShares.isEmpty())
        assertEquals(0, stats.totalKm)
        assertEquals(0, stats.totalMinutes)
    }

    @Test
    fun insightsStats_layoverByCountAndByHours() {
        val entries = listOf(
            entry("MUC", "JFK", LocalDate.of(2026, 1, 10), layover = true, layoverHours = 20),
            entry("MUC", "JFK", LocalDate.of(2026, 2, 10), layover = true, layoverHours = 4),
            entry("MUC", "SIN", LocalDate.of(2026, 3, 10), layover = true, layoverHours = 30),
            entry("MUC", "HAM", LocalDate.of(2026, 4, 10), layover = false)
        )

        val stats = StoryStats.insightsStats(entries)

        assertEquals(listOf("JFK", "SIN"), stats.topLayoverByCount.map { it.code })
        assertEquals(24, stats.topLayoverByCount.first { it.code == "JFK" }.hours)
        assertEquals(listOf("SIN", "JFK"), stats.topLayoverByHours.map { it.code })
        assertEquals(30, stats.topLayoverByHours.first().hours)
    }

    @Test
    fun insightsStats_layoverCountriesSumHoursOfSameCountry() {
        val lookups = StoryStats.Lookups(
            countryOf = { code -> mapOf("JFK" to "US", "SFO" to "US", "LHR" to "GB")[code] },
            countryNameOf = { iso -> mapOf("US" to "USA", "GB" to "Vereinigtes Königreich")[iso] }
        )
        val entries = listOf(
            entry("MUC", "JFK", LocalDate.of(2026, 1, 10), layover = true, layoverHours = 20),
            entry("MUC", "SFO", LocalDate.of(2026, 2, 10), layover = true, layoverHours = 10),
            entry("MUC", "LHR", LocalDate.of(2026, 3, 10), layover = true, layoverHours = 5)
        )

        val stats = StoryStats.insightsStats(entries, lookups = lookups)

        assertEquals("US", stats.topLayoverCountries.first().iso2)
        assertEquals(30, stats.topLayoverCountries.first().hours)
        assertEquals("USA", stats.topLayoverCountries.first().name)
        assertEquals(5, stats.topLayoverCountries.last().hours)
    }

    @Test
    fun insightsStats_layoverCountriesExcludeHomebaseCountry() {
        val lookups = StoryStats.Lookups(
            countryOf = { code -> mapOf("MUC" to "DE", "FRA" to "DE", "LHR" to "GB")[code] },
            countryNameOf = { iso -> mapOf("DE" to "Deutschland", "GB" to "Vereinigtes Königreich")[iso] }
        )
        val entries = listOf(
            // FRA liegt im Heimatland der Homebase MUC: 20 h würden DE anführen.
            entry("MUC", "FRA", LocalDate.of(2026, 1, 10), layover = true, layoverHours = 20),
            entry("MUC", "LHR", LocalDate.of(2026, 2, 10), layover = true, layoverHours = 5)
        )

        val stats = StoryStats.insightsStats(entries, homebase = "MUC", lookups = lookups)

        assertTrue(stats.topLayoverCountries.none { it.iso2 == "DE" })
        assertEquals(listOf("GB"), stats.topLayoverCountries.map { it.iso2 })
        assertEquals(5, stats.topLayoverCountries.first().hours)
    }

    @Test
    fun insightsStats_layoverBuddiesCountOnlyLayoverFlights() {
        val entries = listOf(
            entry("MUC", "JFK", LocalDate.of(2026, 1, 10),
                layover = true, travelBuddy = "Anna"),
            entry("MUC", "SIN", LocalDate.of(2026, 2, 10),
                layover = true, travelBuddy = "Anna"),
            entry("MUC", "LHR", LocalDate.of(2026, 3, 10),
                layover = false, travelBuddy = "Ben")
        )

        val stats = StoryStats.insightsStats(entries)

        assertEquals(listOf("Anna"), stats.topLayoverBuddies.map { it.name })
        assertEquals(2, stats.topLayoverBuddies.first().count)
    }

    @Test
    fun insightsStats_layoverDestinationsCarryCountryFlag() {
        val lookups = StoryStats.Lookups(
            countryOf = { code -> mapOf("JFK" to "US", "SIN" to "SG")[code] }
        )
        val entries = listOf(
            entry("MUC", "JFK", LocalDate.of(2026, 1, 10), layover = true, layoverHours = 20),
            entry("MUC", "SIN", LocalDate.of(2026, 2, 10), layover = true, layoverHours = 30)
        )

        val stats = StoryStats.insightsStats(entries, lookups = lookups)

        assertEquals(
            AirportData.flagEmoji("US"),
            stats.topLayoverByCount.first { it.code == "JFK" }.flag
        )
        assertEquals(
            AirportData.flagEmoji("SG"),
            stats.topLayoverByCount.first { it.code == "SIN" }.flag
        )
    }

    @Test
    fun insightsStats_favoritesNeedRepeatedVisitsAndRewardRegularity() {
        val entries = listOf(
            // HAM: 3 Layover in 3 Monaten - häufig und regelmäßig.
            entry("MUC", "HAM", LocalDate.of(2026, 1, 10), layover = true),
            entry("MUC", "HAM", LocalDate.of(2026, 2, 10), layover = true),
            entry("MUC", "HAM", LocalDate.of(2026, 3, 10), layover = true),
            // LHR: 2 Layover im selben Monat - häufig, aber nicht regelmäßig.
            entry("MUC", "LHR", LocalDate.of(2026, 4, 10), layover = true),
            entry("MUC", "LHR", LocalDate.of(2026, 4, 20), layover = true),
            // MUC ist Homebase und zählt nie als Ziel.
            entry("HAM", "MUC", LocalDate.of(2026, 5, 10), layover = true)
        )

        val stats = StoryStats.insightsStats(entries, homebase = "MUC")

        assertEquals("HAM", stats.favoriteDestinations.first().code)
        assertEquals(3, stats.favoriteDestinations.first().flights)
        assertEquals(3, stats.favoriteDestinations.first().months)
        // Ein einmaliges Ziel ist kein Lieblingsziel.
        assertTrue(stats.favoriteDestinations.none { it.flights < 2 })
    }

    @Test
    fun insightsStats_dislikedAreRareAndLongAgo() {
        val entries = listOf(
            entry("MUC", "HAM", LocalDate.of(2026, 1, 10), layover = true),
            entry("MUC", "HAM", LocalDate.of(2026, 2, 10), layover = true),
            entry("MUC", "OLD", LocalDate.of(2020, 1, 10), layover = true),
            entry("MUC", "MID", LocalDate.of(2023, 1, 10), layover = true),
            entry("MUC", "MID", LocalDate.of(2023, 2, 10), layover = true)
        )

        val stats = StoryStats.insightsStats(entries, homebase = "MUC")

        // Einmalig und am längsten her zuerst.
        assertEquals("OLD", stats.dislikedDestinations.first().code)
        assertEquals(1, stats.dislikedDestinations.first().flights)
        assertEquals(LocalDate.of(2020, 1, 10), stats.dislikedDestinations.first().lastDate)
    }

    @Test
    fun insightsStats_favoritesAndDislikedUseOnlyLayoverFlights() {
        val entries = listOf(
            // HAM nur ohne Layover-Markierung: zählt nicht mit.
            entry("MUC", "HAM", LocalDate.of(2026, 1, 10)),
            entry("MUC", "HAM", LocalDate.of(2026, 2, 10)),
            entry("MUC", "HAM", LocalDate.of(2026, 3, 10)),
            // LHR mit Layover-Markierung: zählt mit.
            entry("MUC", "LHR", LocalDate.of(2026, 4, 10), layover = true),
            entry("MUC", "LHR", LocalDate.of(2026, 4, 20), layover = true)
        )

        val stats = StoryStats.insightsStats(entries, homebase = "MUC")

        assertEquals(listOf("LHR"), stats.favoriteDestinations.map { it.code })
        assertEquals(listOf("LHR"), stats.dislikedDestinations.map { it.code })
    }

    @Test
    fun insightsStats_favoritesAndDislikedExcludeHomebase() {
        val entries = listOf(
            entry("HAM", "MUC", LocalDate.of(2026, 1, 10), layover = true),
            entry("LHR", "MUC", LocalDate.of(2026, 2, 10), layover = true)
        )

        val stats = StoryStats.insightsStats(entries, homebase = "MUC")

        assertTrue(stats.favoriteDestinations.none { it.code == "MUC" })
        assertTrue(stats.dislikedDestinations.none { it.code == "MUC" })
    }

    @Test
    fun insightsStats_emptyLogbookYieldsEmptyLists() {
        val stats = StoryStats.insightsStats(emptyList())

        assertTrue(stats.topLayoverByCount.isEmpty())
        assertTrue(stats.topLayoverByHours.isEmpty())
        assertTrue(stats.topLayoverCountries.isEmpty())
        assertTrue(stats.topLayoverBuddies.isEmpty())
        assertTrue(stats.favoriteDestinations.isEmpty())
        assertTrue(stats.dislikedDestinations.isEmpty())
    }
}
