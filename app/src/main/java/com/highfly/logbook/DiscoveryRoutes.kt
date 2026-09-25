package com.highfly.logbook

import java.time.LocalDate
import java.time.YearMonth

/** Eine Flugstrecke, die zum ersten Mal geflogen wurde. */
data class RouteDiscovery(
    val from: String,
    val to: String,
    val date: LocalDate,
    val aircraftType: String? = null,
    val registration: String? = null,
    val airline: String? = null
)

/** Alle Neuzugänge eines Monats, neuester Eintrag zuerst. */
data class DiscoveryMonth(
    val month: YearMonth,
    val routes: List<RouteDiscovery>
)

object DiscoveryRoutes {

    /**
     * Ermittelt zu jeder Strecke den frühesten Flug, unabhängig vom Jahr.
     * Die Route ist gerichtet (MUC–DEN und DEN–MUC gelten als zwei Strecken),
     * leere Flughafencodes werden ignoriert. Innerhalb desselben Tages gewinnt
     * der zuerst angelegte Eintrag.
     */
    fun firstFlown(entries: List<LogbookEntry>): List<RouteDiscovery> {
        val seen = HashSet<String>()
        val result = mutableListOf<RouteDiscovery>()
        for (entry in entries.sortedWith(
            compareBy<LogbookEntry> { it.date }.thenBy { it.id ?: Long.MAX_VALUE }
        )) {
            val from = entry.fromAirport.trim().uppercase()
            val to = entry.toAirport.trim().uppercase()
            if (from.isEmpty() || to.isEmpty()) continue
            if (!seen.add("$from-$to")) continue
            result.add(
                RouteDiscovery(
                    from = from,
                    to = to,
                    date = entry.date,
                    aircraftType = entry.aircraftType?.trim()?.takeIf { it.isNotEmpty() },
                    registration = entry.registration?.trim()?.takeIf { it.isNotEmpty() },
                    airline = entry.airline?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }
        return result
    }

    /** Alle Strecken, die im angegebenen Jahr zum ersten Mal geflogen wurden. */
    fun firstFlownInYear(entries: List<LogbookEntry>, year: Int): List<RouteDiscovery> =
        firstFlown(entries)
            .filter { it.date.year == year }
            .sortedWith(compareByDescending<RouteDiscovery> { it.date }.thenBy { it.from })

    /**
     * Gruppiert die Strecken nach Monat: neuester Monat zuerst, innerhalb des
     * Monats datumabsteigend (neuester Eintrag oben).
     */
    fun byMonth(discoveries: List<RouteDiscovery>): List<DiscoveryMonth> =
        discoveries.groupBy { YearMonth.from(it.date) }
            .map { (month, routes) ->
                DiscoveryMonth(
                    month,
                    routes.sortedWith(
                        compareByDescending<RouteDiscovery> { it.date }.thenBy { it.from }
                    )
                )
            }
            .sortedByDescending { it.month }

    fun byMonth(entries: List<LogbookEntry>, year: Int): List<DiscoveryMonth> =
        byMonth(firstFlownInYear(entries, year))
}
