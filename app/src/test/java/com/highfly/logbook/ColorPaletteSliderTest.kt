package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für die Rechenfunktionen des Farbpaletten-Sliders
 * ([ColorPaletteSliderView]): Paletten-Index aus der Knopf-Position (Farben
 * bleiben die der Palette), Spektrum-Farbe als HSV-Hue und Hue-Rückrechnung.
 */
class ColorPaletteSliderTest {

    @Test
    fun interpolatePalette_stuetzstellenSindDiePalettenfarben() {
        val palette = listOf(0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(0xFF000000.toInt(), ColorPaletteSliderView.interpolatePalette(0f, palette))
        assertEquals(0xFFFF0000.toInt(), ColorPaletteSliderView.interpolatePalette(0.5f, palette))
        assertEquals(0xFFFFFFFF.toInt(), ColorPaletteSliderView.interpolatePalette(1f, palette))
        // geclampt auesserhalb 0..1
        assertEquals(0xFF000000.toInt(), ColorPaletteSliderView.interpolatePalette(-1f, palette))
        assertEquals(0xFFFFFFFF.toInt(), ColorPaletteSliderView.interpolatePalette(2f, palette))
    }

    @Test
    fun interpolatePalette_mitteIstGemischteFarbe() {
        val palette = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        // exakt in der Mitte: 50 % Schwarz + 50 % Weiss = Grau
        assertEquals(0xFF808080.toInt(), ColorPaletteSliderView.interpolatePalette(0.5f, palette))
        // ein Viertel: 25 % Weiss
        assertEquals(0xFF404040.toInt(), ColorPaletteSliderView.interpolatePalette(0.25f, palette))
    }

    @Test
    fun interpolatePalette_sonderfaelle() {
        assertEquals(0xFF000000.toInt(), ColorPaletteSliderView.interpolatePalette(0.5f, emptyList()))
        val eine = listOf(0xFF123456.toInt())
        assertEquals(0xFF123456.toInt(), ColorPaletteSliderView.interpolatePalette(0.7f, eine))
    }

    @Test
    fun spectrumColor_endpunkteSindRot() {
        assertEquals(0xFFFF0000.toInt(), ColorPaletteSliderView.spectrumColor(0f))
        assertEquals(0xFFFF0000.toInt(), ColorPaletteSliderView.spectrumColor(1f))
    }

    @Test
    fun spectrumColor_mitteIstGruenUndBlau() {
        // 120° = grün, 240° = blau
        assertEquals(0xFF00FF00.toInt(), ColorPaletteSliderView.spectrumColor(1f / 3f))
        assertEquals(0xFF0000FF.toInt(), ColorPaletteSliderView.spectrumColor(2f / 3f))
    }

    @Test
    fun spectrumColor_alphaIstImmerFF() {
        for (i in 0..12) {
            val c = ColorPaletteSliderView.spectrumColor(i / 12f)
            assertEquals(0xFF, (c ushr 24) and 0xFF)
        }
    }

    @Test
    fun hueOf_bekannteFarben() {
        assertEquals(0f, ColorPaletteSliderView.hueOf(0xFFFF0000.toInt()), 0.001f)
        assertEquals(120f, ColorPaletteSliderView.hueOf(0xFF00FF00.toInt()), 0.001f)
        assertEquals(240f, ColorPaletteSliderView.hueOf(0xFF0000FF.toInt()), 0.001f)
        assertEquals(60f, ColorPaletteSliderView.hueOf(0xFFFFFF00.toInt()), 0.001f)
    }

    @Test
    fun spectrumColor_undHueOf_sindKonsistent() {
        // Hin- und Rückrechnung über das Spektrum: hueOf(spectrumColor(p)) ≈ 360p
        for (i in 1..11) {
            val p = i / 12f
            val hue = ColorPaletteSliderView.hueOf(ColorPaletteSliderView.spectrumColor(p))
            assertTrue("p=$p hue=$hue", kotlin.math.abs(hue - p * 360f) < 1f)
        }
    }
}
