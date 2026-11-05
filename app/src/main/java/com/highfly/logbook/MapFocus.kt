package com.highfly.logbook

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reine Rechnung für das programmgesteuerte Anfahren einer Region auf der
 * Rubbelkarte: Mittelpunkt und Zoomstufe für Kontinente, Länder und
 * Bundesstaaten. Ohne Android-Abhängigkeiten, damit die Regel im Test
 * prüfbar ist (die View-Klasse hängt an Android, ihre Konstanten sind aber
 * hier als Zahlen gespiegelt und in [MapFocusTest] gegen die View geprüft).
 *
 * Die Kugel projiziert orthografisch: Ein Punkt im Winkelabstand θ vom
 * Blickpunkt landet im Abstand radius·sin(θ) von der Kugelmitte. Damit eine
 * Region mit Winkelausdehnung `span` mit Luft auf den Bildschirm passt, muss
 * gelten: base·zoom·sin(span/2) <= FIT_ANTEIL·min(w,h). Mit
 * base = min(w,h)·(1-GLOBE_MARGIN)/2 (siehe [RubbelkarteView]) kürzt sich
 * min(w,h) heraus - die Zoomstufe hängt nur noch von der Ausdehnung ab.
 */
object MapFocus {

    /** Ziel eines programmatischen Zooms: Mittelpunkt (lat, lon) und Zoomstufe. */
    data class Focus(val lat: Double, val lon: Double, val zoom: Float)

    /**
     * Wie viel der kleineren Bildschirmseite die Region einnehmen darf.
     * 1.0/sin(span/2) heißt: die äußersten Punkte landen bei (1-GLOBE_MARGIN)/2
     * der kleineren Seite vom Zentrum - gerade noch innerhalb der View, auch
     * wenn die halbhohe Seite unten die Kartenfläche flach macht. Bei 1.8 wären
     * die Randpunkte außerhalb der sichtbaren Fläche und abgeschnitten.
     */
    private const val FIT = 1.0

    /** Kleinster/groesster erlaubter Winkelabstand für die Zoomformel. */
    private const val SPAN_MIN = 2.0
    private const val SPAN_MAX = 170.0

    /** Zoomstufe für eine Winkelausdehnung in Grad, begrenzt auf den Zoombereich. */
    fun zoomForSpan(spanDeg: Double): Float {
        val span = spanDeg.coerceIn(SPAN_MIN, SPAN_MAX)
        val zoom = FIT / sin(Math.toRadians(span) / 2.0)
        return zoom.toFloat().coerceIn(RubbelkarteView.ZOOM_MIN, RubbelkarteView.ZOOM_MAX)
    }

    /** Mittelpunkt einer Menge von Punkten als Vektormittel - datumslinienfest. */
    fun centerOfPoints(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (points.isEmpty()) return null
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for ((lat, lon) in points) {
            val latRad = Math.toRadians(lat)
            val lonRad = Math.toRadians(lon)
            x += cos(latRad) * cos(lonRad)
            y += cos(latRad) * sin(lonRad)
            z += sin(latRad)
        }
        val len = sqrt(x * x + y * y + z * z)
        if (len < 1e-9) return points.first()
        val lat = Math.toDegrees(kotlin.math.asin((z / len).coerceIn(-1.0, 1.0)))
        val lon = Math.toDegrees(atan2(y / len, x / len))
        return lat to lon
    }

    /**
     * Größter Winkelabstand (in Grad) von [lat]/[lon] zu einem der [points],
     * verdoppelt als Ausdehnung um den Mittelpunkt. Arbeitet auf Einheitsvektoren,
     * damit Längengrade an der Datumslinie nicht aufreißen.
     */
    fun spanAround(lat: Double, lon: Double, points: List<Pair<Double, Double>>): Double {
        if (points.isEmpty()) return SPAN_MIN
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val ux = cos(latRad) * cos(lonRad)
        val uy = cos(latRad) * sin(lonRad)
        val uz = sin(latRad)
        var maxDist = 0.0
        for ((pLat, pLon) in points) {
            val pLatRad = Math.toRadians(pLat)
            val pLonRad = Math.toRadians(pLon)
            val dot = (ux * cos(pLatRad) * cos(pLonRad) +
                uy * cos(pLatRad) * sin(pLonRad) +
                uz * sin(pLatRad)).coerceIn(-1.0, 1.0)
            val dist = Math.toDegrees(acos(dot))
            if (dist > maxDist) maxDist = dist
        }
        return (2.0 * maxDist).coerceAtLeast(SPAN_MIN)
    }

    /** Alle Ringpunkte eines Landes/Staates als (lat, lon), flach. */
    private fun ringPoints(rings: List<FloatArray>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(rings.sumOf { it.size / 2 })
        for (ring in rings) {
            for (i in ring.indices step 2) {
                out.add(ring[i + 1].toDouble() to ring[i].toDouble())
            }
        }
        return out
    }

    /**
     * Fokus für eine Menge von Ländern (Kontinent oder EU): Mittelpunkt ist
     * das Vektormittel der Beschriftungsanker (bei Ringpunkten zöge z. B. Alaska
     * das Zentrum aus der Mitte der USA), die Ausdehnung der Abstand vom
     * Mittelpunkt zu allen Ringpunkten - damit auch das östliche Russland bzw.
     * Alaska/Hawaii in der Ausdehnung liegen und der Zoom weit genug herausgeht.
     */
    fun forCountries(
        countries: List<CountryShapes.Country>,
        predicate: (CountryShapes.Country) -> Boolean
    ): Focus? {
        val selected = countries.filter(predicate)
        if (selected.isEmpty()) return null
        val anchors = selected.map { it.labelLat to it.labelLon }
        val center = centerOfPoints(anchors) ?: return null
        val all = selected.flatMap { ringPoints(it.rings) } + anchors
        val span = spanAround(center.first, center.second, all)
        return Focus(center.first, center.second, zoomForSpan(span))
    }

    /** Fokus für ein einzelnes Land: Ankerpunkt des Landes, Ausdehnung über seine Ringe. */
    fun forCountry(country: CountryShapes.Country): Focus {
        val span = spanAround(country.labelLat, country.labelLon, ringPoints(country.rings))
        return Focus(country.labelLat, country.labelLon, zoomForSpan(span))
    }

    /**
     * Fokus für die Staaten eines Landes (Kachel Deutschland/USA): Mittelpunkt
     * ist das Vektormittel der Staatenanker (Hauptstädte - über alle Ringpunkte
     * zöge Alaska das Zentrum nach Nordwesten und die südlichen Staaten wären
     * abgeschnitten), die Ausdehnung der Abstand zu allen Ringpunkten inklusive
     * Alaska/Hawaii, damit der Zoom weit genug herausgeht. Datumslinienfest.
     */
    fun forStates(states: List<StateShapes.State>): Focus? {
        if (states.isEmpty()) return null
        val anchors = states.map { it.labelLat to it.labelLon }
        val center = centerOfPoints(anchors) ?: return null
        val all = states.flatMap { ringPoints(it.rings) } + anchors
        val span = spanAround(center.first, center.second, all)
        return Focus(center.first, center.second, zoomForSpan(span))
    }

    /** Fokus für einen einzelnen Staat: sein Anker, Ausdehnung über seine Ringe. */
    fun forState(state: StateShapes.State): Focus {
        val span = spanAround(state.labelLat, state.labelLon, ringPoints(state.rings))
        return Focus(state.labelLat, state.labelLon, zoomForSpan(span))
    }
}
