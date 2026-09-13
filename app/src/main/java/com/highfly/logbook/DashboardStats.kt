package com.highfly.logbook

import android.content.Context
import java.time.LocalDate
import java.util.Locale

object DashboardStats {

    data class Value(val text: String, val unit: String?)

    fun periodStart(key: String): LocalDate? {
        val today = LocalDate.now()
        return when (key) {
            PeriodOptions.KEY_THIS_MONTH -> today.withDayOfMonth(1)
            PeriodOptions.KEY_YTD -> LocalDate.of(today.year, 1, 1)
            "all" -> null
            else -> key.toIntOrNull()?.let { LocalDate.of(it, 1, 1) } ?: null
        }
    }

    fun filterForPeriod(entries: List<LogbookEntry>, key: String): List<LogbookEntry> {
        val start = periodStart(key)
        return if (start == null) entries else entries.filter { !it.date.isBefore(start) }
    }

    fun values(context: Context, entries: List<LogbookEntry>): Map<String, Value> {
        val flights = entries.size.toString()
        val distanceKm = entries.sumOf { it.distanceKm ?: 0 }
        val minutes = entries.sumOf { it.flightMinutes ?: 0 }
        val routes = entries.map { "${it.fromAirport}-${it.toAirport}" }.distinct().size
        val airports = ((entries.map { it.fromAirport } + entries.map { it.toAirport })
            .filter { it.isNotBlank() }).distinct().size
        val airlines = nonBlankCount(entries) { it.airline }
        val layovers = entries.count { it.layover }.toString()
        val classes = nonBlankCount(entries) { it.classType }
        val travelTypes = nonBlankCount(entries) { it.flightType }
        val aircraftTypes = nonBlankCount(entries) { it.aircraftType }
        val registrations = nonBlankCount(entries) { it.registration }

        return mapOf(
            "flights" to Value(flights, null),
            "distance" to Value(formatInt(context, distanceKm), context.getString(R.string.flight_distance_hint)),
            "time" to formatTime(context, minutes),
            "routes" to Value(formatInt(context, routes), null),
            "airports" to Value(formatInt(context, airports), null),
            "airlines" to Value(formatInt(context, airlines), null),
            "layover" to Value(layovers, null),
            "class" to Value(formatInt(context, classes), null),
            "traveltype" to Value(formatInt(context, travelTypes), null),
            "aircraft" to Value(formatInt(context, aircraftTypes), null),
            "registration" to Value(formatInt(context, registrations), null),
        )
    }

    private fun nonBlankCount(
        entries: List<LogbookEntry>,
        selector: (LogbookEntry) -> String?
    ): Int = entries.mapNotNull { selector(it)?.takeIf { s -> s.isNotBlank() } }.distinct().size

    private fun formatInt(context: Context, value: Int): String =
        String.format(Locale.GERMANY, "%,d", value)

    private fun formatTime(context: Context, minutes: Int): Value {
        if (minutes < 60) {
            return Value(minutes.toString(), "min")
        }
        val hours = minutes / 60.0
        return Value(String.format(Locale.GERMANY, "%,.1f", hours), "h")
    }
}