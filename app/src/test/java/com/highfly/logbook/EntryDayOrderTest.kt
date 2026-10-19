package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Die Tagesordnung der Seite "Einträge": Innerhalb eines Tages steht der
 * erste Flug unten, der letzte oben. Ein Layover-Flug ist immer der letzte
 * Flug des Tages (ganz oben); der erste Flug des Tages steht ganz unten - der
 * Rückflug nach einem Layover am Vortag oder, ohne Layover am Vortag, ein
 * Abflug vom meistgenutzten Flughafen. Die Tests spiegeln die Beispiele aus
 * der Aufgabe.
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
        // Drei Flüge an einem Tag: MUC-SOF, SOF-MUC und MUC-LHR (Layover in
        // LHR). Der Layover-Flug MUC-LHR ist der letzte des Tages (oben in der
        // Liste), der Abflug vom meistgenutzten Flughafen MUC-SOF der erste
        // (unten), SOF-MUC steht dazwischen.
        val d = LocalDate.of(2024, 1, 1)
        // So kommt die Liste aus der Datenbank: Datum absteigend, innerhalb
        // eines Tages die spätere Einfügung zuerst.
        val entries = listOf(
            entry(3, d, "MUC", "SOF"),
            entry(2, d, "MUC", "LHR", layover = true),
            entry(1, d, "SOF", "MUC"),
        )

        val ordered = EntryDayOrder.reorder(entries, EntryDayOrder.busiestAirports(entries))

        assertEquals(listOf("MUC-LHR", "SOF-MUC", "MUC-SOF"), routes(ordered))
        assertEquals(setOf("MUC"), EntryDayOrder.busiestAirports(entries))
    }

    @Test
    fun layoverStehtImmerGanzOben() {
        // Auch ein Layover-Flug, der zugleich der Rückflug des Vortags ist,
        // bleibt der letzte Flug des Tages - die Layover-Regel gilt immer.
        val d1 = LocalDate.of(2024, 1, 1)
        val d2 = LocalDate.of(2024, 1, 2)
        val entries = listOf(
            entry(3, d2, "SOF", "MUC", layover = true),
            entry(2, d2, "MUC", "DUS"),
            entry(1, d1, "MUC", "SOF", layover = true),
        )

        val ordered = EntryDayOrder.reorder(entries, setOf("MUC"))

        assertEquals(listOf("SOF-MUC", "MUC-DUS", "MUC-SOF"), routes(ordered))
    }

    @Test
    fun rueckflugNachLayoverAmVortagStehtGanzUnten() {
        // Beispiel der Aufgabe: MUC-SOF als Layover in SOF markiert (letzter
        // Flug des Vortags, oben); am nächsten Tag SOF-MUC in der Datenbank -
        // der erste Flug des neuen Tages (unten).
        val d1 = LocalDate.of(2024, 1, 1)
        val d2 = LocalDate.of(2024, 1, 2)
        val entries = listOf(
            entry(4, d2, "SOF", "MUC"),
            entry(3, d2, "MUC", "DUS"),
            entry(2, d1, "MUC", "SOF", layover = true),
            entry(1, d1, "MUC", "BER"),
        )

        val ordered = EntryDayOrder.reorder(entries, setOf("MUC"))

        assertEquals(listOf("MUC-DUS", "SOF-MUC", "MUC-SOF", "MUC-BER"), routes(ordered))
    }

    @Test
    fun ohneLayoverAmVortagHatDerRueckflugKeineSonderrolle() {
        // Endete der Vortag ohne Layover, steht nicht der Rückflug, sondern
        // ein Abflug vom meistgenutzten Flughafen ganz unten.
        val d1 = LocalDate.of(2024, 1, 1)
        val d2 = LocalDate.of(2024, 1, 2)
        val entries = listOf(
            entry(3, d2, "SOF", "MUC"),
            entry(2, d2, "MUC", "DUS"),
            entry(1, d1, "MUC", "SOF"),
        )

        val ordered = EntryDayOrder.reorder(entries, setOf("MUC"))

        assertEquals(listOf("SOF-MUC", "MUC-DUS", "MUC-SOF"), routes(ordered))
    }

    @Test
    fun abflugVomMeistgenutztenFlughafenStehtUnten() {
        val d = LocalDate.of(2024, 5, 10)
        val entries = listOf(
            entry(2, d, "MUC", "DUS"),
            entry(1, d, "BER", "MUC"),
        )

        val ordered = EntryDayOrder.reorder(entries, setOf("MUC"))

        assertEquals(listOf("BER-MUC", "MUC-DUS"), routes(ordered))
    }

    @Test
    fun keinAbflugVomMeistgenutztenFlughafenWennVortagMitLayoverEndete() {
        // Endete der Vortag mit einem Layover, gilt die Abflug-Regel nicht -
        // ein Abflug vom meistgenutzten Flughafen bleibt in der Reihenfolge.
        val d1 = LocalDate.of(2024, 1, 1)
        val d2 = LocalDate.of(2024, 1, 2)
        val entries = listOf(
            entry(2, d2, "MUC", "DUS"),
            entry(1, d1, "MUC", "SOF", layover = true),
        )

        val ordered = EntryDayOrder.reorder(entries, setOf("MUC"))

        assertEquals(listOf("MUC-DUS", "MUC-SOF"), routes(ordered))
    }

    @Test
    fun layoverSchlaegtAbflugVomMeistgenutztenFlughafen() {
        val d = LocalDate.of(2024, 5, 10)
        val entries = listOf(
            entry(2, d, "MUC", "DUS", layover = true),
            entry(1, d, "BER", "MUC"),
        )

        val ordered = EntryDayOrder.reorder(entries, setOf("MUC"))

        assertEquals(listOf("MUC-DUS", "BER-MUC"), routes(ordered))
    }

    @Test
    fun reihenfolgeBleibtOhneRegelUnveraendert() {
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
    fun zweiTageMitLayoverUndRueckflug() {
        // Zwei Tage, an denen Layover und Rückflug ineinandergreifen: Das
        // Layover gewinnt immer oben, der Rückflug steht erst an zweiter
        // Stelle unten.
        val d1 = LocalDate.of(2024, 1, 1)
        val d2 = LocalDate.of(2024, 1, 2)
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
                "SOF-MUC", "DUS-MUC", "MUC-DUS",
                "MUC-SOF", "BER-MUC", "MUC-BER",
            ),
            routes(ordered)
        )
        assertEquals(setOf("MUC"), EntryDayOrder.busiestAirports(entries))
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
