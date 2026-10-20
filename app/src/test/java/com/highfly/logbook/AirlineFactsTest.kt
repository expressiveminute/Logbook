package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft das von `tools/build_airline_facts.py` erzeugte Asset
 * `assets/airlines/airline_facts.json`: Die allgemeinen Stammdaten müssen zu
 * bekannten Airlines passen und das Format muss zu dem passen, was
 * `AirlineFacts` einliest. Das Asset wird bewusst mit Textmitteln gelesen:
 * `org.json` ist in den JVM-Unit-Tests nur ein Stub.
 */
class AirlineFactsTest {

    private val file = File("src/main/assets/airlines/airline_facts.json")

    /** Der JSON-Block eines einzelnen IATA-Codes, ohne die umgebenden Klammern. */
    private fun entry(code: String): String {
        val match = Regex("\"$code\":\\{([^}]*)\\}").find(file.readText())
        return match?.groupValues?.get(1).orEmpty()
    }

    @Test
    fun assetLiegtVorUndIstNichtLeer() {
        assertTrue("airline_facts.json fehlt", file.isFile)
        assertTrue("airline_facts.json ist verdächtig klein: ${file.length()} B", file.length() > 10_000)
    }

    @Test
    fun lufthansaHatDieBekanntenStammdaten() {
        val lh = entry("LH")
        assertTrue("LH-Block fehlt", lh.isNotEmpty())
        assertTrue("Gründung fehlt: $lh", lh.contains("\"founded\":1926"))
        assertTrue("Sitz fehlt: $lh", lh.contains("\"hq_de\":\"Köln\""))
        assertTrue("FRA als Hub fehlt: $lh", lh.contains("\"FRA\""))
        assertTrue("MUC als Hub fehlt: $lh", lh.contains("\"MUC\""))
        assertTrue("Allianz fehlt: $lh", lh.contains("\"alliance\":\"Star Alliance\""))
        assertTrue("Rufzeichen fehlt: $lh", lh.contains("\"callsign\":\"LUFTHANSA\""))
        assertTrue(
            "Flottengröße fehlt oder ist zu klein: $lh",
            Regex("\"fleet\":[1-9][0-9]{2,}").containsMatchIn(lh)
        )
    }

    @Test
    fun britishAirwaysHatRufzeichenSpeedbird() {
        val ba = entry("BA")
        assertTrue("BA-Block fehlt", ba.isNotEmpty())
        assertTrue("Rufzeichen fehlt: $ba", ba.contains("\"callsign\":\"SPEEDBIRD\""))
        assertTrue("Allianz fehlt: $ba", ba.contains("\"alliance\":\"Oneworld\""))
    }

    @Test
    fun airBerlinHatInsolvenzjahrUndWikiLink() {
        val ab = entry("AB")
        assertTrue("AB-Block fehlt", ab.isNotEmpty())
        assertTrue("Insolvenzjahr 2017 fehlt: $ab", ab.contains("\"dissolved\":2017"))
        assertTrue(
            "Wiki-Link fehlt: $ab",
            ab.contains("\"wiki\":\"https://en.wikipedia.org/wiki/Air_Berlin\"")
        )
    }

    @Test
    fun lufthansaHatWikiLinkAufDenEnglischenArtikel() {
        val lh = entry("LH")
        assertTrue("LH-Block fehlt", lh.isNotEmpty())
        assertTrue(
            "Wiki-Link fehlt: $lh",
            lh.contains("\"wiki\":\"https://en.wikipedia.org/wiki/Lufthansa\"")
        )
        // Eine aktive Airline hat kein Insolvenzjahr.
        assertTrue("LH darf kein Insolvenzjahr haben: $lh", !lh.contains("\"dissolved\""))
    }

    @Test
    fun insolvenzjahreSindPlausibel() {
        val years = Regex("\"dissolved\":([0-9]{4})")
            .findAll(file.readText())
            .map { it.groupValues[1].toInt() }
            .toList()
        assertTrue("keine Insolvenzjahre im Asset", years.isNotEmpty())
        assertTrue("unplausibles Jahr: $years", years.all { it in 1900..2026 })
    }

    @Test
    fun displayCallsignMachtWorteMitGrossemAnfangsbuchstaben() {
        assertEquals("Speedbird", AirlineFacts.displayCallsign("SPEEDBIRD"))
        assertEquals("All Nippon", AirlineFacts.displayCallsign("ALL NIPPON"))
        assertEquals("Air Berlin", AirlineFacts.displayCallsign("AIR BERLIN"))
        assertEquals("Lufthansa", AirlineFacts.displayCallsign("LUFTHANSA"))
        assertEquals(null, AirlineFacts.displayCallsign(null))
        assertEquals(null, AirlineFacts.displayCallsign("   "))
    }

    @Test
    fun wikiTitleEntschluesseltDenArtikelnamen() {
        assertEquals(
            "British Airways",
            AirlineFacts.wikiTitle("https://en.wikipedia.org/wiki/British_Airways")
        )
        assertEquals(
            "Emirates (airline)",
            AirlineFacts.wikiTitle("https://en.wikipedia.org/wiki/Emirates_%28airline%29")
        )
        assertEquals(
            "Aerolíneas Argentinas",
            AirlineFacts.wikiTitle("https://en.wikipedia.org/wiki/Aerol%C3%ADneas_Argentinas")
        )
    }

    @Test
    fun schluesselSindEindeutigeIataCodes() {
        val keys = Regex("\"([A-Z0-9]{2})\":\\{")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue("zu wenige Airlines: ${keys.size}", keys.size > 300)
        assertEquals("doppelte Schlüssel", keys.size, keys.distinct().size)
    }

    @Test
    fun hubsSindDreilettercodes() {
        val hubs = Regex("\"hubs\":\\[([^]]*)]")
            .findAll(file.readText())
            .flatMap { it.groupValues[1].split(",") }
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .toList()
        assertTrue("keine Hubs im Asset", hubs.isNotEmpty())
        assertTrue(
            "Hub ohne Dreilettercode: $hubs",
            hubs.all { Regex("[A-Z]{3}").matches(it) }
        )
    }

    @Test
    fun gruendungsjahreSindPlausibel() {
        val years = Regex("\"founded\":([0-9]{4})")
            .findAll(file.readText())
            .map { it.groupValues[1].toInt() }
            .toList()
        assertTrue("keine Gründungsjahre im Asset", years.isNotEmpty())
        assertTrue("unplausibles Jahr: $years", years.all { it in 1900..2026 })
    }

    @Test
    fun anzeigeSitzIstDeutschWennVorhanden() {
        val mitDeutsch = AirlineFacts.Facts(
            founded = 1926, hq = "Cologne", hqDe = "Köln",
            hubs = listOf("FRA"), fleet = 500,
            alliance = "Star Alliance", callsign = "LUFTHANSA"
        )
        assertEquals("Köln", mitDeutsch.displayHq)
        val nurEnglisch = AirlineFacts.Facts(
            founded = 1926, hq = "Cologne", hqDe = null,
            hubs = emptyList(), fleet = null, alliance = null, callsign = null
        )
        assertEquals("Cologne", nurEnglisch.displayHq)
    }
}
