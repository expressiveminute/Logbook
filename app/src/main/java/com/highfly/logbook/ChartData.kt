package com.highfly.logbook

import android.content.Context
import java.time.format.DateTimeFormatter
import java.util.Locale

object ChartData {

    data class Bar(val label: String, val count: Int)

    data class Slice(val label: String, val value: Int, val colorRes: Int)

    private val BAR_CHART_TILES = setOf(
        "flights", "routes", "airports", "airlines", "layover", "aircraft", "registration"
    )
    private val PIE_CHART_TILES = setOf("class", "traveltype")

    fun isBarChart(tileId: String): Boolean = tileId in BAR_CHART_TILES

    fun isPieChart(tileId: String): Boolean = tileId in PIE_CHART_TILES

    fun barChart(context: Context, tileId: String): List<Bar> {
        if (!isBarChart(tileId)) return emptyList()
        val key = Settings.getDefaultPeriodKey(context)
        val entries = DashboardStats.filterForPeriod(
            LogbookRepository.getEntries(),
            key
        )
        val counts: Map<String, Int> = when (tileId) {
            "flights" -> flightsPerPeriod(entries, key)
            "layover" -> countBy(entries.filter { it.layover }) { routeOf(it) }
            "routes" -> countBy(entries) { routeOf(it) }
            "airlines" -> countBy(entries) { it.airline }
            "airports" -> countByList(entries.flatMap { listOf(it.fromAirport, it.toAirport) })
            "aircraft" -> countBy(entries) { it.aircraftType }
            "registration" -> countBy(entries) { it.registration }
            else -> emptyMap()
        }
        return counts.map { (label, count) -> Bar(label, count) }
            .sortedWith(compareByDescending<Bar> { it.count }.thenBy { it.label })
    }

    fun classSlices(context: Context, flightType: String?): List<Slice> {
        var entries = periodFiltered(context)
        if (flightType != null) {
            entries = entries.filter { it.flightType == flightType }
        }
        val labels = listOf(
            context.getString(R.string.class_economy),
            context.getString(R.string.class_premium_economy),
            context.getString(R.string.class_business),
            context.getString(R.string.class_first),
        )
        val colors = ClassColorSchemes.colorsFor(Settings.getClassScheme(context))
        return labels.mapIndexedNotNull { index, label ->
            val value = entries.count { it.classType == label }
            if (value > 0) Slice(label, value, colors[index]) else null
        }
    }

    fun travelTypeSlices(context: Context): List<Slice> {
        val entries = periodFiltered(context)
        val labels = listOf(
            context.getString(R.string.flight_type_private),
            context.getString(R.string.flight_type_on_duty),
            context.getString(R.string.flight_type_deadhead),
            context.getString(R.string.flight_type_duty_travel),
        )
        val colors = travelTypeColors()
        return labels.mapIndexedNotNull { index, label ->
            val value = entries.count { it.flightType == label }
            if (value > 0) Slice(label, value, colors[index]) else null
        }
    }

    fun travelTypeColors(): List<Int> = listOf(
        R.color.type_private_bg,
        R.color.type_on_duty_bg,
        R.color.type_deadhead_bg,
        R.color.type_duty_travel_bg,
    )

    private fun periodFiltered(context: Context): List<LogbookEntry> =
        DashboardStats.filterForPeriod(
            LogbookRepository.getEntries(),
            Settings.getDefaultPeriodKey(context)
        )

    private fun flightsPerPeriod(entries: List<LogbookEntry>, periodKey: String): Map<String, Int> {
        val formatter = when {
            periodKey == "all" -> DateTimeFormatter.ofPattern("yyyy", Locale.GERMANY)
            periodKey == "this_month" -> DateTimeFormatter.ofPattern("d. MMM", Locale.GERMANY)
            else -> DateTimeFormatter.ofPattern("MMM yyyy", Locale.GERMANY)
        }
        return entries.groupingBy { it.date.format(formatter) }.eachCount()
    }

    private fun routeOf(entry: LogbookEntry): String =
        "${entry.fromAirport}-${entry.toAirport}"

    private fun countBy(
        entries: List<LogbookEntry>,
        selector: (LogbookEntry) -> String?
    ): Map<String, Int> = entries.mapNotNull { selector(it)?.takeIf(String::isNotBlank) }
        .groupingBy { it }
        .eachCount()

    private fun countByList(values: List<String>): Map<String, Int> =
        values.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
}