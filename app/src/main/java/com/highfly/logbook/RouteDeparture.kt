package com.highfly.logbook

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Reine Geometrie der Abflug-Animation auf der Flughafen-Detailseite: Dort
 * starten vom hervorgehobenen Flughafen kleine Flugzeuge in Richtung jeder
 * Verbindung und ziehen eine verblassende Spur hinter sich her.
 *
 * Der Streckenzug kommt als flaches `float`-Feld herein: `x0, y0, x1, y1, …`
 * in Bildschirm-Pixeln, bereits projiziert. Weil das Feld beim Verschieben und
 * Zoomen der Karte ohnehin neu entsteht, ist die Projektion Sache des
 * Aufrufers; hier steht nur die Rechnung, damit sie ohne Android prüfbar
 * bleibt (siehe [RouteDepartureTest]).
 */
internal object RouteDeparture {

    /** Länge des Streckenzugs aus [count] Punkten, in Pixeln. */
    fun length(points: FloatArray, count: Int): Float {
        if (count < 2) return 0f
        var total = 0f
        var i = 1
        while (i < count) {
            total += hypot(
                points[i * 2].toDouble() - points[(i - 1) * 2].toDouble(),
                points[i * 2 + 1].toDouble() - points[(i - 1) * 2 + 1].toDouble()
            ).toFloat()
            i++
        }
        return total
    }

    /**
     * Punkt in der Strecke [distance] vom Anfang sowie die dortige
     * Fahrtrichtung. Schreibt `x`, `y` und den Drehwinkel in Grad (für ein nach
     * Norden zeigendes Flugzeugsymbol, siehe [RouteMiniMapView.planeRotation])
     * nach [out]. Reicht der Zug nicht bis [distance], wird auf seinen letzten
     * Punkt geklemmt. Nur ein Zug mit weniger als zwei Punkten liefert `false`
     * und lässt [out] unverändert.
     */
    fun pointAt(points: FloatArray, count: Int, distance: Float, out: FloatArray): Boolean {
        if (count < 2) return false
        var remaining = distance.coerceAtLeast(0f)
        var i = 1
        while (i < count) {
            val ax = points[(i - 1) * 2]
            val ay = points[(i - 1) * 2 + 1]
            val bx = points[i * 2]
            val by = points[i * 2 + 1]
            val dx = bx - ax
            val dy = by - ay
            val segment = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (segment > 0f && remaining <= segment) {
                val t = remaining / segment
                out[0] = ax + dx * t
                out[1] = ay + dy * t
                out[2] = RouteMiniMapView.planeRotation(dx, dy)
                return true
            }
            remaining -= segment
            i++
        }
        // Über das Ende hinaus: am letzten Punkt stehen bleiben.
        val last = count - 1
        out[0] = points[last * 2]
        out[1] = points[last * 2 + 1]
        val dx = points[last * 2] - points[(last - 1) * 2]
        val dy = points[last * 2 + 1] - points[(last - 1) * 2 + 1]
        out[2] = RouteMiniMapView.planeRotation(dx, dy)
        return true
    }

    /**
     * Anteil der Strecke (0..1), den das Flugzeug der Bahn [index] gerade
     * geflogen ist, oder `null`, solange es noch am Boden steht. [clockMs] ist
     * eine monoton wachsende Uhr, [periodMs] die Dauer eines vollen Umlaufs,
     * [stagger] der Versatz zwischen zwei Bahnen und [travelFraction] der
     * Anteil des Umlaufs, in dem überhaupt geflogen wird (danach folgt eine
     * kurze Pause).
     */
    fun planeFraction(
        clockMs: Long,
        periodMs: Long,
        index: Int,
        stagger: Float,
        travelFraction: Float
    ): Float? {
        if (periodMs <= 0L || travelFraction <= 0f) return null
        val base = (clockMs % periodMs).toFloat() / periodMs.toFloat()
        val phase = (base + index * stagger) % 1f
        if (phase >= travelFraction) return null
        return phase / travelFraction
    }
}
