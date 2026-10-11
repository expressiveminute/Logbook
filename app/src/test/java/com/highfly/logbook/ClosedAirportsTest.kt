package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft das Asset der geschlossenen Flughäfen ("X" + damaliger Dreilettercode)
 * und die Formprüfung in [AirportData]. Das Asset wird bewusst mit Textmitteln
 * gelesen: `org.json` ist in den JVM-Unit-Tests nur ein Stub.
 */
class ClosedAirportsTest {

    private val json = File("src/main/assets/airports/airports_closed.json").readText()

    /** Die Rümpfe aller Einträge, also der Inhalt je `"XXXX":{...}`. */
    private fun entries(): Map<String, String> =
        Regex("\"(X[A-Z]{3})\":\\{([^}]*)}")
            .findAll(json)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun double(body: String, field: String): Double =
        Regex("\"$field\":(-?[0-9.]+)").find(body)!!.groupValues[1].toDouble()

    @Test
    fun jederSchluesselIstXPlusDreilettercode() {
        val codes = entries().keys
        assertTrue("Erwartet mehrere Einträge, waren ${codes.size}", codes.size > 50)
        for (code in codes) {
            assertTrue("$code ist kein X+Dreilettercode", Regex("^X[A-Z]{3}$").matches(code))
        }
    }

    @Test
    fun jederEintragHatKoordinatenUndLand() {
        for ((code, body) in entries()) {
            val lat = double(body, "lat")
            val lon = double(body, "lon")
            assertTrue("$code lat außerhalb", lat in -90.0..90.0)
            assertTrue("$code lon außerhalb", lon in -180.0..180.0)
            assertTrue("$code ohne ISO-Land", Regex("\"iso\":\"[A-Z]{2}\"").containsMatchIn(body))
        }
    }

    @Test
    fun muenchenRiemLiegtUnterXMUC() {
        val body = entries()["XMUC"] ?: error("XMUC fehlt")
        assertEquals("DE", Regex("\"iso\":\"([A-Z]{2})\"").find(body)!!.groupValues[1])
        assertEquals(48.1378, double(body, "lat"), 0.01)
        assertEquals(11.6903, double(body, "lon"), 0.01)
        assertTrue("Name ohne Riem: $body", body.contains("Riem"))
        assertTrue("deutsche Stadt fehlt: $body", body.contains("\"d\":\"München\""))
    }

    @Test
    fun geschlossenerCodeWirdErkanntOffenerNicht() {
        assertTrue(AirportData.isClosedCode("XMUC"))
        assertFalse("MUC ist offen", AirportData.isClosedCode("MUC"))
        assertFalse("XAP ist ein echter Code mit drei Zeichen", AirportData.isClosedCode("XAP"))
        assertFalse("zu lang", AirportData.isClosedCode("XMUCX"))
        assertFalse("X nicht am Anfang", AirportData.isClosedCode("MUCX"))
    }
}
