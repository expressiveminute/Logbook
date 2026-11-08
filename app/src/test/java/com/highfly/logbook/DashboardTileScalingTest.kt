package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests für die einheitlichen Kachel-Abstände, siehe
 * [DashboardTileScaling.rowTopMargin].
 */
class DashboardTileScalingTest {

    @Test
    fun rowTopMargin_ersteZeileHatKeinenAbstand() {
        assertEquals(0, DashboardTileScaling.rowTopMargin(gap = 12f, scale = 0.9f, prevH = -1f, h = 72f))
    }

    @Test
    fun rowTopMargin_sichtbarerAbstandIstUeberallGleich() {
        val scale = 0.9f
        val gap = (1f - scale) * 110f // eine Spalte = 110 px
        // Kleine Kachel (72) nach kleiner Kachel (72): Schrumpfung 7,2 -> Margin = gap - 7,2
        val mSmall = DashboardTileScaling.rowTopMargin(gap, scale, prevH = 72f, h = 72f)
        // Weltkugel (340) nach kleiner Kachel: Schrumpfung 30,3 -> negativer Margin
        val mGlobe = DashboardTileScaling.rowTopMargin(gap, scale, prevH = 72f, h = 340f)
        // Sichtbarer Abstand = Schrumpfung + Margin = gap, überall gleich.
        val visibleSmall = (1f - scale) * (72f + 72f) / 2f + mSmall
        val visibleGlobe = (1f - scale) * (72f + 340f) / 2f + mGlobe
        assertEquals(gap, visibleSmall, 0.5f)
        assertEquals(gap, visibleGlobe, 0.5f)
    }

    @Test
    fun rowTopMargin_beiVollerGroesseSindDieKachelnAbstandsfrei() {
        // scale = 1: keine Schrumpfung, Lücke 0 -> Margin 0 (Kacheln stossen aneinander wie bisher).
        assertEquals(0, DashboardTileScaling.rowTopMargin(0f, 1f, prevH = 72f, h = 340f))
    }
}
