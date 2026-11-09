package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests für die Zentrierung der Kugel auf der Rubbelkarte, besonders bei
 * geöffneter halbhoher Seite unten, siehe [RubbelkarteView.centerShiftFor].
 */
class CenterShiftTest {

    @Test
    fun ohneSeite_stehtDieKugelInDerBildschirmmitte() {
        // Bildschirmmitte (1000) innerhalb der View (300..1334): Die Kugel
        // rutscht um die Differenz zur View-Mitte nach unten.
        val shift = RubbelkarteView.centerShiftFor(
            viewTop = 300f, viewHeight = 1034f, screenHeight = 2000f, bottomCoverPx = 0
        )
        assertEquals(1000f - 300f - 1034f / 2f, shift, 1e-3f)
        assertTrue(shift > 0f)
    }

    @Test
    fun ohneSeite_undBildschirmmitteAusserhalbDerView_istDerVersatzNull() {
        val shift = RubbelkarteView.centerShiftFor(
            viewTop = 300f, viewHeight = 500f, screenHeight = 2000f, bottomCoverPx = 0
        )
        assertEquals(0f, shift, 1e-3f)
    }

    @Test
    fun mitOffenerSeite_sitztDieKugelInDerMitteDesSichtbarenStreifens() {
        // Drittel-Seite offen (666 px): Die Bildschirmmitte (1000) läge noch
        // innerhalb der View (300..1334) und würde die Kugel nach unten in die
        // Seite ziehen. Stattdessen sitzt sie in der Mitte des sichtbaren
        // Streifens (300 + (1034-666)/2), noch etwas nach oben versetzt.
        val shift = RubbelkarteView.centerShiftFor(
            viewTop = 300f, viewHeight = 1034f, screenHeight = 2000f, bottomCoverPx = 666
        )
        assertTrue(shift < 0f)
        assertEquals(-666f / 2f - 1034f * RubbelkarteView.PAGE_OPEN_UP_FRACTION, shift, 1e-3f)
    }

    @Test
    fun mitHalberSeite_liegtDieKugelObenImSichtbarenStreifen() {
        // Seite offen (500 px von 700 px View-Höhe): Ohne Korrektur läge die
        // Kugelmitte in der View-Mitte und die untere Hälfte wäre verdeckt.
        // Sie sitzt in der Mitte des sichtbaren Streifens minus Versatz.
        val shift = RubbelkarteView.centerShiftFor(
            viewTop = 300f, viewHeight = 700f, screenHeight = 2000f, bottomCoverPx = 500
        )
        assertTrue(shift < 0f)
        assertEquals(-500f / 2f - 700f * RubbelkarteView.PAGE_OPEN_UP_FRACTION, shift, 1e-3f)
    }

    @Test
    fun seiteDecktDieGanzeView_verschiebtDieKugelAufDieViewOberkante() {
        // Deckt die Seite mehr als die View-Höhe, ist der sichtbare Streifen
        // leer (Höhe 0): Die Kugel rutscht auf die Oberkante der View minus
        // Aufwärtsversatz.
        val shift = RubbelkarteView.centerShiftFor(
            viewTop = 300f, viewHeight = 700f, screenHeight = 2000f, bottomCoverPx = 1000
        )
        assertEquals(-700f / 2f - 700f * RubbelkarteView.PAGE_OPEN_UP_FRACTION, shift, 1e-3f)
    }

    @Test
    fun seitenhoeheDeckelt_denZusatzversatzBeiKleinerSeite() {
        // Zweiter Deckel: Viertel der Seitenhöhe, hier kleiner als der Anteil
        // der View-Höhe.
        val shift = RubbelkarteView.centerShiftFor(
            viewTop = 0f, viewHeight = 1000f, screenHeight = 2000f, bottomCoverPx = 10
        )
        assertEquals(-10f / 2f - 10 * RubbelkarteView.PAGE_OPEN_COVER_FRACTION, shift, 1e-3f)
    }

    @Test
    fun nullHoehe_ergibtNull() {
        assertEquals(0f, RubbelkarteView.centerShiftFor(0f, 0f, 2000f, 500), 1e-3f)
    }

    @Test
    fun zoomForCover_ohneSeite_aendertDenZoomNicht() {
        assertEquals(23f, RubbelkarteView.zoomForCover(23f, 1080, 2340, 0), 1e-4f)
    }

    @Test
    fun zoomForCover_seitePasst_schonBeiVollemZoom() {
        // Hochformat 1080x2340, Seite deckt ca. 51 % (PAGE_STATES): Der sichtbare
        // Streifen ist halb so hoch wie die View, aber breiter als die Kugel -
        // der FIT-Zoom (Region = base) passt bereits hinein.
        val cover = (2340 * 0.5133f).toInt()
        assertEquals(23f, RubbelkarteView.zoomForCover(23f, 1080, 2340, cover), 1e-4f)
    }

    @Test
    fun zoomForCover_schmalerStreifen_verkleinertDenZoom() {
        // Viel Verdeckung (1800 px): Die Region darf nur bis zur halben Höhe
        // des sichtbaren Streifens reichen - der Zoom wird um maxExtent/base
        // kleiner skaliert, damit nichts hinter der Seite klebt.
        val w = 1080
        val h = 2340
        val cover = 1800
        val base = w * (1f - RubbelkarteView.GLOBE_MARGIN) / 2f
        val rut = minOf(h * RubbelkarteView.PAGE_OPEN_UP_FRACTION, cover * RubbelkarteView.PAGE_OPEN_COVER_FRACTION)
        val maxExtent = (h - cover) / 2f - rut
        val zoom = RubbelkarteView.zoomForCover(23f, w, h, cover)
        assertEquals(23f * (maxExtent / base), zoom, 1e-3f)
        assertTrue(zoom < 23f)
    }

    @Test
    fun zoomForCover_querformat_beruecksichtigtDieSichtbareHoehe() {
        // Querformat 2340x1080, Seite 500 px: base hängt von der Höhe ab, der
        // sichtbare Streifen ist (1080-500)/2 hoch.
        val w = 2340
        val h = 1080
        val cover = 500
        val base = h * (1f - RubbelkarteView.GLOBE_MARGIN) / 2f
        val rut = minOf(h * RubbelkarteView.PAGE_OPEN_UP_FRACTION, cover * RubbelkarteView.PAGE_OPEN_COVER_FRACTION)
        val maxExtent = (h - cover) / 2f - rut
        val zoom = RubbelkarteView.zoomForCover(10f, w, h, cover)
        assertEquals(10f * (maxExtent / base), zoom, 1e-3f)
    }
}
