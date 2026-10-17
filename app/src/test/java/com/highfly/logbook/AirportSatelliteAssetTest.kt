package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft die von `tools/build_airport_satellite.py` erzeugten Satellitenbilder
 * der Top-100-Flughäfen. Generator und App müssen zusammenpassen: Die
 * Randkoordinaten in `<CODE>.json` legen das Bild auf die Karte. Die Datei
 * wird bewusst mit Textmitteln gelesen – `org.json` ist in den JVM-Unit-Tests
 * nur ein Stub.
 */
class AirportSatelliteAssetTest {

    private val dir = File("src/main/assets/airport_satellite")

    /** Dieselbe Liste wie im Generator. */
    private val codes = listOf(
        "ATL", "PEK", "LAX", "DXB", "HND", "ORD", "LHR", "PVG", "CDG", "DFW",
        "CAN", "AMS", "HKG", "ICN", "FRA", "DEN", "DEL", "SIN", "BKK", "JFK",
        "KUL", "MAD", "SFO", "CTU", "CGK", "SZX", "BCN", "IST", "SEA", "LAS",
        "MCO", "YYZ", "MEX", "CLT", "SVO", "TPE", "KMG", "MUC", "MNL", "XIY",
        "BOM", "LGW", "EWR", "PHX", "MIA", "SHA", "IAH", "CKG", "SYD", "NRT",
        "TFU", "DOH", "PKX", "JED", "FCO", "HGH", "BOG", "ZRH", "VIE", "DUB",
        "CPH", "OSL", "ARN", "HEL", "LIS", "ATH", "PRG", "WAW", "BRU", "PMI",
        "AGP", "AYT", "SAW", "TLV", "DME", "OTP", "BER", "DUS", "MXP", "MAN",
        "STN", "JNB", "CPT", "CAI", "CMN", "ADD", "NBO", "BLR", "MAA", "HYD",
        "DMK", "HKT", "DPS", "SGN", "HAN", "NKG", "WUH", "MSP", "DTW", "BOS"
    )

    /** Flughafenmittelpunkte, um die der Ausschnitt liegen muss. */
    private val airports = mapOf(
        "MUC" to (48.3538 to 11.7861),
        "FRA" to (50.0333 to 8.5706),
        "ATL" to (33.6367 to -84.4281),
        "DXB" to (25.2528 to 55.3644),
        "JFK" to (40.6398 to -73.7789),
        "HND" to (35.5523 to 139.78),
        "SYD" to (-33.9461 to 151.177),
        "BOG" to (4.7016 to -74.1469),
        "MEX" to (19.4363 to -99.0721),
        "DEL" to (28.5665 to 77.1031),
        "JNB" to (-26.1392 to 28.246),
        "LAX" to (33.9425 to -118.408)
    )

    private fun number(json: String, field: String): Double =
        Regex("\"$field\":\\s*(-?[0-9.]+)").find(json)!!.groupValues[1].toDouble()

    private fun json(code: String): String = File(dir, "$code.json").readText()

    @Test
    fun alleHundertBilderLiegenVor() {
        assertEquals(100, codes.distinct().size)
        for (code in codes) {
            assertTrue("$code.webp fehlt", File(dir, "$code.webp").isFile)
            assertTrue("$code.json fehlt", File(dir, "$code.json").isFile)
        }
    }

    @Test
    fun keinBildIstLeerOderWeiss() {
        for (code in codes) {
            val size = File(dir, "$code.webp").length()
            assertTrue("$code.webp ist verdächtig klein (Lücke?): $size B", size > 5_000)
        }
    }

    @Test
    fun ausschnittLiegtAufDemFlughafen() {
        for ((code, expected) in airports) {
            val minLat = number(json(code), "min_lat")
            val minLon = number(json(code), "min_lon")
            val maxLat = number(json(code), "max_lat")
            val maxLon = number(json(code), "max_lon")
            assertEquals("$code Breite", expected.first, (minLat + maxLat) / 2.0, 0.01)
            assertEquals("$code Länge", expected.second, (minLon + maxLon) / 2.0, 0.01)
        }
    }

    /**
     * Der Ausschnitt ist in Web-Mercator 8 km breit. In Längengraden ist das
     * immer dieselbe Spanne; in Breitengraden schrumpft sie mit cos(Breite).
     */
    @Test
    fun ausschnittIstAchtKilometerGross() {
        for ((code, expected) in airports) {
            val json = json(code)
            val spanLat = number(json, "max_lat") - number(json, "min_lat")
            val spanLon = number(json, "max_lon") - number(json, "min_lon")
            val expectedLon = 8000.0 / 111_319.49
            val expectedLat = 8000.0 * Math.cos(Math.toRadians(expected.first)) / 111_319.49
            assertEquals("$code Längenspanne", expectedLon, spanLon, 0.0002)
            assertEquals("$code Breitenspanne", expectedLat, spanLat, 0.0002)
        }
    }

    @Test
    fun quellennachweisIstGesetzt() {
        assertEquals(R.string.satellite_credit_cloudless, AirportSatellite.creditRes())
    }
}
