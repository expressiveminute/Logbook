package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.time.LocalDate
import java.util.Locale

/**
 * Tests fuer die Auswertung der Kachel "Fluggesellschaften". Das Feld im
 * Formular ist ein Code - "LH", "DLH" oder "Lufthansa" sollen dieselbe
 * Airline ergeben, und ein Ground Transfer darf weder Distanz noch Flugzeit
 * zählen, genau wie bei den Kacheln "Distanz" und "Zeit".
 */
class AirlineStatsTest {

    private val collator: Collator = Collator.getInstance(Locale.GERMANY)

    /** kleiner Katalog-Ausschnitt: LH über IATA, ICAO und Name erreichbar. */
    private val resolve: (String) -> AirlineStats.Resolved? = { raw ->
        when (raw.trim().uppercase()) {
            "LH", "DLH", "LUFTHANSA" -> AirlineStats.Resolved("LH", "DLH", "Lufthansa")
            "BA" -> AirlineStats.Resolved("BA", "BAW", "British Airways")
            else -> null
        }
    }

    private fun entry(
        date: LocalDate,
        airline: String?,
        flightType: String? = "Privat",
        from: String = "FRA",
        to: String = "JFK",
        minutes: Int? = 60,
        km: Int? = 1000,
        type: String? = "A320"
    ) = LogbookEntry(
        date = date,
        flightType = flightType,
        fromAirport = from,
        toAirport = to,
        airline = airline,
        flightMinutes = minutes,
        distanceKm = km,
        aircraftType = type
    )

    @Test
    fun keyOf_nimmtIataCode() {
        assertEquals("LH", AirlineStats.keyOf("LH", resolve))
        assertEquals("LH", AirlineStats.keyOf(" lh ", resolve))
    }

    @Test
    fun keyOf_nimmtIcaoCode() {
        assertEquals("LH", AirlineStats.keyOf("DLH", resolve))
    }

    @Test
    fun keyOf_nimmtNamen() {
        assertEquals("LH", AirlineStats.keyOf("Lufthansa", resolve))
    }

    @Test
    fun keyOf_unbekanntBleibtGrossbuchstabe() {
        assertEquals("XYZ", AirlineStats.keyOf("xyz", resolve))
    }

    @Test
    fun keyOf_leerIstNull() {
        assertNull(AirlineStats.keyOf(null, resolve))
        assertNull(AirlineStats.keyOf("  ", resolve))
    }

    @Test
    fun build_gruppiertLhUndDlhZusammen() {
        val entries = listOf(
            entry(LocalDate.of(2026, 1, 1), "LH"),
            entry(LocalDate.of(2026, 2, 1), "DLH"),
            entry(LocalDate.of(2026, 3, 1), "BA")
        )
        val airlines = AirlineStats.build(entries, resolve, collator)
        assertEquals(2, airlines.size)
        val lh = airlines.first { it.code == "LH" }
        assertEquals("Lufthansa", lh.name)
        assertEquals("DLH", lh.icao)
        assertEquals(2, lh.flights)
    }

    @Test
    fun build_unbekannteAirlineBleibtEigenerRohtext() {
        val entries = listOf(entry(LocalDate.of(2026, 1, 1), "xyz"))
        val airlines = AirlineStats.build(entries, resolve, collator)
        assertEquals(1, airlines.size)
        // Schlüssel gross, Anzeigename so wie zuerst geschrieben (wie beim
        // Reisebuddy); in der App ist der Code beim Erfassen bereits gross.
        assertEquals("XYZ", airlines[0].code)
        assertEquals("xyz", airlines[0].name)
        assertNull(airlines[0].icao)
    }

    @Test
    fun build_sortiertNachFluegenDannName() {
        val entries = listOf(
            entry(LocalDate.of(2026, 1, 1), "BA"),
            entry(LocalDate.of(2026, 2, 1), "LH"),
            entry(LocalDate.of(2026, 3, 1), "DLH"),
            entry(LocalDate.of(2026, 4, 1), "LH")
        )
        val airlines = AirlineStats.build(entries, resolve, collator)
        assertEquals(listOf("LH", "BA"), airlines.map { it.code })
    }

