package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ScaleBarTest {

    @Test
    fun niceDistance_roundsDownToRoundedValue() {
        // Schritte 5 / 2,5 / 1 mal 10^n - auf Kartenebene also z. B.
        // 500, 250, 100, 50 km.
        assertEquals(250.0, ScaleBar.niceDistance(396.0), 0.0001)
        assertEquals(100.0, ScaleBar.niceDistance(249.0), 0.0001)
        assertEquals(500.0, ScaleBar.niceDistance(999.0), 0.0001)
        assertEquals(1000.0, ScaleBar.niceDistance(1000.0), 0.0001)
        assertEquals(250_000.0, ScaleBar.niceDistance(433_000.0), 0.0001)
    }

    @Test
    fun niceDistance_handlesNonPositive() {
        assertEquals(0.0, ScaleBar.niceDistance(0.0), 0.0001)
        assertEquals(0.0, ScaleBar.niceDistance(-5.0), 0.0001)
        assertEquals(0.0, ScaleBar.niceDistance(Double.NaN), 0.0001)
    }

    @Test
    fun distanceText_switchesToKilometers() {
        assertEquals("200 m", ScaleBar.distanceText(200.0, Locale.GERMANY))
        assertEquals("1 km", ScaleBar.distanceText(1000.0, Locale.GERMANY))
        assertEquals("20 km", ScaleBar.distanceText(20_000.0, Locale.GERMANY))
        assertEquals("250 km", ScaleBar.distanceText(250_000.0, Locale.GERMANY))
    }

    @Test
    fun distanceText_fractionalKilometersKeepOneDecimal() {
        // Der 2,5er-Schritt braucht eine Nachkommastelle, volle km nicht.
        assertEquals("2,5 km", ScaleBar.distanceText(2500.0, Locale.GERMANY))
        assertEquals("2.5 km", ScaleBar.distanceText(2500.0, Locale.US))
        assertEquals("25 km", ScaleBar.distanceText(25_000.0, Locale.GERMANY))
    }
}
