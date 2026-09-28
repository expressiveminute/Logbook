package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Das Flugzeugsymbol auf der Strecke: mittig platziert und in Fahrtrichtung
 * gedreht. Die Drehung selbst wird getestet, das Platzieren braucht eine
 * Projektion und damit Android.
 */
class RouteMiniMapPlaneTest {

    @Test
    fun flugzeugDrehtSichInFahrtrichtung() {
        // Das Symbol zeigt nach oben, nach Osten muss es also um 90 Grad
        // gedreht werden, nach Sueden um 180.
        assertEquals(90f, RouteMiniMapView.planeRotation(1f, 0f), 0.01f)
        assertEquals(180f, RouteMiniMapView.planeRotation(0f, 1f), 0.01f)
        // Nach Westen muss es auf der anderen Seite liegen, nicht bei 270.
        assertEquals(-90f, RouteMiniMapView.planeRotation(-1f, 0f), 0.01f)
        // Nach Norden bleibt es unverdreht.
        assertEquals(0f, RouteMiniMapView.planeRotation(0f, -1f), 0.01f)
    }

    @Test
    fun flugzeugDrehtSichInDiagonalenRichtungen() {
        // Nordost: Diagonale zwischen oben und rechts.
        assertEquals(45f, RouteMiniMapView.planeRotation(1f, -1f), 0.01f)
        // Suedost
        assertEquals(135f, RouteMiniMapView.planeRotation(1f, 1f), 0.01f)
        // Suedwest
        assertEquals(-135f, RouteMiniMapView.planeRotation(-1f, 1f), 0.01f)
    }

    @Test
    fun richtungOhneLageBleibtUngedreht() {
        // Eine Strecke ohne Ausdehnung hat keine Tangente. Dann darf die
        // Drehung nicht aus einem NaN oder 0/0 werden.
        val wert = RouteMiniMapView.planeRotation(0f, 0f)
        assertTrue(wert.isFinite())
        assertEquals(0f, wert, 0f)
    }

    @Test
    fun mitteLiegtAufDerStrecke() {
        // 49 Stuetzstellen aus ARC_POINTS + 1, die Mitte ist Punkt 24.
        assertEquals(24, RouteMiniMapView.mitte(49))
        // Und immer ein gueltiger Index, egal wie viele Stuetzstellen es gibt.
        for (anzahl in 3..200) {
            val mitte = RouteMiniMapView.mitte(anzahl)
            assertTrue("Anzahl $anzahl", mitte in 1 until anzahl)
        }
    }
}
