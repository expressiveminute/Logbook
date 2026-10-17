package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Die Tagesordnung der Seite "Einträge": Layover oben, Rückflüge und Flüge ab
 * dem Heimatflughafen unten. Die Tests spiegeln das Beispiel aus der Aufgabe.
 */
class EntryDayOrderTest {

    private fun entry(
        id: Long,
        date: LocalDate,
        from: String,
        to: String,
        layover: Boolean = false,
    ) = LogbookEntry(id = id, date = date, fromAirport = from, toAirport = to, layover = layover)

    private fun routes(entries: List<LogbookEntry>): List<String> =
        entries.map { "${it.fromAirport}-${it.toAirport}" }

    @Test
    fun beispielAusDerAufgabe() {
        val d1 = LocalDate.of(2024, 1, 1)
        val d2 = LocalDate.of(2024, 1, 2)
        // So kommt die Liste aus der Datenbank: Datum absteigend, innerhalb
        // eines Tages die spätere Einfügung zuerst.
        val entries = listOf(
            entry(6, d2, "SOF", "MUC", layover = true),
            entry(5, d2, "DUS", "MUC"),
            entry(4, d2, "MUC", "DUS"),
            entry(3, d1, "MUC", "SOF", layover = true),
            entry(2, d1, "BER", "MUC"),
            entry(1, d1, "MUC", "BER"),
        )

        val ordered = EntryDayOrder.reorder(entries, EntryDayOrder.busiestAirports(entries))

        assertEquals(
            listOf(
                "DUS-MUC", "MUC-DUS", "SOF-MUC",
                "MUC-SOF", "BER-MUC", "MUC-BER",
            ),
            routes(ordered)
        )
        assertEquals(setOf("MUC"), EntryDayOrder.busiestAirports(entries))
    }

    @Test
    fun layoverStehtGanzOben() {
        val d = LocalDate.of(2024, 5, 10)
        val entries = listOf(
            entry(3, d, "BER", "MUC"),
            entry(2, d, "MUC", "SOF", layover = true),
            entry(1, d, "HAM", "MUC"),
        )

        val ordered = EntryDayOrder.reorder(entries, setOf("MUC"))

        assertEquals(listOf("MUC-SOF", "BER-MUC", "HAM-MUC"), routes(ordered))
    }

    @Test
    fun rueckflugDesVortagsStehtGanzUnten() {
        val d1 = LocalDate.of(2024, 1, 1)
        val d2 = LocalDate.of(2024, 1, 2)
        val entries = listOf(
            entry(3, d2, "SOF", "MUC"),
            entry(2, d2, "MUC", "DUS"),
            entry(1, d1, "MUC", "SOF"),
        )

        val ordered = EntryDayOrder.reorder(entries, setOf("MUC"))

        assertEquals(listOf("MUC-DUS", "SOF-MUC", "MUC-SOF"), routes(ordered))
    }

    @Test
    fun abflugVomHeimatflughafenStehtUnten() {
        val d = LocalDate.of(2024, 5, 10)
        val entries = listOf(
            entry(2, d, "MUC", "DUS"),
            entry(1, d, "BER", "MUC"),
        )

        val ordered = EntryDayOrder.reorder(entries, setOf("MUC"))

        assertEquals(listOf("BER-MUC", "MUC-DUS"), routes(ordered))
    }

    @Test
    fun layoverSchlaegtHeimatflughafen() {
        val d = LocalDate.of(2024, 5, 10)
        val entries = listOf(
            entry(2, d, "MUC", "DUS", layover = true),
            entry(1, d, "BER", "MUC"),
        )

        val ordered = EntryDayOrder.reorder(entries, setOf("MUC"))

        assertEquals(listOf("MUC-DUS", "BER-MUC"), routes(ordered))
    }

    @Test
    fun umkehrungSchlaegtLayover() {
        val d1 = LocalDate.of(2024, 1, 1)
        val d2 = LocalDate.of(2024, 1, 2)
        val entries = listOf(
            entry(3, d2, "SOF", "MUC", layover = true),
            entry(2, d2, "MUC", "DUS"),
            entry(1, d1, "MUC", "SOF"),
        )

        val ordered = EntryDayOrder.reorder(entries, setOf("MUC"))

        assertEquals(listOf("MUC-DUS", "SOF-MUC", "MUC-SOF"), routes(ordered))
    }

    @Test
    fun reihenfolgeBleibtOhneRegelUngveraendert() {
        val d = LocalDate.of(2024, 5, 10)
        val entries = listOf(
            entry(3, d, "BER", "HAM"),
            entry(2, d, "HAM", "BER"),
            entry(1, d, "BER", "FRA"),
        )

        val ordered = EntryDayOrder.reorder(entries, emptySet())

        assertEquals(listOf("BER-HAM", "HAM-BER", "BER-FRA"), routes(ordered))
    }

    @Test
    fun busiestAirports_zaehltStartsUndLandungen() {
        val d = LocalDate.of(2024, 5, 10)
        val entries = listOf(
            entry(3, d, "FRA", "JFK"),
            entry(2, d, "JFK", "FRA"),
            entry(1, d, "FRA", "LHR"),
        )

        assertEquals(setOf("FRA"), EntryDayOrder.busiestAirports(entries))
    }

    @Test
    fun busiestAirports_leerOhneEintraege() {
        assertEquals(emptySet<String>(), EntryDayOrder.busiestAirports(emptyList()))
    }
}
