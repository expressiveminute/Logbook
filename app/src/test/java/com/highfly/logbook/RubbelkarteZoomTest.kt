package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

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

    @Test
    fun unbrauchbarerZoomFaelltAufNormalgroesseZurueck() {
        // Die Gesteuerung kann unendlich oder NaN melden. Ein NaN-Zoom
        // vergiftet jede weitere Skalierung, also muss er hier auffallen.
        assertEquals(1f, RubbelkarteView.clampZoom(Float.NaN), 0f)
        assertEquals(1f, RubbelkarteView.clampZoom(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, RubbelkarteView.clampZoom(Float.NEGATIVE_INFINITY), 0f)
        // Und danach ist wieder normal zoomen moeglich.
        assertEquals(1.1f, RubbelkarteView.clampZoom(1.1f), 1e-6f)
    }

    @Test
    fun zeigerIndexRutschtNachDemAbhebenNach() {
        // Der Zeiger hinter dem abgehobenen rückt im Folgeereignis vor.
        assertEquals(0, RubbelkarteView.folgeIndex(0, 1))
        assertEquals(0, RubbelkarteView.folgeIndex(1, 0))
        assertEquals(0, RubbelkarteView.folgeIndex(0, 0))

        // Drei Zeiger: der hintere rückt vor, die davor behalten ihren Platz.
        assertEquals(0, RubbelkarteView.folgeIndex(0, 2))
        assertEquals(1, RubbelkarteView.folgeIndex(1, 2))
        assertEquals(1, RubbelkarteView.folgeIndex(2, 1))
    }

    @Test
    fun folgeIndexBleibtImEreignis() {
        // Nach dem Umbau muss jeder überlebende Zeiger im Folgeereignis
        // existieren - genau daran scheitert das Drehen nach dem Pinch.
        for (abgehoben in 0..3) {
            for (zeiger in 0..3) {
                if (zeiger == abgehoben) continue
                val gesamt = 4
                val neu = RubbelkarteView.folgeIndex(zeiger, abgehoben)
                val verbleibend = gesamt - 1
                assertEquals(
                    "Zeiger $zeiger, abgehoben $abgehoben",
                    true,
                    neu in 0 until verbleibend
                )
            }
        }
    }

    @Test
    fun ausgangszustandLaesstLinksUndRechtsPlatz() {
        // Im Ausgangszustand soll die Kugel nicht am Seitenrand kleben. Der
        // Abstand ist die Differenz zwischen dem halben Durchmesser und dem
        // Radius und muss auf beiden Seiten gleich sein.
        val breite = 1000f
        val abstand = breite / 2f * RubbelkarteView.GLOBE_MARGIN
        assertEquals(true, abstand > 0f)
        // Auf einem 400dp breiten Bildschirm bleiben so gut acht dp je Seite.
        assertEquals(true, abstand >= 6f)
    }

    @Test
    fun randVerschwindetBeimZoomen() {
        // Sobald hereingezoomt ist, nutzt die Kugel die volle Breite: Der
        // Radius ist dann mindestens so gross wie die Hälfte des Rahmens.
        val rahmen = 1000f
        val zoomRandlos = RubbelkarteView.ZOOM_RANDLOS
        assertEquals(true, zoomRandlos in RubbelkarteView.ZOOM_MIN..RubbelkarteView.ZOOM_MAX)

        // Knapp davor ist noch ein Spalt, genau darauf ist es noch am Rand.
        val knappDavor = radius(rahmen, zoomRandlos * 0.999f)
        val genau = radius(rahmen, zoomRandlos)
        val danach = radius(rahmen, RubbelkarteView.ZOOM_MAX)
        assertEquals(true, knappDavor < rahmen / 2f)
        assertEquals(rahmen / 2f, genau, 0.01f)
        assertEquals(true, danach > rahmen / 2f)

        // Und zurück auf Normalgrösse ist der Abstand wieder da.
        assertEquals(true, radius(rahmen, RubbelkarteView.ZOOM_MIN) < rahmen / 2f)
    }

    /** Radius wie [RubbelkarteView.globeRadius], nur ohne Android-View drumherum. */
    private fun radius(rahmen: Float, zoom: Float): Float =
        rahmen * (1f - RubbelkarteView.GLOBE_MARGIN) / 2f * zoom

    @Test
    fun unveraenderteKugelBautNichtNeu() {
        // Solange sich nichts bewegt hat, darf kein weiterer Aufbau anlaufen.
        assertEquals(false, RubbelkarteView.weichtAb(0.0, 0.0, 0f))
    }

    @Test
    fun hauchVonDifferenzLoestKeinenAufbauAus() {
        // Der Wischweg landet in Bruchteilen von Pixeln. Solange der
        // Unterschied unterhalb von einem halben Pixel bleibt, waere ein neuer
        // Aufbau vergebliche Arbeit - und bei jedem Bild ein neuer Thread.
        assertEquals(false, RubbelkarteView.weichtAb(1e-6, 1e-6, 0.1f))
        assertEquals(false, RubbelkarteView.weichtAb(0.0, 0.0, 0.5f))
    }

    @Test
    fun echteDrehungBautNeu() {
        // Ab etwa einem Hundertstel Pixel ist der Unterschied sichtbar.
        assertEquals(true, RubbelkarteView.weichtAb(1e-3, 0.0, 0f))
        assertEquals(true, RubbelkarteView.weichtAb(0.0, 1e-3, 0f))
        // Und eine Verschiebung der Kugel ueber ein halbes Pixel auch.
        assertEquals(true, RubbelkarteView.weichtAb(0.0, 0.0, 0.6f))
    }

    @Test
    fun umDenDatumswechselIstEsDieselbeKugel() {
        // Die Kugel wird 0..2PI gerechnet, ein Blickpunkt kurz vor dem
        // Umlauf und derselbe kurz danach sind dieselbe Ansicht. Ohne diese
        // Regel wuerde die Kugel beim Wischen ueber den Punkt hin und her
        // springen.
        val zweiPi = 2.0 * PI
        assertEquals(false, RubbelkarteView.weichtAb(zweiPi, 0.0, 0f))
        assertEquals(false, RubbelkarteView.weichtAb(-zweiPi, 0.0, 0f))
        assertEquals(false, RubbelkarteView.weichtAb(2 * zweiPi, 0.0, 0f))
        // Fast derselbe Punkt ist auch fast derselbe.
        assertEquals(false, RubbelkarteView.weichtAb(zweiPi - 1e-6, 0.0, 0f))
    }

    @Test
    fun unbrauchbareWinkelBauenSicherNeu() {
        // NaN waere im Vergleich mit jedem Wert false und wuerde durchrutschen:
        // Die Kugel bliebe dann auf einem Stand stehen, den es nicht gibt.
        assertEquals(true, RubbelkarteView.weichtAb(Double.NaN, 0.0, 0f))
        assertEquals(true, RubbelkarteView.weichtAb(0.0, Double.NaN, 0f))
        assertEquals(true, RubbelkarteView.weichtAb(Double.POSITIVE_INFINITY, 0.0, 0f))
        assertEquals(true, RubbelkarteView.weichtAb(0.0, 0.0, Float.NaN))
    }
}
