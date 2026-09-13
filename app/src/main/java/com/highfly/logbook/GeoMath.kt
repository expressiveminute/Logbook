package com.highfly.logbook

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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
}