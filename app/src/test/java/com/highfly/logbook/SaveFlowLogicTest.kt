package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SaveFlowLogicTest {

    private val entry = LogbookEntry(
        id = 42L,
        date = LocalDate.of(2026, 9, 7),
        flightType = "On Duty",
        classType = "Business",
        fromAirport = "FRA",
        toAirport = "JFK",
        airline = "LH",
        flightNumber = "400",
        aircraftType = "A350-900",
        registration = "D-AIXA",
        distanceKm = 6195,
        flightMinutes = 455,
        layover = true,
        comment = "Kommando, \"Sonderflug\", Frankfurt -> JFK\nnächste Zeile"
    )

    @Test
    fun csv_roundTrip_preservesAllFields() {
        val csv = LogbookCsv.toCsv(listOf(entry))
        val restored = LogbookCsv.fromCsv(csv)

        assertEquals(1, restored.size)
        val e = restored[0]
        assertEquals(entry.date, e.date)
        assertEquals(entry.flightType, e.flightType)
        assertEquals(entry.classType, e.classType)
        assertEquals(entry.fromAirport, e.fromAirport)
        assertEquals(entry.toAirport, e.toAirport)
        assertEquals(entry.airline, e.airline)
        assertEquals(entry.flightNumber, e.flightNumber)
        assertEquals(entry.aircraftType, e.aircraftType)
        assertEquals(entry.registration, e.registration)
        assertEquals(entry.distanceKm, e.distanceKm)
        assertEquals(entry.flightMinutes, e.flightMinutes)
        assertEquals(entry.layover, e.layover)
        assertEquals(entry.comment, e.comment)
    }

    @Test
    fun csv_roundTrip_handlesNullOptionalFields() {
        val minimal = LogbookEntry(
            date = LocalDate.of(2026, 9, 7),
            flightType = null,
            classType = null,
            fromAirport = "MUC",
            toAirport = "ZRH",
            airline = null,
            flightNumber = null,
            distanceKm = null,
            flightMinutes = null
        )
        val csv = LogbookCsv.toCsv(listOf(minimal))
        val restored = LogbookCsv.fromCsv(csv)

        assertEquals(1, restored.size)
        val e = restored[0]
        assertNull(e.flightType)
        assertNull(e.classType)
        assertNull(e.airline)
        assertNull(e.flightNumber)
        assertNull(e.aircraftType)
        assertNull(e.registration)
        assertNull(e.distanceKm)
        assertNull(e.flightMinutes)
        assertEquals(false, e.layover)
        assertNull(e.comment)
    }

    @Test
    fun csv_from_skipsInvalidRowsWithoutCrash() {
        val csv = buildString {
            appendLine("\uFEFFdate,flightType,classType,fromAirport,toAirport")
            appendLine("kein-datum,FRA,JFK")
            appendLine("2026-09-07,On Duty,Business,FRA,")
            appendLine("2026-09-07,On Duty,Business,MUC,ZRH")
        }
        val entries = LogbookCsv.fromCsv(csv)
        assertEquals(1, entries.size)
        assertEquals("MUC", entries[0].fromAirport)
        assertEquals("ZRH", entries[0].toAirport)
    }

    @Test
    fun geoMath_fraJfk_isAbout6195Km() {
        val fra = AirportData.GeoLocation(50.0333, 8.5706)
        val jfk = AirportData.GeoLocation(40.6413, -73.7781)
        val distance = GeoMath.distanceKm(fra, jfk)
        assertTrue(distance in 6170.0..6220.0)
    }

    @Test
    fun geoMath_flightMinutes_alwaysPositive() {
        assertEquals(25, GeoMath.flightMinutes(0.0))
        assertTrue(GeoMath.flightMinutes(6195.0) in 480..490)
    }

    @Test
    fun saveFlight_dateParsing_acceptsGermanFormat() {
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val parsed = LocalDate.parse("07.09.2026", formatter)
        assertEquals(LocalDate.of(2026, 9, 7), parsed)
        assertEquals("2026-09-07", parsed.toString())
    }

    @Test
    fun saveFlight_dateParsing_returnsNullForInvalidInput() {
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val parsed = try {
            LocalDate.parse("", formatter)
        } catch (e: Exception) {
            null
        }
        assertNull(parsed)
    }

    @Test
    fun entry_idHandling_newFlightHasNullId() {
        val newFlight = LogbookEntry(
            date = LocalDate.of(2026, 9, 7),
            fromAirport = "MUC",
            toAirport = "ZRH"
        )
        assertNull(newFlight.id)
        assertNotNull(entry.id)
    }

    @Test
    fun chartData_normalizesGermanAndEnglishFlightTypes() {
        assertEquals("Privat", ChartData.normalizeFlightType("Privat"))
        assertEquals("Privat", ChartData.normalizeFlightType("Private"))
        assertEquals("Privat", ChartData.normalizeFlightType(" private "))
        assertEquals("On Duty", ChartData.normalizeFlightType("on duty"))
        assertEquals("Deadhead", ChartData.normalizeFlightType("Deadhead"))
        assertEquals("Ferry", ChartData.normalizeFlightType("Ferry"))
        assertEquals("Ground Transfer", ChartData.normalizeFlightType("Ground Transfer"))
        assertEquals("Dienstreise", ChartData.normalizeFlightType("Dienstreise"))
        assertEquals("Dienstreise", ChartData.normalizeFlightType("Duty Travel"))
        assertEquals("Dienstreise", ChartData.normalizeFlightType("Business Travel"))
        assertNull(ChartData.normalizeFlightType(null))
        assertEquals("Sonderflug", ChartData.normalizeFlightType("Sonderflug"))
    }

    @Test
    fun chartData_normalizesGermanAndEnglishClassTypes() {
        assertEquals("Economy", ChartData.normalizeClassType("Economy"))
        assertEquals("Economy", ChartData.normalizeClassType("Economy Class"))
        assertEquals("Premium Eco", ChartData.normalizeClassType("Premium Eco"))
        assertEquals("Premium Eco", ChartData.normalizeClassType("Premium Economy"))
        assertEquals("Business", ChartData.normalizeClassType("Business"))
        assertEquals("First", ChartData.normalizeClassType("First"))
        assertEquals("First", ChartData.normalizeClassType("First Class"))
        assertNull(ChartData.normalizeClassType(null))
    }

    @Test
    fun chartData_travelTypeCounting_isLocaleIndependent() {
        fun entry(type: String?) = LogbookEntry(
            date = LocalDate.of(2026, 1, 1),
            fromAirport = "MUC",
            toAirport = "ZRH",
            flightType = type
        )
        val entries = listOf(
            entry("Privat"),
            entry("Private"),
            entry("On Duty"),
            entry("Deadhead"),
            entry("Dienstreise"),
            entry("Duty Travel"),
            entry("Ferry"),
            entry("Ground Transfer"),
            entry(null)
        )
        val grouped = entries.map { ChartData.normalizeFlightType(it.flightType) }
            .filterNotNull()
            .groupingBy { it }
            .eachCount()
        assertEquals(2, grouped["Privat"])
        assertEquals(1, grouped["On Duty"])
        assertEquals(1, grouped["Deadhead"])
        assertEquals(1, grouped["Ferry"])
        assertEquals(1, grouped["Ground Transfer"])
        assertEquals(2, grouped["Dienstreise"])
    }

    @Test
    fun chartData_travelTypeSlices_encodeAllSixCategories() {
        val saved = listOf("Privat", "On Duty", "Deadhead", "Ferry", "Ground Transfer", "Dienstreise")
        val normalized = saved.map { ChartData.normalizeFlightType(it) }
        assertEquals(
            setOf("Privat", "On Duty", "Deadhead", "Ferry", "Ground Transfer", "Dienstreise"),
            normalized.toSet()
        )
    }
}