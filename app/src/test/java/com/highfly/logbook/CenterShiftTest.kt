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
    fun mitOffenerSeite_rutschtDieKugelNichtInDieBildschirmmitte() {
        // Drittel-Seite offen (666 px): Die Bildschirmmitte (1000) läge noch
        // innerhalb der View (300..1334) und würde die Kugel nach unten in die
        // Seite ziehen. Stattdessen sitzt sie in der Mitte des sichtbaren
        // Streifens, etwas nach oben versetzt.
        val shift = RubbelkarteView.centerShiftFor(
            viewTop = 300f, viewHeight = 1034f, screenHeight = 2000f, bottomCoverPx = 666
        )
        assertTrue(shift <= 0f)
        assertEquals(-1034f * RubbelkarteView.PAGE_OPEN_UP_FRACTION, shift, 1e-3f)
    }

    @Test
    fun mitHalberSeite_liegtDieKugelObenImSichtbarenStreifen() {
        // Halbe Seite offen (1000 px): Bildschirmmitte = Unterkante der View -
        // ohne Korrektur läge die Kugelmitte genau auf der Kante und die halbe
        // Kugel wäre verdeckt.
        val shift = RubbelkarteView.centerShiftFor(
            viewTop = 300f, viewHeight = 700f, screenHeight = 2000f, bottomCoverPx = 1000
        )
        assertTrue(shift < 0f)
        assertEquals(-700f * RubbelkarteView.PAGE_OPEN_UP_FRACTION, shift, 1e-3f)
    }

    @Test
    fun aufwaertsversatz_istAufGlobeMarginUeberZweiGedeckelt() {
        // Bei FIT-Zoom füllt die Region (1-GLOBE_MARGIN)/2 der kleineren Seite
        // um die Kugelmitte; mehr Aufwärtsversatz als GLOBE_MARGIN/2 der Höhe
        // würde oben abschneiden, statt unten freizugeben.
        val h = 1034f
        val shift = RubbelkarteView.centerShiftFor(
            viewTop = 0f, viewHeight = h, screenHeight = 2000f, bottomCoverPx = 666
        )
        assertTrue(abs(shift) <= h * RubbelkarteView.GLOBE_MARGIN / 2f + 1e-3f)
    }

    @Test
    fun seitenhoeheDeckelt_denVersatzBeiKleinerSeite() {
        // Zweiter Deckel: Viertel der Seitenhöhe, hier kleiner als der Anteil
        // der View-Höhe.
        val shift = RubbelkarteView.centerShiftFor(
            viewTop = 0f, viewHeight = 1000f, screenHeight = 2000f, bottomCoverPx = 10
        )
        assertEquals(-10 * RubbelkarteView.PAGE_OPEN_COVER_FRACTION, shift, 1e-3f)
    }

    @Test
    fun nullHoehe_ergibtNull() {
        assertEquals(0f, RubbelkarteView.centerShiftFor(0f, 0f, 2000f, 500), 1e-3f)
    }
}
