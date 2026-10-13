package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ScaleBarTest {

    @Test
    fun niceDistance_roundsDownToRoundedValue() {
        assertEquals(200.0, ScaleBar.niceDistance(396.0), 0.0001)
        assertEquals(500.0, ScaleBar.niceDistance(999.0), 0.0001)
        assertEquals(1000.0, ScaleBar.niceDistance(1000.0), 0.0001)
        assertEquals(200_000.0, ScaleBar.niceDistance(433_000.0), 0.0001)
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
    }
}
