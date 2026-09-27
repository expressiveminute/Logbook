package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Tests für [GeoMath.globeRotation], den Fingerweg als Drehwinkel der Kugel. */
class GeoMathGlobeRotationTest {

    @Test
    fun ziehtDerFingerNachRechtsDrehtDieKugelNachWesten() {
        val radius = 200f
        val nachRechts = GeoMath.globeRotation(100f, radius)
        val nachLinks = GeoMath.globeRotation(-100f, radius)

        // Der Inhalt wandert mit dem Finger, der Mittelpunkt also in die
        // Gegenrichtung. Der Winkel bleibt klein und vorzeichenbehaftet, damit
        // ihn der Aufrufer einfach aufsummieren kann.
        assertEquals(-0.5, nachRechts, 1e-9)
        assertEquals(0.5, nachLinks, 1e-9)
    }

    @Test
    fun einRadiantEntsprichtGenauDemKugelradius() {
        val radius = 137f
        assertEquals(1.0, GeoMath.globeRotation(-radius, radius), 1e-6)
        assertEquals(2 * PI, GeoMath.globeRotation(-radius * 6.2831855f, radius), 1e-3)
    }

    @Test
    fun vieleKleineSchritteErggebenDenGesamtenFingerweg() {
        val radius = 50f
        var summe = 0.0
        repeat(400) { summe += GeoMath.globeRotation(7.5f, radius) }

        // 400 Schritte nach je 7,5 Pixeln nach rechts ergeben -60 Radiant, die
        // Karte ist nach so vielen Schritten wieder die gleiche wie am Anfang.
        // Die Zwischensummen bleiben klein statt über den Nullpunkt zu springen.
        assertEquals(-60.0, summe, 1e-9)
        assertTrue(GeoMath.globeRotation(7.5f, radius) < 0.0)
    }

    @Test
    fun ohneKugelDrehtSichNichts() {
        assertEquals(0.0, GeoMath.globeRotation(120f, 0f), 0.0)
        assertEquals(0.0, GeoMath.globeRotation(120f, -5f), 0.0)
    }

    @Test
    fun ohneFingerwegBleibtDerWinkelUnveraendert() {
        assertEquals(0.0, GeoMath.globeRotation(0f, 200f), 0.0)
    }
}
