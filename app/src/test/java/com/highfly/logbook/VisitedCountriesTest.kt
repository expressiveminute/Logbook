package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale

class VisitedCountriesTest {

    private val collator: Collator = Collator.getInstance(Locale.GERMAN)

    private fun country(iso2: String, de: String, en: String) = CountryShapes.Country(
        iso2 = iso2,
        nameDe = de,
        nameEn = en,
        labelLat = 0.0,
        labelLon = 0.0,
        bbox = doubleArrayOf(0.0, 0.0, 1.0, 1.0),
        rings = emptyList()
    )

    private fun entry(from: String, to: String, fc: String? = null, tc: String? = null) =
        LogbookEntry(
            date = java.time.LocalDate.of(2024, 1, 1),
            fromAirport = from,
            toAirport = to,
            fromCountry = fc,
            toCountry = tc
        )

    private fun countryOf(code: String): String? = when (code.uppercase()) {
        "FRA", "TXL" -> "DE"
        "CDG", "ORY" -> "FR"
        "JFK", "LAX" -> "US"
        else -> null
    }

    private fun rankedItem(flights: Int) = VisitedCountries.Item(
        iso2 = "DE",
        name = "DE",
        flag = "",
        airports = 1,
        flights = flights
    )

    @Test
    fun build_listetNurBesuchteLaender() {
        val items = VisitedCountries.build(
            countries = listOf(
                country("DE", "Deutschland", "Germany"),
                country("FR", "Frankreich", "France"),
                country("US", "Vereinigte Staaten", "United States")
            ),
            entries = listOf(
                entry("FRA", "CDG"),
                entry("JFK", "TXL")
            ),
            german = true,
            collator = collator,
            countryOf = ::countryOf
        )
        assertEquals(listOf("DE", "FR", "US"), items.map { it.iso2 })
    }

    @Test
    fun build_zaehltAbflugUndZielGleichermassen() {
        val items = VisitedCountries.build(
            countries = listOf(country("DE", "Deutschland", "Germany")),
            entries = listOf(
                entry("TXL", "JFK"),
                entry("FRA", "CDG"),
                entry("CDG", "JFK")
            ),
            german = true,
            collator = collator,
            countryOf = ::countryOf
        )
        // Berlin und Frankfurt fliegen aus Deutschland ab, Paris liegt in
        // Frankreich - zu Deutschland gehören also zwei verschiedene Flughäfen.
        val deutschland = items.first { it.iso2 == "DE" }
        assertEquals(2, deutschland.airports)
    }

    @Test
    fun build_zeigtDeutscheOderEnglischeNamen() {
        val laender = listOf(country("DE", "Deutschland", "Germany"))
        val deutsch = VisitedCountries.build(
            laender, listOf(entry("TXL", "FRA")), german = true, collator = collator,
            countryOf = ::countryOf
        )
        val englisch = VisitedCountries.build(
            laender, listOf(entry("TXL", "FRA")), german = false, collator = collator,
            countryOf = ::countryOf
        )
        assertEquals("Deutschland", deutsch.single().name)
        assertEquals("Germany", englisch.single().name)
    }

    @Test
    fun build_haengtDieFlaggeAnDenLändercode() {
        val items = VisitedCountries.build(
            countries = listOf(country("DE", "Deutschland", "Germany")),
            entries = listOf(entry("TXL", "FRA")),
            german = true,
            collator = collator,
            countryOf = ::countryOf
        )
        assertEquals(AirportData.flagEmoji("DE"), items.single().flag)
    }

    @Test
    fun build_sortiertNachFluegenDannName() {
        // Frankreich drei, Deutschland und die USA je zwei Flüge: Frankreich
        // steht oben, die beiden Gleichstände folgen nach Anzeigename.
        val items = VisitedCountries.build(
            countries = listOf(
                country("US", "Vereinigte Staaten", "United States"),
                country("DE", "Deutschland", "Germany"),
                country("FR", "Frankreich", "France")
            ),
            entries = listOf(
                entry("TXL", "CDG"),
                entry("CDG", "JFK"),
                entry("JFK", "TXL"),
                entry("CDG", "ORY")
            ),
            german = true,
            collator = collator,
            countryOf = ::countryOf
        )
        assertEquals(
            listOf("Frankreich", "Deutschland", "Vereinigte Staaten"),
            items.map { it.name }
        )
    }

    @Test
    fun build_zaehltInlandsflugEinmalFuerDasLand() {
        val items = VisitedCountries.build(
            countries = listOf(country("DE", "Deutschland", "Germany")),
            entries = listOf(
                entry("TXL", "FRA"),
                entry("TXL", "FRA"),
                entry("FRA", "TXL")
            ),
            german = true,
            collator = collator,
            countryOf = ::countryOf
        )
        // Inlandsflüge berühren ihr Land nur einmal pro Eintrag: drei Flüge
        // insgesamt, zwei verschiedene Flughäfen.
        val deutschland = items.single()
        assertEquals(3, deutschland.flights)
        assertEquals(2, deutschland.airports)
    }

    @Test
    fun rankOf_teiltGleicheFlugzahlEinenPlatz() {
        val items = listOf(
            rankedItem(3),
            rankedItem(2),
            rankedItem(2),
            rankedItem(1)
        )
        assertEquals(
            listOf(1, 2, 2, 4),
            items.map { VisitedCountries.rankOf(it, items) }
        )
    }

    @Test
    fun build_nutztDasGespeicherteLandVorDerZuordnung() {
        // Der Eintrag trägt ein gespeichertes Land; die Zuordnung des
        // Flughafens (hier absichtlich "unbekannt") darf es nicht überschreiben.
        val items = VisitedCountries.build(
            countries = listOf(country("DE", "Deutschland", "Germany")),
            entries = listOf(entry("NRT", "CDG", fc = "DE", tc = "FR")),
            german = true,
            collator = collator,
            countryOf = { null }
        )
        assertEquals(listOf("DE", "FR"), items.map { it.iso2 })
    }

    @Test
    fun build_ignoriertUnbekannteUndUngueltigeLaender() {
        val items = VisitedCountries.build(
            countries = listOf(country("DE", "Deutschland", "Germany")),
            entries = listOf(entry("FRA", "CDG", fc = "XXX", tc = "!")),
            german = true,
            collator = collator,
            countryOf = ::countryOf
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun build_nenntDenCodeWennDerNameFehlt() {
        val items = VisitedCountries.build(
            countries = listOf(country("ZZ", "", "")),
            entries = listOf(entry("AAA", "BBB", fc = "ZZ", tc = "ZZ")),
            german = true,
            collator = collator,
            countryOf = { "ZZ" }
        )
        assertEquals("ZZ", items.single().name)
        assertFalse(items.single().flag.isEmpty())
    }
}