package com.highfly.logbook

import com.highfly.logbook.GeoMath.Globe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tests für [GeoMath.Globe], die orthografische Sicht auf die Erde.
 *
 * Die Kugel muss in jede Richtung drehbar sein, und der Horizont muss dabei
 * immer genau auf dem Rand der Scheibe liegen, sonst passen Länderumrisse und
 * Wasserkreis nicht mehr zusammen.
 */
class GeoMathGlobeTest {

    private val p = DoubleArray(3)
    private val schraegPunkt = DoubleArray(3)

    private fun globe(lonDeg: Double, latDeg: Double) = Globe(
        Math.toRadians(lonDeg),
        Math.toRadians(latDeg)
    )

    private fun x(lat: Double, lon: Double, g: Globe): Double {
        g.point(lat, lon, p)
        return p[Globe.X]
    }

    private fun y(lat: Double, lon: Double, g: Globe): Double {
        g.point(lat, lon, p)
        return p[Globe.Y]
    }

    private fun front(lat: Double, lon: Double, g: Globe): Double {
        g.point(lat, lon, p)
        return p[Globe.FRONT]
    }

    @Test
    fun derBlickpunktLiegtGenauInDerMitte() {
        for (lat in doubleArrayOf(0.0, 25.0, 48.5, -33.0, 80.0)) {
            val g = globe(11.0, lat)
            assertEquals(0.0, x(lat, 11.0, g), 1e-9)
            assertEquals(0.0, y(lat, 11.0, g), 1e-9)
            assertEquals(1.0, front(lat, 11.0, g), 1e-9)
        }
    }

    @Test
    fun derBlickpunktAmAequatorZeigtNordOben() {
        val g = globe(0.0, 0.0)
        // Genau die alte Projektion: waagerecht cos(lat)·sin(lon), senkrecht sin(lat).
        for (lat in doubleArrayOf(-60.0, -10.0, 0.0, 35.0, 71.5)) {
            for (lon in doubleArrayOf(-120.0, -30.0, 0.0, 45.0, 170.0)) {
                assertEquals(
                    cos(Math.toRadians(lat)) * sin(Math.toRadians(lon)),
                    x(lat, lon, g),
                    1e-9
                )
                assertEquals(sin(Math.toRadians(lat)), y(lat, lon, g), 1e-9)
            }
        }
    }

    @Test
    fun derHorizontFaelltAufDenRandDerScheibe() {
        // Erster Punkt mit FRONT knapp über null ist praktisch auf dem Horizont,
        // dort muss X² + Y² = 1 gelten: der Schnitt liegt auf dem Randkreis.
        for (viewLat in doubleArrayOf(0.0, 30.0, -45.0, 70.0)) {
            val g = globe(0.0, viewLat)
            for (lat in doubleArrayOf(0.0, 20.0, -20.0, 60.0, -60.0, 88.0)) {
                val abstand = front(lat, 0.0, g)
                if (abs(abstand) > 0.05) continue
                g.point(lat, 0.0, p)
                assertEquals(1.0, sqrt(p[Globe.X] * p[Globe.X] + p[Globe.Y] * p[Globe.Y]), 0.05)
            }
        }
    }

    @Test
    fun dieSichtbareHuelfteLiegtInnerhalbDerScheibe() {
        for (viewLat in doubleArrayOf(0.0, 25.0, -25.0, 60.0, -60.0)) {
            val g = globe(17.0, viewLat)
            for (lat in -85..85 step 5) {
                for (lon in -180..180 step 5) {
                    if (front(lat.toDouble(), lon.toDouble(), g) <= 0.0) continue
                    val xx = x(lat.toDouble(), lon.toDouble(), g)
                    val yy = y(lat.toDouble(), lon.toDouble(), g)
                    assertTrue(
                        "Punkt $lat/$lon lag ausserhalb: ${xx * xx + yy * yy}",
                        xx * xx + yy * yy <= 1.0 + 1e-9
                    )
                }
            }
        }
    }

