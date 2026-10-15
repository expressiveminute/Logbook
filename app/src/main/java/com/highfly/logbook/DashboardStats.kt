package com.highfly.logbook

import android.content.Context
import java.time.LocalDate
import java.util.Locale

object DashboardStats {

    /** Canonical flight type of a ground transfer; these are not counted as
     * "distance" (they do not involve flying). */
    private const val GROUND_TRANSFER = "Ground Transfer"
    private const val WORLD_COUNTRIES = 195

    /**
     * Echte Flugdauer: Ein Ground Transfer (Auto, Zug, Bus) ist kein Flug und
     * zaehlt deshalb weder zur Distanz noch zur Flugzeit.
     */
    fun isFlying(entry: LogbookEntry): Boolean =
        ChartData.normalizeFlightType(entry.flightType) != GROUND_TRANSFER

    /** [entries] ohne Ground Transfers. */
    fun flyingEntries(entries: List<LogbookEntry>): List<LogbookEntry> =
        entries.filter { isFlying(it) }

    /** Gesamtflugzeit in Minuten ohne Ground Transfers. */
    fun flightMinutes(entries: List<LogbookEntry>): Int =
        flyingEntries(entries).sumOf { it.flightMinutes ?: 0 }

    data class Value(val text: String, val unit: String?)

    data class DistanceDetails(
        val totalKm: Int,
        val avgKm: Int?,
        val furthestRoute: String?,
        val furthestKm: Int?,
        val shortestRoute: String?,
        val shortestKm: Int?,
        val earthOrbits: Double,
        val moonTrips: Double,
        val sunTrips: Double
    )

