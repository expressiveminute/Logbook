package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test

class FlightCombinedTest {

    @Test
    fun trenntAmLeerzeichen() {
        assertEquals(Pair("LH", "400"), FlightCombined.split("LH 400"))
        assertEquals(Pair("DLH", "220"), FlightCombined.split("DLH 220"))
    }

    @Test
    fun bringtDieFluggesellschaftInGrossbuchstaben() {
        assertEquals(Pair("LH", "400"), FlightCombined.split("lh 400"))
        assertEquals(Pair("Lufthansa".uppercase(), "1172"), FlightCombined.split("Lufthansa 1172"))
    }

    /**
     * Ohne Leerzeichen zaehlt die Buchstabenfolge am Anfang als
     * Fluggesellschaft - das ist der Zustand waehrend der Eingabe.
     */
    @Test
    fun ohneLeerzeichenGefaelltDieBuchstabenfolge() {
        assertEquals(Pair("LH", "4"), FlightCombined.split("LH4"))
        assertEquals(Pair("DLH", "220"), FlightCombined.split("DLH220"))
        assertEquals(Pair("LH", ""), FlightCombined.split("LH"))
    }

    /** Bestandsdaten: Eine laengere Fluggesellschaft wird nicht zerschnitten. */
    @Test
    fun bestandsdatenBleibenUnveraendert() {
        val faelle = listOf(
            arrayOf("LH", "400"),
            arrayOf("DLH", "400"),
            arrayOf("lh", "9999"),
            arrayOf("Lufthansa", "1172"),
            arrayOf("Eurowings", "400"),
            arrayOf("", "512"),
            arrayOf("RYR", "1234")
        )
        for (fall in faelle) {
            val airline = fall[0]
            val flightNumber = fall[1]
            assertEquals(
                "Fluggesellschaft/Flugnummer $airline/$flightNumber",
                Pair(airline.uppercase(), flightNumber),
                FlightCombined.split(FlightCombined.format(airline, flightNumber))
            )
        }
    }

    /** Was fehlt, bleibt weg - kein "LH " und kein " 400". */
    @Test
    fun formatLasstFehlendesWeg() {
        assertEquals("LH 400", FlightCombined.format("LH", "400"))
        assertEquals("LH", FlightCombined.format("LH", null))
        assertEquals("LH", FlightCombined.format("LH", "  "))
        assertEquals("400", FlightCombined.format(null, "400"))
        assertEquals("", FlightCombined.format(null, null))
    }

    /** Was der Nutzer tippt: nach der ersten Ziffer das Leerzeichen von selbst. */
    @Test
    fun LeerzeichenKommtVonSelbst() {
        assertEquals("LH 400", FlightCombined.normalizeTyped("LH400"))
        assertEquals("LH 4", FlightCombined.normalizeTyped("LH4"))
        assertEquals("DLH 220", FlightCombined.normalizeTyped("DLH220"))
        // Die Grossbuchstaben setzt die Tastatur, nicht der Trenner.
        assertEquals("lh 400", FlightCombined.normalizeTyped("lh400"))
    }

    @Test
    fun normalizeTypedLaesstFingerLassen() {
        assertEquals("LH", FlightCombined.normalizeTyped("LH"))
        assertEquals("400", FlightCombined.normalizeTyped("400"))
        assertEquals("LH 400", FlightCombined.normalizeTyped("LH 400"))
        assertEquals("Lufthansa1172", FlightCombined.normalizeTyped("Lufthansa1172"))
        assertEquals("LH 400", FlightCombined.normalizeTyped(" LH 400"))
        assertEquals("", FlightCombined.normalizeTyped("   "))
    }

    @Test
    fun leeresKaestchen() {
        assertEquals(Pair("", ""), FlightCombined.split(""))
        assertEquals(Pair("", ""), FlightCombined.split("   "))
    }

    /**
     * Die ersten beiden Zeichen werden gross und der Cursor springt dahinter,
     * damit die Flugnummer direkt daneben getippt wird.
     */
    @Test
    fun ersteBeideZeichenGrossUndCursorSpringtNachRechts() {
        assertEquals(Pair("L", 1), FlightCombined.capitalizeTyped("l"))
        assertEquals(Pair("LH", 2), FlightCombined.capitalizeTyped("lh"))
        assertEquals(Pair("LH 4", 4), FlightCombined.capitalizeTyped("lh 4"))
        assertEquals(Pair("LH 400", 6), FlightCombined.capitalizeTyped("lh 400"))
    }

    /** Laengere Gesellschaften und schon fertige Eingaben: Cursor bleibt am Ende. */
    @Test
    fun cursorBleibtAmEndeWennDieNummerSchonTippt() {
        assertEquals(Pair("LHA", 3), FlightCombined.capitalizeTyped("lha"))
        assertEquals(Pair("LHA 400", 7), FlightCombined.capitalizeTyped("lha 400"))
        // Eine laengere Gesellschaft wird genau so gespeichert, wie sie dasteht:
        // FlightCombined.split() schreibt sie ebenfalls gross.
        assertEquals(Pair("LUFTHANSA 1172", 14), FlightCombined.capitalizeTyped("lufthansa 1172"))
    }

    @Test
    fun grossSchreibenAendertNichtsAmTextWennNichtsZuAendernIst() {
        assertEquals(Pair("LH 400", 6), FlightCombined.capitalizeTyped("LH 400"))
        assertEquals(Pair("400", 3), FlightCombined.capitalizeTyped("400"))
        assertEquals(Pair("", 0), FlightCombined.capitalizeTyped(""))
    }

    /** Die Nummer des Rückflugs ist immer die des Hinflugs plus eins. */
    @Test
    fun rueckflugsnummerIstHinflugsnummerPlusEins() {
        assertEquals("401", FlightCombined.nextNumber("400"))
        assertEquals("402", FlightCombined.nextNumber("401"))
        assertEquals("1000", FlightCombined.nextNumber("999"))
        assertEquals("LH401", FlightCombined.nextNumber("LH400"))
    }

    @Test
    fun ohneZiffernBleibtDieNummer() {
        assertEquals("", FlightCombined.nextNumber(null))
        assertEquals("", FlightCombined.nextNumber("  "))
        assertEquals("LH", FlightCombined.nextNumber("LH"))
    }
}