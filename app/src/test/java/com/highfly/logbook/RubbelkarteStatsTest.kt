package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RubbelkarteStatsTest {

    private val known = mapOf(
        "FRA" to "DE",
        "JFK" to "US",
        "NRT" to "JP",
        "GRU" to "BR",
        "JNB" to "ZA",
        "SYD" to "AU"
    )

    private fun entry(
        from: String,
        to: String,
        fromCountry: String? = null,
        toCountry: String? = null
    ) = LogbookEntry(
        date = LocalDate.of(2026, 9, 7),
        fromAirport = from,
        toAirport = to,
        fromCountry = fromCountry,
        toCountry = toCountry
    )

    @Test
    fun summarize_zaehltAbUndZielEinesFluges() {
        val summary = RubbelkarteStats.summarize(listOf(entry("FRA", "JFK"))) { known[it] }
        assertEquals(listOf("DE", "US"), summary.countries)
        assertEquals(listOf("FRA", "JFK"), summary.airports)
        assertEquals(1, summary.flightsCount)
    }

    @Test
    fun summarize_bevorzugtDasImEintragGespeicherteLand() {
        val summary = RubbelkarteStats.summarize(
            listOf(entry("FRA", "JFK", fromCountry = "XX", toCountry = "US"))
        ) { known[it] }
        assertEquals(listOf("US", "XX"), summary.countries)
    }

    @Test
    fun summarize_ignoriertUnbekannteFlughafen() {
        val summary = RubbelkarteStats.summarize(listOf(entry("FRA", "ZZZ"))) { known[it] }
        assertEquals(listOf("DE"), summary.countries)
        assertEquals(listOf("FRA", "ZZZ"), summary.airports)
    }

    @Test
    fun summarize_liefertKontinenteInFesterReihenfolge() {
        val entries = listOf(
            entry("SYD", "JFK"),
            entry("GRU", "FRA"),
            entry("JNB", "NRT")
        )
        val summary = RubbelkarteStats.summarize(entries) { known[it] }
        assertEquals(
            listOf(
                Continents.EUROPE, Continents.ASIA, Continents.NORTH_AMERICA,
                Continents.SOUTH_AMERICA, Continents.AFRICA, Continents.OCEANIA
            ),
            summary.continents
        )
    }

    @Test
    fun summarize_ohneEintraegeIstLeer() {
        val summary = RubbelkarteStats.summarize(emptyList()) { known[it] }
        assertTrue(summary.isEmpty)
        assertEquals(emptyList<String>(), summary.countries)
        assertEquals(emptyList<String>(), summary.continents)
    }

    @Test
    fun summarize_verlangtZweiStelligeLaender() {
        val summary = RubbelkarteStats.summarize(
            listOf(entry("FRA", "JFK", toCountry = "Deutschland"))
        ) { known[it] }
        assertEquals(listOf("DE"), summary.countries)
    }

    @Test
    fun centerOf_schwerpunktDerBesuchtenLaender() {
        val shapes = listOf(
            shape("DE", 51.0, 10.0, 8.0),
            shape("US", 40.0, -100.0, 30.0),
            shape("JP", 36.0, 138.0, 6.0)
        )
        val center = RubbelkarteStats.centerOf(shapes, setOf("DE", "US"))
        assertTrue(center != null)
        val lon = center!!.first
        val lat = center.second
        // Die USA sind deutlich groesser und ziehen den Schwerpunkt nach Westen.
        assertTrue("lon=$lon", lon in -100.0..10.0)
        assertTrue("lat=$lat", lat in 40.0..51.0)
    }

    @Test
    fun centerOf_ignoriertLaenderDieUeberDieDatumslinieReichen() {
        // Die USA reichen von Alaska bis zu den Aleuten ueber 180 Grad. Eine
        // Bounding-Box waere hier fast 360 Grad breit und wuerde den
        // Schwerpunkt komplett verzerren.
        val shapes = listOf(
            shape("DE", 51.0, 10.0, 8.0),
            CountryShapes.Country(
                iso2 = "US",
                nameDe = "US",
                nameEn = "US",
                labelLat = 40.0,
                labelLon = -100.0,
                bbox = doubleArrayOf(-179.1, 18.9, 179.8, 71.4),
                rings = emptyList()
            )
        )
        val center = RubbelkarteStats.centerOf(shapes, setOf("DE", "US"))!!
        assertTrue("lon=${center.first}", center.first in -100.0..10.0)
        assertTrue("lat=${center.second}", center.second in 40.0..51.0)
    }

    @Test
    fun centerOf_ohneLaenderLiefertNull() {
        assertNull(RubbelkarteStats.centerOf(emptyList(), setOf("DE")))
    }

    @Test
    fun plusCountries_nimmtSelbstAbgehakteLaenderAuf() {
        val summary = RubbelkarteStats.summarize(listOf(entry("FRA", "JFK"))) { known[it] }
        val merged = summary.plusCountries(listOf("IS", "ZA"))
        assertEquals(listOf("DE", "IS", "US", "ZA"), merged.countries)
        assertEquals(
            listOf(Continents.EUROPE, Continents.NORTH_AMERICA, Continents.AFRICA),
            merged.continents
        )
        // Die Angaben aus den Einträgen bleiben unberührt.
        assertEquals(summary.airports, merged.airports)
        assertEquals(summary.flightsCount, merged.flightsCount)
    }

    @Test
    fun plusCountries_zaehltEinLandNurEinmal() {
        val summary = RubbelkarteStats.summarize(listOf(entry("FRA", "JFK"))) { known[it] }
        val merged = summary.plusCountries(listOf("US", "us"))
        assertEquals(listOf("DE", "US"), merged.countries)
    }

    @Test
    fun plusCountries_verlangtZweiStelligeLaender() {
        val summary = RubbelkarteStats.summarize(emptyList()) { known[it] }
        val merged = summary.plusCountries(listOf("Island", "X", "IS"))
        assertEquals(listOf("IS"), merged.countries)
    }

    @Test
    fun plusCountries_ohneZusatzLiefertDieselbeAuswertung() {
        val summary = RubbelkarteStats.summarize(listOf(entry("FRA", "JFK"))) { known[it] }
        assertEquals(summary, summary.plusCountries(emptyList()))
    }

    @Test
    fun plusCountries_machtAusDerLeerenKarteEineBefüllte() {
        val summary = RubbelkarteStats.summarize(emptyList()) { known[it] }
        assertTrue(summary.isEmpty)
        assertTrue(summary.plusCountries(listOf("DE")).isEmpty.not())
    }

    @Test
    fun minusCountries_nimmtAbgewaehlteLaenderHeraus() {
        val summary = RubbelkarteStats.summarize(listOf(entry("FRA", "JFK"))) { known[it] }
            .plusCountries(listOf("IS"))
        val reduced = summary.minusCountries(listOf("DE"))
        assertEquals(listOf("IS", "US"), reduced.countries)
        // Die Kontinente folgen dem verbleibenden Stand.
        assertEquals(
            listOf(Continents.EUROPE, Continents.NORTH_AMERICA),
            reduced.continents
        )
        // Flughäfen und Flugzahl stammen aus den Einträgen und bleiben.
        assertEquals(summary.airports, reduced.airports)
        assertEquals(summary.flightsCount, reduced.flightsCount)
    }

    @Test
    fun minusCountries_kannDieKarteLeeren() {
        val summary = RubbelkarteStats.summarize(listOf(entry("FRA", "JFK"))) { known[it] }
        val reduced = summary.minusCountries(listOf("DE", "US"))
        assertTrue(reduced.isEmpty)
        assertEquals(emptyList<String>(), reduced.countries)
    }

    @Test
    fun minusCountries_verlangtZweiStelligeLaender() {
        val summary = RubbelkarteStats.summarize(listOf(entry("FRA", "JFK"))) { known[it] }
        val reduced = summary.minusCountries(listOf("Island", "X", "DE"))
        assertEquals(listOf("US"), reduced.countries)
    }

    @Test
    fun minusCountries_ohneAbwahlLiefertDieselbeAuswertung() {
        val summary = RubbelkarteStats.summarize(listOf(entry("FRA", "JFK"))) { known[it] }
        assertEquals(summary, summary.minusCountries(emptyList()))
    }

    private fun shape(iso2: String, lat: Double, lon: Double, latSpan: Double) =
        CountryShapes.Country(
            iso2 = iso2,
            nameDe = iso2,
            nameEn = iso2,
            labelLat = lat,
            labelLon = lon,
            bbox = doubleArrayOf(lon - latSpan / 2, lat - latSpan / 2, lon + latSpan / 2, lat + latSpan / 2),
            rings = emptyList()
        )
}
