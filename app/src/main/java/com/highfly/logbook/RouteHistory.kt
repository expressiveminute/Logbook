package com.highfly.logbook

data class Route(
    val from: String,
    val to: String
)

/**
 * Liefert die Strecke (Abflug -> Ankunft), die für eine Fluggesellschaft mit
 * Flugnummer in den vorhandenen Einträgen am häufigsten gespeichert wurde.
 * Bei gleich oft gespeicherten Strecken gewinnt die zuletzt geflogene.
 * Gibt null zurück, wenn kein passender Eintrag existiert.
 */
fun mostFrequentRoute(
    entries: List<LogbookEntry>,
    airline: String,
    flightNumber: String
): Route? {
    val normalizedNumber = normalizeFlightNumber(flightNumber)
    if (airline.isBlank() || flightNumber.isBlank() || normalizedNumber.isEmpty()) return null

    val matches = entries.filter { entry ->
        entry.airline?.equals(airline.trim(), ignoreCase = true) == true &&
            normalizeFlightNumber(entry.flightNumber ?: "") == normalizedNumber &&
            entry.fromAirport.isNotBlank() && entry.toAirport.isNotBlank()
    }
    if (matches.isEmpty()) return null

    val routesByCount = matches.groupBy { it.fromAirport to it.toAirport }
    val best = routesByCount.entries.maxWithOrNull(
        compareBy<Map.Entry<Pair<String, String>, List<LogbookEntry>>> { it.value.size }
            .thenComparator { a, b ->
                val lastA = a.value.maxOfOrNull { it.date }?.toEpochDay() ?: Long.MIN_VALUE
                val lastB = b.value.maxOfOrNull { it.date }?.toEpochDay() ?: Long.MIN_VALUE
                lastA.compareTo(lastB)
            }
    ) ?: return null

    return Route(from = best.key.first, to = best.key.second)
}

/**
 * Vergleicht Flugnummern unabhängig von führenden Nullen, damit "0480" und
 * "480" als derselbe Flug erkannt werden.
 */
fun normalizeFlightNumber(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.trimStart('0').ifEmpty { "0" }
}

/**
 * Was der Routenvorschlag mit Abflug und Ankunft tun soll.
 */
sealed class RoutePrefillAction {
    /**
     * Die Felder bleiben, wie sie sind: Dort steht eine eigene Eingabe oder
     * schon genau die Strecke, die auch jetzt herauskommt.
     */
    object Keep : RoutePrefillAction()

    /**
     * Die Flugnummer hat keinen Treffer mehr – die zuletzt selbst eingesetzte
     * Strecke wird wieder entfernt.
     */
    object Clear : RoutePrefillAction()

    /**
     * Die Flugnummer hat exakt getroffen – die Felder bekommen diese Strecke.
     */
    data class Fill(val route: Route) : RoutePrefillAction()
}

/**
 * Entscheidet, was der Routenvorschlag beim Tippen der Flugnummer tun soll.
 * [current] ist der heutige Inhalt von Abflug und Ankunft, [suggested] die
 * zuletzt selbst eingesetzte Strecke. Nur diese darf auch wieder verschwinden,
 * eigene Eingaben bleiben unangetastet. Sonst gilt immer: Nur eine Flugnummer,
 * die in den Einträgen exakt so gespeichert ist, führt ihre Strecke ein – ein
 * Vorschlag, der beim Weiter-tippen nicht mehr trifft („LH 16" wird zu
 * „LH 1672"), räumt wieder auf.
 */
fun routePrefillAction(
    entries: List<LogbookEntry>,
    airline: String,
    flightNumber: String,
    current: Route?,
    suggested: Route?
): RoutePrefillAction {
    if (current != null && current != suggested) return RoutePrefillAction.Keep
    val match = mostFrequentRoute(entries, airline, flightNumber)
    return when {
        match == null -> if (current != null) RoutePrefillAction.Clear else RoutePrefillAction.Keep
        match == current -> RoutePrefillAction.Keep
        else -> RoutePrefillAction.Fill(match)
    }
}
