package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FlightTypeOptionsTest {

    private fun entry(type: String?, id: Long) = LogbookEntry(
        id = id,
        date = LocalDate.of(2026, 5, id.toInt()),
        flightType = type,
        fromAirport = "FRA",
        toAirport = "JFK"
    )

    private val entries = listOf(
        entry("Privat", 1L),
        entry("On Duty", 2L),
        entry("Deadhead", 3L),
        entry("Ferry", 4L),
        entry("Ground Transfer", 5L),
        entry("Dienstreise", 6L),
        entry(null, 7L)
    )

    @Test
    fun alleFluegeAendernDieListeNicht() {
        assertEquals(entries, FlightTypeOptions.filter(entries, FlightTypeOptions.KEY_ALL))
    }

    @Test
    fun nurDienstlichZaehltFuenfReisearten() {
        val duty = FlightTypeOptions.filter(entries, FlightTypeOptions.KEY_DUTY)

        assertEquals(
            listOf("On Duty", "Deadhead", "Ferry", "Ground Transfer", "Dienstreise"),
            duty.map { it.flightType }
        )
    }

    @Test
    fun nurPrivatZaehltNurPrivat() {
        val private = FlightTypeOptions.filter(entries, FlightTypeOptions.KEY_PRIVATE)

        assertEquals(listOf("Privat"), private.map { it.flightType })
    }

    @Test
    fun beideFilterLassenSichAddieren() {
        // "Nur Dienstlich" und "Nur Privat" zusammen decken alle Einträge mit
        // Reiseart ab - nur der Eintrag ohne Reiseart bleibt außen vor.
        val combined = FlightTypeOptions.filter(entries, FlightTypeOptions.KEY_DUTY) +
            FlightTypeOptions.filter(entries, FlightTypeOptions.KEY_PRIVATE)

        assertEquals(6, combined.size)
        assertEquals(entries.size - 1, combined.size)
    }

    @Test
    fun englischeBezeichnungenGehoerenZurRichtigenGruppe() {
        // Ein in englischer Sprache erfasster Eintrag trägt "Duty Travel",
        // nicht "Dienstreise" - die Zuchnung darf an der Sprache nicht hängen.
        val english = listOf(entry("Duty Travel", 8L), entry("Private", 9L))

        assertEquals(1, FlightTypeOptions.filter(english, FlightTypeOptions.KEY_DUTY).size)
        assertEquals(1, FlightTypeOptions.filter(english, FlightTypeOptions.KEY_PRIVATE).size)
    }

    @Test
    fun unbekannterSchluesselZeigtAlleFluege() {
        // Ein Schlüssel aus einer älteren Version darf die Kacheln nicht leeren.
        assertEquals(FlightTypeOptions.KEY_ALL, FlightTypeOptions.normalize("irgendwas"))
        assertEquals(FlightTypeOptions.KEY_ALL, FlightTypeOptions.normalize(null))
        assertEquals(FlightTypeOptions.KEY_DUTY, FlightTypeOptions.normalize("duty"))
        assertEquals(entries, FlightTypeOptions.filter(entries, FlightTypeOptions.normalize("x")))
    }

    @Test
    fun dienstlicheArtenSindGenauDieFuenfUebrigen() {
        assertEquals(
            listOf("On Duty", "Deadhead", "Ferry", "Ground Transfer", "Dienstreise"),
            ChartData.DUTY_CANONICALS
        )
        assertFalse(ChartData.DUTY_CANONICALS.contains(ChartData.PRIVATE_CANONICAL))
        assertTrue(
            entries.filter { it.flightType != null }.all {
                val type = ChartData.normalizeFlightType(it.flightType)
                type == ChartData.PRIVATE_CANONICAL || type in ChartData.DUTY_CANONICALS
            }
        )
    }
}