package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Prüft die von `tools/build_orthophotos.py` erzeugten Luftbild-Assets der
 * CO₂-Karte. Der Generator und die Karte müssen zusammenpassen: Die
 * Randkoordinaten in `<CODE>.json` legen das Bild auf die Karte, also müssen
 * sie zum Flughafen und zur gewählten Ausschnittsgröße passen. Das Asset wird
 * bewusst mit Textmitteln gelesen: `org.json` ist in den JVM-Unit-Tests nur
 * ein Stub.
 */
class OrthophotosAssetTest {

    private val dir = File("src/main/assets/orthophotos")

    /** Flughafenmittelpunkte, um die der Ausschnitt liegen muss. */
    private val airports = mapOf(
        "FRA" to (50.0379 to 8.5622),
        "MUC" to (48.3538 to 11.7861)
    )

    private fun number(json: String, field: String): Double =
        Regex("\"$field\":(-?[0-9.]+)").find(json)!!.groupValues[1].toDouble()

    private fun fields(json: String, field: String): Int =
        Regex("\"$field\":([0-9]+)").find(json)!!.groupValues[1].toInt()

    @Test
    fun fuerFraUndMucLiegenBildUndMetadatenVor() {
        for (code in airports.keys) {
            assertTrue("$code.webp fehlt", File(dir, "$code.webp").isFile)
            assertTrue("$code.json fehlt", File(dir, "$code.json").isFile)
        }
    }

    @Test
    fun bilderSindNichtLeerOderAbgeschnitten() {
        for (code in airports.keys) {
            val size = File(dir, "$code.webp").length()
            assertTrue("$code.webp ist verdächtig klein: $size B", size > 100_000)
        }
    }

    @Test
    fun ausschnittLiegtAufDemFlughafen() {
        for ((code, expected) in airports) {
            val json = File(dir, "$code.json").readText()
            val minLat = number(json, "min_lat")
            val minLon = number(json, "min_lon")
            val maxLat = number(json, "max_lat")
            val maxLon = number(json, "max_lon")
            assertEquals("$code Breite", expected.first, (minLat + maxLat) / 2.0, 0.01)
            assertEquals("$code Länge", expected.second, (minLon + maxLon) / 2.0, 0.01)
        }
    }

    @Test
    fun ausschnittIstRundZwoelfKilometerGross() {
        for (code in airports.keys) {
            val json = File(dir, "$code.json").readText()
            val spanLatM = (number(json, "max_lat") - number(json, "min_lat")) * 111_320.0
            assertTrue("$code Höhe $spanLatM m", abs(spanLatM - 12_000.0) < 200.0)
            assertEquals("$code Pixel", 2048, fields(json, "pixels"))
            assertEquals("$code Kantenlänge", 12.0, number(json, "box_km"), 0.001)
        }
    }

    @Test
    fun nurFraUndMucSindUnterstuetzt() {
        assertTrue(Orthophotos.isSupported("FRA"))
        assertTrue(Orthophotos.isSupported("MUC"))
        assertFalse(Orthophotos.isSupported("BER"))
        assertFalse(Orthophotos.isSupported(""))
    }

    @Test
    fun quellennachweisPasstZumBundesland() {
        assertEquals(R.string.co2_offset_credit_hessen, Orthophotos.creditRes("FRA"))
        assertEquals(R.string.co2_offset_credit_bayern, Orthophotos.creditRes("MUC"))
        assertNull(Orthophotos.creditRes("BER"))
    }
}
