package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class Co2CalculatorTest {

    private fun entry(
        distanceKm: Int? = null,
        flightMinutes: Int? = null,
        flightType: String? = null,
        aircraftType: String? = null
    ) = LogbookEntry(
        date = LocalDate.of(2026, 9, 7),
        flightType = flightType,
        fromAirport = "FRA",
        toAirport = "JFK",
        aircraftType = aircraftType,
        distanceKm = distanceKm,
        flightMinutes = flightMinutes
    )

    @Test
    fun emissions_shortHaulUsesHigherFactor() {
        val short = entry(distanceKm = 300)
        val long = entry(distanceKm = 6195)
        assertEquals(85.2, Co2Calculator.emissionsKg(short), 0.001)
        assertEquals(1282.365, Co2Calculator.emissionsKg(long), 0.001)
    }

    @Test
    fun emissions_fallbackOnFlightMinutesUsesDefaultSpeed() {
        val minutes = entry(flightMinutes = 120)
        assertEquals(249.6, Co2Calculator.emissionsKg(minutes), 0.001)
    }

    @Test
    fun modernAircraft_emitsLessThanLegacy() {
        val legacy = entry(distanceKm = 6195)
        val modern = entry(distanceKm = 6195, aircraftType = "A350-900")
        assertEquals(1025.892, Co2Calculator.emissionsKg(modern), 0.001)
        assert(Co2Calculator.emissionsKg(modern) < Co2Calculator.emissionsKg(legacy))
    }

    @Test
    fun regionalAircraft_emitsMoreThanDefault() {
        val legacy = entry(distanceKm = 500)
        val regional = entry(distanceKm = 500, aircraftType = "CRJ900")
        assertEquals(112.125, Co2Calculator.emissionsKg(regional), 0.001)
        assert(Co2Calculator.emissionsKg(regional) > Co2Calculator.emissionsKg(legacy))
    }

    @Test
    fun groundTransfer_emitsNothing() {
        assertEquals(0.0, Co2Calculator.emissionsKg(entry(distanceKm = 500, flightType = "Ground Transfer")), 0.0)
    }

    @Test
    fun noDistanceAndNoMinutes_emitsNothing() {
        assertEquals(0.0, Co2Calculator.emissionsKg(entry()), 0.0)
    }

    @Test
    fun details_aggregateTonnesAndTreesPerYear() {
        val details = Co2Calculator.details(listOf(entry(distanceKm = 6195, aircraftType = "A350-900")))
        assertEquals(1.025892, details.tonnes, 0.000001)
        assertEquals(103, details.treesPerYear)
    }
}