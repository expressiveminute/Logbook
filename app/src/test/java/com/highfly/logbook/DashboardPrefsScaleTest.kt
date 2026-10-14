package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class DashboardPrefsScaleTest {

    @Test
    fun snapScale_roundsFreeValueOntoStepGrid() {
        // Früher frei gezogener Wert, der den Schieber beim Aufbau abstürzen ließ.
        assertEquals(1f, DashboardPrefs.snapScale(1.0214286f), 0.0001f)
    }

    @Test
    fun snapScale_keepsValueOnGrid() {
        assertEquals(1f, DashboardPrefs.snapScale(1f), 0.0001f)
        assertEquals(0.9f, DashboardPrefs.snapScale(0.9f), 0.0001f)
        assertEquals(0.85f, DashboardPrefs.snapScale(DashboardPrefs.MIN_SCALE), 0.0001f)
    }

    @Test
    fun snapScale_clampsToRange() {
        assertEquals(DashboardPrefs.MIN_SCALE, DashboardPrefs.snapScale(0.5f), 0.0001f)
        assertEquals(DashboardPrefs.MAX_SCALE, DashboardPrefs.snapScale(2f), 0.0001f)
    }

    @Test
    fun snapScale_resultAlwaysLiesOnWholePercent() {
        var value = DashboardPrefs.MIN_SCALE
        while (value <= DashboardPrefs.MAX_SCALE + 0.0001f) {
            val percent = (DashboardPrefs.snapScale(value) * 100f).roundToInt()
            assertTrue(percent in DashboardPrefs.MIN_PERCENT..DashboardPrefs.MAX_PERCENT)
            assertEquals(percent.toFloat(), DashboardPrefs.snapScale(value) * 100f, 0.0001f)
            value += 0.001f
        }
    }

    @Test
    fun sliderUsesWholePercentSoAllTicksExist() {
        // Die Schrittweite des Schiebers ist 1 %; dann liegen genau
        // (MAX - MIN) / Schritt + 1 = 16 Rastpunkte vor. Das ist der Grund für
        // die ganzzahligen Werte (siehe [DashboardPrefs.MIN_PERCENT]).
        val ticks = (DashboardPrefs.MAX_PERCENT - DashboardPrefs.MIN_PERCENT) / 1 + 1
        assertEquals(16, ticks)
    }

    @Test
    fun horizontalScale_insetsEachTileByHalfAColumn() {
        // Eine einspaltige Kachel schrumpft proportional, eine volle Breite nur
        // um eine halbe Spalte je Seite.
        val column = 120f
        assertEquals(0.85f, DashboardPrefs.horizontalScale(column, column, 0.85f), 0.0001f)
        assertEquals(0.95f, DashboardPrefs.horizontalScale(3 * column, column, 0.85f), 0.0001f)
    }

    @Test
    fun roundContentCompensation_keepsBothAxesEqual() {
        // Nach dem Gegenfaktor erscheint ein runder Inhalt auf beiden Achsen
        // mit der senkrechten Kachelgrösse [scale], wird also nicht oval.
        val column = 120f
        for (scale in listOf(0.85f, 0.9f, 0.95f, 1f)) {
            for (span in 1..3) {
                val width = span * column
                val horizontal = DashboardPrefs.horizontalScale(width, column, scale)
                val compensation =
                    DashboardPrefs.roundContentCompensation(width, column, scale)
                assertEquals(scale, horizontal * compensation, 0.0001f)
            }
        }
    }

    @Test
    fun roundContentCompensation_isOneForSingleColumnTile() {
        // Einspaltige (quadratische) Kacheln brauchen keinen Ausgleich.
        val column = 120f
        assertEquals(
            1f,
            DashboardPrefs.roundContentCompensation(column, column, 0.85f),
            0.0001f
        )
    }
}
