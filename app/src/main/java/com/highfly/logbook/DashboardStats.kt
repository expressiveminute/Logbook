package com.highfly.logbook

import android.content.Context
import java.time.LocalDate
import java.util.Locale

object DashboardStats {

    /** Canonical flight type of a ground transfer; these are not counted as
     * "distance" (they do not involve flying). */
    private const val GROUND_TRANSFER = "Ground Transfer"
    private const val WORLD_COUNTRIES = 195

    private fun isDistanceRelevant(entry: LogbookEntry): Boolean =
        ChartData.normalizeFlightType(entry.flightType) != GROUND_TRANSFER

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
        val withDistance = entries.filter { isDistanceRelevant(it) && it.distanceKm != null }
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
        val flights = formatInt(context, entries.size)
        val distanceEntries = entries.filter { isDistanceRelevant(it) }
        val distanceKm = distanceEntries.sumOf { it.distanceKm ?: 0 }
        val minutes = entries.sumOf { it.flightMinutes ?: 0 }
        val routes = entries.map { "${it.fromAirport}-${it.toAirport}" }.distinct().size
        val airlines = nonBlankCount(entries) { it.airline }
        val layovers = entries.count { it.layover }.toString()
        val classes = nonBlankCount(entries) { it.classType }
        val travelTypes = nonBlankCount(entries) { it.flightType }
        val aircraftTypes = nonBlankCount(entries) { it.aircraftType }
        val countries = (entries.mapNotNull { it.toCountry }
            .filter { it.isNotBlank() }).distinct().size
        val earthOrbits = distanceKm / earthCircumferenceKm
        val moonFlights = distanceKm / moonDistanceKm

        return mapOf(
            "flights" to Value(flights, null),
            "distance" to Value(formatInt(context, distanceKm), context.getString(R.string.flight_distance_hint)),
            "time" to formatTime(context, minutes),
            "routes" to Value(formatInt(context, routes), null),
            "airlines" to Value(formatInt(context, airlines), null),
            "layover" to Value(layovers, null),
            "class" to Value(formatInt(context, classes), null),
            "traveltype" to Value(formatInt(context, travelTypes), null),
            "aircraftreg" to Value(formatInt(context, aircraftTypes), null),
            "countries" to Value(formatInt(context, countries),
                String.format(Locale.GERMANY, "(%s%%)",
                    formatInt(context, (countries * 100.0 / WORLD_COUNTRIES).toInt()))),
            "earthorbits" to Value(formatFactor(earthOrbits), "×"),
            "moon" to Value(formatFactor(moonFlights), "×"),
        )
    }

    fun tileCount(tileId: String, entries: List<LogbookEntry>): Int = when (tileId) {
        "flights" -> entries.size
        "routes" -> entries.map { "${it.fromAirport}-${it.toAirport}" }.distinct().size
        "airlines" -> nonBlankCount(entries) { it.airline }
        "aircraftreg" -> nonBlankCount(entries) { it.aircraftType }
        "countries" -> (entries.mapNotNull { it.toCountry }
            .filter { it.isNotBlank() }).distinct().size
        else -> -1
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