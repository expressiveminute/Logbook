package com.highfly.logbook

import android.content.Context
import java.time.format.DateTimeFormatter
import java.util.Locale

object ChartData {

    data class Bar(val label: String, val count: Int)

    data class Slice(val label: String, val value: Int, val colorRes: Int)

    private data class TravelType(val resId: Int, val canonical: String, val aliases: List<String>)

    private data class ClassType(val resId: Int, val canonical: String, val aliases: List<String>)

    private val travelTypes = listOf(
        TravelType(R.string.flight_type_private, "Privat", listOf("privat", "private")),
        TravelType(R.string.flight_type_on_duty, "On Duty", listOf("on duty")),
        TravelType(R.string.flight_type_deadhead, "Deadhead", listOf("deadhead")),
        TravelType(R.string.flight_type_ferry, "Ferry", listOf("ferry")),
        TravelType(
            R.string.flight_type_ground_transfer,
            "Ground Transfer",
            listOf("ground transfer", "ground")
        ),
        TravelType(
            R.string.flight_type_duty_travel,
            "Dienstreise",
            listOf("dienstreise", "duty travel", "business travel")
        ),
    )

    private val classTypes = listOf(
        ClassType(R.string.class_economy, "Economy", listOf("economy", "economy class")),
        ClassType(
            R.string.class_premium_economy,
            "Premium Eco",
            listOf("premium eco", "premium economy")
        ),
        ClassType(
            R.string.class_business,
            "Business",
            listOf("business", "business class")
        ),
        ClassType(R.string.class_first, "First", listOf("first", "first class")),
        ClassType(R.string.class_jump, "Jump", listOf("jump", "jump seat")),
    )

    private val BAR_CHART_TILES = setOf(
        "flights", "routes", "airports", "airlines", "layover", "aircraft", "registration"
    )
    private val PIE_CHART_TILES = setOf("class", "traveltype")

    fun isBarChart(tileId: String): Boolean = tileId in BAR_CHART_TILES

    fun isPieChart(tileId: String): Boolean = tileId in PIE_CHART_TILES

    /**
     * Normalisiert eine gespeicherte Reiseart auf den kanonischen deutschen
     * Bezeichner, damit unabhängig von der Sprache, in der der Eintrag erfasst
     * wurde, korrekt gezählt werden kann. Unbekannte Werte bleiben unverändert.
     */
    fun normalizeFlightType(value: String?): String? {
        val v = value?.trim() ?: return null
        val lower = v.lowercase()
        return travelTypes.firstOrNull { t ->
            t.aliases.any { it == lower } || t.canonical.lowercase() == lower
        }?.canonical ?: v
    }

    /**
     * Normalisiert eine gespeicherte Reiseklasse auf den kanonischen deutschen
     * Bezeichner. Unbekannte Werte bleiben unverändert.
     */
    fun normalizeClassType(value: String?): String? {
        val v = value?.trim() ?: return null
        val lower = v.lowercase()
        return classTypes.firstOrNull { c ->
            c.aliases.any { it == lower } || c.canonical.lowercase() == lower
        }?.canonical ?: v
    }

    fun barChart(context: Context, tileId: String): List<Bar> {
        if (!isBarChart(tileId)) return emptyList()
        val key = Settings.getDefaultPeriodKey(context)
        val entries = DashboardStats.filterForPeriod(
            LogbookRepository.getEntries(),
            key
        )
        val counts: Map<String, Int> = when (tileId) {
            "flights" -> flightsPerPeriod(entries, key)
            "layover" -> countBy(entries.filter { it.layover }) { it.toAirport }
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

    fun classSlices(context: Context): List<Slice> =
        classSlices(context, null)

    fun classSlices(context: Context, classType: String?): List<Slice> =
        classSlices(context, classType, periodFiltered(context))

    fun classSlices(
        context: Context,
        classType: String?,
        entries: List<LogbookEntry>
    ): List<Slice> {
        var filtered = entries
        if (classType != null) {
            val target = normalizeClassType(classType)
            filtered = filtered.filter { normalizeClassType(it.classType) == target }
        }
        val colors = ClassColorSchemes.colorsFor(Settings.getClassScheme(context))
        return classTypes.mapIndexed { index, type ->
            val label = context.getString(type.resId)
            val value = filtered.count { normalizeClassType(it.classType) == type.canonical }
            Slice(label, value, colors[index])
        }
    }

    fun travelTypeSlices(context: Context): List<Slice> =
        travelTypeSlices(context, periodFiltered(context))

    fun travelTypeSlices(
        context: Context,
        entries: List<LogbookEntry>
    ): List<Slice> {
        val colors = travelTypeColors()
        return travelTypes.mapIndexed { index, type ->
            val label = context.getString(type.resId)
            val value = entries.count { normalizeFlightType(it.flightType) == type.canonical }
            Slice(label, value, colors[index])
        }
    }

    fun travelTypeColors(): List<Int> = listOf(
        R.color.type_private_bg,
        R.color.type_on_duty_bg,
        R.color.type_deadhead_bg,
        R.color.type_ferry_bg,
        R.color.type_ground_transfer_bg,
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