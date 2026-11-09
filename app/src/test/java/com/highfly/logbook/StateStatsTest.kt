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
        // FR ist kein Kachel-Land: Regionen anderer Länder zählen nicht.
        val summary = summarize(
            listOf(entry("ORY", "CDG")),
            mapOf("ORY" to "FR-IDF", "CDG" to "FR-IDF")
        )
        assertTrue(summary.states.isEmpty())
        assertTrue(summary.countries.isEmpty())
    }

    @Test
    fun summarize_nimmtAuchAustralienUndGrossbritannien() {
        val summary = summarize(
            listOf(entry("SYD", "LHR"), entry("MEL", "BNE")),
            mapOf("SYD" to "AU-NSW", "LHR" to "GB-ENG", "MEL" to "AU-VIC", "BNE" to "AU-QLD")
        )
        assertEquals(setOf("AU-NSW", "AU-VIC", "AU-QLD", "GB-ENG"), summary.states)
        assertEquals(setOf("AU", "GB"), summary.countries)
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

    @Test
    fun summarize_berlinerFlughaefen_zaehlenAuchBerlin() {
        // BER traegt die Region Brandenburg (DE-BB) - Berlin (DE-BE) kommt
        // zusätzlich dazu.
        val summary = summarize(
            listOf(entry("FRA", "BER")),
            mapOf("FRA" to "DE-HE", "BER" to "DE-BB")
        )
        assertEquals(setOf("DE-BE", "DE-BB", "DE-HE"), summary.states)
        assertEquals(setOf("DE"), summary.countries)
    }

    @Test
    fun summarize_berlinerFlughaefenOhneRegion_zaehlenBerlinUndLand() {
        // TXL/SXF/THF haben keinen Regionseintrag; die geschlossenen stehen
        // als XTHF etc. im Logbuch. Berlin und das Land DE zählen trotzdem.
        for (code in listOf("TXL", "SXF", "THF", "XTXL", "XSXF", "XTHF")) {
            val summary = summarize(listOf(entry("FRA", code)), mapOf("FRA" to "DE-HE"))
            assertEquals("bei $code", setOf("DE-BE", "DE-HE"), summary.states)
            assertEquals("bei $code", setOf("DE"), summary.countries)
        }
    }

    @Test
    fun summarize_abgewaehltesBerlinFaelltWiederHeraus() {
        val summary = StateStats.summarize(
            listOf(entry("FRA", "BER")),
            { mapOf("FRA" to "DE-HE", "BER" to "DE-BB")[it.uppercase()] },
            emptyList(),
            emptySet(),
            setOf("DE-BE")
        )
        assertEquals(setOf("DE-BB", "DE-HE"), summary.states)
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