    @Test
    fun dieRueckseiteIstUnsichtbar() {
        val g = globe(0.0, 0.0)
        // Sichtbar ist die Halbkugel von -90 bis +90 Längengrad, der Rest nicht.
        assertTrue(g.inFront(0.0, 89.0, p))
        assertFalse(g.inFront(0.0, 91.0, p))
        assertFalse(g.inFront(0.0, 180.0, p))
        assertFalse(g.inFront(0.0, -180.0, p))
        assertTrue(front(0.0, 180.0, g) < 0.0)
    }

    @Test
    fun dieKugelLaesstSichInJedeRichtungDrehen() {
        // Ein Grad neben dem Nordpol: der Pol selbst liegt minimal über der Mitte.
        val nord = globe(20.0, 89.0)
        assertTrue(nord.inFront(90.0, 20.0, p))
        assertEquals(sin(Math.toRadians(1.0)), y(90.0, 20.0, nord), 1e-9)
        assertFalse(nord.inFront(-60.0, 20.0, p))

        // Blick auf den Südpol: genau umgekehrt, der Pol liegt minimal unter der
        // Mitte, denn Norden ist oben.
        val sued = globe(20.0, -89.0)
        assertTrue(sued.inFront(-90.0, 20.0, p))
        assertEquals(-sin(Math.toRadians(1.0)), y(-90.0, 20.0, sued), 1e-9)
        assertFalse(sued.inFront(60.0, 20.0, p))

        // Blick schräg von oben: Europa und der Nordatlantik sind sichtbar, der
        // Pazifik und Australien liegen auf der Rückseite. New York dagegen ist
        // von hier aus nur 67 Grad entfernt und wäre noch zu sehen.
        val schraeg = globe(15.0, 60.0)
        assertTrue(schraeg.inFront(52.0, 13.0, schraegPunkt))
        assertTrue(schraeg.inFront(40.0, -74.0, schraegPunkt))
        assertFalse(schraeg.inFront(0.0, -140.0, schraegPunkt))
        assertFalse(schraeg.inFront(-25.0, 135.0, schraegPunkt))
    }

    @Test
    fun derBlickWandertMitDemInhaltMit() {
        // Der Punkt unter dem Blickpunkt bleibt in der Mitte, egal wohin man dreht.
        for (lat in doubleArrayOf(0.0, 45.0, -45.0)) {
            for (lon in doubleArrayOf(-170.0, -20.0, 0.0, 95.0)) {
                val g = globe(lon, lat)
                assertEquals(0.0, x(lat, lon, g), 1e-9)
                assertEquals(0.0, y(lat, lon, g), 1e-9)
            }
        }
    }

    @Test
    fun wischenNachUntenZeigtMehrNorden() {
        val g = globe(0.0, 20.0)
        val radius = 200f
        // 100 Pixel nach unten über einen Radius sind 0,5 Radiant nach Norden.
        val nach = Globe(g.centerLon, g.centerLat).drag(0f, 100f, radius)
        assertEquals(0.0, nach[0], 1e-9)
        assertEquals(Math.toRadians(20.0) + 0.5, nach[1], 1e-6)

        // Der Inhalt folgt dem Finger: Nordeuropa rutscht dabei nach unten, also
        // in Richtung Bildschirmmitte, und wird nicht etwa nach oben gekippt.
        val neu = Globe(nach[0], nach[1])
        assertTrue(y(60.0, 0.0, neu) < y(60.0, 0.0, g))
        // Der Punkt unter dem Blickpunkt bleibt dabei in der Mitte.
        assertEquals(
            0.0,
            y(Math.toDegrees(nach[1]), Math.toDegrees(nach[0]), neu),
            1e-9
        )
    }

    @Test
    fun wischenNachRechtsZeigtMehrOsten() {
        val g = globe(0.0, 0.0)
        val nach = Globe(g.centerLon, g.centerLat).drag(100f, 0f, 200f)
        assertEquals(-0.5, nach[0], 1e-9)
        assertEquals(0.0, nach[1], 1e-9)
        // Asien rutscht nach links aus der Mitte heraus, Europa kommt herein.
        val neu = Globe(nach[0], nach[1])
        assertTrue(x(100.0, 0.0, neu) < 0.0)
    }

