package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft den Sitzplatz-Datensatz `assets/aircraft/aircraft_seats.json`: Die
 * Sitzplätze müssen im plausiblen Bereich liegen und jeder Alias muss auf
 * eine vorhandene Familie zeigen.
 *
 * Das JSON wird bewusst mit Textmitteln gelesen: `org.json` ist in den
 * JVM-Unit-Tests nur ein Stub.
 */
class AircraftSeatsAssetTest {

    private val file = File("src/main/assets/aircraft/aircraft_seats.json")

    private val seats: Map<String, Int>
    private val aliases: Map<String, String>

    init {
        val text = file.readText()
        // Sitzplätze haben Zahlenwerte, Aliase String-Werte - die beiden
        // Muster treffen sich nicht.
        seats = Regex("\"([A-Z0-9]+)\":\\s*(\\d+)").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        aliases = Regex("\"([A-Z0-9]+)\":\\s*\"([A-Z0-9]+)\"").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun dateiExistiertUndHatGenugFamilien() {
        assertTrue("fehlende Datei: ${file.path}", file.isFile)
        assertTrue("zu wenige Familien: ${seats.size}", seats.size >= 20)
    }

    @Test
    fun sitzplaetzeLiegenImPlausiblenBereich() {
        for ((family, count) in seats) {
            assertTrue("$family: $count Sitze outside 50..900", count in 50..900)
        }
    }

    @Test
    fun aliaseZeigenAufVorhandeneFamilien() {
        assertTrue("zu wenige Aliase: ${aliases.size}", aliases.size >= 40)
        for ((code, family) in aliases) {
            assertTrue(
                "Alias $code zeigt auf unbekannte Familie $family",
                seats.containsKey(family)
            )
        }
    }

    @Test
    fun durchschnittLiegtImPlausiblenBereich() {
        val average = seats.values.average()
        assertTrue("Durchschnitt $average outside 150..350", average in 150.0..350.0)
    }

    @Test
    fun realistischeVollbesetzungenSindEingetragen() {
        // Realistische Sitzplätze bei Vollbesetzung je Typ (typische
        // 2-/3-Klassen-Bestuhlung).
        val erwartet = mapOf(
            "A319" to 139,
            "A320" to 168,
            "A320NEO" to 180,
            "A321" to 200,
            "A321NEO" to 216,
            "A330300" to 252,
            "A340300" to 279,
            "A340600" to 281,
            "A350900" to 277,
            "A3501000" to 300,
            "A380" to 503
        )
        for ((typ, sitze) in erwartet) {
            assertEquals("Falsche Vollbesetzung für $typ", sitze, seats[typ])
        }
    }

    @Test
    fun variantenWerdenUeberDieFamilieGefunden() {
        // Auflösung über die echte Matching-Logik: Varianten mit eigener
        // Zahl gewinnen vor ihrer Familie, Neo-Kurzcodes vor der Basisfamilie.
        val catalog = AircraftSeats.Catalog(seats, aliases)
        assertEquals(139, catalog.seatsFor("A319"))
        assertEquals(168, catalog.seatsFor("A320"))
        assertEquals(168, catalog.seatsFor("A320-200"))
        assertEquals(180, catalog.seatsFor("A320neo"))
        assertEquals(180, catalog.seatsFor("A20N"))
        assertEquals(200, catalog.seatsFor("A321"))
        assertEquals(216, catalog.seatsFor("A321neo"))
        assertEquals(216, catalog.seatsFor("A21N"))
        assertEquals(252, catalog.seatsFor("A330-300"))
        assertEquals(440, catalog.seatsFor("A330-200"))
        assertEquals(279, catalog.seatsFor("A340-300"))
        assertEquals(281, catalog.seatsFor("A340-600"))
        assertEquals(277, catalog.seatsFor("A350-900"))
        assertEquals(300, catalog.seatsFor("A350-1000"))
        assertEquals(480, catalog.seatsFor("A350"))
        assertEquals(503, catalog.seatsFor("A380-800"))
        assertEquals(503, catalog.seatsFor("A380"))
    }
}
