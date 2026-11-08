package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tests für die DP-Dezimierung der Kugel-Ringe ([GeoMath.decimateUnitsDp] /
 * [GeoMath.decimateUnitsSafe] / [GeoMath.selfIntersects]): Eine gleichmäßige
 * Schritt-Dezimierung konnte lange Sehnen quer durch konkave Länder erzeugen
 * (sichtbare Fehlflächen), eine Schlaufe Löcher in der Füllung.
 */
class GeoMathDecimateTest {

    private fun unitsOf(points: List<Pair<Double, Double>>): DoubleArray {
        val out = DoubleArray(points.size * 3)
        for ((i, p) in points.withIndex()) {
            val lat = Math.toRadians(p.second)
            val lon = Math.toRadians(p.first)
            val cl = cos(lat)
            out[i * 3] = cl * cos(lon)
            out[i * 3 + 1] = cl * sin(lon)
            out[i * 3 + 2] = sin(lat)
        }
        return out
    }

    @Test
    fun selfIntersects_erkenntFigurAcht() {
        // Bowtie: (0,0)-(2,2)-(2,0)-(0,2) schneidet sich in der Mitte.
        val bowtie = unitsOf(listOf(0.0 to 0.0, 2.0 to 2.0, 2.0 to 0.0, 0.0 to 2.0))
        assertTrue(GeoMath.selfIntersects(bowtie))
    }

