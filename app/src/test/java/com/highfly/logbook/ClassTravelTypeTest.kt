package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassTravelTypeTest {

    @Test
    fun standardIstPrivatUndUnbekanntesFaelltZurueck() {
        assertEquals("Privat", ClassTravelType.DEFAULT)
        assertEquals("Privat", ClassTravelType.normalize(null))
        assertEquals("Privat", ClassTravelType.normalize("Unbekannt"))
    }

    @Test
    fun jedeKanonischeReiseartBleibtErhalten() {
        assertEquals(6, ChartData.TRAVEL_TYPE_CANONICALS.size)
        ChartData.TRAVEL_TYPE_CANONICALS.forEach { type ->
            assertEquals(type, ClassTravelType.normalize(type))
        }
    }

    @Test
    fun obersteZeileFuehrtVierReisearten() {
        assertEquals(
            listOf("Privat", "On Duty", "Deadhead", "Dienstreise"),
            ClassTravelType.TOP_LEVEL
        )
    }

    @Test
    fun ferryUndGroundTransferLiegenUnterDeadhead() {
        assertEquals(0, ClassTravelType.topIndexOf("Privat"))
        assertEquals(1, ClassTravelType.topIndexOf("On Duty"))
        assertEquals(2, ClassTravelType.topIndexOf("Deadhead"))
        assertEquals(2, ClassTravelType.topIndexOf("Ferry"))
        assertEquals(2, ClassTravelType.topIndexOf("Ground Transfer"))
        assertEquals(3, ClassTravelType.topIndexOf("Dienstreise"))
    }

    @Test
    fun unterzeileTrenntDeadheadFerryUndGroundTransfer() {
        assertEquals(0, ClassTravelType.detailIndexOf("Deadhead"))
        assertEquals(1, ClassTravelType.detailIndexOf("Ferry"))
        assertEquals(2, ClassTravelType.detailIndexOf("Ground Transfer"))
        assertEquals(-1, ClassTravelType.detailIndexOf("Privat"))
    }
}
