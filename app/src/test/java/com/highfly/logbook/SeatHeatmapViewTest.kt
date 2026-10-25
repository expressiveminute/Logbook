package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Platzierung der Sitzplätze im Flugzeugschema: Jeder Sitzplatz landet in
 * einem Klassenbereich (die Klasse des Eintrags gewinnt, sonst die Reihe),
 * und die belegten Reihen werden proportional auf den Bereich verteilt, mit
 * einem halben Reihenabstand zum Rand. Nur die reinen Rechenwege werden
 * geprüft; das Zeichnen braucht ein Android-Canvas.
 */
class SeatHeatmapViewTest {

    private val a320 = AircraftLayout.resolve("A320") // Business 1–6, Economy 7–32
    private val a330 = AircraftLayout.resolve("A330") // Business 1–11, Premium 15–19, Economy 22–46
    private val a350 = AircraftLayout.resolve("A350") // First 1, Business 2–16, Premium 18–23, Economy 24–48

    // --- sectionIndexFor ---

    @Test
    fun klasseGewinntUeberDieReihe() {
        // Reihe 20 wäre Economy, der Eintrag nennt aber Business.
        assertEquals(0, SeatHeatmapView.sectionIndexFor(a320, 20, "Business"))
        // Reihe 2 wäre Business, der Eintrag nennt aber Economy.
        assertEquals(1, SeatHeatmapView.sectionIndexFor(a320, 2, "Economy"))
    }

    @Test
    fun klasseWirdUnabhaengigVonDerSchreibweiseGefunden() {
        assertEquals(0, SeatHeatmapView.sectionIndexFor(a320, 3, "business"))
        assertEquals(1, SeatHeatmapView.sectionIndexFor(a320, 3, "ECONOMY"))
    }

    @Test
    fun ohneKlasseEntscheidetDieReihe() {
        assertEquals(0, SeatHeatmapView.sectionIndexFor(a320, 3, null))
        assertEquals(1, SeatHeatmapView.sectionIndexFor(a320, 20, null))
    }

    @Test
    fun unbekannteKlasseFaelltAufDieReiheZurueck() {
        // Die A320 hat keine First – die Reihe entscheidet.
        assertEquals(1, SeatHeatmapView.sectionIndexFor(a320, 20, "First"))
        assertEquals(0, SeatHeatmapView.sectionIndexFor(a320, 3, "First"))
    }

    @Test
    fun lueckenReiheGehoertZumAbschnittDavor() {
        // A330: Reihen 12–14 und 20–21 sind nicht belegt.
        assertEquals(0, SeatHeatmapView.sectionIndexFor(a330, 13, null))
        assertEquals(1, SeatHeatmapView.sectionIndexFor(a330, 20, null))
    }

    @Test
    fun reiheHinterDemLetztenAbschnittGehoertZumLetzten() {
        assertEquals(2, SeatHeatmapView.sectionIndexFor(a330, 50, null))
    }

    @Test
    fun reiheVorDemErstenAbschnittGehoertZumErsten() {
        val layout = AircraftLayout.Layout(
            totalRows = 10,
            sections = listOf(
                AircraftLayout.ClassSection("Business", 3, 6),
                AircraftLayout.ClassSection("Economy", 7, 10)
            ),
            seatLetters = listOf('A', 'B', 'C', 'D', 'E', 'F'),
            aisleAfter = listOf(2),
            rightWindowLetter = 'F'
        )
        assertEquals(0, SeatHeatmapView.sectionIndexFor(layout, 1, null))
        assertEquals(0, SeatHeatmapView.sectionIndexFor(layout, 2, null))
    }

    @Test
    fun jederSitzplatzLandetInEinemKlassenbereich() {
        // Alle Reihen (auch jenseits des Layouts) und alle Klasse-Namen
        // (auch unbekannte) müssen auf einen gültigen Abschnitt zeigen.
        val klassen = listOf(null, "First", "Business", "Premium Eco", "Economy", "Unbekannt")
        for (row in 0..60) {
            for (klasse in klassen) {
                val idx = SeatHeatmapView.sectionIndexFor(a350, row, klasse)
                assertTrue(
                    "Reihe $row, Klasse $klasse → Abschnitt $idx",
                    idx in a350.sections.indices
                )
            }
        }
    }

    // --- relativeRowPosition ---

