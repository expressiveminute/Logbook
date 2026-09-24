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
