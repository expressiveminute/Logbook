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
        assertEquals(
            RubbelkarteView.ZOOM_MAX,
            RubbelkarteView.clampZoom(RubbelkarteView.ZOOM_MAX + 1f),
            0f
        )
        var zoom = 1f
        repeat(200) { zoom = RubbelkarteView.clampZoom(zoom * 1.1f) }

        assertEquals(RubbelkarteView.ZOOM_MAX, zoom, 1e-6f)
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

    @Test
    fun beschriftungenBrauchenZoomUndLiegenAufDerKugel() {
        // Ohne genug Zoom gibt es keine Beschriftung, sonst stünde der Name
        // eines Landes in Schriftgroesse ueber einem Kontinent, in dem man
        // gerade nichts erkennt.
        assertEquals(true, RubbelkarteView.ZOOM_LABELS > RubbelkarteView.ZOOM_MIN)
        assertEquals(true, RubbelkarteView.ZOOM_STAEDTE > RubbelkarteView.ZOOM_LABELS)
        // Sonst waeren die Städte unerreichbar: Der obere Anschlag muess-te
        // ueber beiden Schwellen liegen.
        assertEquals(true, RubbelkarteView.ZOOM_MAX > RubbelkarteView.ZOOM_STAEDTE)
        // Der Doppeltipp soll wenigstens die Ländernamen erreichen, sonst
        // waere die Geste bis zu den Städten der einzige Weg dorthin.
        assertEquals(
            true,
            RubbelkarteView.ZOOM_DOPPELTIPP >= RubbelkarteView.ZOOM_LABELS
        )
    }

    @Test
    fun zuBreiterNamePasstNichtInsLand() {
        // Ein Land, das schmaler ist als sein eigener Name, bekommt keinen.
        // Sonst stünde der Name ueber den Kartenrand hinaus.
        assertEquals(
            null,
            RubbelkarteView.labelRechteck(
                textBreite = 200f,
                textHoehe = 14f,
                ankerX = 500f,
                ankerY = 500f,
                landBreite = 120f,
                landHoehe = 300f,
                rand = 3f
            )
        )
    }

    @Test
    fun zuHohesLandPasstNicht() {
        // Ein sehr flaches Land, etwa eine Insel im Meer, kann keinen Namen
        // tragen, egal wie breit es ist.
        assertEquals(
            null,
            RubbelkarteView.labelRechteck(
                textBreite = 60f,
                textHoehe = 14f,
                ankerX = 500f,
                ankerY = 500f,
                landBreite = 300f,
                landHoehe = 10f,
                rand = 3f
            )
        )
    }

    @Test
    fun passenderNameLiegtMittigAufDemAnker() {
        val rect = RubbelkarteView.labelRechteck(
            textBreite = 60f,
            textHoehe = 14f,
            ankerX = 500f,
            ankerY = 500f,
            landBreite = 300f,
            landHoehe = 200f,
            rand = 3f
        )
        assertEquals(470f, rect!![0], 0.01f)
        assertEquals(493f, rect[1], 0.01f)
        assertEquals(530f, rect[2], 0.01f)
        assertEquals(507f, rect[3], 0.01f)
        // Der Anker ist die Mitte: Genau dort liegt der Name, was bei einem
        // langen Land wie Chile den Unterschied zwischen Landmitte und
        // Ankerpunkt ausmacht.
        assertEquals(500f, (rect[0] + rect[2]) / 2f, 0.01f)
        assertEquals(500f, (rect[1] + rect[3]) / 2f, 0.01f)
    }

    @Test
    fun randUmDenNameBleibtAmRandDesLandes() {
        // Genau auf der Grenze: Der Name passt ohne Rand, mit Rand nicht mehr.
        // Diese eine dp entscheidet darueber, ob der Name gesetzt wird.
        val ohneRand = RubbelkarteView.labelRechteck(
            textBreite = 60f, textHoehe = 14f, ankerX = 50f, ankerY = 50f,
            landBreite = 60f, landHoehe = 40f, rand = 0f
        )
        assertEquals(true, ohneRand != null)
        assertEquals(
            null,
            RubbelkarteView.labelRechteck(
                textBreite = 60f, textHoehe = 14f, ankerX = 50f, ankerY = 50f,
                landBreite = 60f, landHoehe = 40f, rand = 1f
            )
        )
    }

    @Test
    fun leereTextmasseErgibtKeinenNamen() {
        // Ein leerer Name (etwa weil die Sprache einen Namen nicht fuehrt)
        // darf keine Nullbreite erzeugen, die als passend gewertet wuerde.
        assertEquals(
            null,
            RubbelkarteView.labelRechteck(
                textBreite = 0f, textHoehe = 14f, ankerX = 50f, ankerY = 50f,
                landBreite = 300f, landHoehe = 200f, rand = 3f
            )
        )
        assertEquals(
            null,
            RubbelkarteView.labelRechteck(
                textBreite = 60f, textHoehe = 0f, ankerX = 50f, ankerY = 50f,
                landBreite = 300f, landHoehe = 200f, rand = 3f
            )
        )
    }
}
