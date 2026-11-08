package com.highfly.logbook

import kotlin.math.cos
import kotlin.math.sin

/**
 * Routenberechnung für die Weltkugel ([GlobeView]): Aus den (bereits
 * gefilterten) Einträgen werden die angeflogenen Flughäfen und die
 * geflogenen Verbindungen als Großkreisbögen ermittelt. Die Bogenpunkte
 * liegen als Einheitsvektoren vor (x, y, z je Punkt, verflacht), damit die
 * Kugel sie beim Drehen/Zoomen per Matrixmultiplikation projizieren kann,
 * ohne Trigonometrie pro Punkt (siehe [GeoMath.Globe.unitPoint]).
 *
 * Bewusst ohne Android-Abhängigkeiten, damit [GlobeRoutesTest] die
 * Zuordnung prüfen kann; Koordinaten kommen als Funktion herein.
 */
object GlobeRoutes {

    /** Koordinaten eines Flughafens. */
    data class Airport(val lat: Double, val lon: Double)

    /**
     * Eine geflogene Verbindung: Abflug- und Zielflughafen (Dreilettercodes),
     * Zahl der Flüge und der Großkreisbogen als Einheitsvektoren
     * (x, y, z abwechselnd, `3 * (Punkte)` lang).
     */
    data class Arc(
        val from: String,
        val to: String,
        val count: Int,
        val units: DoubleArray
    ) {
        /** Bogenpunkte als (lat, lon), nur für Tests und Nicht-Kugel-Nutzer. */
        fun points(): List<Pair<Double, Double>> {
            val n = units.size / 3
            return List(n) { i ->
                val x = units[i * 3]
                val y = units[i * 3 + 1]
                val z = units[i * 3 + 2]
                val lat = Math.toDegrees(kotlin.math.atan2(z, kotlin.math.sqrt(x * x + y * y)))
                val lon = Math.toDegrees(kotlin.math.atan2(y, x))
                lat to lon
            }
        }
    }

    /** Ergebnis: Flughäfen mit Koordinaten, Verbindungen und Vernetzungsgrad. */
    data class Result(
        val airports: Map<String, Airport>,
        val routes: List<Arc>,
        /** Zahl der verschiedenen Gegenstellen je Flughafen (für die Badge-Reihenfolge). */
        val connections: Map<String, Int>
    )

    /**
     * Ermittelt Flughäfen und Verbindungen aus den Einträgen. Verbindungen
     * werden richtungsabhängig gruppiert (FRA→JFK und JFK→FRA sind zwei
     * Bögen, wie auf der Weltkarte); Flüge ohne bekannte Koordinaten fallen
     * heraus.
     */
    fun compute(
        entries: List<LogbookEntry>,
        location: (String) -> AirportData.GeoLocation?
    ): Result {
        val airports = linkedMapOf<String, Airport>()
        val routeCounts = linkedMapOf<Pair<String, String>, Int>()
        for (entry in entries) {
            val from = entry.fromAirport.trim().uppercase()
            val to = entry.toAirport.trim().uppercase()
            if (from.isEmpty() || to.isEmpty() || from == to) continue
            val fromLoc = location(from) ?: continue
            val toLoc = location(to) ?: continue
            airports.putIfAbsent(from, Airport(fromLoc.lat, fromLoc.lon))
            airports.putIfAbsent(to, Airport(toLoc.lat, toLoc.lon))
            val key = from to to
            routeCounts[key] = (routeCounts[key] ?: 0) + 1
        }
        val routes = routeCounts.map { (key, count) ->
            val from = airports.getValue(key.first)
            val to = airports.getValue(key.second)
            Arc(
                from = key.first,
                to = key.second,
                count = count,
                units = arcUnits(
                    AirportData.GeoLocation(from.lat, from.lon),
                    AirportData.GeoLocation(to.lat, to.lon)
                )
            )
        }
        val neighbors = HashMap<String, MutableSet<String>>()
        for (route in routes) {
            neighbors.getOrPut(route.from) { HashSet() }.add(route.to)
            neighbors.getOrPut(route.to) { HashSet() }.add(route.from)
        }
        return Result(airports, routes, neighbors.mapValues { it.value.size })
    }

    /**
     * Großkreisbogen von [from] nach [to] als Einheitsvektoren. Die Längen
     * brauchen nicht entrollt zu werden: Einheitsvektoren sind auf der Kugel
     * stetig, auch über die Datumslinie hinweg.
     */
    fun arcUnits(from: AirportData.GeoLocation, to: AirportData.GeoLocation): DoubleArray {
        // Genug Punkte für glatte Bögen auch beim tiefen Hineinzoomen (50-km-Zoom).
        val arc = GeoMath.greatCircleArc(from, to, numPoints = 96)
        val out = DoubleArray(arc.size * 3)
        for ((i, p) in arc.withIndex()) {
            val lat = Math.toRadians(p.latitude)
            val lon = Math.toRadians(p.longitude)
            val cosLat = cos(lat)
            out[i * 3] = cosLat * cos(lon)
            out[i * 3 + 1] = cosLat * sin(lon)
            out[i * 3 + 2] = sin(lat)
        }
        return out
    }
}
