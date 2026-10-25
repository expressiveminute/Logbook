package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Prüft das Typ-Matching von [AircraftSeats.Catalog] an einem handgebauten
 * Datensatz: Familien, Varianten, Herstellerwörter und Alias-Codes müssen
 * auf die Sitzplatzanzahl der richtigen Familie führen, unbekannte Typen
 * liefern null (der Aufrufer nimmt dann den Durchschnitt).
 */
class AircraftSeatsTest {

    private val catalog = AircraftSeats.Catalog(
        seats = mapOf(
            "A320" to 186,
            "A321" to 236,
            "A380" to 853,
            "B737" to 230,
            "CRJ" to 104,
            "ATR72" to 78
        ),
        aliases = mapOf(
            "320" to "A320",
            "321" to "A321",
            "380" to "A380",
            "737" to "B737",
            "738" to "B737",
            "AT72" to "ATR72",
            "ATR" to "ATR72"
        )
    )

    @Test
    fun seatsFor_erkenntFamilienUndVarianten() {
        assertEquals(186, catalog.seatsFor("A320"))
        assertEquals(186, catalog.seatsFor("A320-200"))
        assertEquals(236, catalog.seatsFor("A321neo"))
        assertEquals(853, catalog.seatsFor("A380-800"))
        assertEquals(230, catalog.seatsFor("B737-800"))
        assertEquals(230, catalog.seatsFor("B737 MAX 8"))
        assertEquals(104, catalog.seatsFor("CRJ900"))
        assertEquals(78, catalog.seatsFor("ATR72-600"))
    }

    @Test
    fun seatsFor_erkenntHerstellerWoerter() {
        assertEquals(186, catalog.seatsFor("Airbus A320"))
        assertEquals(186, catalog.seatsFor("AIRBUSA320-200"))
        assertEquals(230, catalog.seatsFor("Boeing 737-800"))
        assertEquals(104, catalog.seatsFor("Bombardier CRJ900"))
        assertEquals(104, catalog.seatsFor("Canadair Regional Jet CRJ900"))
    }

    @Test
    fun seatsFor_erkenntAliase() {
        assertEquals(186, catalog.seatsFor("320"))
        assertEquals(236, catalog.seatsFor("321"))
        assertEquals(230, catalog.seatsFor("737"))
        assertEquals(230, catalog.seatsFor("738"))
        assertEquals(853, catalog.seatsFor("380"))
        assertEquals(78, catalog.seatsFor("ATR"))
        assertEquals(78, catalog.seatsFor("AT72"))
    }

    @Test
    fun seatsFor_varianteMitEigenerZahlSchlaegtDieFamilie() {
        // Varianten mit eigener Zahl stehen neben ihrer Familie; die längste
        // Präfix-Übereinstimmung gewinnt.
        val mitVarianten = AircraftSeats.Catalog(
            seats = mapOf(
                "A320" to 168,
                "A320NEO" to 180,
                "A350" to 480,
                "A350900" to 277,
                "A3501000" to 300
            ),
            aliases = mapOf("20N" to "A320NEO")
        )
        assertEquals(180, mitVarianten.seatsFor("A320neo"))
        assertEquals(180, mitVarianten.seatsFor("Airbus A320 NEO"))
        assertEquals(180, mitVarianten.seatsFor("A20N"))
        assertEquals(168, mitVarianten.seatsFor("A320"))
        assertEquals(168, mitVarianten.seatsFor("A320-200"))
        assertEquals(277, mitVarianten.seatsFor("A350-900"))
        assertEquals(300, mitVarianten.seatsFor("A350-1000"))
        assertEquals(480, mitVarianten.seatsFor("A350"))
    }

    @Test
    fun seatsForfindetAliasAlsPraefixUndAufDemZiffernteil() {
        // "737 MAX" beginnt mit dem Alias "737".
        assertEquals(230, catalog.seatsFor("737 MAX"))
        // "B738" trägt den Alias erst hinter dem Herstellerbuchstaben.
        assertEquals(230, catalog.seatsFor("B738"))
    }

    @Test
    fun seatsFor_liefertNullBeiUnbekanntemTyp() {
        assertNull(catalog.seatsFor(null))
        assertNull(catalog.seatsFor(""))
        assertNull(catalog.seatsFor("   "))
        assertNull(catalog.seatsFor("Concorde"))
        assertNull(catalog.seatsFor("AIRBUS"))
    }

    @Test
    fun averageSeats_istDerMittelwertDerFamilien() {
        // (186 + 236 + 853 + 230 + 104 + 78) / 6 = 281,166...
        assertEquals(281.1666, catalog.averageSeats, 0.001)
    }

    @Test
    fun averageSeats_hatRueckfallBeiLeererFamilienliste() {
        val empty = AircraftSeats.Catalog(emptyMap(), emptyMap())
        assertEquals(200.0, empty.averageSeats, 0.0)
        assertNull(empty.seatsFor("A320"))
    }
}