    /**
     * Zeitraumgrenzen des Periodenschlüssels als halb-offenes Intervall
     * [von, bis), damit der 1. Januar des Folgejahres nicht doppelt hinein
     * fällt. `null` bedeutet "alle Einträge", auch bei einem unbekannten
     * Schlüssel.
     */
    fun periodRange(key: String): Pair<LocalDate, LocalDate>? = when (key) {
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
            val year = key.toIntOrNull() ?: return null
            LocalDate.of(year, 1, 1) to LocalDate.of(year + 1, 1, 1)
        }
    }

    fun filterForPeriod(entries: List<LogbookEntry>, key: String): List<LogbookEntry> {
        val range = periodRange(key) ?: return entries
        return entries.filter { it.date >= range.first && it.date < range.second }
    }

    /**
     * Die Einträge, die das Dashboard gerade zeigt: erst nach Flugart
     * ("Alle Flüge", "Nur Dienstlich", "Nur Privat"), dann nach dem gewählten
     * Zeitraum. Beide Filter der Zeile über dem Dashboard wirken deshalb
     * gleich: auf jede Kachel und auf jede Seite dahinter (Balken, Listen,
     * Karten, Summen). So stimmt die Zahl auf der Kachel immer mit dem Inhalt
     * überein, den ein Tipp darauf öffnet.
     */
    fun dashboardEntries(context: Context): List<LogbookEntry> =
        filterForPeriod(
            FlightTypeOptions.filter(
                LogbookRepository.getFlownEntries(),
                Settings.getFlightTypeFilterKey(context)
            ),
            Settings.getDefaultPeriodKey(context)
        )

    /**
     * Anzahl der Strecken, die im Jahr [year] zum ersten Mal geflogen wurden.
     *
     * Eine Entdeckung ist immer relativ zur Gesamtheit aller Einträge: Eine
     * Strecke, die 2023 zum ersten Mal geflogen wurde, zählt 2026 nicht als
     * neu, auch wenn sie 2026 erneut geflogen wurde. Deshalb wird erst über
     * alle Einträge ermittelt, welche Strecken es überhaupt neu gab, und
     * danach auf das Jahr gefiltert. Ein Filtern der Einträge vor der
     * Auswertung würde jeden Wiederholungsflug als Entdeckung zählen.
     */
    fun discoveryCount(allEntries: List<LogbookEntry>, year: Int): Int =
        DiscoveryRoutes.firstFlownInYear(allEntries, year).size

    private val earthCircumferenceKm = 40075.0
    private val moonDistanceKm = 384400.0
    // Eine nautische Meile (nm) entspricht 1,852 Kilometern.
    private const val NAUTICAL_MILE_KM = 1.852

    /** Mittlere Entfernung Erde–Sonne in Kilometern (eine astronomische Einheit). */
    const val SUN_DISTANCE_KM = 149_600_000

    // Dieselbe Strecke als Fließkommazahl für die Faktor-Rechnung.
    private val sunDistanceKm = SUN_DISTANCE_KM.toDouble()

    /**
     * Anteil der Strecke zur Sonne in Prozent, den [totalKm] abdeckt. Die
     * Distanz-Detailseite zeigt damit "erreicht" und "verbleibend"; der
     * Faktor [DistanceDetails.sunTrips] ist derselbe Anteil, nur als Vielfaches
     * einer astronomischen Einheit statt in Prozent.
     */
    fun sunPercentReached(totalKm: Int): Double = totalKm / sunDistanceKm * 100.0

    /** Strecke zur Sonne in Kilometern, die noch fehlt (nie negativ). */
    fun sunRemainingKm(totalKm: Int): Int = (SUN_DISTANCE_KM - totalKm).coerceAtLeast(0)

    fun distanceDetails(context: Context, entries: List<LogbookEntry>): DistanceDetails {
        val withDistance = flyingEntries(entries).filter { it.distanceKm != null }
        val totalKm = withDistance.sumOf { it.distanceKm ?: 0 }
        val distances = withDistance.mapNotNull { it.distanceKm }
        val avgKm = if (distances.isEmpty()) null else
            Math.round(totalKm.toDouble() / distances.size).toInt()
        val furthest = distances.maxOrNull()
        val shortest = distances.minOrNull()
        val earthOrbits = totalKm / earthCircumferenceKm
        val moonFlights = totalKm / moonDistanceKm
        val sunFlights = totalKm / sunDistanceKm
        return DistanceDetails(
            totalKm = totalKm,
            avgKm = avgKm,
            furthestRoute = furthest?.let { km -> routeOf(withDistance, km) },
            furthestKm = furthest,
            shortestRoute = shortest?.let { km -> routeOf(withDistance, km) },
            shortestKm = shortest,
            earthOrbits = earthOrbits,
            moonTrips = moonFlights,
            sunTrips = sunFlights
        )
    }

    private fun routeOf(list: List<LogbookEntry>, km: Int): String {
        val entry = list.first { it.distanceKm == km }
        return "${entry.fromAirport.uppercase()}–${entry.toAirport.uppercase()}"
    }

    fun kmText(context: Context, km: Int): String =
        formatInt(context, km) + " " + context.getString(R.string.flight_distance_hint)

    fun factorText(value: Double, locale: Locale = Locale.GERMANY): String =
        formatFactor(locale, value)

    /**
     * Werte aller Kacheln. [entries] ist bereits auf die Periode gefiltert.
     */
    fun values(
        context: Context,
        entries: List<LogbookEntry>
    ): Map<String, Value> {
        val flights = formatInt(context, entries.size)
        val distanceEntries = flyingEntries(entries)
        val distanceKm = distanceEntries.sumOf { it.distanceKm ?: 0 }
        val minutes = flightMinutes(entries)
        val airports = (entries.mapNotNull { it.fromAirport } + entries.mapNotNull { it.toAirport })
            .filter { it.isNotBlank() }.distinct().size
        val routes = entries.map { "${it.fromAirport}-${it.toAirport}" }.distinct().size
        val airlines = nonBlankCount(entries) { it.airline }
        val layovers = entries.count { it.layover }.toString()
        val classes = nonBlankCount(entries) { it.classType }
        val travelTypes = nonBlankCount(entries) { it.flightType }
        val aircraftTypes = nonBlankCount(entries) { it.aircraftType }
        val countries = (entries.mapNotNull { it.toCountry }
            .filter { it.isNotBlank() }).distinct().size
        // Die Kachel zählt die verschiedenen Buddies, nicht die Flüge: wer
        // fünfmal mit derselben Person flog, ist ein Reisebuddy.
        val buddies = TravelBuddyStats.build(entries).size
        val earthOrbits = distanceKm / earthCircumferenceKm
        val moonFlights = distanceKm / moonDistanceKm

        return mapOf(
            "flights" to Value(flights, null),
            "distance" to Value(formatInt(context, distanceKm), context.getString(R.string.flight_distance_hint)),
            "time" to formatTime(context, minutes),
            "routes" to Value(formatInt(context, airports), null),
            "flightroutes" to Value(formatInt(context, routes), null),
            "airlines" to Value(formatInt(context, airlines), null),
            "layover" to Value(layovers, null),
            "class" to Value(formatInt(context, classes), null),
            "traveltype" to Value(formatInt(context, travelTypes), null),
            "aircraftreg" to Value(formatInt(context, aircraftTypes), null),
            "countries" to Value(formatInt(context, countries),
                String.format(Settings.language(context), "(%s%%)",
                    formatInt(context, (countries * 100.0 / WORLD_COUNTRIES).toInt()))),
            "reisebuddy" to Value(formatInt(context, buddies), null),
            "earthorbits" to Value(formatFactor(Settings.language(context), earthOrbits), "×"),
            "moon" to Value(formatFactor(Settings.language(context), moonFlights), "×"),
        )
    }

    fun tileCount(tileId: String, entries: List<LogbookEntry>): Int = when (tileId) {
        "flights" -> entries.size
        "routes" -> (entries.mapNotNull { it.fromAirport } + entries.mapNotNull { it.toAirport })
            .filter { it.isNotBlank() }.distinct().size
        "flightroutes" -> entries.map { "${it.fromAirport}-${it.toAirport}" }.distinct().size
        "airlines" -> nonBlankCount(entries) { it.airline }
        "aircraftreg" -> nonBlankCount(entries) { it.aircraftType }
        "countries" -> (entries.mapNotNull { it.toCountry }
            .filter { it.isNotBlank() }).distinct().size
        "reisebuddy" -> TravelBuddyStats.build(entries).size
        else -> -1
    }

    private fun formatFactor(locale: Locale, value: Double): String =
        String.format(locale, "%,.3f", value)

    private fun nonBlankCount(
        entries: List<LogbookEntry>,
        selector: (LogbookEntry) -> String?
    ): Int = entries.mapNotNull { selector(it)?.takeIf { s -> s.isNotBlank() } }.distinct().size

    private fun formatInt(context: Context, value: Int): String =
        String.format(Settings.language(context), "%,d", value)

    private fun formatTime(context: Context, minutes: Int): Value {
        if (minutes < 60) {
            return Value(minutes.toString(), "min")
        }
        val hours = minutes / 60.0
        return Value(String.format(Settings.language(context), "%,.1f", hours), "h")
    }

    enum class TimeUnit { HOURS, DAYS, MONTHS, YEARS }

    fun timeValue(context: Context, minutes: Int, unit: TimeUnit): Value {
        val days = minutes / 60.0 / 24.0
        val locale = Settings.language(context)
        return when (unit) {
            TimeUnit.HOURS -> if (minutes < 60) {
                Value(minutes.toString(), "min")
            } else {
                Value(
                    String.format(locale, "%,.1f", minutes / 60.0),
                    context.getString(R.string.tile_time_unit_h)
                )
            }
            TimeUnit.DAYS -> Value(
                String.format(locale, "%,.1f", days),
                context.getString(R.string.tile_time_unit_d)
            )
            TimeUnit.MONTHS -> Value(
                String.format(locale, "%,.2f", days / 30.4375),
                context.getString(R.string.tile_time_unit_mo)
            )
            TimeUnit.YEARS -> Value(
                String.format(locale, "%,.2f", days / 365.25),
                context.getString(R.string.tile_time_unit_y)
            )
        }
    }

    /**
     * Werte der obersten Zeile der Zeit-Kachel: Gesamtflugzeit in Stunden und
     * in Tagen, getrennt durch einen Punkt (dort steht "x Stunden · y Tage").
     */
    fun timeTopValues(context: Context, minutes: Int): Pair<Value, Value> =
        timeValue(context, minutes, TimeUnit.HOURS) to timeValue(context, minutes, TimeUnit.DAYS)

    /** Gesamtflugzeit in Wochen für die Wochen-Karte der Zeit-Kachel. */
    fun weeksValue(context: Context, minutes: Int): Value =
        Value(
            String.format(Settings.language(context), "%,.1f", minutes / 60.0 / 24.0 / 7.0),
            context.getString(R.string.tile_time_unit_w)
        )

    /**
     * Werte der obersten Zeile der Distanz-Kachel: Gesamtdistanz in Kilometern
     * und in nautischen Meilen, getrennt durch einen Punkt ("x km · y nm").
     */
    fun distanceTopValues(context: Context, totalKm: Int): Pair<Value, Value> =
        Value(
            formatInt(context, totalKm),
            context.getString(R.string.flight_distance_hint)
        ) to Value(
            formatInt(context, Math.round(totalKm / NAUTICAL_MILE_KM).toInt()),
            context.getString(R.string.flight_distance_nm_hint)
        )
}