    @Test
    fun build_groundTransferZaehltNichtZuDistanzUndZeit() {
        val entries = listOf(
            entry(LocalDate.of(2026, 1, 1), "LH", minutes = 120, km = 2000),
            entry(
                LocalDate.of(2026, 2, 1), "LH",
                flightType = "Ground Transfer", minutes = 60, km = 500
            )
        )
        val lh = AirlineStats.build(entries, resolve, collator).single()
        // Beide Einträge zählen als Flug, aber nur der echte als Distanz.
        assertEquals(2, lh.flights)
        assertEquals(120, lh.minutes)
        assertEquals(2000, lh.km)
    }

    @Test
    fun build_zaehltZieleTypenUndErstesLetztesDatum() {
        val entries = listOf(
            entry(LocalDate.of(2026, 1, 1), "LH", to = "JFK", type = "A320"),
            entry(LocalDate.of(2026, 2, 1), "LH", to = "JFK", type = "A320"),
            entry(LocalDate.of(2026, 3, 1), "LH", to = "MUC", type = "B747")
        )
        val lh = AirlineStats.build(entries, resolve, collator).single()
        assertEquals(2, lh.destinations)
        assertEquals(2, lh.aircraftTypes)
        assertEquals(LocalDate.of(2026, 1, 1), lh.firstFlight)
        assertEquals(LocalDate.of(2026, 3, 1), lh.lastFlight)
    }

    @Test
    fun build_eintragOhneAirlineFaehlt() {
        val entries = listOf(
            entry(LocalDate.of(2026, 1, 1), null),
            entry(LocalDate.of(2026, 2, 1), "  "),
            entry(LocalDate.of(2026, 3, 1), "LH")
        )
        val airlines = AirlineStats.build(entries, resolve, collator)
        assertEquals(1, airlines.size)
        assertEquals("LH", airlines[0].code)
    }

    @Test
    fun flightsWithAirline_zaehltNurEintraegeMitAirline() {
        val entries = listOf(
            entry(LocalDate.of(2026, 1, 1), "LH"),
            entry(LocalDate.of(2026, 2, 1), null),
            entry(LocalDate.of(2026, 3, 1), "BA")
        )
        assertEquals(2, AirlineStats.flightsWithAirline(entries))
    }

    @Test
    fun searchScore_exakterCodeIstBesterTreffer() {
        assertEquals(0, AirlineStats.searchScore("LH", "DLH", "Lufthansa", "LH"))
        assertEquals(0, AirlineStats.searchScore("LH", "DLH", "Lufthansa", "dlh"))
    }

    @Test
    fun searchScore_codePrefixVorName() {
        assertEquals(1, AirlineStats.searchScore("LH", "DLH", "Lufthansa", "L"))
        assertEquals(2, AirlineStats.searchScore("LH", "DLH", "Lufthansa", "Lufthansa".take(4)))
        assertEquals(3, AirlineStats.searchScore("LH", "DLH", "Lufthansa", "ufthansa"))
    }

    @Test
    fun searchScore_keinTrefferUndLeereSuche() {
        assertEquals(AirlineStats.NO_MATCH, AirlineStats.searchScore("LH", "DLH", "Lufthansa", "xy"))
        assertEquals(AirlineStats.NO_MATCH, AirlineStats.searchScore("LH", "DLH", "Lufthansa", "  "))
    }

    @Test
    fun matchesQuery_grossUndKleinschreibungEgal() {
        assertTrue(AirlineStats.matchesQuery("LH", "DLH", "Lufthansa", "lufthansa"))
        assertFalse(AirlineStats.matchesQuery("LH", "DLH", "Lufthansa", "british"))
    }
}
