package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SeatStatsTest {

    private fun entry(
        seat: String?,
        classType: String? = null,
        aircraftType: String? = null
    ) = LogbookEntry(
        date = LocalDate.of(2025, 1, 1),
        fromAirport = "FRA",
        toAirport = "JFK",
        seat = seat,
        classType = classType,
        aircraftType = aircraftType
    )

    // --- parse (ohne Layout = DEFAULT) ---

    @Test
    fun parse_erkenntReiheUndBuchstabe() {
        val seat = SeatStats.parse("1A")
        assertNotNull(seat)
        assertEquals(1, seat!!.row)
        assertEquals('A', seat.letter)
    }

    @Test
    fun parse_erkenntZweistelligeReihe() {
        val seat = SeatStats.parse("24D")
        assertNotNull(seat)
        assertEquals(24, seat!!.row)
        assertEquals('D', seat.letter)
    }

    @Test
    fun parse_istKaseInsensitivUndTrimmt() {
        val seat = SeatStats.parse(" 12f ")
        assertNotNull(seat)
        assertEquals(12, seat!!.row)
        assertEquals('F', seat.letter)
    }

    @Test
    fun parse_gibtNullBeiNull() {
        assertNull(SeatStats.parse(null))
    }

    @Test
    fun parse_gibtNullBeiLeer() {
        assertNull(SeatStats.parse(""))
        assertNull(SeatStats.parse("   "))
    }

    @Test
    fun parse_gibtNullBeiNurBuchstabe() {
        assertNull(SeatStats.parse("A"))
    }

    @Test
    fun parse_gibtNullBeiNurZahl() {
        assertNull(SeatStats.parse("24"))
    }

    @Test
    fun parse_gibtNullBeiUngueltigerReihe() {
        assertNull(SeatStats.parse("0A"))
        assertNull(SeatStats.parse("-1A"))
        assertNull(SeatStats.parse("101A"))
        assertNull(SeatStats.parse("AB"))
    }

    @Test
    fun parse_gibtNullBeiZuKurz() {
        assertNull(SeatStats.parse("1"))
    }

    // --- Fenster / Gang (DEFAULT-Layout: 3+3, F rechts) ---

    @Test
    fun parse_AIstFensterLinks() {
        val seat = SeatStats.parse("1A")!!
        assertTrue(seat.isWindow)
        assertFalse(seat.isAisle)
    }

    @Test
    fun parse_FIstFensterRechtsImDefaultLayout() {
        // DEFAULT-Layout hat F als rechten Fensterplatz
        val seat = SeatStats.parse("10F")!!
        assertTrue(seat.isWindow)
        assertFalse(seat.isAisle)
    }

    @Test
    fun parse_CUndDSindGang() {
        assertTrue(SeatStats.parse("5C")!!.isAisle)
        assertTrue(SeatStats.parse("5D")!!.isAisle)
        assertFalse(SeatStats.parse("5C")!!.isWindow)
    }

    @Test
    fun parse_mittlererPlatzIstWederFensterNochGang() {
        val seat = SeatStats.parse("5E")!!
        assertFalse(seat.isWindow)
        assertFalse(seat.isAisle)
    }

    // --- Front / Seite (DEFAULT: 40 Reihen) ---

    @Test
    fun parse_ersteReiheIstGanzVorne() {
        val seat = SeatStats.parse("1A")!!
        assertEquals(0f, seat.frontFraction, 0.001f)
    }

    @Test
    fun parse_letzteReiheIstGanzHinten() {
        val seat = SeatStats.parse("40A")!!
        assertEquals(1f, seat.frontFraction, 0.001f)
    }

    @Test
    fun parse_AIstGanzLinks() {
        val seat = SeatStats.parse("1A")!!
        assertEquals(0f, seat.sideFraction, 0.001f)
    }

    @Test
    fun parse_FIstGanzRechtsImDefaultLayout() {
        val seat = SeatStats.parse("1F")!!
        assertEquals(1f, seat.sideFraction, 0.001f)
    }

    // --- parse mit A320-Layout (32 Reihen, F rechts) ---

    @Test
    fun parse_mitA320Layout_letzteReihe() {
        val layout = AircraftLayout.resolve("A320")
        val seat = SeatStats.parse("32A", layout)!!
        assertEquals(1f, seat.frontFraction, 0.001f)
    }

    @Test
    fun parse_mitA320Layout_FIstFenster() {
        val layout = AircraftLayout.resolve("A320")
        val seat = SeatStats.parse("10F", layout)!!
        assertTrue(seat.isWindow)
    }

    // --- parse mit A330-Layout (46 Reihen, K rechts, 3+4+3) ---

    @Test
    fun parse_mitA330Layout_KIstFensterRechts() {
        val layout = AircraftLayout.resolve("A330")
        val seat = SeatStats.parse("10K", layout)!!
        assertTrue(seat.isWindow)
        assertEquals(1f, seat.sideFraction, 0.001f)
    }

    @Test
    fun parse_mitA330Layout_letzteReihe() {
        val layout = AircraftLayout.resolve("A330")
        val seat = SeatStats.parse("46A", layout)!!
        assertEquals(1f, seat.frontFraction, 0.001f)
    }

    @Test
    fun parse_mitA330Layout_GUndHSindGang() {
        val layout = AircraftLayout.resolve("A330")
        // 3+4+3: Gänge nach C (Index 2) und G (Index 6)
        // C und D sind Gang, G und H sind Gang
        assertTrue(SeatStats.parse("10C", layout)!!.isAisle)
        assertTrue(SeatStats.parse("10D", layout)!!.isAisle)
        assertTrue(SeatStats.parse("10G", layout)!!.isAisle)
        assertTrue(SeatStats.parse("10H", layout)!!.isAisle)
    }

    @Test
    fun parse_mitA330Layout_EUndFMitteSindKeinGang() {
        val layout = AircraftLayout.resolve("A330")
        assertFalse(SeatStats.parse("10E", layout)!!.isAisle)
        assertFalse(SeatStats.parse("10F", layout)!!.isAisle)
    }

    @Test
    fun parse_mitA330Layout_FIstKeinFenster() {
        val layout = AircraftLayout.resolve("A330")
        // Bei 3+4+3 ist K der rechte Fensterplatz, nicht F
        assertFalse(SeatStats.parse("10F", layout)!!.isWindow)
    }

    // --- summarize ---

    @Test
    fun summarize_leerBeiKeinenSitzen() {
        val summary = SeatStats.summarize(emptyList())
        assertEquals(0, summary.total)
    }

    @Test
    fun summarize_ignoriertEintraegeOhneSitz() {
        val summary = SeatStats.summarize(listOf(entry(null), entry("")))
        assertEquals(0, summary.total)
    }

    @Test
    fun summarize_zaehltGueltigeSitze() {
        val summary = SeatStats.summarize(listOf(entry("1A"), entry("24D"), entry("12F")))
        assertEquals(3, summary.total)
    }

    @Test
    fun summarize_berechnetDurchschnittlicheFrontPosition() {
        val summary = SeatStats.summarize(listOf(entry("1A"), entry("40A")))
        // Reihe 1 = 0.0, Reihe 40 = 1.0 → Durchschnitt 0.5
        assertEquals(0.5f, summary.avgFrontFraction, 0.01f)
    }

    @Test
    fun summarize_berechnetFensterAnteil() {
        val summary = SeatStats.summarize(listOf(entry("1A"), entry("1E"), entry("1F")))
        // A und F sind Fenster (DEFAULT-Layout), E ist Mitte → 2/3
        assertEquals(2f / 3f, summary.windowFraction, 0.01f)
    }

    @Test
    fun summarize_berechnetGangAnteil() {
        val summary = SeatStats.summarize(listOf(entry("1C"), entry("1E")))
        // C ist Gang, E ist Mitte → 1/2
        assertEquals(0.5f, summary.aisleFraction, 0.01f)
    }

    @Test
    fun summarize_findetHaeufigsteReiheUndBuchstabe() {
        val summary = SeatStats.summarize(
            listOf(entry("12A"), entry("12B"), entry("24A"))
        )
        assertEquals(12, summary.mostCommonRow)
        assertEquals('A', summary.mostCommonLetter)
    }

    @Test
    fun summarize_seatHeatZaehltHaeufigkeiten() {
        val summary = SeatStats.summarize(
            listOf(entry("12A"), entry("12A"), entry("24D"))
        )
        assertEquals(2, summary.seatHeat["12A"]?.count)
        assertEquals(1, summary.seatHeat["24D"]?.count)
    }

    @Test
    fun summarize_seatHeatNormalisiertSchreibweise() {
        val summary = SeatStats.summarize(
            listOf(entry("12a"), entry(" 12A "))
        )
        assertEquals(2, summary.seatHeat["12A"]?.count)
    }

    @Test
    fun summarize_seatHeatSpeichertKlasse() {
        val summary = SeatStats.summarize(
            listOf(entry("12A", classType = "Economy"))
        )
        assertEquals("Economy", summary.seatHeat["12A"]?.className)
    }

    @Test
    fun summarize_seatHeatNormalisiertKlasse() {
        val summary = SeatStats.summarize(
            listOf(entry("12A", classType = "economy"))
        )
        // ChartData.normalizeClassType macht "economy" → "Economy"
        assertEquals("Economy", summary.seatHeat["12A"]?.className)
    }

    @Test
    fun summarize_seatHeatOhneKlasseIstNull() {
        val summary = SeatStats.summarize(listOf(entry("12A")))
        assertNull(summary.seatHeat["12A"]?.className)
    }

    @Test
    fun summarize_mitLayoutVerwendetReihenDesLayouts() {
        val layout = AircraftLayout.resolve("A320") // 32 Reihen
        val summary = SeatStats.summarize(listOf(entry("1A"), entry("32F")), layout)
        // Reihe 1 = 0.0, Reihe 32 = 1.0 → Durchschnitt 0.5
        assertEquals(0.5f, summary.avgFrontFraction, 0.01f)
    }

    @Test
    fun summarize_mitA330LayoutFensterAnteil() {
        val layout = AircraftLayout.resolve("A330")
        val summary = SeatStats.summarize(
            listOf(entry("1A", aircraftType = "A330"), entry("1K", aircraftType = "A330")),
            layout
        )
        // Beide Fenster → 100%
        assertEquals(1f, summary.windowFraction, 0.01f)
    }
}
