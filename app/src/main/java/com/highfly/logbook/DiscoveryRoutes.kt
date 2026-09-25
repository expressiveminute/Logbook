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
    val airline: String? = null,
    /**
     * Datum des Rückflugs, wenn Hinflug und Rückflug zu einem Hin- und
     * Rückflug zusammengefasst wurden. Der Hinflug steht dann in [date], der
     * Eintrag erscheint nur einmal in der Liste.
     */
    val returnDate: LocalDate? = null
) {
    val isRoundTrip: Boolean get() = returnDate != null
}

/** Alle Neuzugänge eines Monats, neuester Eintrag zuerst. */
data class DiscoveryMonth(
    val month: YearMonth,
    val routes: List<RouteDiscovery>
)

object DiscoveryRoutes {

    /**
     * Anzahl der Tage, innerhalb derer ein Rückflug noch zum Hinflug gehört.
     * Danach werden Hin- und Rückflug als zwei eigene Strecken geführt.
     */
    const val ROUND_TRIP_MAX_DAYS = 7L

    private fun routeKey(from: String, to: String) = "$from-$to"

    /**
     * Ermittelt zu jeder Strecke den frühesten Flug, unabhängig vom Jahr.
     * Die Route ist gerichtet (MUC–DEN und DEN–MUC gelten als zwei Strecken),
     * leere Flughafencodes werden ignoriert. Innerhalb desselben Tages gewinnt
     * der zuerst angelegte Eintrag.
     *
     * Wurde eine Strecke innerhalb von [ROUND_TRIP_MAX_DAYS] Tagen in der
     * Gegenrichtung zurückgeflogen, gelten Hin- und Rückflug als eine
     * Strecke: der Rückflug wandert in [RouteDiscovery.returnDate] und es
     * bleibt ein Eintrag. Gilt der Rückflug als eigener Erstdurchflug einer
     * anderen Strecke, zählt nur der frühere der beiden Flüge als Hinflug.
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
            if (!seen.add(routeKey(from, to))) continue
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
        return mergeRoundTrips(result)
    }

    /**
     * Fasst Hinflug und Rückflug zusammen, wenn der Rückflug der Gegenrichtung
     * innerhalb von [ROUND_TRIP_MAX_DAYS] Tagen folgte. Die Eingabeliste ist
     * nach Datum aufsteigend sortiert, deshalb ist der frühere Flug immer der
     * Hinflug; der Rückflug wird aus der Liste entfernt.
     */
    private fun mergeRoundTrips(discoveries: List<RouteDiscovery>): List<RouteDiscovery> {
        val byRoute = discoveries.associateBy { routeKey(it.from, it.to) }
        val consumed = HashSet<String>()
        val result = mutableListOf<RouteDiscovery>()
        for (outbound in discoveries) {
            if (!consumed.add(routeKey(outbound.from, outbound.to))) continue
            val back = byRoute[routeKey(outbound.to, outbound.from)]
            val gap = back?.let { it.date.toEpochDay() - outbound.date.toEpochDay() }
            if (back != null && gap != null && gap in 0L..ROUND_TRIP_MAX_DAYS) {
                consumed.add(routeKey(back.from, back.to))
                result.add(outbound.copy(returnDate = back.date))
            } else {
                result.add(outbound)
            }
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
