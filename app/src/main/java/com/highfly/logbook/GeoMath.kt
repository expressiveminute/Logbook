package com.highfly.logbook

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.osmdroid.util.GeoPoint

object GeoMath {

    private const val EARTH_RADIUS_KM = 6371.0

    private const val CRUISE_SPEED_KMH = 805.0
    private const val FLIGHT_PUSHER_MINUTES = 25.0

    fun distanceKm(a: AirportData.GeoLocation, b: AirportData.GeoLocation): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val h = sinLat * sinLat +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sinLon * sinLon
        val c = 2.0 * asin(sqrt(h))
        return EARTH_RADIUS_KM * c
    }

    fun flightMinutes(distanceKm: Double): Int =
        (distanceKm / CRUISE_SPEED_KMH * 60.0 + FLIGHT_PUSHER_MINUTES).roundToInt()

    /**
     * Drehwinkel einer Kugel in Radiant für einen Fingerweg von [dxPx] Pixeln
     * bei einem Kugelradius von [radiusPx] Pixeln.
     *
     * Am Äquator entspricht ein Radiant genau dem Kugelradius, deshalb genügt der
     * Radius als Umrechnung. Zieht der Finger nach rechts, wandert die Kugel nach
     * rechts, der Mittelpunkt also nach Westen: deshalb das negative Vorzeichen.
     *
     * Der Winkel ist bewusst nicht normalisiert. Ein kleiner Fingerweg soll auch
     * einen kleinen Winkel liefern, sonst springt die Summe beim Ausrichten über
     * den Nullpunkt. Der Aufrufer summiert und rechnet selbst modulo 2*PI, weil
     * die Projektion ohnehin periodisch ist.
     */
    fun globeRotation(dxPx: Float, radiusPx: Float): Double =
        if (radiusPx <= 0f) 0.0 else -dxPx.toDouble() / radiusPx

    /**
     * Sicht auf die Erde aus einer beliebigen Richtung: orthografische
     * Projektion auf die Scheibe, die man von außen vor sich hält.
     *
     * Der Blickpunkt steht in Längen- und Breitengrad, jeweils in Radiant.
     * [point] liefert die Lage eines Punktes auf der Scheibe, [X] nach rechts,
     * [Y] nach oben und [FRONT] den Abstand zur Ebene durch den Erdmittelpunkt.
     * Der Blickpunkt selbst hat X = Y = 0, der Scheibenrand liegt bei
     * X² + Y² = 1, und der Horizont fällt genau auf diesen Rand: Dort ist FRONT
     * null. Damit füllt die sichtbare Halbkugel die Scheibe lückenlos aus und
     * die Rückseite fällt einfach weg.
     *
     * Drehbar ist die Kugel, indem ein neues [Globe] mit verschobenem Blickpunkt
     * gebaut wird, siehe [RubbelkarteView].
     *
     * X und Y sind linear in den Raumkoordinaten. Deshalb lässt sich der
     * Schnittpunkt eines Segments mit dem Horizont durch direkte Interpolation
     * von X und Y bestimmen, ohne in Kugelkoordinaten zurückzurechnen.
     */
    class Globe(centerLon: Double, centerLat: Double) {

        val centerLon = centerLon
        val centerLat = centerLat

        private val sinLon = sin(centerLon)
        private val cosLon = cos(centerLon)
        private val sinLat = sin(centerLat)
        private val cosLat = cos(centerLat)

        /** Lage von (lat, lon) auf der Scheibe, geschrieben nach [out]. */
        fun point(lat: Double, lon: Double, out: DoubleArray) {
            val latRad = Math.toRadians(lat)
            val dLon = Math.toRadians(lon) - centerLon
            val cosPointLat = cos(latRad)
            val sinPointLat = sin(latRad)
            val cosDLon = cos(dLon)
            out[X] = cosPointLat * sin(dLon)
            out[Y] = cosLat * sinPointLat - sinLat * cosPointLat * cosDLon
            out[FRONT] = cosLat * cosPointLat * cosDLon + sinLat * sinPointLat
        }

        /** Liegt der Punkt auf der sichtbaren Seite der Kugel? */
        fun inFront(lat: Double, lon: Double, out: DoubleArray): Boolean {
            point(lat, lon, out)
            return out[FRONT] > 0.0
        }

        /**
         * Winkel auf dem Randkreis, an dem das Segment von (latA, lonA) nach
         * (latB, lonB) den Horizont schneidet. NaN, wenn beide Endpunkte auf
         * derselben Seite liegen, dann schneidet das Segment den Horizont nicht.
         * Die Puffer [a] und [b] sind nur Rechenfläche.
         *
         * X und Y sind linear in den Raumkoordinaten, deshalb wird zwischen
         * beiden Endpunkten interpoliert. Das Ergebnis ist noch nicht auf dem
         * Randkreis: es ist der Ort auf der Sehne durch die Erde, erst die
         * Teilung durch seine Länge bringt ihn auf die Kugeloberfläche und damit
         * auf den Rand.
         */
        fun horizon(
            latA: Double, lonA: Double,
            latB: Double, lonB: Double,
            a: DoubleArray,
            b: DoubleArray
        ): Double {
            point(latA, lonA, a)
            point(latB, lonB, b)
            val fa = a[FRONT]
            val fb = b[FRONT]
            if (fa == fb || (fa > 0.0) == (fb > 0.0)) return Double.NaN
            val t = fa / (fa - fb)
            val x = (a[X] + t * (b[X] - a[X]))
            val y = (a[Y] + t * (b[Y] - a[Y]))
            val len = sqrt(x * x + y * y)
            if (len < 1e-12) return Double.NaN
            return atan2(y / len, x / len)
        }

        /**
         * Blickpunkt nach einem Fingerweg von [dxPx] zu [dyPx] Pixeln bei einem
         * Kugelradius von [radiusPx]. Der Weg wirkt eins zu eins auf die
         * Kugelmitte: ein Fingerweg von einem Kugelradius entspricht einem
         * Radiant, mehr passiert an den Polen nicht.
         *
         * Waagerecht wandert der Inhalt mit dem Finger, der Blickpunkt läuft
         * gegenläufig. Senkrecht ist es umgekehrt, weil [Y] in der Projektion nach
         * oben zeigt: Ein Zug nach unten schiebt den Inhalt nach unten und hebt
         * damit den Blickpunkt, es kommt mehr Norden in den Blick.
         */
        fun drag(dxPx: Float, dyPx: Float, radiusPx: Float): DoubleArray {
            val lat = if (radiusPx <= 0f) {
                centerLat
            } else {
                (centerLat - globeRotation(dyPx, radiusPx)).coerceIn(-PI / 2.0, PI / 2.0)
            }
            return doubleArrayOf(centerLon + globeRotation(dxPx, radiusPx), lat)
        }

        companion object {
            const val X = 0
            const val Y = 1
            const val FRONT = 2
        }
    }

    fun greatCircleArc(
        from: AirportData.GeoLocation,
        to: AirportData.GeoLocation,
        numPoints: Int = 50
    ): List<GeoPoint> {
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val lat2 = Math.toRadians(to.lat)
        val lon2 = Math.toRadians(to.lon)

        val d = 2.0 * asin(
            sqrt(
                sin((lat2 - lat1) / 2.0) * sin((lat2 - lat1) / 2.0) +
                    cos(lat1) * cos(lat2) * sin((lon2 - lon1) / 2.0) * sin((lon2 - lon1) / 2.0)
            )
        )

        if (d < 1e-10) {
            return listOf(
                GeoPoint(from.lat, from.lon),
                GeoPoint(to.lat, to.lon)
            )
        }

        val points = mutableListOf<GeoPoint>()
        for (i in 0..numPoints) {
            val f = i.toDouble() / numPoints
            val a = sin((1.0 - f) * d) / sin(d)
            val b = sin(f * d) / sin(d)
            val x = a * cos(lat1) * cos(lon1) + b * cos(lat2) * cos(lon2)
            val y = a * cos(lat1) * sin(lon1) + b * cos(lat2) * sin(lon2)
            val z = a * sin(lat1) + b * sin(lat2)
            val lat = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
            val lon = Math.toDegrees(atan2(y, x))
            points.add(GeoPoint(lat, lon))
        }
        return points
    }
}