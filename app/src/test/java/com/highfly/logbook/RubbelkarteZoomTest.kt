package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests für den Zoombereich des Globus, siehe [RubbelkarteView.clampZoom]. */
class RubbelkarteZoomTest {

    @Test
    fun normaleGroesseBleibtNormal() {
        assertEquals(1f, RubbelkarteView.clampZoom(1f), 0f)
        assertEquals(1.2f, RubbelkarteView.clampZoom(1.2f), 1e-6f)
    }

    @Test
    fun zuschlagenStopsAmOberenAnschlag() {
        // Ein Finger auseinandergespreizt meldet viele Schritte hintereinander,
        // jeder einzelne darf die Kugel nicht weiter hinaus schieben.
        assertEquals(1.35f, RubbelkarteView.clampZoom(2.5f), 0f)
        var zoom = 1f
        repeat(200) { zoom = RubbelkarteView.clampZoom(zoom * 1.1f) }

        assertEquals(1.35f, zoom, 1e-6f)
    }

    @Test
    fun auseinanderziehenStopsAmUnterenAnschlag() {
        assertEquals(1f, RubbelkarteView.clampZoom(0.2f), 0f)
        var zoom = 1.3f
        repeat(200) { zoom = RubbelkarteView.clampZoom(zoom / 1.1f) }

        assertEquals(1f, zoom, 1e-6f)
    }

    @Test
    fun doppeltippZieltUndWechselZurueck() {
        // Ziel des Doppeltipps liegt im Bereich, sonst könnte die zweite
        // Stufe gar nicht erreicht werden.
        assertEquals(true, RubbelkarteView.ZOOM_DOPPELTIPP in 1f..RubbelkarteView.ZOOM_MAX)
        assertEquals(true, RubbelkarteView.ZOOM_MIN < RubbelkarteView.ZOOM_MAX)
    }
}
