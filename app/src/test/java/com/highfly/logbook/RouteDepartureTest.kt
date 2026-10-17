package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometrie der Abflug-Animation auf der Flughafen-Detailseite: Länge eines
 * projizierten Streckenzugs, Punkt und Fahrtrichtung darauf sowie der Zeitplan
 * der startenden Flugzeuge. Nur die reinen Rechenwege werden geprüft; das
 * Zeichnen braucht ein Android-Canvas.
 */
class RouteDepartureTest {

    /** Waagerechte Strecke von 0 nach 100 Pixeln. */
    private val eastward = floatArrayOf(0f, 0f, 50f, 0f, 100f, 0f)

    @Test
    fun length_summiertDieAbschnitte() {
        assertEquals(100f, RouteDeparture.length(eastward, 3), 0.001f)
        // Ein einzelner Punkt hat keine Länge.
        assertEquals(0f, RouteDeparture.length(floatArrayOf(3f, 4f), 1), 0.001f)
    }

    @Test
    fun pointAt_trifftDieStuetzstellen() {
        val out = FloatArray(3)
        assertTrue(RouteDeparture.pointAt(eastward, 3, 25f, out))
        assertEquals(25f, out[0], 0.001f)
        assertEquals(0f, out[1], 0.001f)

        assertTrue(RouteDeparture.pointAt(eastward, 3, 75f, out))
        assertEquals(75f, out[0], 0.001f)
        assertEquals(0f, out[1], 0.001f)
    }

    @Test
    fun pointAt_zeigtNachOstenUndNorden() {
        val out = FloatArray(3)
        // Nach Osten: das nach Norden zeigende Symbol dreht sich um 90 Grad.
        assertTrue(RouteDeparture.pointAt(eastward, 3, 10f, out))
        assertEquals(90f, out[2], 0.001f)

        // Nach Norden (y nach unten also nach oben auf dem Schirm): 0 Grad.
        val northward = floatArrayOf(0f, 0f, 0f, -100f)
        assertTrue(RouteDeparture.pointAt(northward, 2, 50f, out))
        assertEquals(0f, out[2], 0.001f)
    }

    @Test
    fun pointAt_klemmtAmEnde() {
        val out = FloatArray(3)
        assertTrue(RouteDeparture.pointAt(eastward, 3, 500f, out))
        assertEquals(100f, out[0], 0.001f)
        assertEquals(0f, out[1], 0.001f)
    }

    @Test
    fun pointAt_ohneStreckeLiefertNichts() {
        assertFalse(RouteDeparture.pointAt(floatArrayOf(1f, 2f), 1, 5f, FloatArray(3)))
    }

    @Test
    fun planeFraction_startetBeiNullUndSteigt() {
        val start = RouteDeparture.planeFraction(0L, 1000L, 0, 0f, 1f)
        assertEquals(0f, start!!, 0.001f)

        val mitte = RouteDeparture.planeFraction(500L, 1000L, 0, 0f, 1f)
        assertEquals(0.5f, mitte!!, 0.001f)
    }

    @Test
    fun planeFraction_pausiertNachDemFlug() {
        // Bei halber Flugzeit (travelFraction) ist die Bahn noch unterwegs, bei
        // drei Vierteln hat sie ihre Pause.
        assertTrue(RouteDeparture.planeFraction(250L, 1000L, 0, 0f, 0.5f) != null)
        assertNull(RouteDeparture.planeFraction(750L, 1000L, 0, 0f, 0.5f))
    }

    @Test
    fun planeFraction_versetztDieBahnen() {
        // Bahn 1 startet später als Bahn 0: zum selben Zeitpunkt ist ihr Anteil
        // kleiner (bzw. sie steht noch, wenn der Versatz über den Umlauf läuft).
        val erste = RouteDeparture.planeFraction(1000L, 10000L, 0, 0.2f, 1f)!!
        val zweite = RouteDeparture.planeFraction(1000L, 10000L, 1, 0.2f, 1f)!!
        assertEquals(0.1f, erste, 0.001f)
        assertEquals(0.3f, zweite, 0.001f)
    }
}
