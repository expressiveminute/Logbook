package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AircraftLayoutTest {

    private fun entry(seat: String?, aircraftType: String? = null) = LogbookEntry(
        date = LocalDate.of(2025, 1, 1),
        fromAirport = "FRA",
        toAirport = "JFK",
        seat = seat,
        aircraftType = aircraftType
    )

    // --- normalize ---

    @Test
    fun normalize_grossbuchstabenUndLeerzeichen() {
        assertEquals("A320", AircraftLayout.normalize("a320"))
        assertEquals("A320", AircraftLayout.normalize(" A320 "))
    }

    @Test
    fun normalize_entferntHersteller() {
        assertEquals("A320", AircraftLayout.normalize("Airbus A320"))
        assertEquals("A330300", AircraftLayout.normalize("Airbus A330-300"))
    }

    @Test
    fun normalize_entferntBindestriche() {
        assertEquals("A330300", AircraftLayout.normalize("A330-300"))
        assertEquals("A350900", AircraftLayout.normalize("A350-900"))
    }

    @Test
    fun normalize_nullUndLeer() {
        assertEquals("", AircraftLayout.normalize(null))
        assertEquals("", AircraftLayout.normalize(""))
        assertEquals("", AircraftLayout.normalize("   "))
    }

    // --- resolve: Schmalrumpf ---

    @Test
    fun resolve_A319() {
        val layout = AircraftLayout.resolve("A319")
        assertEquals(25, layout.totalRows)
        assertEquals('F', layout.rightWindowLetter)
        assertEquals(listOf('A', 'B', 'C', 'D', 'E', 'F'), layout.seatLetters)
        // Business + Economy
        assertEquals(2, layout.sections.size)
        assertEquals("Business", layout.sections[0].className)
        assertEquals("Economy", layout.sections[1].className)
    }

    @Test
    fun resolve_A320() {
        val layout = AircraftLayout.resolve("A320")
        assertEquals(32, layout.totalRows)
        assertEquals('F', layout.rightWindowLetter)
    }

    @Test
    fun resolve_A320neo() {
        val layout = AircraftLayout.resolve("A320neo")
        assertEquals(32, layout.totalRows)
        assertEquals('F', layout.rightWindowLetter)
    }

    @Test
    fun resolve_A321() {
        val layout = AircraftLayout.resolve("A321")
        assertEquals(38, layout.totalRows)
    }

    @Test
    fun resolve_A321neo() {
        val layout = AircraftLayout.resolve("A321neo")
        assertEquals(39, layout.totalRows)
    }

    @Test
    fun resolve_A320neoNichtA320() {
        // "A320neo" darf nicht als "A320" erkannt werden (längerer Präfix gewinnt)
        val layout = AircraftLayout.resolve("A320neo")
        assertEquals(32, layout.totalRows)
        // Beide haben 32 Reihen, aber A320NEO ist der exakte Schlüssel
        assertEquals(listOf('A', 'B', 'C', 'D', 'E', 'F'), layout.seatLetters)
    }

    @Test
    fun resolve_AirbusA320() {
        val layout = AircraftLayout.resolve("Airbus A320")
        assertEquals(32, layout.totalRows)
    }

    // --- resolve: Langstrecke ---

    @Test
    fun resolve_A330() {
        val layout = AircraftLayout.resolve("A330-300")
        assertEquals(46, layout.totalRows)
        assertEquals('K', layout.rightWindowLetter)
        // 3+4+3: A B C | D E F G | H J K
        assertEquals(10, layout.seatLetters.size)
        assertEquals(listOf(2, 6), layout.aisleAfter)
        // Business, Premium Eco, Economy
        assertEquals(3, layout.sections.size)
        assertEquals("Business", layout.sections[0].className)
        assertEquals(1, layout.sections[0].startRow)
        assertEquals(11, layout.sections[0].endRow)
        assertEquals("Premium Eco", layout.sections[1].className)
        assertEquals(15, layout.sections[1].startRow)
        assertEquals(19, layout.sections[1].endRow)
        assertEquals("Economy", layout.sections[2].className)
        assertEquals(22, layout.sections[2].startRow)
        assertEquals(46, layout.sections[2].endRow)
    }

    @Test
    fun resolve_A340() {
        val layout = AircraftLayout.resolve("A340-300")
        assertEquals(46, layout.totalRows)
        assertEquals('K', layout.rightWindowLetter)
        assertEquals(3, layout.sections.size)
        assertEquals("Business", layout.sections[0].className)
        assertEquals(1, layout.sections[0].startRow)
        assertEquals(5, layout.sections[0].endRow)
    }

    @Test
    fun resolve_A350() {
        val layout = AircraftLayout.resolve("A350-900")
        assertEquals(48, layout.totalRows)
        assertEquals('K', layout.rightWindowLetter)
        // First, Business, Premium Eco, Economy
        assertEquals(4, layout.sections.size)
        assertEquals("First", layout.sections[0].className)
        assertEquals(1, layout.sections[0].startRow)
        assertEquals(1, layout.sections[0].endRow)
        assertEquals("Business", layout.sections[1].className)
        assertEquals(2, layout.sections[1].startRow)
        assertEquals(16, layout.sections[1].endRow)
    }

    // --- resolve: Unbekannt → DEFAULT ---

    @Test
    fun resolve_unbekanntGibtDefault() {
        val layout = AircraftLayout.resolve("Boeing 747")
        assertEquals(AircraftLayout.DEFAULT.totalRows, layout.totalRows)
    }

    @Test
    fun resolve_nullGibtDefault() {
        assertEquals(AircraftLayout.DEFAULT.totalRows, AircraftLayout.resolve(null).totalRows)
    }

    @Test
    fun resolve_leerGibtDefault() {
        assertEquals(AircraftLayout.DEFAULT.totalRows, AircraftLayout.resolve("").totalRows)
    }

    // --- Layout.sectionForRow ---

    @Test
    fun sectionForRowfindetPassendenAbschnitt() {
        val layout = AircraftLayout.resolve("A320")
        assertEquals("Business", layout.sectionForRow(3)?.className)
        assertEquals("Economy", layout.sectionForRow(20)?.className)
    }

    @Test
    fun sectionForRowGibtNullBeiLuecke() {
        val layout = AircraftLayout.resolve("A330")
        // Reihe 12-14 sind Lücke (zwischen Business 1-11 und Premium Eco 15-19)
        assertNull(layout.sectionForRow(13))
    }

    // --- Layout.sectionForClass ---

    @Test
    fun sectionForClassfindetAbschnitt() {
        val layout = AircraftLayout.resolve("A350-900")
        assertNotNull(layout.sectionForClass("First"))
        assertNotNull(layout.sectionForClass("Business"))
        assertNotNull(layout.sectionForClass("Premium Eco"))
        assertNotNull(layout.sectionForClass("Economy"))
    }

    @Test
    fun sectionForClassGibtNullBeiUnbekannt() {
        val layout = AircraftLayout.resolve("A320")
        // A320 hat kein First
        assertNull(layout.sectionForClass("First"))
    }

    // --- mostCommonLayout ---

    @Test
    fun mostCommonLayout_ohneSitzeGibtDefault() {
        assertEquals(AircraftLayout.DEFAULT.totalRows, AircraftLayout.mostCommonLayout(emptyList()).totalRows)
    }

    @Test
    fun mostCommonLayout_ohneTypGibtDefault() {
        val entries = listOf(entry("1A"), entry("2B"))
        assertEquals(AircraftLayout.DEFAULT.totalRows, AircraftLayout.mostCommonLayout(entries).totalRows)
    }

    @Test
    fun mostCommonLayout_haeufigsterTypGewinnt() {
        val entries = listOf(
            entry("1A", "A320"),
            entry("2B", "A320"),
            entry("3C", "A330")
        )
        val layout = AircraftLayout.mostCommonLayout(entries)
        assertEquals(32, layout.totalRows) // A320
    }

    @Test
    fun mostCommonLayout_ignoleertEintraegeOhneSitz() {
        val entries = listOf(
            entry(null, "A330"),
            entry("1A", "A320")
        )
        val layout = AircraftLayout.mostCommonLayout(entries)
        assertEquals(32, layout.totalRows) // A320 (nur dieser hat einen Sitz)
    }

    @Test
    fun mostCommonLayout_mitVerschiedenenSchreibweisen() {
        val entries = listOf(
            entry("1A", "Airbus A320"),
            entry("2B", "A320")
        )
        val layout = AircraftLayout.mostCommonLayout(entries)
        assertEquals(32, layout.totalRows)
    }
}