    @Test
    fun dieBreiteBleibtAnDenPolenStehen() {
        var lat = 0.0
        repeat(200) { lat = Globe(0.0, lat).drag(0f, 50f, 100f)[1] }
        assertEquals(PI / 2.0, lat, 1e-9)

        var zurueck = 0.0
        repeat(400) { zurueck = Globe(0.0, zurueck).drag(0f, -50f, 100f)[1] }
        assertEquals(-PI / 2.0, zurueck, 1e-9)
    }

    @Test
    fun derHorizontSchnittStimmtMitDerRaeumlichenRechnungUeberein() {
        // Die Kürzung im Code interpoliert X und Y direkt zwischen den Endpunkten.
        // Das ist nur richtig, wenn beide linear in den Raumkoordinaten sind. Der
        // Test rechnet den Schnittpunkt deshalb auf dem langen Weg: erst im Raum
        // interpolieren, dann projizieren.
        val g = globe(15.0, 60.0)
        // Alle Paare müssen den Horizont auch wirklich schneiden, je ein Endpunkt
        // auf jeder Seite: Mitteleuropa nach Südamerika, Australien nach Kanada,
        // Japan nach Chile, Südpol nach Nordpol.
        val paare = arrayOf(
            doubleArrayOf(52.0, 13.0, -20.0, -60.0),
            doubleArrayOf(-33.0, 151.0, 60.0, -100.0),
            doubleArrayOf(35.0, 139.0, -40.0, -70.0),
            doubleArrayOf(-80.0, 0.0, 80.0, 0.0)
        )
        val a = DoubleArray(3)
        val b = DoubleArray(3)
        for (paar in paare) {
            val winkel = g.horizon(paar[0], paar[1], paar[2], paar[3], a, b)
            assertFalse("Segment ${paar.toList()} schneidet nicht", winkel.isNaN())

            val t = front(paar[0], paar[1], g) /
                (front(paar[0], paar[1], g) - front(paar[2], paar[3], g))
            val ax = raumX(paar[0], paar[1])
            val ay = raumY(paar[0], paar[1])
            val az = Math.sin(Math.toRadians(paar[0]))
            val bx = raumX(paar[2], paar[3])
            val by = raumY(paar[2], paar[3])
            val bz = Math.sin(Math.toRadians(paar[2]))
            val qx = ax + t * (bx - ax)
            val qy = ay + t * (by - ay)
            val qz = az + t * (bz - az)
            val len = sqrt(qx * qx + qy * qy + qz * qz)
            // X ist cosφ·sin(λ−λ0), im Raum also Qy·cos λ0 − Qx·sin λ0.
            val rx = (qy / len) * cos(g.centerLon) - (qx / len) * sin(g.centerLon)
            val ry = cos(g.centerLat) * (qz / len) -
                sin(g.centerLat) * ((qx / len) * cos(g.centerLon) + (qy / len) * sin(g.centerLon))

            assertEquals("X ${paar.toList()}", rx, cos(winkel), 1e-9)
            assertEquals("Y ${paar.toList()}", ry, sin(winkel), 1e-9)
        }
    }

    @Test
    fun einSegmentInnerhalbEinerSeiteSchneidetDenHorizontNicht() {
        val g = globe(0.0, 0.0)
        val a = DoubleArray(3)
        val b = DoubleArray(3)
        assertTrue(g.horizon(0.0, 0.0, 10.0, 10.0, a, b).isNaN())
        assertTrue(g.horizon(0.0, 100.0, 10.0, 140.0, a, b).isNaN())
    }

    private fun raumX(lat: Double, lon: Double): Double =
        cos(Math.toRadians(lat)) * cos(Math.toRadians(lon))

    private fun raumY(lat: Double, lon: Double): Double =
        cos(Math.toRadians(lat)) * sin(Math.toRadians(lon))
}
