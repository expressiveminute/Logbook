package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Die Homebase ist der meistgenutzte Flughafen des Logbuchs: jeder Start
 * und jede Landung zählt als eine Bewegung. Bei Gleichstand gewinnt der
 * alphabetisch erste Code.
 */
class HomebaseTest {

    private fun entry(
        id: Long,
        from: String,
        to: String,
    ) = LogbookEntry(
        id = id,
        date = LocalDate.of(2024, 1, 1),
        fromAirport = from,
        toAirport = to
    )

    @Test
    fun meistgenutzterFlughafenGewinnt() {
        // FRA: 2 Starts + 2 Landungen = 4 Bewegungen, MUC: 1 Start + 1
        // Landung = 2 Bewegungen.
        val entries = listOf(
            entry(1, "FRA", "MUC"),
            entry(2, "MUC", "FRA"),
            entry(3, "FRA", "LHR"),
            entry(4, "LHR", "FRA")
        )
        assertEquals("FRA", Homebase.homebaseOf(entries))
    }

    @Test
    fun startUndLandungZaehlenBeide() {
        // Starts allein: JFK, FRA und LHR je 1 (Gleichstand, FRA würde
        // alphabetisch gewinnen). Die Landungen machen JFK mit 3 Bewegungen
        // zur Homebase.
        val entries = listOf(
            entry(1, "JFK", "FRA"),
            entry(2, "FRA", "JFK"),
            entry(3, "LHR", "JFK")
        )
        assertEquals("JFK", Homebase.homebaseOf(entries))
    }

    @Test
    fun gleichstandAlphabetisch() {
        val entries = listOf(
            entry(1, "MUC", "LHR"),
            entry(2, "FRA", "JFK")
        )
        assertEquals("FRA", Homebase.homebaseOf(entries))
    }

    @Test
    fun trimUndGrossKlein() {
        val entries = listOf(
            entry(1, " fra ", "JFK"),
            entry(2, "FRA", "JFK"),
            entry(3, "JFK", "fra")
        )
        assertEquals("FRA", Homebase.homebaseOf(entries))
    }

    @Test
    fun leereListeIstNull() {
        assertNull(Homebase.homebaseOf(emptyList()))
    }

    @Test
    fun ohneFlughafenIstNull() {
        val entries = listOf(entry(1, "", "  "))
        assertNull(Homebase.homebaseOf(entries))
    }

    @Test
    fun updateMerktSichDieHomebase() {
        Homebase.update(listOf(entry(1, "MUC", "FRA"), entry(2, "FRA", "MUC")))
        // MUC und FRA haben je 2 Bewegungen, FRA gewinnt alphabetisch.
        assertEquals("FRA", Homebase.current)
        Homebase.update(emptyList())
        assertNull(Homebase.current)
    }
}
