package com.highfly.logbook

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object ChartData {

    data class Bar(
        val label: String,
        val count: Int,
        val subLabel: String? = null,
        val countLabel: String? = null
    )

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
        "flights", "routes", "airlines", "layover", "aircraftreg", "countries"
    )
    private val PIE_CHART_TILES = setOf("class", "traveltype", "function")

    /** Balken der Flugdauer-Histogramm: 1-19 Stunden, danach "20+". */
    private const val HOUR_BUCKETS = 20

    private val DATE_LABEL_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd.MM.yyyy")

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
            "aircraftreg" -> countBy(entries) { it.registration }
            "countries" -> countBy(entries) { it.toCountry }
                .mapKeys { (code, _) -> "${code} ${AirportData.flagEmoji(code)}" }
            else -> emptyMap()
        }
        val subLabels = if (tileId == "layover") {
            layoverLastLabels(entries).mapValues { (_, date) ->
                context.getString(R.string.layover_last, date)
            }
        } else {
            emptyMap()
        }
        return counts.map { (label, count) ->
            Bar(label, count, subLabels[label])
        }
            .sortedWith(compareByDescending<Bar> { it.count }.thenBy { it.label })
    }

    fun airportBars(context: Context): List<Bar> =
        periodFiltered(context)
            .flatMap { listOf(it.fromAirport, it.toAirport) }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .map { (label, count) -> Bar(label, count) }
            .sortedWith(compareByDescending<Bar> { it.count }.thenBy { it.label })

    fun aircraftBars(context: Context): List<Bar> =
        aircraftBars(periodFiltered(context))

    fun aircraftBars(entries: List<LogbookEntry>): List<Bar> =
        countBy(entries) { it.aircraftType }
            .map { (label, count) -> Bar(label, count) }
            .sortedWith(compareByDescending<Bar> { it.count }.thenBy { it.label })

    /**
     * Längster bzw. kürzester Einzelflug des Zeitraums als einzelner Balken:
     * Strecke als Label, Dauer als Balkenlänge und als [Bar.countLabel], das
     * Datum im Balken. Bei gleicher Dauer entscheidet das Datum, damit die
     * Auswahl eindeutig ist. `null`, wenn kein Flug eine Flugzeit hat.
     */
    fun longestFlightBar(entries: List<LogbookEntry>): Bar? =
        extremeFlightBar(entries, longest = true)

    fun shortestFlightBar(entries: List<LogbookEntry>): Bar? =
        extremeFlightBar(entries, longest = false)

    private fun extremeFlightBar(
        entries: List<LogbookEntry>,
        longest: Boolean
    ): Bar? {
        val timed = entries.filter { (it.flightMinutes ?: 0) > 0 }
        val byDuration = compareBy<LogbookEntry>({ it.flightMinutes ?: 0 }, { it.date })
        val flight = if (longest) {
            timed.maxWithOrNull(byDuration)
        } else {
            timed.minWithOrNull(byDuration)
        } ?: return null
        val minutes = flight.flightMinutes ?: return null
        return Bar(
            label = routeOf(flight),
            count = minutes,
            subLabel = flight.date.format(DATE_LABEL_FORMAT),
            countLabel = durationText(minutes)
        )
    }

    /**
     * Mittlere Flugzeit ueber alle Fluege des Zeitraums als einzelner Balken.
     * Gerechnet wird ueber jeden einzelnen Flug, nicht ueber einen Mittelwert
     * je Strecke, damit jede Flugzeit genau einmal eingeht. `null`, wenn kein
     * Flug eine Flugzeit hat.
     */
    fun averageDurationBar(
        entries: List<LogbookEntry>,
        label: String,
        flightsText: (Int) -> String
    ): Bar? {
        val minutes = entries.mapNotNull { it.flightMinutes }.filter { it > 0 }
        if (minutes.isEmpty()) return null
        val average = (minutes.sum().toDouble() / minutes.size).roundToInt()
        return Bar(
            label = label,
            count = average,
            subLabel = flightsText(minutes.size),
            countLabel = durationText(average)
        )
    }

    /**
     * Anzahl der Flüge je voller Stunde: 1 bis 19 Stunden, danach ein
     * Sammel-Balken "20+" für alles darueber. Kuerzere Fluege landen im
     * 1-Stunden-Balken. Ohne erfasste Flugzeit wird der Flug nicht gezaehlt.
     */
    fun durationHistogram(context: Context): List<Bar> =
        durationHistogram(periodFiltered(context))

    fun durationHistogram(entries: List<LogbookEntry>): List<Bar> {
        val counts = IntArray(HOUR_BUCKETS)
        entries.forEach { entry ->
            val minutes = entry.flightMinutes ?: return@forEach
            if (minutes <= 0) return@forEach
            val hours = Math.round(minutes / 60.0).toInt()
            counts[hours.coerceIn(1, HOUR_BUCKETS) - 1]++
        }
        return counts.mapIndexed { index, count ->
            val hours = index + 1
            Bar(
                label = if (hours >= HOUR_BUCKETS) "${HOUR_BUCKETS}+" else "$hours",
                count = count
            )
        }
    }

    /** Flugdauer als "7:05 h", unter einer Stunde als "45 min". */
    fun durationText(minutes: Int): String =
        if (minutes < 60) {
            "$minutes min"
        } else {
            String.format(Locale.GERMANY, "%d:%02d h", minutes / 60, minutes % 60)
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
        }.filter { it.value > 0 }
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
        }.filter { it.value > 0 }
    }

    fun travelTypeColors(): List<Int> = listOf(
        R.color.type_private_bg,
        R.color.type_on_duty_bg,
        R.color.type_deadhead_bg,
        R.color.type_ferry_bg,
        R.color.type_ground_transfer_bg,
        R.color.type_duty_travel_bg,
    )

    fun functionColors(): List<Int> = listOf(
        R.color.function_purser_i,
        R.color.function_purser_ii,
        R.color.function_flight_attendant,
        R.color.function_other,
    )

    fun functionSlices(context: Context): List<Slice> =
        functionSlices(context, periodFiltered(context))

    fun functionSlices(
        context: Context,
        entries: List<LogbookEntry>
    ): List<Slice> {
        val counts = entries.mapNotNull { it.function?.takeIf(String::isNotBlank) }
            .groupingBy { it.trim() }
            .eachCount()
        val colors = functionColors()
        return counts.entries.mapIndexed { index, (label, count) ->
            Slice(label, count, colors[index % colors.size])
        }.sortedWith(compareByDescending<Slice> { it.value }.thenBy { it.label })
    }

    fun continentColors(): List<Int> = listOf(
        R.color.continent_europe,
        R.color.continent_asia,
        R.color.continent_north_america,
        R.color.continent_south_america,
        R.color.continent_africa,
        R.color.continent_oceania,
        R.color.continent_antarctica,
        R.color.continent_unknown,
    )

    fun continentSlices(context: Context): List<Slice> =
        continentSlices(context, periodFiltered(context))

    fun continentSlices(
        context: Context,
        entries: List<LogbookEntry>
    ): List<Slice> {
        val colors = continentColors()
        val counts = entries.mapNotNull { it.toCountry?.takeIf(String::isNotBlank) }
            .map { code -> Continents.continentOf(code) ?: Continents.UNKNOWN }
            .groupingBy { it }
            .eachCount()
        return counts.entries.map { (continent, count) ->
            val colorIdx = Continents.ORDER.indexOf(continent).coerceAtLeast(0)
            Slice(
                context.getString(continentRes(continent)),
                count,
                colors[colorIdx]
            )
        }.sortedWith(compareByDescending<Slice> { it.value }.thenBy { it.label })
    }

    private fun continentRes(continent: String): Int = when (continent) {
        Continents.EUROPE -> R.string.continent_europe
        Continents.ASIA -> R.string.continent_asia
        Continents.NORTH_AMERICA -> R.string.continent_north_america
        Continents.SOUTH_AMERICA -> R.string.continent_south_america
        Continents.AFRICA -> R.string.continent_africa
        Continents.OCEANIA -> R.string.continent_oceania
        Continents.ANTARCTICA -> R.string.continent_antarctica
        else -> R.string.continent_unknown
    }

    fun periodFiltered(context: Context): List<LogbookEntry> =
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

    private fun layoverLastLabels(entries: List<LogbookEntry>): Map<String, String> {
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val lastDates = entries.filter { it.layover && !it.toAirport.isNullOrBlank() }
            .groupingBy { it.toAirport!!.trim() }
            .aggregate { _, acc: LocalDate?, element, _ ->
                if (acc == null || element.date.isAfter(acc)) element.date else acc
            }
        return lastDates.mapValues { (_, date) -> date.format(formatter) }
    }

    private fun routeOf(entry: LogbookEntry): String =
        "${entry.fromAirport}-${entry.toAirport}"

    private fun countBy(
        entries: List<LogbookEntry>,
        selector: (LogbookEntry) -> String?
    ): Map<String, Int> = entries.mapNotNull { selector(it)?.takeIf(String::isNotBlank) }
        .groupingBy { it }
        .eachCount()
}
