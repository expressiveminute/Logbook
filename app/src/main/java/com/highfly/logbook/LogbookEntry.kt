package com.highfly.logbook

import java.time.LocalDate

data class LogbookEntry(
    val id: Long? = null,
    val date: LocalDate,
    val flightType: String? = null,
    val classType: String? = null,
    val fromAirport: String,
    val toAirport: String,
    val airline: String? = null,
    val flightNumber: String? = null,
    val aircraftType: String? = null,
    val registration: String? = null,
    val distanceKm: Int? = null,
    val flightMinutes: Int? = null,
    val layover: Boolean = false,
    val comment: String? = null
)