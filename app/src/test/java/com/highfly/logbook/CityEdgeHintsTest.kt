package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tests für die Randhinweise der CO₂-Ausgleichskarte, siehe
 * [CityEdgeHints.clamp].
 */
class CityEdgeHintsTest {

    private val left = 0f
    private val top = 0f
    private val right = 1000f
    private val bottom = 500f

    private fun dirLength(x: Float, y: Float) = sqrt(x * x + y * y)

    @Test
    fun punktImBildBleibtUnveraendert() {
        val edge = CityEdgeHints.clamp(400f, 300f, left, top, right, bottom)
        assertTrue(edge.onScreen)
        assertEquals(400f, edge.x, 0.001f)
        assertEquals(300f, edge.y, 0.001f)
    }

    @Test
    fun punktRechtsWirdAufDenRechtenRandGezogen() {
        val edge = CityEdgeHints.clamp(2400f, 250f, left, top, right, bottom)
        assertFalse(edge.onScreen)
        assertEquals(right, edge.x, 0.001f)
        // Geradeaus rechts: die Mitte der Höhe bleibt.
        assertEquals(250f, edge.y, 0.001f)
    }

    @Test
    fun punktObenWirdAufDenOberenRandGezogen() {
        val edge = CityEdgeHints.clamp(500f, -800f, left, top, right, bottom)
        assertFalse(edge.onScreen)
        assertEquals(top, edge.y, 0.001f)
        assertEquals(500f, edge.x, 0.001f)
    }

    @Test
    fun diagonaleKommtAnDerPassendenKanteAn() {
        // Nach rechts unten, aber deutlich mehr nach unten als nach rechts:
        // die untere Kante ist zuerst erreicht.
        val unten = CityEdgeHints.clamp(3000f, 3000f, left, top, right, bottom)
        assertFalse(unten.onScreen)
        assertEquals(bottom, unten.y, 0.001f)
        assertTrue(unten.x < right)

        // Nach rechts unten, aber deutlich mehr nach rechts als nach unten:
        // die rechte Kante ist zuerst erreicht.
        val rechts = CityEdgeHints.clamp(3000f, 1000f, left, top, right, bottom)
        assertFalse(rechts.onScreen)
        assertEquals(right, rechts.x, 0.001f)
        assertTrue(rechts.y < bottom)
    }

    @Test
    fun richtungIstEinEinheitsvektor() {
        val edge = CityEdgeHints.clamp(3000f, 3000f, left, top, right, bottom)
        assertEquals(1f, dirLength(edge.dirX, edge.dirY), 0.001f)
        assertTrue(edge.dirX > 0f)
        assertTrue(edge.dirY > 0f)
    }

    @Test
    fun richtungDesPunktsImBildZeigtNachAussen() {
        // Ein Ort oben links im Bild: sein Hinweis zeigt nach links oben.
        val edge = CityEdgeHints.clamp(100f, 100f, left, top, right, bottom)
        assertTrue(edge.onScreen)
        assertTrue(edge.dirX < 0f)
        assertTrue(edge.dirY < 0f)
    }

    @Test
    fun mittelpunktHatKeineRichtung() {
        val edge = CityEdgeHints.clamp(500f, 250f, left, top, right, bottom)
        assertTrue(edge.onScreen)
        // Ohne Richtung: Einheitsvektor fällt auf "nach rechts" zurück.
        assertEquals(500f, edge.x, 0.001f)
        assertEquals(1f, dirLength(edge.dirX, edge.dirY), 0.001f)
        assertEquals(1f, edge.dirX, 0.001f)
        assertEquals(0f, edge.dirY, 0.001f)
    }

    @Test
    fun unfaelligesRechteckBleibtStabil() {
        // Ein Rechteck ohne Fläche darf weder teilen noch überlaufen.
        val edge = CityEdgeHints.clamp(10f, 20f, 5f, 5f, 5f, 5f)
        assertEquals(5f, edge.x, 0.001f)
        assertEquals(5f, edge.y, 0.001f)
    }
}