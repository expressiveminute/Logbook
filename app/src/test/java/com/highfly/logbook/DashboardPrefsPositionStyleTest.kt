package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardPrefsPositionStyleTest {

    private fun style(row: Int, column: Int, accent: String) =
        DashboardPrefs.positionStyle(DashboardPrefs.TilePosition(row, column), accent)

    @Test
    fun positionStyle_coversFirstThreeRowsAndColumns() {
        for (row in 0 until DashboardPrefs.COLORED_ROWS) {
            for (column in 0 until DashboardPrefs.COLORED_COLUMNS) {
                for (accent in Settings.ACCENT_OPTIONS) {
                    assertNotNull(
                        "Position ${row + 1}/${column + 1} ohne Farbe ($accent)",
                        style(row, column, accent)
                    )
                }
            }
        }
    }

    @Test
    fun positionStyle_usesAccentSpecificColorsForNinthPosition() {
        assertEquals(R.color.dashboard_brown_pos9, style(2, 2, Settings.ACCENT_BROWN)?.backgroundColorRes)
        assertEquals(R.color.dashboard_magenta_pos9, style(2, 2, Settings.ACCENT_MAGENTA)?.backgroundColorRes)
        assertEquals(R.color.dashboard_turquoise_pos9, style(2, 2, Settings.ACCENT_TURQUOISE)?.backgroundColorRes)
    }

    @Test
    fun positionStyle_usesWhiteTextAndIconColor() {
        for (accent in Settings.ACCENT_OPTIONS) {
            val style = style(0, 0, accent)!!
            assertEquals(R.color.white, style.contentColorRes)
            assertEquals(R.color.white, style.strokeColorRes)
            assertEquals(0, style.strokeWidthDp)
        }
    }

    @Test
    fun positionStyle_returnsNullOutsideColoredGrid() {
        assertNull(style(DashboardPrefs.COLORED_ROWS, 0, Settings.ACCENT_BROWN))
        assertNull(style(0, DashboardPrefs.COLORED_COLUMNS, Settings.ACCENT_BROWN))
    }

    @Test
    fun positionStyle_usesAccentSpecificColors() {
        assertEquals(
            R.color.dashboard_brown_pos1,
            style(0, 0, Settings.ACCENT_BROWN)?.backgroundColorRes
        )
        assertEquals(
            R.color.dashboard_magenta_pos5,
            style(1, 1, Settings.ACCENT_MAGENTA)?.backgroundColorRes
        )
        assertEquals(
            R.color.dashboard_turquoise_pos8,
            style(2, 1, Settings.ACCENT_TURQUOISE)?.backgroundColorRes
        )
        assertEquals(
            R.color.dashboard_magenta_pos6,
            style(1, 2, Settings.ACCENT_MAGENTA)?.backgroundColorRes
        )
        assertEquals(
            R.color.dashboard_magenta_pos1,
            style(0, 0, Settings.ACCENT_MAGENTA)?.backgroundColorRes
        )
    }
}
