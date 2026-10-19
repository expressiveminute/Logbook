package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testet die Umformung der Eingabe im Hangar-Suchfeld. Besonders wichtig
 * sind die leeren Eingaben (Löschen): Sie dürfen nicht abstürzen, und das
 * Feld muss sich bis auf leer zurück löschen lassen.
 */
class HangarSearchFilterTest {

    /**
     * Wendet den Filter so an, wie das EditText es tut: Das Ergebnis des
     * Filters ersetzt die Eingabe, null bedeutet "Eingabe unverändert".
     */
    private fun type(
        dest: String,
        input: String,
        dstart: Int = dest.length,
        dend: Int = dest.length
    ): String {
        val replacement = HangarSearchFilter.transform(input, dest, dstart, dend)
        return dest.substring(0, dstart) + (replacement ?: input) + dest.substring(dend)
    }

    // --- Löschen: eine leere Eingabe darf nie abstürzen (Regression) ---

    @Test
    fun löschen_des_letzten_Zeichens_ist_erlaubt() {
        assertNull(HangarSearchFilter.transform("", "D", 0, 1))
        assertEquals("", type("D", "", 0, 1))
    }

    @Test
    fun löschen_des_Strichs_ist_erlaubt() {
        assertNull(HangarSearchFilter.transform("", "D-", 1, 2))
        assertEquals("D", type("D-", "", 1, 2))
    }

    @Test
    fun alles_markieren_und_löschen_ist_erlaubt() {
        assertNull(HangarSearchFilter.transform("", "A320", 0, 4))
        assertEquals("", type("A320", "", 0, 4))
    }

    @Test
    fun leere_Eingabe_in_leerem_Feld_ist_erlaubt() {
        // Manche Tastaturen senden beim Fokus ein leeres Ereignis.
        assertNull(HangarSearchFilter.transform("", "", 0, 0))
        assertEquals("", type("", ""))
    }

    // --- Registrierungsmodus: beginnt die Suche mit "D" ---

    @Test
    fun erstes_d_wird_zu_D_mit_Strich() {
        assertEquals("D-", HangarSearchFilter.transform("d", "", 0, 0))
        assertEquals("D-", HangarSearchFilter.transform("D", "", 0, 0))
    }

    @Test
    fun registrierung_wird_komplett_gross_geschrieben() {
        assertEquals("D-A", type("D-", "a"))
        assertEquals("D-AB", type("D-A", "b"))
        assertEquals("D-ABC", type("D-AB", "c"))
        assertEquals("D-ABCD", type("D-ABC", "d"))
        assertEquals("D-AB", type("D-", "ab"))
    }

    @Test
    fun gelöschter_Strich_wird_nicht_erneut_eingesetzt() {
        // Strich löschen, danach tippen: weiter gross, aber ohne neuen Strich.
        val ohneStrich = type("D-", "", 1, 2)
        assertEquals("D", ohneStrich)
        assertEquals("DX", type(ohneStrich, "x"))
    }

    // --- Typmodus: beginnt die Suche mit einem anderen Buchstaben ---

    @Test
    fun erster_anderer_Buchstabe_wird_nur_selbst_gross() {
        assertEquals("A", HangarSearchFilter.transform("a", "", 0, 0))
    }

    @Test
    fun typname_darf_kleinbuchstaben_behalten() {
        assertEquals("A3", type("A", "3"))
        assertEquals("A32", type("A3", "2"))
        assertEquals("A320", type("A32", "0"))
        assertEquals("A320n", type("A320", "n"))
        assertEquals("A320ne", type("A320n", "e"))
        assertEquals("A320neo", type("A320ne", "o"))
    }

    @Test
    fun tippen_mitten_im_Typ_bleibt_unveraendert() {
        assertEquals("A3x20", type("A320", "x", 2, 2))
    }

    // --- Alles markieren ersetzt den Modus ---

    @Test
    fun alles_markieren_und_d_tippen_startet_Registrierung() {
        assertEquals("D-", HangarSearchFilter.transform("d", "A320", 0, 4))
    }

    @Test
    fun alles_markieren_und_a_tippen_startet_Typ() {
        assertEquals("A", HangarSearchFilter.transform("a", "D-AB", 0, 4))
    }

    // --- Einfügen ---

    @Test
    fun paste_in_leeres_Feld_nimmt_ersten_Buchstaben_gross() {
        assertEquals("A320neo", HangarSearchFilter.transform("a320neo", "", 0, 0))
        assertEquals("D-abcd", HangarSearchFilter.transform("d-abcd", "", 0, 0))
    }

    @Test
    fun paste_ersetzt_alles_markierten_Text() {
        assertEquals("A320", type("D-AB", "A320", 0, 4))
    }
}
