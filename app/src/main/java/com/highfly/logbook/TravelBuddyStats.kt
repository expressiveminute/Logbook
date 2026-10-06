package com.highfly.logbook

import java.text.Collator
import java.time.LocalDate

/**
 * Auswertung des Feldes "Reisebuddy": mit wem wie oft geflogen wurde.
 *
 * Das Feld im Formular ist Freitext - der Nutzer schreibt die Namen so, wie er
 * sie kennt, mehrere durch Komma. Erst hier wird daraus eine Liste. Ohne
 * Android-Abhängigkeiten, damit Zählung und Sortierung im Test ohne
 * Datenbankzugriff geprüft werden können.
 *
 * Gezählt wird je Flug: Wer auf demselben Flug zweimal im Text steht, zählt
 * einmal. Ein Flug mit zwei Buddies erhöht beide um eins - die Summe der
 * Zähler kann deshalb größer sein als die Zahl der Flüge.
 */
object TravelBuddyStats {

    /**
     * @param name Anzeigename, so wie ihn zuerst jemand geschrieben hat
     * @param flights Flüge, auf denen dieser Buddy mitkam
     * @param lastDate jüngstes Flugdatum mit diesem Buddy, entscheidet bei
     *   gleicher Zahl die Reihenfolge
     */
    data class Buddy(
        val name: String,
        val flights: Int,
        val lastDate: LocalDate?
    )

    private val WHITESPACE = Regex("\\s+")

    /**
     * Zerlegt den Freitext eines Eintrags in Namen. Komma, Semikolon und
     * Zeilenumbruch trennen, weil beim Tippen alle drei in der Hand sind.
     * Leere Stücke fallen weg, mehrerer Leerraum wird zu einem, und
     * Groß-/Kleinschreibung zählt als derselbe Name.
     */
    fun parse(raw: String?): List<String> =
        raw.orEmpty()
            .split(',', ';', '\n')
            .map { it.trim().replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    /**
     * Trifft der Filter [filter] den Reisebuddy eines Eintrags? Wie bei den
     * anderen Feldern eine Teilangabe, aber je Name: "Anna" trifft auch einen
     * Flug, auf dem "Anna, Ben" steht, und ein Filter, der über den Trenner
     * hinaus passt, kann nicht zufällig treffen.
     */
    fun matches(raw: String?, filter: String): Boolean {
        val wanted = filter.trim()
        if (wanted.isEmpty()) return true
        return parse(raw).any { it.lowercase().contains(wanted.lowercase()) }
    }

    /**
     * Zählt die Flüge je Reisebuddy, absteigend nach Zahl. Bei gleicher Zahl
     * gewinnt der zuletzt geflogene Buddy, danach entscheidet [collator] über
     * die Reihenfolge - die Liste soll in jeder Sprache aufsteigend lesen.
     *
     * Anzeigename ist die Schreibweise des ersten Eintrags, in dem der Name
     * vorkam: "anna" im Januar und "Anna" im Juli ergeben eine Zeile "Anna".
     */
    fun build(
        entries: List<LogbookEntry>,
        collator: Collator = Collator.getInstance()
    ): List<Buddy> {
        val anzeige = HashMap<String, String>()
        val counts = HashMap<String, Int>()
        val lastDate = HashMap<String, LocalDate>()
        for (entry in entries) {
            // [parse] liefert ohne Duplikate, "Anna, anna" zählt nur einmal.
            for (name in parse(entry.travelBuddy)) {
                val key = name.lowercase()
                anzeige.putIfAbsent(key, name)
                counts[key] = (counts[key] ?: 0) + 1
                val bisher = lastDate[key]
                if (bisher == null || entry.date > bisher) lastDate[key] = entry.date
            }
        }
        return counts.map { (key, anzahl) ->
            Buddy(
                name = anzeige.getValue(key),
                flights = anzahl,
                lastDate = lastDate[key]
            )
        }.sortedWith(
            compareByDescending<Buddy> { it.flights }
                .thenByDescending { it.lastDate }
                .thenBy(collator) { it.name }
        )
    }

    /**
     * Flüge, auf denen überhaupt ein Reisebuddy eingetragen ist. Das ist die
     * Zahl in der Kachel und in der Überschrift der Seite.
     */
    fun flightsWithBuddy(entries: List<LogbookEntry>): Int =
        entries.count { parse(it.travelBuddy).isNotEmpty() }
}
