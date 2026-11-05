package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tests für die Aufenthalts-Auswertung in [CountryTimeStats] und die
 * Serialisierung von [BorderCrossing].
 */
class CountryTimeTest {

    private val countryOf: (String) -> String? = { iata ->
        when (iata.trim().uppercase()) {
            "FRA", "MUC" -> "DE"
            "JFK" -> "US"
            "LHR" -> "GB"
            "CPH" -> "DK"
            "LYS" -> "FR"
            "ALA" -> "KZ"
            else -> null
        }
    }

    private fun entry(
        id: Long, y: Int, m: Int, d: Int, from: String, to: String,
        flightType: String? = "Privat", layover: Boolean = false
    ) = LogbookEntry(
        id = id, date = LocalDate.of(y, m, d),
        fromAirport = from, toAirport = to,
        flightType = flightType, layover = layover
    )

    @Test
    fun hinUndRueckflug_ergibtAufenthaltImZielland() {
        // FRA -> JFK am 10.03., JFK -> FRA am 15.03.: 5 Tage in den USA.
        val entries = listOf(
            entry(2, 2026, 3, 15, "JFK", "FRA"),
            entry(1, 2026, 3, 10, "FRA", "JFK")
        )
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 20))
        assertEquals(1, result.size)
        val us = result[0]
        assertEquals("US", us.iso2)
        assertEquals(5L, us.totalDays)
        assertEquals(1, us.visits)
        assertFalse(us.open)
    }

    @Test
    fun heimatlandWirdNichtGezaehlt() {
        // Homebase FRA -> Heimatland DE; DE-Ereignisse tauchen nicht auf.
        val entries = listOf(entry(1, 2026, 3, 10, "FRA", "JFK"))
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 20))
        assertTrue(result.none { it.iso2 == "DE" })
        assertEquals(listOf("US"), result.map { it.iso2 })
    }

    @Test
    fun layover_zaehltImUmstiegsland() {
        // FRA -> LHR am 10.03., LHR -> JFK am 11.03., JFK -> FRA am 15.03.
        val entries = listOf(
            entry(3, 2026, 3, 15, "JFK", "FRA"),
            entry(2, 2026, 3, 11, "LHR", "JFK"),
            entry(1, 2026, 3, 10, "FRA", "LHR")
        )
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 20))
        val gb = result.single { it.iso2 == "GB" }
        assertEquals(1L, gb.totalDays)
        val us = result.single { it.iso2 == "US" }
        assertEquals(4L, us.totalDays)
    }

    @Test
    fun offenerAufenthalt_zaehltBisHeute() {
        val entries = listOf(entry(1, 2026, 3, 10, "FRA", "JFK"))
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 20))
        val us = result.single { it.iso2 == "US" }
        assertEquals(10L, us.totalDays)
        assertTrue(us.open)
    }

    @Test
    fun tagesausflug_zaehltNullTageUndBleibtNichtOffen() {
        // Hin und zurueck am selben Tag (Hinflug zuerst erfasst, kleinere id).
        val entries = listOf(
            entry(2, 2026, 3, 10, "LHR", "FRA"),
            entry(1, 2026, 3, 10, "FRA", "LHR")
        )
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 20))
        val gb = result.single { it.iso2 == "GB" }
        assertEquals(0L, gb.totalDays)
        assertEquals(1, gb.visits)
        assertFalse(gb.open)
    }

    @Test
    fun inlandsflug_erzeugtKeineEreignisse() {
        val entries = listOf(entry(1, 2026, 3, 10, "FRA", "MUC"))
        val result = CountryTimeStats.evaluate(entries, countryOf, null, emptyList(), LocalDate.of(2026, 3, 20))
        assertTrue(result.isEmpty())
    }

    @Test
    fun manuelleUebertritte_fliessenEin() {
        // Landweg nach Daenemark: manuell betreten am 01.04., verlassen am 05.04.
        val manual = listOf(
            BorderCrossing("DK", LocalDate.of(2026, 4, 5), enter = false),
            BorderCrossing("DK", LocalDate.of(2026, 4, 1), enter = true)
        )
        val result = CountryTimeStats.evaluate(emptyList(), countryOf, "DE", manual, LocalDate.of(2026, 4, 10))
        val dk = result.single()
        assertEquals("DK", dk.iso2)
        assertEquals(4L, dk.totalDays)
        assertEquals(1, dk.visits)
        assertFalse(dk.open)
    }

    @Test
    fun manuellerUebertritt_offenBisHeute() {
        val manual = listOf(BorderCrossing("DK", LocalDate.of(2026, 4, 1), enter = true))
        val result = CountryTimeStats.evaluate(emptyList(), countryOf, "DE", manual, LocalDate.of(2026, 4, 10))
        assertEquals(9L, result.single().totalDays)
        assertTrue(result.single().open)
    }

    @Test
    fun manuelleUebertritte_imHeimatlandWerdenIgnoriert() {
        val manual = listOf(BorderCrossing("DE", LocalDate.of(2026, 4, 1), enter = true))
        val result = CountryTimeStats.evaluate(emptyList(), countryOf, "DE", manual, LocalDate.of(2026, 4, 10))
        assertTrue(result.isEmpty())
    }

    @Test
    fun ergebnis_istNachTagenAbsteigendSortiert() {
        val entries = listOf(
            entry(1, 2026, 3, 10, "FRA", "JFK"),   // US 10.03.-12.03. = 2 Tage
            entry(2, 2026, 3, 12, "JFK", "FRA"),
            entry(3, 2026, 3, 20, "FRA", "LHR"),   // GB 20.03.-25.03. = 5 Tage
            entry(4, 2026, 3, 25, "LHR", "FRA")
        )
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 30))
        assertEquals(listOf("GB", "US"), result.map { it.iso2 })
        assertEquals(listOf(5L, 2L), result.map { it.totalDays })
    }

    @Test
    fun periods_werdenMitgeliefert() {
        // Hin 10.03., Rueck 15.03., dann nochmal 20.03.-25.03.
        val entries = listOf(
            entry(4, 2026, 3, 25, "LHR", "FRA"),
            entry(3, 2026, 3, 20, "FRA", "LHR"),
            entry(2, 2026, 3, 15, "JFK", "FRA"),
            entry(1, 2026, 3, 10, "FRA", "JFK")
        )
        // US: eine Periode; GB: eine Periode.
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 30))
        val us = result.single { it.iso2 == "US" }
        assertEquals(1, us.periods.size)
        val p = us.periods[0]
        assertEquals(LocalDate.of(2026, 3, 10), p.start)
        assertEquals(LocalDate.of(2026, 3, 15), p.end)
        assertEquals(5L, p.days)
        assertTrue(p.closedByFlight)
        assertFalse(p.open)
        // Hin- und Rückflug stehen an der Periode (für die Zeitstrahl-Zeile).
        assertEquals("FRA", p.enterEntry?.fromAirport)
        assertEquals("JFK", p.enterEntry?.toAirport)
        assertEquals("JFK", p.exitEntry?.fromAirport)
        assertEquals("FRA", p.exitEntry?.toAirport)
    }

    @Test
    fun periodOffen_hatKeinEnddatumUndKeinenFlugAbschluss() {
        val entries = listOf(entry(1, 2026, 3, 10, "FRA", "JFK"))
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 20))
        val p = result.single { it.iso2 == "US" }.periods.single()
        assertEquals(null, p.end)
        assertEquals(10L, p.days)
        assertFalse(p.closedByFlight)
        assertTrue(p.open)
    }

    @Test
    fun periodManuellgeschlossen_istNichtClosedByFlight() {
        // Per Flug betreten, per Landweg verlassen.
        val entries = listOf(entry(1, 2026, 3, 10, "FRA", "JFK"))
        val manual = listOf(BorderCrossing("US", LocalDate.of(2026, 3, 14), enter = false))
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", manual, LocalDate.of(2026, 3, 20))
        val p = result.single { it.iso2 == "US" }.periods.single()
        assertEquals(LocalDate.of(2026, 3, 14), p.end)
        assertEquals(4L, p.days)
        assertFalse(p.closedByFlight)
        assertFalse(p.open)
        // Betreten per Flug, verlassen manuell (Landweg).
        assertEquals("JFK", p.enterEntry?.toAirport)
        assertEquals(null, p.exitEntry)
    }

    @Test
    fun borderCrossing_serialisierungRundtrip() {
        val c = BorderCrossing("dk", LocalDate.of(2026, 4, 1), enter = true)
        val parsed = BorderCrossing.parse(c.toLine())
        assertEquals(BorderCrossing("DK", LocalDate.of(2026, 4, 1), enter = true), parsed)
        val exit = BorderCrossing.parse(BorderCrossing("US", LocalDate.of(2026, 3, 15), enter = false).toLine())
        assertEquals(false, exit?.enter)
    }

    @Test
    fun manuellerAufenthalt_mitTransport_wirdAlsStayGezaehlt() {
        // Szenario FAB-Popup auf "Times": kompletter Aufenthalt, beide Wege
        // Landweg (Auto hin, Fähre zurück) - zaehlt als ein Aufenthalt.
        val manual = listOf(
            BorderCrossing("DK", LocalDate.of(2026, 4, 1), enter = true, transport = "car"),
            BorderCrossing("DK", LocalDate.of(2026, 4, 5), enter = false, transport = "ferry")
        )
        val result = CountryTimeStats.evaluate(emptyList(), countryOf, "DE", manual, LocalDate.of(2026, 4, 10))
        val dk = result.single()
        assertEquals("DK", dk.iso2)
        assertEquals(4L, dk.totalDays)
        assertEquals(1, dk.visits)
        assertEquals(1, dk.periods.size)
        assertFalse(dk.periods[0].closedByFlight)
        assertFalse(dk.open)
        // Im Heimatland wird nichts gezaehlt.
        val home = listOf(
            BorderCrossing("DE", LocalDate.of(2026, 4, 1), enter = true, transport = "car"),
            BorderCrossing("DE", LocalDate.of(2026, 4, 5), enter = false, transport = "car")
        )
        assertTrue(
            CountryTimeStats.evaluate(emptyList(), countryOf, "DE", home, LocalDate.of(2026, 4, 10)).isEmpty()
        )
    }

    @Test
    fun borderCrossing_transportRundtrip() {
        val c = BorderCrossing("DK", LocalDate.of(2026, 4, 1), enter = true, transport = "car")
        assertEquals(c, BorderCrossing.parse(c.toLine()))
        // Alte 3-Feld-Zeilen ohne Transport parsen weiter.
        assertEquals(null, BorderCrossing.parse("DK|2026-04-01|enter|hovercraft")?.transport)
        assertEquals("ferry", BorderCrossing.parse("DK|2026-04-01|exit|ferry")?.transport)
    }

    @Test
    fun borderCrossing_parseLehntKaputtesAb() {
        assertNull(BorderCrossing.parse(""))
        assertNull(BorderCrossing.parse("DE|2026-04-01"))
        assertNull(BorderCrossing.parse("DE|kein-datum|enter"))
        assertNull(BorderCrossing.parse("DE|2026-04-01|rein"))
        assertNull(BorderCrossing.parse("D|2026-04-01|enter"))
        assertNull(BorderCrossing.parse("D1|2026-04-01|enter"))
    }

    @Test
    fun onDutyOhneLayover_zaehltNicht() {
        // Dienstlicher Flug ohne Layover-Kennzeichen: kein Aufenthalt.
        val entries = listOf(entry(1, 2026, 3, 10, "FRA", "JFK", flightType = "On Duty"))
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 20))
        assertTrue(result.isEmpty())
    }

    @Test
    fun onDutyMitLayover_zaehlt() {
        // FRA -> JFK am 10.03. und JFK -> FRA am 15.03., beide On Duty als Layover:
        // 5 Tage in den USA.
        val entries = listOf(
            entry(2, 2026, 3, 15, "JFK", "FRA", flightType = "On Duty", layover = true),
            entry(1, 2026, 3, 10, "FRA", "JFK", flightType = "On Duty", layover = true)
        )
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 20))
        val us = result.single { it.iso2 == "US" }
        assertEquals(5L, us.totalDays)
        assertFalse(us.open)
    }

    @Test
    fun tagesausflugOnDutyOhneLayover_zaehltNicht() {
        // Hin und zurueck am selben Tag als On Duty ohne Layover: gar kein Land.
        val entries = listOf(
            entry(2, 2026, 3, 10, "LHR", "FRA", flightType = "On Duty"),
            entry(1, 2026, 3, 10, "FRA", "LHR", flightType = "On Duty")
        )
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 20))
        assertTrue(result.isEmpty())
    }

    @Test
    fun tagesausflugPrivat_zaehltAlsNullTage() {
        // Dasselbe als Privat: Tagesausflug, 0 Tage, bleibt nicht offen.
        val entries = listOf(
            entry(2, 2026, 3, 10, "LHR", "FRA", flightType = "Privat"),
            entry(1, 2026, 3, 10, "FRA", "LHR", flightType = "Privat")
        )
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 20))
        val gb = result.single { it.iso2 == "GB" }
        assertEquals(0L, gb.totalDays)
        assertEquals(1, gb.visits)
        assertFalse(gb.open)
    }

    @Test
    fun gleichtagsRueckflug_mitKleinererId_wirdErkannt() {
        // MUC -> LYS am 24.10. und LYS -> MUC am selben Tag, aber der Rueckflug
        // hat die kleinere id (z. B. per Import in anderer Reihenfolge erfasst).
        // Trotzdem darf kein Aufenthalt offen bleiben.
        val entries = listOf(
            entry(1, 2026, 10, 24, "LYS", "MUC"),
            entry(2, 2026, 10, 24, "MUC", "LYS")
        )
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 10, 30))
        val fr = result.single { it.iso2 == "FR" }
        assertEquals(0L, fr.totalDays)
        assertEquals(1, fr.visits)
        assertFalse(fr.open)
        assertNull(fr.openSince)
    }

    @Test
    fun rueckflugAndererReiseart_wirdNichtErkannt_bleibtOffen() {
        // Hinflug MUC -> LYS als Privat, Rueckflug am selben Tag als On Duty
        // ohne Layover: nur die Hinreise ist in den Daten, der Aufenthalt
        // bleibt offen (in der Liste als "Offene Zeiträume" markiert).
        val entries = listOf(
            entry(1, 2026, 10, 24, "LYS", "MUC", flightType = "On Duty"),
            entry(2, 2026, 10, 24, "MUC", "LYS", flightType = "Privat")
        )
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 10, 30))
        val fr = result.single { it.iso2 == "FR" }
        assertTrue(fr.open)
        assertEquals(LocalDate.of(2026, 10, 24), fr.openSince)
        assertEquals(6L, fr.totalDays)
    }

    @Test
    fun nurAuslandsflug_erzeugtAufenthaltMitUnbekannterEinreise() {
        // Nur ALA -> FRA am 17.10. im Logbuch (Einreise nach Kasachstan z. B.
        // per Bus drei Tage davor nicht erfasst): KZ taucht trotzdem auf, die
        // Einreise ist unbekannt - auf der Detailseite erscheint der
        // Einreise-Button.
        val entries = listOf(entry(1, 2026, 10, 17, "ALA", "FRA"))
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 10, 20))
        val kz = result.single { it.iso2 == "KZ" }
        assertEquals(1, kz.visits)
        assertEquals(0L, kz.totalDays)
        assertFalse(kz.open)
        assertTrue(kz.hasOpenPeriods)
        val p = kz.periods.single()
        assertNull(p.start)
        assertTrue(p.enterUnknown)
        assertEquals(LocalDate.of(2026, 10, 17), p.end)
        assertEquals("ALA", p.exitEntry?.fromAirport)
        assertEquals("FRA", p.exitEntry?.toAirport)
        assertNull(p.enterEntry)
        assertTrue(p.closedByFlight)
    }

    @Test
    fun nurAuslandsflug_mitManuellerEinreise_wirdZumAufenthalt() {
        // Dieselbe Konstellation, die Einreise per Bus am 14.10. manuell
        // nachtragen: 3 Tage Aufenthalt mit bekannter Einreise.
        val entries = listOf(entry(1, 2026, 10, 17, "ALA", "FRA"))
        val manual = listOf(
            BorderCrossing("KZ", LocalDate.of(2026, 10, 14), enter = true, transport = "bus")
        )
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", manual, LocalDate.of(2026, 10, 20))
        val kz = result.single { it.iso2 == "KZ" }
        assertEquals(1, kz.visits)
        assertEquals(3L, kz.totalDays)
        assertFalse(kz.open)
        assertFalse(kz.hasOpenPeriods)
        val p = kz.periods.single()
        assertEquals(LocalDate.of(2026, 10, 14), p.start)
        assertEquals(LocalDate.of(2026, 10, 17), p.end)
        assertEquals(3L, p.days)
        assertNull(p.enterEntry)
        assertEquals("ALA", p.exitEntry?.fromAirport)
        assertTrue(p.closedByFlight)
    }

    @Test
    fun gemischteLayoverKette_zaehltWeiterhinProLand() {
        // FRA -> LHR (Layover), LHR -> JFK (Privat), JFK -> FRA (Privat):
        // GB 1 Tag, US 4 Tage.
        val entries = listOf(
            entry(3, 2026, 3, 15, "JFK", "FRA"),
            entry(2, 2026, 3, 11, "LHR", "JFK"),
            entry(1, 2026, 3, 10, "FRA", "LHR", flightType = "On Duty", layover = true)
        )
        val result = CountryTimeStats.evaluate(entries, countryOf, "DE", emptyList(), LocalDate.of(2026, 3, 20))
        assertEquals(1L, result.single { it.iso2 == "GB" }.totalDays)
        assertEquals(4L, result.single { it.iso2 == "US" }.totalDays)
    }
}
