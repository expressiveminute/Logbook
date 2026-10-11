package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CountryDetailModelTest {

    private val DE = "DE"
    private val FR = "FR"
    private val US = "US"

    private fun entry(
        from: String,
        to: String,
        fc: String? = null,
        tc: String? = null
    ) = LogbookEntry(
        date = LocalDate.of(2024, 1, 1),
        fromAirport = from,
        toAirport = to,
        fromCountry = fc,
        toCountry = tc
    )

    private val locations = mapOf(
        "TXL" to AirportData.GeoLocation(52.56, 13.29),
        "FRA" to AirportData.GeoLocation(50.03, 8.57),
        "CDG" to AirportData.GeoLocation(49.01, 2.55),
        "ORY" to AirportData.GeoLocation(48.72, 2.36),
        "JFK" to AirportData.GeoLocation(40.64, -73.78),
        "AAA" to AirportData.GeoLocation(1.00, 1.00)
    )

    private fun countryOf(code: String): String? = when (code.uppercase()) {
        "TXL", "FRA" -> DE
        "CDG" -> FR
        "JFK" -> US
        else -> null
    }

    private fun location(code: String): AirportData.GeoLocation? = locations[code.uppercase()]

    @Test
    fun analyze_erkenntInlandsrouten() {
        val result = CountryDetailModel.analyze(
            listOf(entry("TXL", "FRA")),
            DE, ::countryOf, ::location
        )
        assertEquals(1, result.domestic.size)
        val route = result.domestic.single()
        assertFalse(route.international)
        assertEquals("TXL", route.from)
        assertEquals("FRA", route.to)
        assertTrue(result.international.isEmpty())
    }

    @Test
    fun analyze_erkenntAusfluegeAlsInternational() {
        val result = CountryDetailModel.analyze(
            listOf(entry("TXL", "CDG")),
            DE, ::countryOf, ::location
        )
        assertTrue(result.domestic.isEmpty())
        assertEquals(1, result.international.size)
        val route = result.international.single()
        assertTrue(route.international)
        // Die Linie beginnt am Flughafen des Landes.
        assertEquals("TXL", route.from)
        assertEquals("CDG", route.to)
    }

    @Test
    fun analyze_erkenntHeimfluegeAlsInternationalAmLandesflughafen() {
        val result = CountryDetailModel.analyze(
            listOf(entry("CDG", "FRA")),
            DE, ::countryOf, ::location
        )
        assertEquals(1, result.international.size)
        val route = result.international.single()
        // Auch der Heimflug startet auf der Karte am Flughafen im Land.
        assertEquals("FRA", route.from)
        assertEquals("CDG", route.to)
    }

    @Test
    fun analyze_sammeltGleicheStrecken() {
        val result = CountryDetailModel.analyze(
            listOf(
                entry("TXL", "CDG"),
                entry("TXL", "CDG"),
                entry("CDG", "FRA")
            ),
            DE, ::countryOf, ::location
        )
        // TXL→CDG zweimal, CDG→FRA (Heimflug) beginnt bei FRA: drei Strecken.
        assertEquals(2, result.international.size)
        assertEquals(3, result.international.sumOf { it.count })
        assertEquals(2, result.international.first { it.from == "TXL" }.count)
    }

    @Test
    fun analyze_zaehltFluegeMitUnbekanntemAusland() {
        // Das Zielland ist unbekannt, der Flughafen aber bekannt: Das ist
        // weiterhin ein Ausflug aus dem Land heraus.
        val result = CountryDetailModel.analyze(
            listOf(entry("TXL", "AAA")),
            DE, ::countryOf, ::location
        )
        assertEquals(1, result.international.size)
        assertEquals("TXL", result.international.single().from)
    }

    @Test
    fun analyze_markiertAlleAngeflogenenFlughaefenDesLandes() {
        val result = CountryDetailModel.analyze(
            listOf(
                entry("TXL", "CDG"),
                entry("CDG", "FRA"),
                entry("CDG", "JFK")
            ),
            DE, ::countryOf, ::location
        )
        assertEquals(listOf("FRA", "TXL"), result.airports.map { it.iata })
        val fra = result.airports.first { it.iata == "FRA" }
        assertEquals(50.03, fra.lat, 0.001)
        assertEquals(8.57, fra.lon, 0.001)
    }

    @Test
    fun analyze_laesstFlughaefenOhneKoordinatenWeg() {
        val result = CountryDetailModel.analyze(
            listOf(entry("FRA", "XXX")),
            DE, ::countryOf, { code -> if (code == "FRA") location(code) else null }
        )
        // Der Flughafen im Land ist markiert, die unbekannte Gegenseite fällt
        // ganz aus der Liste - und die Route braucht beide Seiten.
        assertEquals(listOf("FRA"), result.airports.map { it.iata })
        assertTrue(result.domestic.isEmpty())
        assertTrue(result.international.isEmpty())
    }

    @Test
    fun analyze_zaehltDieFluegeDesLandes() {
        val result = CountryDetailModel.analyze(
            listOf(
                entry("TXL", "CDG"),
                entry("CDG", "FRA"),
                entry("JFK", "LAX")
            ),
            DE, ::countryOf, ::location
        )
        // Nur die ersten beiden berühren Deutschland, der dritte liegt ganz
        // in den USA.
        assertEquals(2, result.flights)
    }

    @Test
    fun analyze_ignoriertFremdeLaenderUndKleinschreibung() {
        val result = CountryDetailModel.analyze(
            listOf(entry("CDG", "JFK")),
            "de", ::countryOf, ::location
        )
        assertTrue(result.airports.isEmpty())
        assertTrue(result.domestic.isEmpty())
        assertTrue(result.international.isEmpty())
        assertEquals(0, result.flights)
    }

    @Test
    fun analyze_ohneEintraegeIstLeer() {
        val result = CountryDetailModel.analyze(emptyList(), DE, ::countryOf, ::location)
        assertTrue(result.airports.isEmpty())
        assertTrue(result.domestic.isEmpty())
        assertTrue(result.international.isEmpty())
        assertEquals(0, result.flights)
    }

    @Test
    fun analyze_landsRoutenNichtImFremdenLand() {
        // Ein rein frankreich-interner Flug darf nicht in der Deutschland-
        // Auswertung auftauchen.
        val result = CountryDetailModel.analyze(
            listOf(entry("CDG", "ORY")),
            DE, ::countryOf, ::location
        )
        assertTrue(result.airports.isEmpty())
        assertTrue(result.domestic.isEmpty())
        assertEquals(0, result.flights)
        // Und umgekehrt: Der gleiche Flug ist für Frankreich eine Inlandsroute.
        val france = CountryDetailModel.analyze(
            listOf(entry("CDG", "ORY", tc = FR, fc = FR)),
            FR, ::countryOf, ::location
        )
        assertEquals(1, france.domestic.size)
    }

    @Test
    fun analyze_nutztDasGespeicherteLandVorDerZuordnung() {
        val result = CountryDetailModel.analyze(
            listOf(entry("TXL", "CDG", fc = DE, tc = US)),
            US, { null }, ::location
        )
        // CDG trägt gespeichert "US": Aus Sicht der USA ist das ein Heimflug,
        // die Linie beginnt am Flughafen im Land (CDG).
        assertEquals(1, result.international.size)
        assertEquals("CDG", result.international.single().from)
        assertEquals("TXL", result.international.single().to)
        assertNull(result.airports.firstOrNull { it.iata == "TXL" })
    }

    @Test
    fun focusBounds_umschliesstAlleFlughaefen() {
        // Auch ein weit entfernter Flughafen gehört dazu: Der Fokus deckt
        // alle angeflogenen Flughäfen ab, vom Festland bis ins Überseegebiet.
        val focus = CountryDetailModel.focusBounds(
            listOf(
                marker("PPT", -17.55, -149.60),
                marker("ORY", 48.72, 2.36),
                marker("CDG", 49.01, 2.55)
            )
        )
        val box = CountryDetailModel.GeoBox(-17.55, 49.01, -149.6, 2.55)
        assertEquals(box, focus)
    }

    @Test
    fun focusBounds_mitMindestgroesseFuerEinenFlughafen() {
        val focus = CountryDetailModel.focusBounds(
            listOf(marker("CDG", 49.01, 2.55))
        )
        val box = CountryDetailModel.GeoBox(46.01, 52.01, -0.45, 5.55)
        assertEquals(box.minLat, focus!!.minLat, 0.001)
        assertEquals(box.maxLat, focus.maxLat, 0.001)
        assertEquals(box.minLon, focus.minLon, 0.001)
        assertEquals(box.maxLon, focus.maxLon, 0.001)
    }

    @Test
    fun focusBounds_ohneFlughaefenIstNull() {
        assertNull(CountryDetailModel.focusBounds(emptyList()))
    }

    private fun marker(iata: String, lat: Double, lon: Double) =
        CountryDetailModel.AirportMarker(iata, lat, lon)
}