    @Test
    fun innerhalbDesAbschnittsBleibtDiePositionErhalten() {
        // Abschnitt Reihe 1–6, belegte Reihen 1 und 6: Mitte der Reihe.
        val halbe = 0.5f / 6f
        assertEquals(halbe, SeatHeatmapView.relativeRowPosition(1, 6, listOf(1, 6), 1), 0.0001f)
        assertEquals(2.5f / 6f, SeatHeatmapView.relativeRowPosition(1, 6, listOf(1, 6), 3), 0.0001f)
        assertEquals(1f - halbe, SeatHeatmapView.relativeRowPosition(1, 6, listOf(1, 6), 6), 0.0001f)
    }

    @Test
    fun einzelneReiheHinterDemAbschnittZiehtAnDenUnterenRand() {
        // Reihe 20 in einem Abschnitt Reihe 1–6: unten innen, nicht am Rand.
        val halbe = 0.5f / 6f
        val pos = SeatHeatmapView.relativeRowPosition(1, 6, listOf(20), 20)
        assertEquals(1f - halbe, pos, 0.0001f)
        assertTrue("nicht am unteren Rand", pos < 1f)
    }

    @Test
    fun einzelneReiheVorDemAbschnittZiehtAnDenOberenRand() {
        // Abschnitt Reihe 5–10, belegte Reihe 2: oben innen, nicht am Rand.
        val halbe = 0.5f / 6f
        val pos = SeatHeatmapView.relativeRowPosition(5, 6, listOf(2), 2)
        assertEquals(halbe, pos, 0.0001f)
        assertTrue("nicht am oberen Rand", pos > 0f)
    }

    @Test
    fun stauchungErhaeltReihenfolgeUndAbstaende() {
        // Abschnitt Reihe 1–6, belegte Reihen 1, 3, 6 und 20 (außerhalb).
        // Die Reihen 1 und 20 sitzen an den Rändern (innen), Reihe 3 behält
        // ihren proportionalen Abstand: 2 von 19 Reihen-Schritten.
        val halbe = 0.5f / 6f
        val pos1 = SeatHeatmapView.relativeRowPosition(1, 6, listOf(1, 3, 6, 20), 1)
        val pos3 = SeatHeatmapView.relativeRowPosition(1, 6, listOf(1, 3, 6, 20), 3)
        val pos6 = SeatHeatmapView.relativeRowPosition(1, 6, listOf(1, 3, 6, 20), 6)
        val pos20 = SeatHeatmapView.relativeRowPosition(1, 6, listOf(1, 3, 6, 20), 20)
        assertEquals(halbe, pos1, 0.0001f)
        assertEquals(1f - halbe, pos20, 0.0001f)
        assertTrue("Reihenfolge bleibt erhalten", pos1 < pos3 && pos3 < pos6 && pos6 < pos20)
        val verhaeltnis = (pos3 - pos1) / (pos20 - pos1)
        assertEquals(2f / 19f, verhaeltnis, 0.0001f)
    }

    @Test
    fun allePositionenBleibenImBereichMitRandabstand() {
        // In der App wird die Position nur für belegte Reihen abgefragt; für
        // die gilt: immer innerhalb des Abschnitts, mit halbem Reihenabstand
        // zum Rand, in der Reihenreihenfolge.
        val halbe = 0.5f / 6f
        val belegungen = listOf(
            listOf(1, 20),
            listOf(1, 3, 6, 20),
            listOf(1, 6, 7, 30),
            listOf(20, 25, 30),
            listOf(1)
        )
        for (belegt in belegungen) {
            var vorherige = -1f
            for (reihe in belegt.sorted()) {
                val pos = SeatHeatmapView.relativeRowPosition(1, 6, belegt, reihe)
                assertTrue(
                    "Reihe $reihe bei $belegt → $pos",
                    pos in halbe..(1f - halbe)
                )
                assertTrue("Reihenfolge bleibt erhalten", pos > vorherige)
                vorherige = pos
            }
        }
    }

    @Test
    fun ohneBelegteReihenWirdAufDenAbschnittBegrenzt() {
        val halbe = 0.5f / 6f
        assertEquals(1f - halbe, SeatHeatmapView.relativeRowPosition(1, 6, emptyList(), 20), 0.0001f)
        assertEquals(halbe, SeatHeatmapView.relativeRowPosition(1, 6, emptyList(), 0), 0.0001f)
        assertEquals(2.5f / 6f, SeatHeatmapView.relativeRowPosition(1, 6, emptyList(), 3), 0.0001f)
    }
}
