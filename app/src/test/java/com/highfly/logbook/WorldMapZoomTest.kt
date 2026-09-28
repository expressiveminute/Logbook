package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * Tests für Zoomstufe und Beschriftung der Weltkarte, siehe
 * [WorldMapFragment.startZoom] und [CountryLabelOverlay.passtInsLand].
 */
class WorldMapZoomTest {

    /** Standardansicht wie in [WorldMapFragment.standardZoom]. */
    private fun standardZoom(breitePx: Int): Double =
        (ln(breitePx.toDouble() / 256.0) / ln(2.0)).coerceIn(1.0, 10.0)

    @Test
    fun startZoomLiegtWeiterVorneAlsDieStandardansicht() {
        val standard = standardZoom(1080)
        assertTrue(WorldMapFragment.startZoom(standard) > standard)
        // Zwei Stufen, damit Laendernamen und Hauptstaedte schon lesbar sind.
        assertEquals(standard + 2.0, WorldMapFragment.startZoom(standard), 1e-9)
    }

    @Test
    fun startZoomBleibtUnterDerObergrenze() {
        // Die Standardansicht braucht 256px * 2^8 fuer Stufe 8. Erst darueber
        // waere die Startstufe durch die Obergrenze begrenzt - dann darf sie
        // die Grenze auch nicht ueberreissen.
        val ueberGrenze = standardZoom(100000)
        assertTrue(ueberGrenze + 2.0 > 10.0)
        assertEquals(10.0, WorldMapFragment.startZoom(ueberGrenze), 1e-9)
        assertTrue(WorldMapFragment.startZoom(ueberGrenze) <= 10.0)
    }

    @Test
    fun startZoomBleibtUeberDerStandardansicht() {
        // Auch im guenstigsten Fall (sehr schmales Geraet) muss die Karte noch
        // von der Standardansicht aus aufgehen.
        val standard = standardZoom(320)
        assertTrue(WorldMapFragment.startZoom(standard) > standard)
    }

    @Test
    fun hauptstaedteSindBeimStartSchonSichtbar() {
        // Die Karte soll nicht mit einem leeren Blatt aufgehen: die
        // Hauptstaedte muessen bei der Startstufe schon stehen.
        for (breite in listOf(320, 720, 1080, 1600, 2560)) {
            val start = WorldMapFragment.startZoom(standardZoom(breite))
            assertTrue(
                "Breite $breite startet bei $start",
                start >= WorldMapFragment.HAUPTSTADT_ZOOM
            )
        }
    }

    @Test
    fun laendernamePasstNurWennErInsLandPasst() {
        // Name 40 breit, 10 hoch.
        assertTrue(CountryLabelOverlay.passtInsLand(40f, 10f, 120f, 80f))
        // Knapp: passt genau noch.
        assertTrue(CountryLabelOverlay.passtInsLand(40f, 10f, 40f, 10f))
        // Zu breit für das Land: nicht zeichnen.
        assertFalse(CountryLabelOverlay.passtInsLand(40f, 10f, 39f, 80f))
        // Passt in der Breite, aber nicht in der Höhe.
        assertFalse(CountryLabelOverlay.passtInsLand(40f, 10f, 120f, 9f))
    }

    @Test
    fun kleinesLandBrauchtMehrZoomAlsEinGrosses() {
        // Der ganze Punkt der Groessenpruefung: bei gleichem Zoom passt der
        // Name nur in das grosse Land, das kleine muss weiter zugezoomt werden.
        val textBreite = 60f
        val textHoehe = 12f
        val zoom = 3f
        val proZoomstufe = 40f

        val grossesLand = 200f * zoom / 2f
        val kleinesLand = 20f * zoom / 2f
        assertTrue(CountryLabelOverlay.passtInsLand(textBreite, textHoehe, grossesLand, 500f))
        assertFalse(CountryLabelOverlay.passtInsLand(textBreite, textHoehe, kleinesLand, 500f))
        // Und weiter zoomt holt das kleine Land den Platz irgendwann nach.
        val spater = kleinesLand * 6f
        assertTrue(spater > proZoomstufe)
        assertTrue(CountryLabelOverlay.passtInsLand(textBreite, textHoehe, spater, 500f))
    }

    @Test
    fun unbrauchbareGroessenZeigenKeinenNamen() {
        // Ein Land quer ueber den Datumswechsel oder eine kaputte Projektion
        // liefert 0 oder NaN. In dem Fall wird nichts gezeichnet - nicht der
        // halbe Name und schon gar nicht ueber dem Nachbarn.
        assertFalse(CountryLabelOverlay.passtInsLand(40f, 10f, 0f, 80f))
        assertFalse(CountryLabelOverlay.passtInsLand(40f, 10f, -5f, 80f))
        assertFalse(CountryLabelOverlay.passtInsLand(40f, 10f, Float.NaN, 80f))
        assertFalse(CountryLabelOverlay.passtInsLand(40f, 10f, Float.POSITIVE_INFINITY, 80f))
        assertFalse(CountryLabelOverlay.passtInsLand(Float.NaN, 10f, 120f, 80f))
    }
}
