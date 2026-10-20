package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * Prüft die Schätzung der Passagierzahlen ([PassengersCalculator]): Sitzplätze
 * × Auslastung pro Flug, Durchschnitt als Rückfall für unbekannte Typen, kein
 * Zählen von Ground Transfers, Jahresgruppierung und Tausender-Rundung.
 */
class PassengersCalculatorTest {

    private val catalog = AircraftSeats.Catalog(
        seats = mapOf("A320" to 186, "B737" to 230),
        aliases = mapOf("320" to "A320", "737" to "B737")
    )
    // Durchschnitt des Datensatzes: (186 + 230) / 2 = 208

    private fun entry(
        date: LocalDate = LocalDate.of(2026, 3, 15),
        aircraftType: String? = "A320",
        flightType: String = "Privat"
    ) = LogbookEntry(
        date = date,
        flightType = flightType,
        fromAirport = "FRA",
        toAirport = "JFK",
        aircraftType = aircraftType
    )

    @Test
    fun passengersPerFlight_rechnetSitzeMalAuslastung() {
        // 186 × 80 % = 148,8 → 149
        assertEquals(149, PassengersCalculator.passengersPerFlight(entry(), 80, catalog))
        assertEquals(186, PassengersCalculator.passengersPerFlight(entry(), 100, catalog))
        assertEquals(93, PassengersCalculator.passengersPerFlight(entry(), 50, catalog))
        // B737: 230 × 80 % = 184
        assertEquals(
            184,
            PassengersCalculator.passengersPerFlight(entry(aircraftType = "B737"), 80, catalog)
        )
    }

    @Test
    fun passengersPerFlight_nimmtBeiUnbekanntemTypDenDurchschnitt() {
        // 208 × 80 % = 166,4 → 166
        assertEquals(
            166,
            PassengersCalculator.passengersPerFlight(entry(aircraftType = "Concorde"), 80, catalog)
        )
        assertEquals(
            166,
            PassengersCalculator.passengersPerFlight(entry(aircraftType = null), 80, catalog)
        )
    }

    @Test
    fun passengersPerFlight_groundTransferZaehltNicht() {
        assertEquals(
            0,
            PassengersCalculator.passengersPerFlight(
                entry(flightType = "Ground Transfer"),
                80,
                catalog
            )
        )
    }

    @Test
    fun byYear_gruppiertNeustesZuerstUndOhneGroundTransfers() {
        val entries = listOf(
            entry(date = LocalDate.of(2024, 5, 1), aircraftType = "A320"),
            entry(date = LocalDate.of(2026, 1, 10), aircraftType = "A320"),
            entry(date = LocalDate.of(2026, 2, 20), aircraftType = "B737"),
            entry(date = LocalDate.of(2026, 3, 1), flightType = "Ground Transfer")
        )
        val years = PassengersCalculator.byYear(entries, 80, catalog)
        assertEquals(2, years.size)
        assertEquals(2026, years[0].year)
        assertEquals(333, years[0].passengers) // 149 + 184
        assertEquals(2, years[0].flights)
        assertEquals(2024, years[1].year)
        assertEquals(149, years[1].passengers)
        assertEquals(1, years[1].flights)
    }

    @Test
    fun summary_summiertUndBildetDenDurchschnitt() {
        val entries = listOf(
            entry(date = LocalDate.of(2025, 6, 1), aircraftType = "A320"),
            entry(date = LocalDate.of(2026, 6, 1), aircraftType = "A320")
        )
        val summary = PassengersCalculator.summary(entries, 80, catalog)
        assertEquals(298, summary.totalPassengers)
        assertEquals(2, summary.flights)
        assertEquals(149, summary.averagePerFlight)
        assertEquals(2, summary.years.size)
    }

    @Test
    fun summary_ohneFluege_istNull() {
        val summary = PassengersCalculator.summary(emptyList(), 80, catalog)
        assertEquals(0, summary.totalPassengers)
        assertEquals(0, summary.flights)
        assertEquals(0, summary.averagePerFlight)
        assertEquals(0, summary.years.size)
    }

    @Test
    fun roundToThousands_rundetAufVolleTausender() {
        assertEquals(0, PassengersCalculator.roundToThousands(0))
        assertEquals(0, PassengersCalculator.roundToThousands(499))
        assertEquals(1000, PassengersCalculator.roundToThousands(500))
        assertEquals(1000, PassengersCalculator.roundToThousands(1499))
        assertEquals(2000, PassengersCalculator.roundToThousands(1500))
        assertEquals(1284000, PassengersCalculator.roundToThousands(1284390))
    }

    @Test
    fun normalizeLoadFactor_begrenztUndRundetAuf5erSchritte() {
        assertEquals(80, PassengersCalculator.normalizeLoadFactor(80))
        assertEquals(80, PassengersCalculator.normalizeLoadFactor(83))
        assertEquals(80, PassengersCalculator.normalizeLoadFactor(82))
        assertEquals(50, PassengersCalculator.normalizeLoadFactor(50))
        assertEquals(50, PassengersCalculator.normalizeLoadFactor(47))
        assertEquals(50, PassengersCalculator.normalizeLoadFactor(52))
        assertEquals(95, PassengersCalculator.normalizeLoadFactor(97))
        assertEquals(100, PassengersCalculator.normalizeLoadFactor(100))
        assertEquals(100, PassengersCalculator.normalizeLoadFactor(103))
    }

    @Test
    fun passengersText_gruppiertNachAppSprache() {
        assertEquals("1.284.000", PassengersCalculator.passengersText(1284000, Locale.GERMANY))
        assertEquals("1,284,000", PassengersCalculator.passengersText(1284000, Locale.ENGLISH))
        assertEquals("0", PassengersCalculator.passengersText(0, Locale.GERMANY))
    }
}
