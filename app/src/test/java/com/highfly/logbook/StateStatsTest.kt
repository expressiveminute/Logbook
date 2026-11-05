package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tests für die Zuordnung von Flügen zu Bundesstaaten/-ländern, siehe
 * [StateStats].
 */
class StateStatsTest {

    private fun entry(from: String, to: String) = LogbookEntry(
        date = LocalDate.of(2026, 3, 12),
        fromAirport = from,
        toAirport = to
    )

    private fun summarize(
        entries: List<LogbookEntry>,
        regions: Map<String, String>,
        flownCountries: List<String> = emptyList()
    ) = StateStats.summarize(entries, { regions[it.uppercase()] }, flownCountries)

    @Test
    fun summarize_ordnetAbflugUndZielDerRegionZu() {
        val summary = summarize(
            listOf(entry("FRA", "JFK")),
            mapOf("FRA" to "DE-HE", "JFK" to "US-NY")
        )
        assertEquals(setOf("DE-HE", "US-NY"), summary.states)
        assertEquals(setOf("DE", "US"), summary.countries)
    }

    @Test
    fun summarize_ignoriertRegionenAndererLaender() {
        val summary = summarize(
            listOf(entry("LHR", "CDG")),
            mapOf("LHR" to "GB-ENG", "CDG" to "FR-IDF")
        )
        assertTrue(summary.states.isEmpty())
        assertTrue(summary.countries.isEmpty())
    }

    @Test
    fun summarize_landZaehltAuchOhneRegionseintrag() {
        // Geschlossener Flughafen ohne Region in der Datei: das Land zählt
        // trotzdem, sobald es in den angeflogenen Ländern steht.
        val summary = summarize(
            listOf(entry("FRA", "XMUC")),
            mapOf("FRA" to "DE-HE"),
            flownCountries = listOf("DE")
        )
        assertEquals(setOf("DE-HE"), summary.states)
        assertEquals(setOf("DE"), summary.countries)
    }

    @Test
    fun summarize_leereCodesFallenHeraus() {
        val summary = summarize(listOf(entry("", "  ")), emptyMap())
        assertTrue(summary.states.isEmpty())
    }

    @Test
    fun normalizeRegion_haeltNurZweistufigeCodes() {
        assertEquals("US-CA", StateStats.normalizeRegion("us-ca"))
        assertEquals("DE-BY", StateStats.normalizeRegion(" DE-BY "))
        assertNull(StateStats.normalizeRegion(null))
        assertNull(StateStats.normalizeRegion("USA"))
        assertNull(StateStats.normalizeRegion("US"))
        assertNull(StateStats.normalizeRegion("US-CA-XX"))
        assertNull(StateStats.normalizeRegion("U-CA"))
    }

    @Test
    fun tileProgress_zaehltNurDieStaatenDesLandes() {
        val states = listOf(
            state("US-CA", "US"),
            state("US-NY", "US"),
            state("DE-BY", "DE"),
            state("DE-HE", "DE")
        )
        val us = StateStats.tileProgress(states, "US", setOf("us-ca", "DE-BY"))
        assertEquals("US", us.continent)
        assertEquals(1, us.visited)
        assertEquals(2, us.total)
        val de = StateStats.tileProgress(states, "de", setOf("DE-BY"))
        assertEquals(1, de.visited)
        assertEquals(2, de.total)
    }

    @Test
    fun tileProgress_ohneStaatenIstNullVonNull() {
        val progress = StateStats.tileProgress(emptyList(), "US", setOf("US-CA"))
        assertEquals(0, progress.visited)
        assertEquals(0, progress.total)
        assertEquals(0f, progress.fraction, 1e-6f)
    }

    private fun state(code: String, iso2: String) = StateShapes.State(
        code = code,
        iso2 = iso2,
        capLat = 0.0,
        capLon = 0.0,
        labelLat = 0.0,
        labelLon = 0.0,
        bbox = doubleArrayOf(0.0, 0.0, 1.0, 1.0),
        nameDe = code,
        nameEn = code,
        capDe = "",
        capEn = "",
        rings = emptyList()
    )
}
