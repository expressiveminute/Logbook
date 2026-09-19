package com.highfly.logbook

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
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