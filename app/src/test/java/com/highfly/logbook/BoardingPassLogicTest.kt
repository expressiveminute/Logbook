package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft die reine Logik der Bordkarte ([BoardingPassLogic]): Hintergrundwahl
 * (Primär, bei LH Akzent), Klassenbuchstabe, Funktions-Kurzformen, Farben der
 * Reiseart-Kachel und die Textfarbe auf der Karte.
 */
class BoardingPassLogicTest {

    @Test
    fun hintergrund_istPrimaerAusserBeiLufthansa() {
        assertEquals("primary", BoardingPassLogic.backgroundKind("airline_lx"))
        assertEquals("primary", BoardingPassLogic.backgroundKind("airline_tp"))
        assertEquals("accent", BoardingPassLogic.backgroundKind("airline_lh"))
        assertEquals("primary", BoardingPassLogic.backgroundKind(null))
        assertEquals("primary", BoardingPassLogic.backgroundKind("brown"))
    }

    @Test
    fun klassenbuchstabe_yecfj() {
        assertEquals("Y", BoardingPassLogic.classLetter("Economy"))
        assertEquals("Y", BoardingPassLogic.classLetter("economy"))
        assertEquals("E", BoardingPassLogic.classLetter("Premium Eco"))
        assertEquals("E", BoardingPassLogic.classLetter("premium economy"))
        assertEquals("C", BoardingPassLogic.classLetter("Business"))
        assertEquals("F", BoardingPassLogic.classLetter("First"))
        assertEquals("J", BoardingPassLogic.classLetter("Jump"))
        assertNull(BoardingPassLogic.classLetter(null))
        assertNull(BoardingPassLogic.classLetter(""))
    }

    @Test
    fun klassenfarbe_indexStimmtZuClassColorSchemes() {
        // Reihenfolge in ClassColorSchemes: Economy, Premium Eco, Business, First, Jump.
        assertEquals(0, BoardingPassLogic.classColorIndex("Economy"))
        assertEquals(1, BoardingPassLogic.classColorIndex("Premium Eco"))
        assertEquals(2, BoardingPassLogic.classColorIndex("Business"))
        assertEquals(3, BoardingPassLogic.classColorIndex("First"))
        assertEquals(4, BoardingPassLogic.classColorIndex("Jump"))
        assertNull(BoardingPassLogic.classColorIndex(null))
    }

    @Test
    fun funktionsKurzform_p1P2Fb() {
        assertEquals("P1", BoardingPassLogic.functionShortForm("Purser I"))
        assertEquals("P1", BoardingPassLogic.functionShortForm("purser i"))
        assertEquals("P2", BoardingPassLogic.functionShortForm("Purser II"))
        assertEquals("FB", BoardingPassLogic.functionShortForm("Flugbegleiter"))
        assertEquals("FB", BoardingPassLogic.functionShortForm("flight attendant"))
        // Sonstiger Freitext: Anfangsbuchstaben, max. zwei Zeichen.
        assertEquals("SL", BoardingPassLogic.functionShortForm("Safety Lead"))
        assertNull(BoardingPassLogic.functionShortForm(null))
        assertNull(BoardingPassLogic.functionShortForm("  "))
    }

    @Test
    fun funktionsKachel_nurBeiDienstlichenReisearten() {
        assertTrue(BoardingPassLogic.showsFunctionChip("On Duty"))
        assertTrue(BoardingPassLogic.showsFunctionChip("Deadhead"))
        assertTrue(BoardingPassLogic.showsFunctionChip("Dienstreise"))
        assertTrue(BoardingPassLogic.showsFunctionChip("Ferry"))
        assertFalse(BoardingPassLogic.showsFunctionChip("Privat"))
        assertFalse(BoardingPassLogic.showsFunctionChip(null))
        assertTrue(BoardingPassLogic.isPrivate("privat"))
        assertFalse(BoardingPassLogic.isPrivate("On Duty"))
    }

