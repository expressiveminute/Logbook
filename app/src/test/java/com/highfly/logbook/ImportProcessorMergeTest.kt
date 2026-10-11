package com.highfly.logbook

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Die Anreicherung beim Import darf vorhandene Werte nie ueberschreiben:
 * Was in der CSV-Datei steht (Entfernung, Flugzeit, Laender), behaelt
 * Vorrang; nur fehlende Angaben werden aus den Flughafenkoordinaten
 * ergaenzt. [ImportProcessor.merged] ist die reine Funktion dahinter und
 * wird hier ohne Android-Zugriff geprueft.
 */
class ImportProcessorMergeTest {

    private val entry = LogbookEntry(
        date = LocalDate.of(2024, 6, 1),
        fromAirport = "MUC",
        toAirport = "JFK"
    )

    @Test
    fun `vorhandene Werte bleiben unangetastet`() {
        val given = entry.copy(
            distanceKm = 7000,
            flightMinutes = 480,
            fromCountry = "DE",
            toCountry = "US"
        )
        val merged = ImportProcessor.merged(given, 123, 45, "XX", "YY")
        assertEquals(7000, merged.distanceKm)
        assertEquals(480, merged.flightMinutes)
        assertEquals("DE", merged.fromCountry)
        assertEquals("US", merged.toCountry)
    }

    @Test
    fun `fehlende Werte werden ergaenzt`() {
        val merged = ImportProcessor.merged(entry, 123, 45, "XX", "YY")
        assertEquals(123, merged.distanceKm)
        assertEquals(45, merged.flightMinutes)
        assertEquals("XX", merged.fromCountry)
        assertEquals("YY", merged.toCountry)
    }

    @Test
    fun `leere Laender gelten als fehlend`() {
        val given = entry.copy(fromCountry = "", toCountry = " ")
        val merged = ImportProcessor.merged(given, null, null, "DE", "US")
        assertEquals("DE", merged.fromCountry)
        assertEquals("US", merged.toCountry)
    }

    @Test
    fun `laenger mitgeführte Eintraege behalten ihre Nullwerte nicht kuenstlich`() {
        // Weder CSV noch Berechnung liefert einen Wert: Der Eintrag bleibt
        // ohne Angabe, geschriebene null-Werte sind kein Ersatz.
        val merged = ImportProcessor.merged(entry, null, null, null, null)
        assertNull(merged.distanceKm)
        assertNull(merged.flightMinutes)
        assertNull(merged.fromCountry)
        assertNull(merged.toCountry)
    }
}