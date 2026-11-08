package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests für die reinen Rechnungen der Weltkugel, siehe [GlobeView].
 */
class GlobeViewTest {

    @Test
    fun metersPerPixel_mitteAmAequator() {
        // Am Äquator: ein Radiant = Erdradius lang, belegt radiusPx Pixel.
        val mpp = GlobeView.metersPerPixel(0.0, 500.0)
        assertEquals(GlobeView.EARTH_RADIUS_M / 500.0, mpp, 1e-6)
    }

    @Test
    fun metersPerPixel_schrumpftMitDerBreite() {
        // Am 60. Breitengrad ist der Parallelkreis halb so lang.
        val mpp = GlobeView.metersPerPixel(Math.toRadians(60.0), 500.0)
        assertEquals(GlobeView.EARTH_RADIUS_M * 0.5 / 500.0, mpp, 1e-6)
    }

    @Test
    fun metersPerPixel_ungueltigerRadiusIstNull() {
        assertEquals(0.0, GlobeView.metersPerPixel(0.0, 0.0), 0.0)
        assertEquals(0.0, GlobeView.metersPerPixel(0.0, -10.0), 0.0)
    }

    @Test
    fun labelTier_zoomKnopfMal3_6_8_ergibtDieStufen() {
        // Zoom-Knopf = Faktor 1,6: 3x = 4,096 (500 km), 6x = 16,8, 8x = 43 (25 km).
        assertEquals(0, GlobeView.labelTier(1f))
        assertEquals(0, GlobeView.labelTier(1.6f * 1.6f))       // 2x Plus
        assertEquals(1, GlobeView.labelTier(1.6f * 1.6f * 1.6f)) // 3x Plus
        assertEquals(2, GlobeView.labelTier(1.6f * 1.6f * 1.6f * 1.6f * 1.6f * 1.6f)) // 6x
        assertEquals(3, GlobeView.labelTier(1.6f * 1.6f * 1.6f * 1.6f * 1.6f * 1.6f * 1.6f * 1.6f)) // 8x
    }

    @Test
    fun tierIncludesCity_nurHauptstaedteAufStufe1() {
        fun city(capital: Boolean, rank: Int) = MapCity(0.0, 0.0, rank, capital, "n", "d", "e")
        assertEquals(true, GlobeView.tierIncludesCity(1, city(capital = true, rank = 3)))
        assertEquals(false, GlobeView.tierIncludesCity(1, city(capital = false, rank = 0)))
        assertEquals(true, GlobeView.tierIncludesCity(2, city(capital = false, rank = 0)))
        assertEquals(false, GlobeView.tierIncludesCity(2, city(capital = false, rank = 1)))
        assertEquals(true, GlobeView.tierIncludesCity(3, city(capital = false, rank = 2)))
    }
}
