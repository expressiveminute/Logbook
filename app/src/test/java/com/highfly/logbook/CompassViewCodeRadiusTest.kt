package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Abstand zwischen dem Buchstaben der Himmelsrichtung und dem Dreilettercode auf
 * dem Kompass. Der Code soll deutlich weiter aussen stehen als die Richtung,
 * aber nie so weit, dass er seitlich aus der Ansicht läuft.
 */
class CompassViewCodeRadiusTest {

    @Test
    fun codeStehtWeiterAussenAlsDieRichtung() {
        // Genug Platz: der gewünschte Abstand wird voll ausgeschöpft.
        val radius = CompassView.clampCodeRadius(
            letterRadius = 100f,
            gap = 40f,
            halfWidth = 400f,
            codeHalfWidth = 18f,
            margin = 4f
        )
        assertEquals(140f, radius, 0.01f)
    }

    @Test
    fun codeWirdNichtAbgeschnitten() {
        // Schmale Ansicht: der Code wird so weit hereingeholt, dass er samt
        // Rand noch in die halbe Breite passt, aber nicht unter die Richtung.
        val halfWidth = 140f
        val codeHalf = 18f
        val margin = 4f
        val radius = CompassView.clampCodeRadius(
            letterRadius = 100f,
            gap = 40f,
            halfWidth = halfWidth,
            codeHalfWidth = codeHalf,
            margin = margin
        )
        assertTrue(radius + codeHalf + margin <= halfWidth + 0.01f)
        assertEquals(118f, radius, 0.01f)
    }

    @Test
    fun codeBleibtMindestensAufDerRichtung() {
        // Extrem enge Ansicht: der Radius darf nicht unter den der Richtung
        // fallen, sonst lägen die Texte übereinander.
        val radius = CompassView.clampCodeRadius(
            letterRadius = 100f,
            gap = 40f,
            halfWidth = 10f,
            codeHalfWidth = 18f,
            margin = 4f
        )
        assertEquals(100f, radius, 0.01f)
    }

    @Test
    fun codeFuerNordUndSuedBleibtInnerhalbDerHoehe() {
        // Nord und Süd werden über die halbe Höhe begrenzt. Ohne diese Schranke
        // (nur die Breite begrenzt) stand der Code oben und unten am Rand und
        // war abgeschnitten, obwohl er waagerecht mitten in der Ansicht saß.
        val halfHeight = 160f
        val codeHalfHeight = 9f
        val margin = 4f
        val radius = CompassView.clampCodeRadius(
            letterRadius = 118f,
            gap = 52f,
            halfWidth = halfHeight,
            codeHalfWidth = codeHalfHeight,
            margin = margin
        )
        assertTrue(radius + codeHalfHeight + margin <= halfHeight + 0.01f)
        assertTrue(radius > 118f)
    }
}
