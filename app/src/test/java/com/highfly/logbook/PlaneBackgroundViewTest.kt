package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometrie der Hintergrund-Flugzeuge: Die kubische Bahn wird abgetastet, aus
 * den Stützstellen kommen Position und Flugrichtung. Nur die reinen Rechenwege
 * werden geprüft; das Zeichnen braucht ein Android-Canvas.
 */
class PlaneBackgroundViewTest {

    @Test
    fun cubicPoint_trifftAnfangUndEnde() {
        val start = PlaneBackgroundView.cubicPoint(0f, 0f, 10f, 20f, 30f, 20f, 40f, 0f, 0f)
        assertEquals(0f, start[0], 0.001f)
        assertEquals(0f, start[1], 0.001f)

        val end = PlaneBackgroundView.cubicPoint(0f, 0f, 10f, 20f, 30f, 20f, 40f, 0f, 1f)
        assertEquals(40f, end[0], 0.001f)
        assertEquals(0f, end[1], 0.001f)
    }

    @Test
    fun cubicPoint_hatBeiSymmetrieDenScheitelInDerMitte() {
        // Symmetrische Kontrollpunkte: die Mitte liegt genau auf der Sehne,
        // aber um die halbe Kontrollhoehe angehoben.
        val mid = PlaneBackgroundView.cubicPoint(0f, 0f, 0f, 30f, 40f, 30f, 40f, 0f, 0.5f)
        assertEquals(20f, mid[0], 0.001f)
        assertEquals(22.5f, mid[1], 0.001f)
    }

    @Test
    fun kontrollpunkte_ohneAuslenkungLiegenAufDerSehne() {
        val c = PlaneBackgroundView.curveControls(0f, 0f, 100f, 0f, 0.25f, 0.75f, 0f, 0f)
        assertEquals(25f, c[0], 0.001f)
        assertEquals(0f, c[1], 0.001f)
        assertEquals(75f, c[2], 0.001f)
        assertEquals(0f, c[3], 0.001f)
    }

    @Test
    fun gegenlaeufigeAuslenkung_ergibtEineSKurveMitZweiBogen() {
        // Sehne nach Osten (0,0)->(100,0). Die Normale zeigt nach oben, also
        // lenkt +30 den ersten Kontrollpunkt nach oben, -30 den zweiten nach
        // unten. Die Bahn schwingt zuerst nach oben, dann nach unten.
        val c = PlaneBackgroundView.curveControls(0f, 0f, 100f, 0f, 0.25f, 0.75f, 30f, -30f)
        val points = PlaneBackgroundView.sampleCubic(
            0f, 0f, c[0], c[1], c[2], c[3], 100f, 0f, PlaneBackgroundView.PATH_SAMPLES
        )
        val quarter = PlaneBackgroundView.pointAt(points, 0.25f)
        val mid = PlaneBackgroundView.pointAt(points, 0.5f)
        val threeQuarter = PlaneBackgroundView.pointAt(points, 0.75f)
        assertTrue("Viertel muss nach oben ausschwingen", quarter[1] > 1f)
        assertEquals("Mitte liegt auf der Sehne", 0f, mid[1], 0.01f)
        assertTrue("Dreiviertel muss nach unten ausschwingen", threeQuarter[1] < -1f)
    }

    @Test
    fun gleichlaeufigeAuslenkung_ergibtEinenBogen() {
        val c = PlaneBackgroundView.curveControls(0f, 0f, 100f, 0f, 0.25f, 0.75f, 30f, 30f)
        val points = PlaneBackgroundView.sampleCubic(
            0f, 0f, c[0], c[1], c[2], c[3], 100f, 0f, PlaneBackgroundView.PATH_SAMPLES
        )
        // Alle Punkte liegen auf einer Seite der Sehne, die Mitte am weitesten.
        val mid = PlaneBackgroundView.pointAt(points, 0.5f)
        assertTrue("Bogen muss die Sehne verlassen", mid[1] > 5f)
        for (i in 1 until PlaneBackgroundView.PATH_SAMPLES - 1) {
            assertTrue("Punkt $i darf nicht unter die Sehne", points[i * 2 + 1] >= -0.001f)
        }
    }

    @Test
    fun kontrollpunkte_ohneLaengeBleibenEndlich() {
        val c = PlaneBackgroundView.curveControls(5f, 5f, 5f, 5f, 0.3f, 0.7f, 20f, -20f)
        assertEquals(5f, c[0], 0.001f)
        assertEquals(5f, c[1], 0.001f)
    }

    @Test
    fun sampleCubic_legtAnfangUndEndeRichtigUndAbtastungIstDicht() {
        val points = PlaneBackgroundView.sampleCubic(
            0f, 0f, 10f, 20f, 30f, 20f, 40f, 0f, PlaneBackgroundView.PATH_SAMPLES
        )
        assertEquals(PlaneBackgroundView.PATH_SAMPLES * 2, points.size)
        assertEquals(0f, points[0], 0.001f)
        assertEquals(0f, points[1], 0.001f)
        val last = PlaneBackgroundView.PATH_SAMPLES - 1
        assertEquals(40f, points[last * 2], 0.001f)
        assertEquals(0f, points[last * 2 + 1], 0.001f)
    }

    @Test
    fun punktAufDerBahn_wirdLinearZwischenDenStuetzstellenInterpoliert() {
        // Gerade Bahn von (0,0) nach (100,0) in 3 Stuetzstellen.
        val points = floatArrayOf(0f, 0f, 50f, 0f, 100f, 0f)
        assertEquals(0f, PlaneBackgroundView.pointAt(points, 0f)[0], 0.001f)
        assertEquals(25f, PlaneBackgroundView.pointAt(points, 0.25f)[0], 0.001f)
        assertEquals(50f, PlaneBackgroundView.pointAt(points, 0.5f)[0], 0.001f)
        assertEquals(100f, PlaneBackgroundView.pointAt(points, 1f)[0], 0.001f)
    }

    @Test
    fun punktAufDerBahn_begrenztWerteAusserhalb() {
        val points = floatArrayOf(0f, 0f, 100f, 0f)
        assertEquals(0f, PlaneBackgroundView.pointAt(points, -5f)[0], 0.001f)
        assertEquals(100f, PlaneBackgroundView.pointAt(points, 5f)[0], 0.001f)
    }

    @Test
    fun laengeDerBahn_summiertDieAbschnitte() {
        val points = floatArrayOf(0f, 0f, 30f, 40f, 30f, 80f)
        // 50 + 40
        assertEquals(90f, PlaneBackgroundView.pathLength(points), 0.001f)
    }

    @Test
    fun flugrichtung_zeigtInFahrtrichtung() {
        // Nach Osten (rechts): Drehung 90 Grad wie auf der Streckenkarte.
        val east = floatArrayOf(0f, 0f, 100f, 0f)
        assertEquals(90f, PlaneBackgroundView.planeRotation(east, 0.5f), 0.01f)
        // Nach Sueden (unten): 180 Grad.
        val south = floatArrayOf(0f, 0f, 0f, 100f)
        assertEquals(180f, PlaneBackgroundView.planeRotation(south, 0.5f), 0.01f)
    }

    @Test
    fun flugrichtung_bleibtBeiLeererBahnEndlich() {
        val wert = PlaneBackgroundView.planeRotation(FloatArray(0), 0.5f)
        assertTrue(wert.isFinite())
        assertEquals(0f, wert, 0f)
    }
}
