package com.highfly.logbook

/**
 * Die Auswahl auf der Weltkarte: Welche Verbindungen gehören zu einem
 * angeklickten Flughafen, welche Flughäfen sind damit verbunden.
 *
 * Steht als eigenes Objekt neben dem Fragment, weil sich genau hier zwei
 * Fehler eingeschlichen hatten, die man sonst nur auf dem Gerät sieht: Es
 * zählten nur die Flüge, die vom Flughafen ausgingen, und eine Ankunft galt
 * damit als "nicht verbunden". Bei einem Flughafen, den man nur anfliegt -
 * im Demodatensatz etwa SFO, LAX, WAW, JNB oder PEK - blieb die Menge leer und
 * damit blieb die ganze Karte ausgeblendet.
 */
object MapSelection {

    /**
     * Gehoert [route] zur Auswahl? Ohne Auswahl gehoert jede Verbindung dazu.
     */
    fun touches(route: Pair<String, String>, selected: String?): Boolean =
        selected == null || route.first == selected || route.second == selected

    /**
     * Die Verbindungen, an denen [selected] beteiligt ist - als Abflug und als
     * Ankunft. Ohne Auswahl bleibt alles da.
     */
    fun touches(
        routes: List<Pair<String, String>>,
        selected: String?
    ): List<Pair<String, String>> = routes.filter { touches(it, selected) }

    /**
     * Alle Flughäfen, mit denen [selected] verbunden ist, in beide Richtungen.
     * [selected] selbst ist nicht enthalten; ohne Auswahl ist die Menge leer,
     * weil dann nichts hervorgehoben, sondern alles gleich gezeichnet wird.
     */
    fun connected(
        routes: List<Pair<String, String>>,
        selected: String?
    ): Set<String> {
        if (selected == null) return emptySet()
        return touches(routes, selected).flatMapTo(mutableSetOf()) { (from, to) ->
            when (selected) {
                from -> listOf(to)
                to -> listOf(from)
                else -> emptyList()
            }
        }
    }
}