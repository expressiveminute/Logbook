package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ChartDataHangarTest {

    private fun entry(
        aircraftType: String?,
        registration: String? = null,
        distanceKm: Int? = null,
        flightMinutes: Int? = null
    ) = LogbookEntry(
        date = LocalDate.of(2026, 1, 1),
        fromAirport = "FRA",
        toAirport = "JFK",
        aircraftType = aircraftType,
        registration = registration,
        distanceKm = distanceKm,
        flightMinutes = flightMinutes
    )

    @Test
    fun aircraftBars_byCountOrdersByNumberOfFlights() {
        // Der A320 fliegt man haeufiger, aber kuerzer: nach Anzahl steht er
        // vorne, obwohl der A350 weiter fliegt.
        val entries = listOf(
            entry("A320", distanceKm = 500),
            entry("A320", distanceKm = 500),
            entry("A320", distanceKm = 500),
            entry("A350", distanceKm = 9000)
        )

        val bars = ChartData.aircraftBars(entries, ChartData.HangarSort.ANZAHL)

        assertEquals(listOf("A320", "A350"), bars.map { it.label })
        assertEquals(listOf(3, 1), bars.map { it.count })
        assertEquals(listOf(null, null), bars.map { it.countLabel })
    }

    @Test
    fun aircraftBars_byDistanceOrdersByKilometres() {
        // Nach Entfernung dreht sich die Reihenfolge: der seltenere, aber
        // weiter fliegende A350 steht vorne.
        val entries = listOf(
            entry("A320", distanceKm = 500),
            entry("A320", distanceKm = 500),
            entry("A320", distanceKm = 500),
            entry("A350", distanceKm = 9000)
        )

        val bars = ChartData.aircraftBars(entries, ChartData.HangarSort.ENTFERNUNG)

        assertEquals(listOf("A350", "A320"), bars.map { it.label })
        assertEquals(listOf(9000, 1500), bars.map { it.count })
        assertNull(bars[0].countLabel)
    }

    @Test
    fun aircraftBars_byDistanceKeepsTypesWithoutDistance() {
        // Ohne erfasste Entfernung zaehlt der Typ mit null Kilometern, bleibt
        // aber sichtbar - er wurde ja geflogen.
        val entries = listOf(
            entry("B737", distanceKm = null),
            entry("A320", distanceKm = 1200)
        )

        val bars = ChartData.aircraftBars(entries, ChartData.HangarSort.ENTFERNUNG)

        assertEquals(listOf("A320", "B737"), bars.map { it.label })
        assertEquals(listOf(1200, 0), bars.map { it.count })
    }

    @Test
    fun aircraftBars_byDurationOrdersByFlightTime() {
        // Nach Dauer summiert sich die Flugzeit je Typ: drei kurze A320-Fluege
        // ergeben weniger als der eine lange A350-Flug.
        val entries = listOf(
            entry("A320", distanceKm = 500, flightMinutes = 60),
            entry("A320", distanceKm = 500, flightMinutes = 60),
            entry("A320", distanceKm = 500, flightMinutes = 60),
            entry("A350", distanceKm = 9000, flightMinutes = 600)
        )

        val bars = ChartData.aircraftBars(entries, ChartData.HangarSort.DAUER)

        assertEquals(listOf("A350", "A320"), bars.map { it.label })
        assertEquals(listOf(600, 180), bars.map { it.count })
    }

    @Test
    fun aircraftBars_byDurationKeepsTypesWithoutFlightTime() {
        // Ohne erfasste Flugzeit zaehlt der Typ mit null Minuten, bleibt aber
        // sichtbar - er wurde ja geflogen.
        val entries = listOf(
            entry("B737", flightMinutes = null),
            entry("A320", flightMinutes = 120)
        )

        val bars = ChartData.aircraftBars(entries, ChartData.HangarSort.DAUER)

        assertEquals(listOf("A320", "B737"), bars.map { it.label })
        assertEquals(listOf(120, 0), bars.map { it.count })
    }

    @Test
    fun hangarBars_groupsRegistrationsSeparately() {
        val entries = listOf(
            entry("A320", registration = "D-AIZX", distanceKm = 500),
            entry("A320", registration = "D-AIZX", distanceKm = 500),
            entry("A320", registration = "D-ABYZ", distanceKm = 1500)
        )

        val byCount = ChartData.hangarBars(entries, ChartData.HangarSort.ANZAHL) {
            it.registration
        }
        assertEquals(listOf("D-AIZX", "D-ABYZ"), byCount.map { it.label })
        assertEquals(listOf(2, 1), byCount.map { it.count })

        val byDistance = ChartData.hangarBars(entries, ChartData.HangarSort.ENTFERNUNG) {
            it.registration
        }
        assertEquals(listOf("D-ABYZ", "D-AIZX"), byDistance.map { it.label })
        assertEquals(listOf(1500, 1000), byDistance.map { it.count })
    }

    @Test
    fun hangarBars_withoutDistanceIgnoresBlankLabels() {
        val entries = listOf(
            entry("A320", distanceKm = 100),
            entry("  ", distanceKm = 500),
            entry(null, distanceKm = 500)
        )

        val bars = ChartData.hangarBars(entries, ChartData.HangarSort.ENTFERNUNG) {
            it.aircraftType
        }

        assertEquals(listOf("A320"), bars.map { it.label })
    }
}
