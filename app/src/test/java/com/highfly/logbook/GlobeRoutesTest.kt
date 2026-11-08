package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tests für die Routenberechnung der Weltkugel, siehe [GlobeRoutes].
 */
class GlobeRoutesTest {

    private val locations = mapOf(
        "FRA" to AirportData.GeoLocation(50.0333, 8.5706),
        "JFK" to AirportData.GeoLocation(40.6413, -73.7781),
        "LHR" to AirportData.GeoLocation(51.47, -0.4543)
    )

    private fun entry(from: String, to: String) = LogbookEntry(
        date = LocalDate.of(2026, 3, 12),
        fromAirport = from,
        toAirport = to
    )

    @Test
    fun compute_grupppiertVerbindungenRichtungsabhaengig() {
        val result = GlobeRoutes.compute(
            listOf(entry("FRA", "JFK"), entry("FRA", "JFK"), entry("JFK", "FRA")),
            { locations[it.uppercase()] }
        )
        assertEquals(2, result.routes.size)
        val fraJfk = result.routes.single { it.from == "FRA" && it.to == "JFK" }
        assertEquals(2, fraJfk.count)
        val jfkFra = result.routes.single { it.from == "JFK" && it.to == "FRA" }
        assertEquals(1, jfkFra.count)
        assertEquals(setOf("FRA", "JFK"), result.airports.keys)
        // FRA und JFK sind je einmal verbunden.
        assertEquals(1, result.connections["FRA"])
        assertEquals(1, result.connections["JFK"])
    }

    @Test
    fun compute_bogenPunkteSindEinheitsvektoren() {
        val result = GlobeRoutes.compute(listOf(entry("FRA", "JFK")), { locations[it.uppercase()] })
        val arc = result.routes.single()
        assertTrue(arc.units.size % 3 == 0)
        assertTrue(arc.units.size >= 6) // mindestens zwei Punkte
        for (i in 0 until arc.units.size / 3) {
            val x = arc.units[i * 3]
            val y = arc.units[i * 3 + 1]
            val z = arc.units[i * 3 + 2]
            val len = kotlin.math.sqrt(x * x + y * y + z * z)
            assertEquals(1.0, len, 1e-9)
        }
        // Anfang und Ende des Bogens sind die Flughäfen.
        val pts = arc.points()
        assertEquals(50.0333, pts.first().first, 1e-6)
        assertEquals(8.5706, pts.first().second, 1e-6)
        assertEquals(40.6413, pts.last().first, 1e-6)
        assertEquals(-73.7781, pts.last().second, 1e-6)
    }

    @Test
    fun compute_unbekannteKoordinatenFallenHeraus() {
        val result = GlobeRoutes.compute(
            listOf(entry("FRA", "XXX"), entry("FRA", "LHR")),
            { locations[it.uppercase()] }
        )
        assertEquals(1, result.routes.size)
        assertEquals(setOf("FRA", "LHR"), result.airports.keys)
    }

    @Test
    fun compute_leereUndGleicheCodesFallenHeraus() {
        val result = GlobeRoutes.compute(
            listOf(entry("", "JFK"), entry("FRA", "FRA"), entry("  ", "LHR")),
            { locations[it.uppercase()] }
        )
        assertTrue(result.routes.isEmpty())
        assertTrue(result.airports.isEmpty())
    }
}
