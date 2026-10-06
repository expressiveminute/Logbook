package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Auswahl auf der Weltkarte. Der Fehler, den es hier gab, liess sich nur
 * auf dem Geraet sehen: Ein Flughafen, den man nur anfliegt, hatte keine
 * verbundenen Flughäfen, und damit blieb die ganze Karte ausgeblendet.
 */
class MapSelectionTest {

    private val routes = listOf(
        "FRA" to "JFK",
        "MUC" to "JFK",
        "FRA" to "DXB",
        "BER" to "WAW",
        "DXB" to "BOM",
    )

    @Test
    fun `ohne Auswahl bleibt alles sichtbar`() {
        assertEquals(routes, MapSelection.touches(routes, null))
        assertEquals(emptySet<String>(), MapSelection.connected(routes, null))
    }

    @Test
    fun `ein Abflughafen zeigt alle Fluege von ihm`() {
        assertEquals(
            listOf("FRA" to "JFK", "FRA" to "DXB"),
            MapSelection.touches(routes, "FRA")
        )
        assertEquals(setOf("JFK", "DXB"), MapSelection.connected(routes, "FRA"))
    }

    @Test
    fun `ein nur angeflogener Flughafen zeigt seine Ankunft`() {
        // JFK wird in den Daten nie angeflogen, nur angeflogen von FRA und MUC.
        assertEquals(
            listOf("FRA" to "JFK", "MUC" to "JFK"),
            MapSelection.touches(routes, "JFK")
        )
        assertEquals(setOf("FRA", "MUC"), MapSelection.connected(routes, "JFK"))
    }

    @Test
    fun `ein Flughafen mit Ab- und Anfluegen zeigt beides`() {
        // DXB wird angeflogen (FRA-DXB) und fliegt selbst weiter (DXB-BOM):
        // beide Enden der Auswahl gehoeren zu derselben Menge.
        assertEquals(
            listOf("FRA" to "DXB", "DXB" to "BOM"),
            MapSelection.touches(routes, "DXB")
        )
        assertEquals(setOf("FRA", "BOM"), MapSelection.connected(routes, "DXB"))
    }

    @Test
    fun `eine einzelne Verbindung wird erkannt`() {
        assertTrue(MapSelection.touches("BER" to "WAW", "WAW"))
        assertTrue(MapSelection.touches("BER" to "WAW", null))
        assertTrue(!MapSelection.touches("BER" to "WAW", "FRA"))
    }

    @Test
    fun `ein Flughafen ohne Verbindung hat keine Gegenstelle`() {
        assertEquals(emptySet<String>(), MapSelection.connected(routes, "CDG"))
        assertEquals(emptyList<Pair<String, String>>(), MapSelection.touches(routes, "CDG"))
    }

    @Test
    fun `im Demodatensatz hat jeder angeflogene Flughafen eine Gegenstelle`() {
        val today = java.time.LocalDate.of(2026, 10, 4)
        val geflogen = DemoData.entries(today).filter { !it.date.isAfter(today) }
        val abfluege = geflogen.map { it.fromAirport }.toSet()
        val ankuenfte = geflogen.map { it.toAirport }.toSet()
        val nurAnkunft = (abfluege + ankuenfte).filter { it !in abfluege }
        val pairs = geflogen.flatMap { listOf(it.fromAirport to it.toAirport, it.toAirport to it.fromAirport) }
        // Genau diese Flughäfen waren die, bei denen nichts hervorgehoben wurde.
        assertEquals(listOf("JNB", "LAX", "PEK", "SFO", "WAW"), nurAnkunft.sorted())
        nurAnkunft.forEach { ap ->
            assertTrue(
                "$ap hat keine Gegenstelle",
                MapSelection.connected(pairs, ap).isNotEmpty()
            )
        }
    }
}
