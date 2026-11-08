package com.highfly.logbook

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
         * Lage des Einheitsvektors `(ux, uy, uz)` auf der Scheibe - dasselbe
         * Ergebnis wie [point] für den zugehörigen Punkt (lat, lon), aber ohne
         * sin/cos pro Punkt: Die trigonometrischen Werte des Blickpunkts stecken
         * schon in dieser Instanz, und X, Y und FRONT sind linear in den
         * Raumkoordinaten. Beim Neuaufbau der Rubbelkarte werden die
         * Einheitsvektoren aller ~80.000 Länderpunkte einmalig vorberechnet;
         * dreht der Nutzer die Kugel, kostet die Projektion so pro Punkt nur
         * wenige Multiplikationen statt mehrerer Trigonometrieaufrufe.
         */
        fun unitPoint(ux: Double, uy: Double, uz: Double, out: DoubleArray) {
            // cos(lat)·cos(lon - centerLon) als Skalarprodukt mit der Äquatorachse.
            val d = ux * cosLon + uy * sinLon
            out[X] = uy * cosLon - ux * sinLon
            out[Y] = cosLat * uz - sinLat * d
            out[FRONT] = cosLat * d + sinLat * uz
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

    /**
     * Dezimiert einen Ring aus Einheitsvektoren per Douglas-Peucker in
     * entrollten lon/lat-Koordinaten mit Toleranz [epsRad] (Radiant). Im Gegensatz
     * zur gleichmäßigen Schritt-Dezimierung bleibt die Form erhalten: Eine lange
     * Sehne quer durch eine konkave Küste (sichtbares Fehlstück) entsteht nicht -
     * jeder Originalpunkt liegt höchstens [epsRad] von der vereinfachten Linie
     * entfernt. Beim Kugelradius radiusPx entspricht epsRad = spacingPx/radiusPx
     * einer sichtbaren Toleranz von spacingPx Pixeln.
     */
    fun decimateUnitsDp(units: DoubleArray, epsRad: Double): DoubleArray {
        val count = units.size / 3
        if (count < 3 || epsRad <= 0.0) return units
        val (lons, lats) = unitsToLonLat(units)
        val keep = dpKeep(lons, lats, epsRad)
        return subsetUnits(units, keep)
    }

    /**
     * Wie [decimateUnitsDp], aber selbstschnittfrei: Erzeugt die Vereinfachung
     * eine Schlaufe (Figuren-acht), wird die Toleranz halbiert und erneut
     * versucht - bei der Füllung entstünde sonst ein Loch in der Länderfläche.
     * lon/lat wird nur einmal konvertiert (teuer bei großen Ringen pro Frame).
     *
     * [forceKeep] markiert Punkte, die auf keinen Fall entfernt werden dürfen
     * (z. B. gemeinsame Grenzpunkte zweier Länder, siehe
     * [CountryShapes.Country.sharedMasks]): Solange beide Nachbarländer dieselben
     * Grenzpunkte behalten, ist die Grenze bit-identisch und zwischen den
     * Länderflächen klafft keine Lücke - die Dezimierung je Land allein würde
     * die gemeinsamen Punkte nämlich unterschiedlich entfernen.
     */
    fun decimateUnitsSafe(
        units: DoubleArray,
        epsRad: Double,
        forceKeep: BooleanArray? = null
    ): DoubleArray {
        val count = units.size / 3
        if (count < 3 || epsRad <= 0.0) return units
        val (lons, lats) = unitsToLonLat(units)
        var eps = epsRad
        repeat(3) {
            val keep = dpKeep(lons, lats, eps)
            if (forceKeep != null && forceKeep.size == count) {
                for (i in 0 until count) if (forceKeep[i]) keep[i] = true
            }
            if (!selfIntersectsKeep(lons, lats, keep)) return subsetUnits(units, keep)
            eps /= 2.0
        }
        return units
    }

    /** Douglas-Peucker auf lon/lat: liefert die Keep-Maske pro Punkt. */
    private fun dpKeep(lons: DoubleArray, lats: DoubleArray, epsRad: Double): BooleanArray {
        val count = lons.size
        val keep = BooleanArray(count)
        keep[0] = true
        keep[count - 1] = true
        // Längengrad an die mittlere Breite skalieren (Aspect)
        var sumLat = 0.0
        for (v in lats) sumLat += v
        val cosLat = cos(sumLat / count)
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to count - 1)
        val eps2 = epsRad * epsRad
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            if (last <= first + 1) continue
            var worst = -1.0
            var worstIndex = -1
            val ax = lons[first] * cosLat
            val ay = lats[first]
            val bx = lons[last] * cosLat
            val by = lats[last]
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            for (i in first + 1 until last) {
                val px = lons[i] * cosLat
                val py = lats[i]
                val d: Double = if (len2 < 1e-18) {
                    val ex = px - ax
                    val ey = py - ay
                    ex * ex + ey * ey
                } else {
                    var t = ((px - ax) * dx + (py - ay) * dy) / len2
                    t = max(0.0, min(1.0, t))
                    val ex = px - (ax + t * dx)
                    val ey = py - (ay + t * dy)
                    ex * ex + ey * ey
                }
                if (d > worst) {
                    worst = d
                    worstIndex = i
                }
            }
            if (worst > eps2) {
                keep[worstIndex] = true
                stack.addLast(first to worstIndex)
                stack.addLast(worstIndex to last)
            }
        }
        return keep
    }

    /**
     * Unterabtastung einer offenen Linie (Route) mit maximalem Segmentwinkel
     * [maxSegRad]: Es werden so viele Punkte behalten, dass der Winkel zwischen
     * zwei aufeinanderfolgenden Punkten höchstens [maxSegRad] beträgt (plus die
     * Endpunkte). Im Gegensatz zu Douglas-Peucker entstehen so garantiert keine
     * langen Sehnen - am Kugelrand folgt die Linie sonst nicht dem Rand, sondern
     * schneidet als Sehne durchs Land (sieht aus wie ein Glitch). Der Winkel ist
     * drehungsunabhängig, das Ergebnis also pro Bogen konstant und cachebar.
     */
    fun subsampleUnitsMaxSegment(units: DoubleArray, maxSegRad: Double): DoubleArray {
        val count = units.size / 3
        if (count < 3 || maxSegRad <= 0.0) return units
        val keep = BooleanArray(count)
        keep[0] = true
        // Akkumuliert wird der Winkel zwischen aufeinanderfolgenden Originalpunkten;
        // sobald er maxSegRad erreicht, wird der aktuelle Punkt behalten. Ein
        // Segment zwischen zwei behaltenen Punkten ist damit höchstens
        // maxSegRad + ein Originalschritt lang.
        var prevX = units[0]
        var prevY = units[1]
        var prevZ = units[2]
        var acc = 0.0
        for (i in 1 until count - 1) {
            val x = units[i * 3]
            val y = units[i * 3 + 1]
            val z = units[i * 3 + 2]
            val dot = (x * prevX + y * prevY + z * prevZ).coerceIn(-1.0, 1.0)
            acc += kotlin.math.acos(dot)
            prevX = x
            prevY = y
            prevZ = z
            if (acc >= maxSegRad) {
                keep[i] = true
                acc = 0.0
            }
        }
        keep[count - 1] = true
        return subsetUnits(units, keep)
    }

    /** Subset der Einheitsvektoren gemäß Keep-Maske. */
    private fun subsetUnits(units: DoubleArray, keep: BooleanArray): DoubleArray {
        val count = units.size / 3
        var kept = 0
        for (i in 0 until count) if (keep[i]) kept++
        if (kept == count) return units
        val out = DoubleArray(kept * 3)
        var j = 0
        for (i in 0 until count) {
            if (!keep[i]) continue
            out[j++] = units[i * 3]
            out[j++] = units[i * 3 + 1]
            out[j++] = units[i * 3 + 2]
        }
        return out
    }

    /** Rechnet Einheitsvektoren in entrollte lon/lat-Koordinaten (Radiant) um. */
    private fun unitsToLonLat(units: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = units.size / 3
        val lons = DoubleArray(n)
        val lats = DoubleArray(n)
        var prevLon = 0.0
        for (i in 0 until n) {
            var lon = atan2(units[i * 3 + 1], units[i * 3])
            if (i > 0) {
                while (lon - prevLon > PI) lon -= 2 * PI
                while (prevLon - lon > PI) lon += 2 * PI
            }
            prevLon = lon
            lons[i] = lon
            lats[i] = asin(units[i * 3 + 2].coerceIn(-1.0, 1.0))
        }
        return lons to lats
    }

    /**
     * Prüft per Grid-Beschleunigung, ob sich der Ring aus Einheitsvektoren selbst
     * schneidet (strikte Kreuzung, keine bloße Berührung an gemeinsamen Ecken).
     * Rechnet dazu in entrollte lon/lat-Koordinaten um (Sprünge über die
     * Datumslinie werden ausgeglichen).
     */
    fun selfIntersects(units: DoubleArray): Boolean {
        val n = units.size / 3
        if (n < 4) return false
        val (lons, lats) = unitsToLonLat(units)
        return selfIntersectsKeep(lons, lats, BooleanArray(n) { true })
    }

    /**
     * Selbstschnitt-Prüfung auf bereits konvertierten lon/lat-Koordinaten, nur
     * für die per [keep] markierten Punkte (kompaktiert). Spart die erneute
     * Konvertierung bei [decimateUnitsSafe].
     */
    private fun selfIntersectsKeep(lons: DoubleArray, lats: DoubleArray, keep: BooleanArray): Boolean {
        val n0 = lons.size
        var n = 0
        for (i in 0 until n0) if (keep[i]) n++
        if (n < 4) return false
        val cl = DoubleArray(n)
        val ca = DoubleArray(n)
        var m = 0
        for (i in 0 until n0) if (keep[i]) {
            cl[m] = lons[i]
            ca[m] = lats[i]
            m++
        }
        val cell = 0.035 // ~2°
        val grid = HashMap<Long, IntArrayList>()
        fun key(gx: Int, gy: Int): Long = (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = min(cl[i], cl[j])
            val x1 = max(cl[i], cl[j])
            val y0 = min(ca[i], ca[j])
            val y1 = max(ca[i], ca[j])
            var gx = (x0 / cell).toInt()
            val gx1 = (x1 / cell).toInt()
            var gy = (y0 / cell).toInt()
            val gy1 = (y1 / cell).toInt()
            while (gx <= gx1) {
                gy = (y0 / cell).toInt()
                while (gy <= gy1) {
                    grid.getOrPut(key(gx, gy)) { IntArrayList() }.add(i)
                    gy++
                }
                gx++
            }
        }
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = min(cl[i], cl[j])
            val x1 = max(cl[i], cl[j])
            val y0 = min(ca[i], ca[j])
            val y1 = max(ca[i], ca[j])
            val seen = HashSet<Int>()
            var gx = (x0 / cell).toInt()
            val gx1 = (x1 / cell).toInt()
            var gy = (y0 / cell).toInt()
            val gy1 = (y1 / cell).toInt()
            while (gx <= gx1) {
                gy = (y0 / cell).toInt()
                while (gy <= gy1) {
                    val bucket = grid[key(gx, gy)] ?: IntArrayList()
                    for (k in 0 until bucket.size) {
                        val o = bucket[k]
                        if (o <= i + 1 || (i == 0 && o == n - 1)) continue
                        if (seen.add(o)) {
                            if (segmentsCross(
                                    cl[i], ca[i], cl[j], ca[j],
                                    cl[o], ca[o], cl[(o + 1) % n], ca[(o + 1) % n]
                                )
                            ) return true
                        }
                    }
                    gy++
                }
                gx++
            }
        }
        return false
    }

    /** Strikte Segmentkreuzung: beide Segmente müssen einander echt kreuzen. */
    private fun segmentsCross(
        x1: Double, y1: Double, x2: Double, y2: Double,
        x3: Double, y3: Double, x4: Double, y4: Double
    ): Boolean {
        fun ccw(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
            (cy - ay) * (bx - ax) - (by - ay) * (cx - ax)
        val d1 = ccw(x3, y3, x4, y4, x1, y1)
        val d2 = ccw(x3, y3, x4, y4, x2, y2)
        val d3 = ccw(x1, y1, x2, y2, x3, y3)
        val d4 = ccw(x1, y1, x2, y2, x4, y4)
        return d1 * d2 < 0 && d3 * d4 < 0
    }

    /** Kleiner Int-Listen-Helfer für die Grid-Buckets. */
    private class IntArrayList {
        private var arr = IntArray(8)
        private var n = 0
        val size: Int get() = n
        fun add(v: Int) {
            if (n == arr.size) arr = arr.copyOf(arr.size * 2)
            arr[n++] = v
        }
        operator fun get(i: Int): Int = arr[i]
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