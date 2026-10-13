package com.highfly.logbook

import kotlin.math.sqrt

/**
 * Randhinweise der CO₂-Ausgleichskarte: Orte, die außerhalb des Kartenbildes
 * liegen, werden entlang ihrer Blickrichtung auf den Bildrand gezogen. So
 * bleibt auch im engen Ausschnitt um die Waldfläche ablesbar, wo die nächste
 * Stadt liegt.
 *
 * Bewusst ohne Android-Abhängigkeiten, damit die Randrechnung im Test geprüft
 * werden kann.
 */
object CityEdgeHints {

    /**
     * Ein Punkt auf dem Bildrand. [onScreen] ist true, wenn der Ort schon
     * innerhalb des Rechtecks liegt und deshalb unverändert bleibt. [dirX] und
     * [dirY] zeigen als Einheitsvektor vom Bildmittelpunkt nach außen zum Ort;
     * damit lässt sich die Blickrichtung des Hinweises zeichnen.
     */
    data class Edge(
        val x: Float,
        val y: Float,
        val onScreen: Boolean,
        val dirX: Float,
        val dirY: Float
    )

    /**
     * Zieht den Punkt ([x], [y]) entlang des Strahls vom Mittelpunkt des
     * Rechtecks auf dessen Rand. Liegt er bereits im Rechteck, kommt er
     * unverändert zurück. Ein Punkt genau im Mittelpunkt hat keine Richtung und
     * landet dort; [Edge.dirX]/[Edge.dirY] zeigen dann nach rechts.
     */
    fun clamp(
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Edge {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val dx = x - cx
        val dy = y - cy
        val length = sqrt(dx * dx + dy * dy)
        val dirX: Float
        val dirY: Float
        if (length > 0f && length.isFinite()) {
            dirX = dx / length
            dirY = dy / length
        } else {
            dirX = 1f
            dirY = 0f
        }
        if (x in left..right && y in top..bottom) {
            return Edge(x, y, true, dirX, dirY)
        }

        var scale = Float.POSITIVE_INFINITY
        if (dx > 0f) scale = minOf(scale, (right - cx) / dx)
        if (dx < 0f) scale = minOf(scale, (left - cx) / dx)
        if (dy > 0f) scale = minOf(scale, (bottom - cy) / dy)
        if (dy < 0f) scale = minOf(scale, (top - cy) / dy)
        if (!scale.isFinite()) return Edge(cx, cy, false, dirX, dirY)
        return Edge(cx + dx * scale, cy + dy * scale, false, dirX, dirY)
    }
}
