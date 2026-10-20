package com.highfly.logbook

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
}
