package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class Co2OffsetModelTest {

    private val DE = "DE"
    private val FR = "FR"
    private val US = "US"

    private fun entry(
        from: String,
        to: String,
        fc: String? = null,
        tc: String? = null,
        distanceKm: Int? = null,
        flightType: String? = null,
        aircraftType: String? = null
    ) = LogbookEntry(
        date = LocalDate.of(2024, 1, 1),
        flightType = flightType,
        fromAirport = from,
        toAirport = to,
        fromCountry = fc,
        toCountry = tc,
        distanceKm = distanceKm,
        aircraftType = aircraftType
    )

    private val locations = mapOf(
        "TXL" to AirportData.GeoLocation(52.56, 13.29),
        "FRA" to AirportData.GeoLocation(50.03, 8.57),
        "QYG" to AirportData.GeoLocation(50.72, 6.36),
        "CDG" to AirportData.GeoLocation(49.01, 2.55),
        "JFK" to AirportData.GeoLocation(40.64, -73.78)
    )

    private fun countryOf(code: String): String? = when (code.uppercase()) {
        "TXL", "FRA", "QYG" -> DE
        "CDG" -> FR
        "JFK" -> US
        else -> null
    }

    private fun location(code: String): AirportData.GeoLocation? = locations[code.uppercase()]

    @Test
    fun analyze_waehltAbfluglandMitDenMeistenAbfluegen() {
        // Aus Frankreich nur ein Abflug, aus Deutschland drei.
        val result = Co2OffsetModel.analyze(
            listOf(
                entry("TXL", "CDG"),
                entry("FRA", "JFK"),
                entry("QYG", "CDG"),
                entry("CDG", "FRA")
            ),
            ::countryOf, ::location
        )
        assertEquals(DE, result!!.iso2)
    }

    @Test
    fun analyze_nutztGespeichertesLandVorDerZuordnung() {
        // Ohne Zuordnungsfunktion zählt allein das gespeicherte Abflugland.
        val result = Co2OffsetModel.analyze(
            listOf(entry("TXL", "CDG", fc = DE, tc = FR), entry("JFK", "FRA", fc = US, tc = DE)),
            { null }, ::location
        )
        assertEquals(DE, result!!.iso2)
    }

    @Test
    fun analyze_waehltFlughafenMitDenMeistenVerbindungen() {
        // FRA hat drei verschiedene Verbindungen (TXL, JFK, CDG), TXL nur eine.
        val result = Co2OffsetModel.analyze(
            listOf(
                entry("TXL", "CDG"),
                entry("FRA", "JFK"),
                entry("QYG", "FRA"),
                entry("FRA", "CDG")
            ),
            ::countryOf, ::location
        )
        assertEquals("FRA", result!!.airport.iata)
        assertEquals(3, result.airport.connections)
    }

    @Test
    fun analyze_zaehltVerbindungenNichtDoppelt() {
        // Eine Strecke doppelt geflogen ist weiterhin eine Verbindung.
        val result = Co2OffsetModel.analyze(
            listOf(
                entry("FRA", "JFK"),
                entry("JFK", "FRA"),
                entry("FRA", "CDG")
            ),
            ::countryOf, ::location
        )
        assertEquals("FRA", result!!.airport.iata)
        assertEquals(2, result.airport.connections)
        assertEquals(3, result.airport.flights)
    }

    @Test
    fun analyze_brichtGleichstandMitDenMeistenFluegen() {
        // FRA und TXL haben je eine Verbindung, FRA aber mehr Einträge.
        val result = Co2OffsetModel.analyze(
            listOf(
                entry("FRA", "CDG"),
                entry("FRA", "JFK"),
                entry("TXL", "QYG")
            ),
            ::countryOf, ::location
        )
        assertEquals("FRA", result!!.airport.iata)
    }

    @Test
    fun analyze_rechnetBaeumeUndHektarAusEmissionswerten() {
        // 6195 km in einer A350: 1025,892 kg → 103 Bäume → 0,103 ha.
        val result = Co2OffsetModel.analyze(
            listOf(entry("FRA", "JFK", distanceKm = 6195, aircraftType = "A350-900")),
            ::countryOf, ::location
        )
        assertEquals(1.025892, result!!.tonnes, 0.000001)
        assertEquals(103, result.treesPerYear)
        assertEquals(0.103, result.hectares, 0.000001)
    }

    @Test
    fun analyze_ignoriertGroundTransfers() {
        val result = Co2OffsetModel.analyze(
            listOf(
                entry("FRA", "JFK", flightType = "Ground Transfer"),
                entry("CDG", "ORY", flightType = "Ground Transfer")
            ),
            ::countryOf, ::location
        )
        // Ohne echte Flüge ist kein Ausgleich bestimmbar.
        assertNull(result)
    }

    @Test
    fun analyze_ohneEintraegeIstNull() {
        assertNull(Co2OffsetModel.analyze(emptyList(), ::countryOf, ::location))
    }

    @Test
    fun analyze_laesstFlughaefenOhneKoordinatenWeg() {
        val result = Co2OffsetModel.analyze(
            listOf(entry("FRA", "XXX")),
            ::countryOf, ::location
        )
        assertEquals("FRA", result!!.airport.iata)
        assertEquals(50.03, result.airport.lat, 0.001)
        assertEquals(0.0, result.tonnes, 0.0)
    }

    @Test
    fun analyze_vorhandeneHektarKonstante() {
        // Die Fläche ist Bäume durch Pflanzdichte.
        assertEquals(1000.0, Co2OffsetModel.TREES_PER_HECTARE, 0.0)
        assertTrue(Co2OffsetModel.TREES_PER_HECTARE > 0.0)
    }
}