    @Test
    fun reiseartKachel_holtFarbeZurReiseart() {
        assertEquals(R.color.type_private_bg, BoardingPassLogic.typeColorRes("Privat"))
        assertEquals(R.color.type_on_duty_bg, BoardingPassLogic.typeColorRes("on duty"))
        assertEquals(R.color.type_deadhead_bg, BoardingPassLogic.typeColorRes("Deadhead"))
        assertEquals(R.color.type_ferry_bg, BoardingPassLogic.typeColorRes("Ferry"))
        assertEquals(R.color.type_ground_transfer_bg, BoardingPassLogic.typeColorRes("Ground Transfer"))
        assertEquals(R.color.type_duty_travel_bg, BoardingPassLogic.typeColorRes("Dienstreise"))
        assertNull(BoardingPassLogic.typeColorRes(null))
    }

    @Test
    fun zusatzkachel_funktionOderSitzplatz() {
        // Dienstlich: Funktions-Kurzform.
        assertEquals("P1", BoardingPassLogic.extraChipText("On Duty", "Purser I", "12A"))
        assertEquals("FB", BoardingPassLogic.extraChipText("Ferry", "Flugbegleiter", null))
        assertNull(BoardingPassLogic.extraChipText("On Duty", null, "12A"))
        // Privat: Sitzplatz statt Funktion.
        assertEquals("12A", BoardingPassLogic.extraChipText("Privat", "Purser I", "12A"))
        assertEquals("1A", BoardingPassLogic.extraChipText("privat", null, " 1A "))
        assertNull(BoardingPassLogic.extraChipText("Privat", null, null))
        assertNull(BoardingPassLogic.extraChipText("Privat", null, "  "))
        // Sonstige/unbekannte Reiseart: keine Zusatzkachel.
        assertNull(BoardingPassLogic.extraChipText("Ground Transfer", "Purser I", "12A"))
        assertNull(BoardingPassLogic.extraChipText(null, "Purser I", "12A"))
    }

    @Test
    fun textfarbe_kontrastiertZumHintergrund() {
        // Heller Hintergrund (LH-Akzent Gold) -> schwarze Schrift.
        assertEquals(0xFF000000.toInt(), BoardingPassLogic.onColor(0xFFFFAD00.toInt()))
        // Dunkler Hintergrund (LH-Primär Navy) -> weiße Schrift.
        assertEquals(0xFFFFFFFF.toInt(), BoardingPassLogic.onColor(0xFF05164D.toInt()))
        assertEquals(0xFF000000.toInt(), BoardingPassLogic.onColor(0xFFFFFFFF.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), BoardingPassLogic.onColor(0xFF000000.toInt()))
        // Eurowings-Primär (dunkles Violett) -> weiße Schrift auf der Bordkarte.
        assertEquals(0xFFFFFFFF.toInt(), BoardingPassLogic.onColor(0xFF871C54.toInt()))
        // TAP-Primär (helles Grün) -> schwarze Schrift.
        assertEquals(0xFF000000.toInt(), BoardingPassLogic.onColor(0xFFBFD730.toInt()))
    }

    @Test
    fun textfarbe_beiLufthansaImmerWeiss() {
        // LH trägt die Akzentfarbe (Gold) als Hintergrund - die Schrift ist
        // dort bewusst weiß, auch wenn der Kontrast zu Schwarz sprechen würde.
        assertEquals(
            0xFFFFFFFF.toInt(),
            BoardingPassLogic.textColorFor(BoardingPassLogic.LH_KEY, 0xFFFFAD00.toInt())
        )
        // Alle anderen Schemata folgen weiter dem Kontrast.
        assertEquals(
            0xFF000000.toInt(),
            BoardingPassLogic.textColorFor("airline_ew", 0xFFFFAD00.toInt())
        )
        assertEquals(
            0xFFFFFFFF.toInt(),
            BoardingPassLogic.textColorFor(null, 0xFF05164D.toInt())
        )
    }

    @Test
    fun flugzeit_textImFormatXhYMin() {
        assertEquals("0 h 40 min", BoardingPassLogic.flightTimeText(40))
        assertEquals("1 h 25 min", BoardingPassLogic.flightTimeText(85))
        assertEquals("14 h 10 min", BoardingPassLogic.flightTimeText(850))
        assertEquals("0 h 0 min", BoardingPassLogic.flightTimeText(0))
    }
}
