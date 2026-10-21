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

    /** Flughafenkoordinaten (aus `airports.json`): Sie müssen im Ausschnitt liegen. */
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
        "LAX" to (33.9425 to -118.408),
        "DEN" to (39.8617 to -104.673),
        "OSL" to (60.1939 to 11.1004)
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

    /**
     * Die Flughafenkoordinate liegt im Ausschnitt, der Mittelpunkt in deren
     * Nähe – der Kasten ist pro Flughafen auf das Pistengebiet zentriert, nicht
     * exakt auf die Koordinate (die liegt z. B. in Madrid am Südrand).
     */
    @Test
    fun flughafenLiegtImAusschnitt() {
        for ((code, expected) in airports) {
            val minLat = number(json(code), "min_lat")
            val minLon = number(json(code), "min_lon")
            val maxLat = number(json(code), "max_lat")
            val maxLon = number(json(code), "max_lon")
            assertTrue(
                "$code: Koordinate liegt nicht im Ausschnitt",
                expected.first in minLat..maxLat && expected.second in minLon..maxLon
            )
            assertEquals("$code Mittelpunkt Breite", expected.first, (minLat + maxLat) / 2.0, 0.05)
            assertEquals("$code Mittelpunkt Länge", expected.second, (minLon + maxLon) / 2.0, 0.05)
        }
    }

    /**
     * Der Ausschnitt ist in Web-Mercator quadratisch und pro Flughafen so groß,
     * dass das ganze Pistengebiet samt Rand hineinpasst: mindestens 8 km,
     * höchstens 18 km Kantenlänge (Generator: `--radius-km` … `--max-box-km`).
     */
    @Test
    fun ausschnittIstQuadratischUndImRahmen() {
        for ((code, _) in airports) {
            val json = json(code)
            val spanX = mercX(number(json, "max_lon")) - mercX(number(json, "min_lon"))
            val spanY = mercY(number(json, "max_lat")) - mercY(number(json, "min_lat"))
            assertEquals("$code quadratisch (Mercator)", spanX, spanY, spanX * 0.02)
            assertTrue("$code Kasten zu klein: $spanX m", spanX >= 7_900.0)
            assertTrue("$code Kasten zu groß: $spanX m", spanX <= 18_100.0)
        }
    }

    private fun mercX(lon: Double): Double = Math.toRadians(lon) * 6_378_137.0

    private fun mercY(lat: Double): Double =
        Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(lat) / 2.0)) * 6_378_137.0

    @Test
    fun quellennachweisIstGesetzt() {
        assertEquals(R.string.satellite_credit_cloudless, AirportSatellite.creditRes())
    }
}