    @Test
    fun selfIntersects_einfachesRechteckIstSauber() {
        val square = unitsOf(listOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 4.0, 0.0 to 4.0))
        assertFalse(GeoMath.selfIntersects(square))
    }

    @Test
    fun selfIntersects_beruehrungAnGemeinsamemPunktZaehltNicht() {
        val ring = unitsOf(
            listOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 4.0, 4.0 to 4.0, 0.0 to 4.0)
        )
        assertFalse(GeoMath.selfIntersects(ring))
    }

    @Test
    fun selfIntersects_ueberDatumslinieIstKeinSchnitt() {
        val ring = unitsOf(listOf(179.0 to 0.0, -179.0 to 1.0, -179.0 to -1.0, 179.0 to 0.0))
        assertFalse(GeoMath.selfIntersects(ring))
    }

    @Test
    fun decimateUnitsDp_grosseToleranzSpartPunkteKleineHaeltAlle() {
        // C-Form mit Zacken: 8 Punkte, ~4° Ausdehnung = ~0.07 rad
        val cShape = unitsOf(
            listOf(
                0.0 to 0.0, 4.0 to 0.0, 4.0 to 1.0, 1.0 to 1.0,
                1.0 to 3.0, 4.0 to 3.0, 4.0 to 4.0, 0.0 to 4.0
            )
        )
        assertFalse(GeoMath.selfIntersects(cShape))
        // sehr große Toleranz (0.2 rad > Ausdehnung) -> nur Endpunkte bleiben
        val coarse = GeoMath.decimateUnitsDp(cShape, 0.2)
        assertTrue(coarse.size / 3 <= 3)
        // winzige Toleranz -> alles bleibt
        val fine = GeoMath.decimateUnitsDp(cShape, 1e-6)
        assertEquals(cShape.size, fine.size)
    }

    @Test
    fun decimateUnitsDp_haeltFormBeiKonkavitaet() {
        // schmaler Einschnitt: gleichmäßige Schritt-Dezimierung schneidet ihn weg,
        // DP mit kleiner Toleranz behält die Spitze.
        val notch = unitsOf(
            listOf(
                0.0 to 0.0, 4.0 to 0.0, 4.0 to 4.0, 2.1 to 4.0, 2.1 to 1.0, 1.9 to 1.0,
                1.9 to 4.0, 0.0 to 4.0
            )
        )
        val eps = Math.toRadians(0.05) // 0.05° ~ schmaler Einschnitt
        val dec = GeoMath.decimateUnitsDp(notch, eps)
        // die Spitze des Einschnitts (2.1,1.0)/(1.9,1.0) darf nicht fehlen:
        assertTrue(dec.size / 3 >= 6)
        assertFalse(GeoMath.selfIntersects(dec))
    }

    @Test
    fun decimateUnitsSafe_hältDenRingSchnittfrei() {
        val cShape = unitsOf(
            listOf(
                0.0 to 0.0, 4.0 to 0.0, 4.0 to 1.0, 1.0 to 1.0,
                1.0 to 3.0, 4.0 to 3.0, 4.0 to 4.0, 0.0 to 4.0
            )
        )
        // naive große Toleranz erzeugt eine Schlaufe, die sichere Variante nicht:
        for (epsDeg in doubleArrayOf(2.0, 1.0, 0.5, 0.2, 0.1, 0.05)) {
            val eps = Math.toRadians(epsDeg)
            val safe = GeoMath.decimateUnitsSafe(cShape, eps)
            assertFalse("eps=$epsDeg ergab einen Schnitt", GeoMath.selfIntersects(safe))
        }
    }

    @Test
    fun decimateUnitsSafe_forceKeepHältMarkiertePunkteAuchBeiGrosserToleranz() {
        // Rechteck mit zwei "Grenz"-Punkten in der Mitte der Kanten: Selbst bei
        // riesiger Toleranz (die alle Innenpunkte entfernen würde) müssen die
        // als forceKeep markierten Punkte erhalten bleiben - sonst klafft an
        // gemeinsamen Ländergrenzen eine Lücke zwischen den beiden Füllungen.
        val ring = unitsOf(
            listOf(
                0.0 to 0.0, 2.0 to 0.0, 4.0 to 0.0,
                4.0 to 2.0, 4.0 to 4.0,
                2.0 to 4.0, 0.0 to 4.0, 0.0 to 2.0
            )
        )
        val force = BooleanArray(8)
        force[1] = true // (2, 0)
        force[5] = true // (2, 4)
        val eps = Math.toRadians(10.0) // viel größer als die Ausdehnung
        val out = GeoMath.decimateUnitsSafe(ring, eps, force)
        // umgekehrte lon/lat-Konvertierung zum Vergleich
        val outPoints = (0 until out.size / 3).map { i ->
            val ux = out[i * 3]; val uy = out[i * 3 + 1]; val uz = out[i * 3 + 2]
            Math.toDegrees(kotlin.math.atan2(uy, ux)) to Math.toDegrees(kotlin.math.asin(uz))
        }
        assertTrue("forceKeep-Punkt (2,0) fehlt", outPoints.any { kotlin.math.abs(it.first - 2.0) < 1e-6 && kotlin.math.abs(it.second - 0.0) < 1e-6 })
        assertTrue("forceKeep-Punkt (2,4) fehlt", outPoints.any { kotlin.math.abs(it.first - 2.0) < 1e-6 && kotlin.math.abs(it.second - 4.0) < 1e-6 })
        // ohne forceKeep wären sie bei dieser Toleranz weg
        val plain = GeoMath.decimateUnitsSafe(ring, eps)
        val plainPoints = (0 until plain.size / 3).map { i ->
            Math.toDegrees(kotlin.math.atan2(plain[i * 3 + 1], plain[i * 3])) to
                Math.toDegrees(kotlin.math.asin(plain[i * 3 + 2]))
        }
        assertFalse(plainPoints.any { kotlin.math.abs(it.first - 2.0) < 1e-6 && kotlin.math.abs(it.second - 0.0) < 1e-6 })
    }

    @Test
    fun subsampleUnitsMaxSegment_begrenztDenSegmentwinkel() {
        // Halbkreisbogen (180°) mit 96 Punkten: bei maxSeg 10° bleiben höchstens
        // ~19 Punkte, und aufeinanderfolgende Punkte sind nie weiter als ~10° entfernt.
        val arc = unitsOf((0..95).map { i ->
            val a = Math.PI * i / 95.0
            Math.toDegrees(a) to 0.0 // Halbkreis auf dem Äquator (lon 0..180°, lat 0)
        })
        val out = GeoMath.subsampleUnitsMaxSegment(arc, Math.toRadians(10.0))
        val n = out.size / 3
        assertTrue("zu viele Punkte: $n", n <= 20)
        assertTrue(n >= 3)
        // Endpunkte erhalten
        assertEquals(arc[0], out[0], 1e-12)
        assertEquals(arc[arc.size - 3], out[out.size - 3], 1e-12)
        // Segmentwinkel <= 10° + kleine Toleranz
        for (i in 0 until n - 1) {
            val dot = out[i * 3] * out[(i + 1) * 3] + out[i * 3 + 1] * out[(i + 1) * 3 + 1] +
                out[i * 3 + 2] * out[(i + 1) * 3 + 2]
            val angle = kotlin.math.acos(dot.coerceIn(-1.0, 1.0))
            // ein Originalschritt (180°/95 ≈ 1,9°) darf zusätzlich drin sein
            assertTrue("Segment $i zu lang: ${Math.toDegrees(angle)}°", angle <= Math.toRadians(12.0))
        }
    }

    @Test
    fun subsampleUnitsMaxSegment_kurzerBogenKlapptAufEndpunkte() {
        // 10°-Bogen mit 11 Punkten, maxSeg 30°: der ganze Bogen ist kürzer als
        // maxSeg -> nur die Endpunkte bleiben (Sehne weicht < 0,004 rad ab).
        val arc = unitsOf((0..10).map { i -> (1.0 * i) to 0.0 })
        val out = GeoMath.subsampleUnitsMaxSegment(arc, Math.toRadians(30.0))
        assertEquals(2 * 3, out.size)
        // maxSeg 3°: alle ~3-4 Punkte bleiben
        val fine = GeoMath.subsampleUnitsMaxSegment(arc, Math.toRadians(3.0))
        assertTrue(fine.size / 3 >= 4)
        assertTrue(fine.size / 3 <= 11)
    }

    @Test
    fun decimateUnitsSafe_forceKeepErzeugtKeineSelbstschnitte() {
        // C-Form: alle Punkte force-keep -> Ergebnis ist der Originalring (sauber).
        val cShape = unitsOf(
            listOf(
                0.0 to 0.0, 4.0 to 0.0, 4.0 to 1.0, 1.0 to 1.0,
                1.0 to 3.0, 4.0 to 3.0, 4.0 to 4.0, 0.0 to 4.0
            )
        )
        val all = BooleanArray(8) { true }
        val out = GeoMath.decimateUnitsSafe(cShape, Math.toRadians(2.0), all)
        assertFalse(GeoMath.selfIntersects(out))
        assertEquals(cShape.size, out.size)
    }
}
