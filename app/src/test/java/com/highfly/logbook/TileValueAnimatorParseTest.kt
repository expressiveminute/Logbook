package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Zerlegen der hochzählenden Wertetexte. Das Trennzeichen hängt an der
 * App-Sprache: Deutsch schreibt "0,951", Englisch "0.951". Beides muss als
 * Zahl 0,951 erkannt werden, nicht als 951.
 */
class TileValueAnimatorParseTest {

    private fun parse(text: String, locale: Locale) =
        TileValueAnimator.parseAnimated(text, locale)

    @Test
    fun deutschesKommaIstDasDezimaltrennzeichen() {
        val parts = parse("0,951 ×", Locale.GERMANY)
        assertEquals(0.951, parts!!.number, 1e-9)
        assertEquals(3, parts.decimals)
        assertEquals("×", parts.suffix)
    }

    @Test
    fun englischerPunktIstDasDezimaltrennzeichen() {
        val parts = parse("0.951 ×", Locale.ENGLISH)
        assertEquals(0.951, parts!!.number, 1e-9)
        assertEquals(3, parts.decimals)
    }

    @Test
    fun tausenderzeichenWirdEntfernt() {
        assertEquals(1234.0, parse("1.234", Locale.GERMANY)!!.number, 1e-9)
        assertEquals(1234567.0, parse("1,234,567", Locale.ENGLISH)!!.number, 1e-9)
        assertEquals(
            1234.567,
            parse("1.234,567", Locale.GERMANY)!!.number,
            1e-9
        )
        assertEquals(
            1234.567,
            parse("1,234.567", Locale.ENGLISH)!!.number,
            1e-9
        )
    }

    @Test
    fun ganzeZahlOhneAnhang() {
        val parts = parse("10 (5%)", Locale.GERMANY)
        assertEquals(10.0, parts!!.number, 1e-9)
        assertEquals(0, parts.decimals)
        assertEquals("(5%)", parts.suffix)
    }

    @Test
    fun textOhneZahlWirdNichtAnimiert() {
        assertNull(parse("–", Locale.GERMANY))
        assertNull(parse("", Locale.GERMANY))
    }
}
