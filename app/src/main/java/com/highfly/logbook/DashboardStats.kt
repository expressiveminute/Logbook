package com.highfly.logbook

import android.content.Context
import java.time.LocalDate
import java.util.Locale

object DashboardStats {

    data class Value(val text: String, val unit: String?)

    data class DistanceDetails(
        val totalKm: Int,
        val avgKm: Int?,
        val furthestRoute: String?,
        val furthestKm: Int?,
        val shortestRoute: String?,
        val shortestKm: Int?,
        val earthOrbits: Double,
        val moonTrips: Double
    )

    fun filterForPeriod(entries: List<LogbookEntry>, key: String): List<LogbookEntry> {
        val range = when (key) {
            PeriodOptions.KEY_ALL -> null
            PeriodOptions.KEY_THIS_MONTH -> {
                val today = LocalDate.now()
                today.withDayOfMonth(1) to today.withDayOfMonth(1).plusMonths(1)
            }
            PeriodOptions.KEY_YTD -> {
                val today = LocalDate.now()
                LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year + 1, 1, 1)
            }
            else -> {
                val year = key.toIntOrNull() ?: return entries
                LocalDate.of(year, 1, 1) to LocalDate.of(year + 1, 1, 1)
            }
        }
        return if (range == null) {
            entries
        } else {
            entries.filter { it.date >= range.first && it.date < range.second }
        }
    }

    private val earthCircumferenceKm = 40075.0
    private val moonDistanceKm = 384400.0

    fun distanceDetails(context: Context, entries: List<LogbookEntry>): DistanceDetails {
        val withDistance = entries.filter { it.distanceKm != null }
        val totalKm = withDistance.sumOf { it.distanceKm ?: 0 }
        val distances = withDistance.mapNotNull { it.distanceKm }
        val avgKm = if (distances.isEmpty()) null else
            Math.round(totalKm.toDouble() / distances.size).toInt()
        val furthest = distances.maxOrNull()
        val shortest = distances.minOrNull()
        val earthOrbits = totalKm / earthCircumferenceKm
        val moonFlights = totalKm / moonDistanceKm
        return DistanceDetails(
            totalKm = totalKm,
            avgKm = avgKm,
            furthestRoute = furthest?.let { km -> routeOf(withDistance, km) },
            furthestKm = furthest,
            shortestRoute = shortest?.let { km -> routeOf(withDistance, km) },
            shortestKm = shortest,
            earthOrbits = earthOrbits,
            moonTrips = moonFlights
        )
    }

    private fun routeOf(list: List<LogbookEntry>, km: Int): String {
        val entry = list.first { it.distanceKm == km }
        return "${entry.fromAirport.uppercase()}–${entry.toAirport.uppercase()}"
    }

    fun kmText(context: Context, km: Int): String =
        formatInt(context, km) + " " + context.getString(R.string.flight_distance_hint)

    fun factorText(value: Double): String =
        formatFactor(value)

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
        val earthOrbits = distanceKm / earthCircumferenceKm
        val moonFlights = distanceKm / moonDistanceKm

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
            "earthorbits" to Value(formatFactor(earthOrbits), "×"),
            "moon" to Value(formatFactor(moonFlights), "×"),
        )
    }

    private fun formatFactor(value: Double): String =
        String.format(Locale.GERMANY, "%,.3f", value)

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

    enum class TimeUnit { HOURS, DAYS, MONTHS, YEARS }

    fun timeValue(context: Context, minutes: Int, unit: TimeUnit): Value {
        val days = minutes / 60.0 / 24.0
        return when (unit) {
            TimeUnit.HOURS -> if (minutes < 60) {
                Value(minutes.toString(), "min")
            } else {
                Value(
                    String.format(Locale.GERMANY, "%,.1f", minutes / 60.0),
                    context.getString(R.string.tile_time_unit_h)
                )
            }
            TimeUnit.DAYS -> Value(
                String.format(Locale.GERMANY, "%,.1f", days),
                context.getString(R.string.tile_time_unit_d)
            )
            TimeUnit.MONTHS -> Value(
                String.format(Locale.GERMANY, "%,.2f", days / 30.4375),
                context.getString(R.string.tile_time_unit_mo)
            )
            TimeUnit.YEARS -> Value(
                String.format(Locale.GERMANY, "%,.2f", days / 365.25),
                context.getString(R.string.tile_time_unit_y)
            )
        }
    }
}