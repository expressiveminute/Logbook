package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardPrefsPackRowsTest {

    private val defaultSpans: Map<String, Int> =
        DashboardPrefs.CATALOG.associate { it.id to DashboardPrefs.defaultSpan(it.id) }

    @Test
    fun packRows_reproducesDefaultLayoutWithDefaultSpans() {
        val packed = DashboardPrefs.packRows(DashboardPrefs.DEFAULT_ROWS.flatten(), defaultSpans)
        assertEquals(DashboardPrefs.DEFAULT_ROWS, packed)
    }

    @Test
    fun packRows_placesTwoSpanTileNextToOneSpanTile() {
        val spans = mapOf("flights" to 2, "layover" to 1)
        val packed = DashboardPrefs.packRows(listOf("flights", "layover"), spans)
        assertEquals(listOf(listOf("flights", "layover")), packed)
    }

    @Test
    fun packRows_wrapsTileThatDoesNotFitRemainingColumns() {
        // Drei Spalten sind voll, "worldmap" (volle Breite) beginnt eine neue Zeile.
        val packed = DashboardPrefs.packRows(
            listOf("flights", "layover", "aircraftreg", "worldmap"),
            defaultSpans
        )
        assertEquals(
            listOf(
                listOf("flights", "layover", "aircraftreg"),
                listOf("worldmap")
            ),
            packed
        )
    }

    @Test
    fun packRows_wrapsTwoSpanTileAfterSingleColumnTile() {
        // 1 + 2 passt genau; die nächste 1-Spalten-Kachel beginnt darunter.
        val spans = mapOf("flights" to 1, "layover" to 2, "aircraftreg" to 1)
        val packed = DashboardPrefs.packRows(listOf("flights", "layover", "aircraftreg"), spans)
        assertEquals(
            listOf(listOf("flights", "layover"), listOf("aircraftreg")),
            packed
        )
    }

    @Test
    fun packRows_ignoresUnknownIds() {
        val packed = DashboardPrefs.packRows(listOf("flights", "does_not_exist"), defaultSpans)
        assertEquals(listOf(listOf("flights")), packed)
    }

    @Test
    fun packRows_ofEmptyListIsEmpty() {
        assertEquals(emptyList<List<String>>(), DashboardPrefs.packRows(emptyList(), defaultSpans))
    }
}
