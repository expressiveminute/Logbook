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

    /** Kennzahlen einer einzelnen Flugstrecke fuer ihre Detailseite. */
    data class FlightRouteStats(val flights: Int, val minutes: Int, val km: Int)

    /** Layover-Eintrag als Angabe fuer die Kacheln ueber dem Diagramm. */
    data class LayoverExtreme(val airport: String, val date: LocalDate)

    /** Womit die Balken der Layover-Kachel gemessen und sortiert werden. */
    enum class LayoverSort { ANZAHL, DAUER }

    /**
     * Womit die Balken der Hangar-Kachel (Flugzeugtypen und Registrierungen)
     * gemessen und sortiert werden. [ANZAHL] zaehlt die Fluege, [ENTFERNUNG]
     * summiert die geflogenen Kilometer, [DAUER] die Flugzeit in Minuten.
     */
    enum class HangarSort { ANZAHL, ENTFERNUNG, DAUER }

    /**
     * Womit die Balken der Kachel "Flugrouten" gemessen und sortiert werden.
     * [ANZAHL] zaehlt die Fluege je Strecke, [ENTFERNUNG] summiert die
     * geflogenen Kilometer, [DAUER] die geflogenen Minuten.
     */
    enum class FlightRouteSort { ANZAHL, ENTFERNUNG, DAUER }

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

    /** Die einzige Reiseart, die nicht als dienstlich gilt. */
    const val PRIVATE_CANONICAL = "Privat"
    const val ON_DUTY_CANONICAL = "On Duty"
    const val DEADHEAD_CANONICAL = "Deadhead"
    const val FERRY_CANONICAL = "Ferry"
    const val GROUND_TRANSFER_CANONICAL = "Ground Transfer"
    const val DUTY_TRAVEL_CANONICAL = "Dienstreise"

    /** Alle Reisearten in kanonischer Schreibweise, in Anzeige-Reihenfolge. */
    val TRAVEL_TYPE_CANONICALS: List<String> = travelTypes.map { it.canonical }

    /**
     * Alle Reisearten ausser [PRIVATE_CANONICAL]: On Duty, Deadhead, Ferry,
     * Ground Transfer und Dienstreise. Der Filter "Nur Dienstlich" des
     * Dashboards zaehlt genau diese, damit die Liste der Reisearten und der
     * Filter nicht auseinanderlaufen koennen.
     */
    val DUTY_CANONICALS: List<String> =
        travelTypes.map { it.canonical }.filterNot { it == PRIVATE_CANONICAL }

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
        "flights", "routes", "flightroutes", "airlines", "layover", "aircraftreg", "countries"
    )
    private val PIE_CHART_TILES = setOf("class", "traveltype", "function")

    /** Obergrenze der Stunden-Balken: alles ab dieser Stunde landet im Sammel-Balken. */
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

    fun barChart(context: Context, tileId: String): List<Bar> =
        barChart(context, tileId, LayoverSort.ANZAHL)

    /**
     * @param layoverSort Bei der Layover-Kachel: Balken nach Zahl der Layover
     *   oder nach der gesamten Layoverzeit ordnen, siehe [layoverBars]. Bei den
     *   anderen Kacheln ohne Wirkung.
     */
    fun barChart(context: Context, tileId: String, layoverSort: LayoverSort): List<Bar> {
        if (!isBarChart(tileId)) return emptyList()
        val key = Settings.getDefaultPeriodKey(context)
        // Dieselbe Auswahl wie das Dashboard (Flugart und Zeitraum): die
        // Balken der Seite duerfen nicht mehr zeigen als die Kachel.
        val entries = DashboardStats.dashboardEntries(context)
        if (tileId == "layover") {
            val bars = layoverBars(
                entries = entries,
                sort = layoverSort,
                subLabels = layoverLastLabels(entries).mapValues { (_, date) ->
                    context.getString(R.string.layover_last, date)
                }
            )
            // Im Dauer-Modus steht die Zeit in Tagen neben dem Balken - mit
            // einer Nachkommastelle, wie sie auch die Detailseite zeigt.
            return if (layoverSort == LayoverSort.DAUER) {
                bars.map { it.copy(countLabel = layoverDaysText(context, it.count)) }
            } else {
                bars
            }
        }
        val counts: Map<String, Int> = when (tileId) {
            "flights" -> flightsPerPeriod(entries, key, context)
            "flightroutes" -> countBy(entries) { routeOf(it) }
            "airlines" -> countBy(entries) { it.airline }
            "aircraftreg" -> countBy(entries) { it.registration }
            "countries" -> countBy(entries) { it.toCountry }
                .mapKeys { (code, _) -> "${code} ${AirportData.flagEmoji(code)}" }
            else -> emptyMap()
        }
        return counts.map { (label, count) ->
            Bar(label, count)
        }
            .sortedWith(compareByDescending<Bar> { it.count }.thenBy { it.label })
    }

    /**
     * Balken der Layover-Kachel: pro Flughafen ein Balken. Nach
     * [LayoverSort.ANZAHL] misst er, wie oft man dort im Layover war, nach
     * [LayoverSort.DAUER] wie lange man dort insgesamt verbracht hat -
     * gerechnet aus den Angaben zur Layoverlänge. Einträge ohne Angabe
     * zählen mit null Stunden, weil diese Zeit schlicht nicht erfasst ist.
     *
     * Die Unterbeschriftung "Zuletzt: …" bleibt in beiden Fällen gleich,
     * sortiert wird absteigend nach dem dargestellten Wert und bei Gleichstand
     * nach dem Flughafencode. Die Rangzeichen (Medaillen) des Diagramms
     * folgen automatisch demselben Wert. Die Beschriftung des Werts im
     * Dauer-Modus hängt an [barChart], weil sie Sprache und Einheit braucht.
     */
    fun layoverBars(
        entries: List<LogbookEntry>,
        sort: LayoverSort,
        subLabels: Map<String, String> = emptyMap()
    ): List<Bar> {
        val layovers = entries.filter { it.layover }
        val values: Map<String, Int> = when (sort) {
            LayoverSort.ANZAHL -> countBy(layovers) { it.toAirport }
            LayoverSort.DAUER -> layoverHoursByAirport(layovers)
        }
        return values.map { (label, value) ->
            Bar(label = label, count = value, subLabel = subLabels[label])
        }
            .sortedWith(compareByDescending<Bar> { it.count }.thenBy { it.label })
    }

    /**
     * Teilt die Balken der Layover-Kachel in die Rangliste (Wert über null) und
     * die Flughäfen ohne erfasste Dauer (Wert null). Im Dauer-Modus stehen
     * solche Flughäfen nicht in der Rangliste, sondern in einer eigenen,
     * einklappbaren Gruppe darunter; die Reihenfolge bleibt erhalten. Im
     * Anzahl-Modus ist die zweite Liste leer, weil dort jeder gelistete
     * Flughafen mindestens einmal besucht wurde.
     */
    fun splitLayoverZeroDuration(bars: List<Bar>): Pair<List<Bar>, List<Bar>> =
        bars.partition { it.count > 0 }

    /**
     * Layoverdauer in Tagen mit hoechstens einer Nachkommastelle, z. B.
     * "3,5 Tage" - dieselbe Schreibweise wie die Dauer ueber der Leiste auf der
     * Detailseite. Eine volle Einheit steht ohne die ",0" da ("2 Tage" statt
     * "2,0 Tage"). Der Singular richtet sich nach der gerundeten Anzeige: nur
     * wenn wirklich "1" dasteht, heisst es "Tag", sonst stuende bei knapp
     * eineinhalb Tagen "1 Tage".
     */
    private fun layoverDaysText(context: Context, hours: Int): String {
        val locale = Locale.getDefault()
        val days = LayoverTimeScale.daysText(hours, locale)
        val quantity =
            if (days == LayoverTimeScale.daysText(LayoverTimeScale.DAY_HOURS, locale)) 1 else 2
        return context.resources.getQuantityString(
            R.plurals.layover_time_unit_days,
            quantity,
            days
        )
    }

    /**
     * Jüngster Layover-Eintrag der Liste: der Flughafen, der am kürzesten her
     * ist. Ohne Layover `null`.
     */
    fun newestLayover(entries: List<LogbookEntry>): LayoverExtreme? =
        layoverExtremum(entries, newest = true)

    /**
     * Layover, das am längsten her ist: der Flughafen, bei dem der *letzte*
     * Layover am weitesten zurückliegt. Nicht der früheste Layover überhaupt -
     * das wäre der Ort, an dem man das erste Mal war, auch wenn man seither
     * ständig dort stand. Gesucht ist "am längsten nicht mehr dort gewesen".
     * Ohne Layover `null`.
     */
    fun oldestLayover(entries: List<LogbookEntry>): LayoverExtreme? =
        layoverExtremum(entries, newest = false)

    private fun layoverExtremum(entries: List<LogbookEntry>, newest: Boolean): LayoverExtreme? {
        val zeilen = lastLayoverPerAirport(entries)
        val (airport, date) = if (newest) {
            zeilen.maxByOrNull { it.second }
        } else {
            zeilen.minByOrNull { it.second }
        } ?: return null
        return LayoverExtreme(airport, date)
    }

    /**
     * Letzter Layover je Flughafen als Paar aus Flughafen und Datum. Der
     * Flughafen dient als Schlüssel, deshalb zählt ein mehrfach besuchter
     * Flughafen nur mit seinem jüngsten Layover - sonst gewönne das älteste
     * Layover eines oft besuchten Flughafens immer die Kachel.
     */
    private fun lastLayoverPerAirport(entries: List<LogbookEntry>): List<Pair<String, LocalDate>> =
        entries.filter { it.layover && it.toAirport.isNotBlank() }
            .groupBy { it.toAirport.trim().uppercase() }
            .map { (airport, layovers) -> airport to layovers.maxOf { it.date } }

    /**
     * Layover-Einträge eines Flughafens für den Zeitstrahl der Detailseite:
     * nur Einträge mit Layover-Kennzeichnung, chronologisch von alt nach neu.
     * Der Code wird wie beim Erfassen normalisiert (trimmen, grossschreiben).
     */
    fun layoverHistory(entries: List<LogbookEntry>, airport: String): List<LogbookEntry> {
        val target = airport.trim().uppercase()
        return entries.filter {
            it.layover && it.toAirport.trim().uppercase() == target
        }.sortedBy { it.date }
    }

    /**
     * Dieselbe Liste wie [layoverHistory], aber für die Anzeige umgekehrt: der
     * jüngste Layover steht oben, der am längsten her zurückliegende unten. So
     * steht das, was man zuletzt gemacht hat, oben - wie in der Eintragliste.
     */
    fun layoverHistoryNewestFirst(
        entries: List<LogbookEntry>,
        airport: String
    ): List<LogbookEntry> = layoverHistory(entries, airport).reversed()

    /**
     * Summe der angegebenen Layoverlängen für einen Flughafen, in Stunden.
     * Grundlage der Leiste auf der Detailseite: jeder Eintrag zählt mit seiner
     * eigenen Angabe aus "Neuer Flug". Einträge ohne Angabe bleiben offen -
     * ihre Zeit ist schlicht nicht erfasst, nicht etwa null Stunden.
     */
    fun layoverHoursTotal(entries: List<LogbookEntry>, airport: String): Int {
        val target = airport.trim().uppercase()
        return entries.filter {
            it.layover && it.toAirport.trim().uppercase() == target
        }.sumOf { it.layoverHours ?: 0 }
    }

    /**
     * Summe der Layoverlängen je Flughafen in Stunden. Die Schlüssel sind
     * dieselben wie bei der Zählung: der gespeicherte Flughafencode unverändert
     * und ohne leere Codes, damit beide Auswertungen dieselben Balken zeigen.
     * Einträge ohne Angabe zählen mit null Stunden, bleiben also als Balken
     * mit "0 h" sichtbar.
     */
    fun layoverHoursByAirport(entries: List<LogbookEntry>): Map<String, Int> {
        val hours = LinkedHashMap<String, Int>()
        entries.filter { it.layover }.forEach { entry ->
            val airport = entry.toAirport.takeIf(String::isNotBlank) ?: return@forEach
            hours[airport] = (hours[airport] ?: 0) + (entry.layoverHours ?: 0)
        }
        return hours
    }

    fun airportBars(context: Context): List<Bar> =
        DashboardStats.dashboardEntries(context)
            .flatMap { listOf(it.fromAirport, it.toAirport) }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .map { (label, count) -> Bar(label, count) }
            .sortedWith(compareByDescending<Bar> { it.count }.thenBy { it.label })

    fun aircraftBars(context: Context, sort: HangarSort = HangarSort.ANZAHL): List<Bar> =
        hangarBars(context, sort) { it.aircraftType }

    fun aircraftBars(
        entries: List<LogbookEntry>,
        sort: HangarSort = HangarSort.ANZAHL
    ): List<Bar> = hangarBars(entries, sort) { it.aircraftType }

    /**
     * Balken der Hangar-Kachel ueber den Zeitraum der Einstellungen: je
     * Flugzeugtyp oder Registrierung ein Balken. [selector] waehlt die Gruppe,
     * [sort], ob nach Zahl der Fluege, nach Entfernung oder nach Flugdauer
     * geordnet wird.
     */
    fun hangarBars(
        context: Context,
        sort: HangarSort,
        selector: (LogbookEntry) -> String?
    ): List<Bar> = withHangarValueLabels(
        context,
        hangarBars(DashboardStats.dashboardEntries(context), sort, selector),
        sort
    )

    /**
     * Balken der Hangar-Kachel: je Gruppe ein Balken. Nach [HangarSort.ANZAHL]
     * zaehlt jeder Eintrag als ein Flug, nach [HangarSort.ENTFERNUNG] die
     * Summe der geflogenen Kilometer, nach [HangarSort.DAUER] die Summe der
     * Flugminuten. Eintraege ohne erfasste Entfernung oder Dauer zaehlen dort
     * mit null, der Typ bleibt als Balken mit dem Wert 0 sichtbar, weil er ja
     * geflogen wurde. Sortiert wird absteigend nach dem dargestellten Wert und
     * bei Gleichstand nach der Beschriftung.
     */
    fun hangarBars(
        entries: List<LogbookEntry>,
        sort: HangarSort,
        selector: (LogbookEntry) -> String?
    ): List<Bar> {
        val values: Map<String, Int> = when (sort) {
            HangarSort.ANZAHL -> countBy(entries) { selector(it) }
            HangarSort.ENTFERNUNG -> distanceBy(entries, selector)
            HangarSort.DAUER -> durationBy(entries, selector)
        }
        return values.map { (label, value) -> Bar(label, value) }
            .sortedWith(compareByDescending<Bar> { it.count }.thenBy { it.label })
    }

    /**
     * Balken der Kachel "Flugrouten" ueber den Zeitraum der Einstellungen: je
     * Strecke ein Balken. [sort] waehlt, ob nach Zahl der Fluege, nach den
     * geflogenen Kilometern oder nach der gesamten Flugdauer geordnet wird.
     */
    fun flightRouteBars(
        context: Context,
        sort: FlightRouteSort = FlightRouteSort.ANZAHL
    ): List<Bar> = withFlightRouteValueLabels(
        context,
        flightRouteBars(DashboardStats.dashboardEntries(context), sort),
        sort
    )

    /**
     * Balken der Kachel "Flugrouten": je Strecke ein Balken. Nach
     * [FlightRouteSort.ANZAHL] zaehlt jeder Eintrag als ein Flug, nach
     * [FlightRouteSort.ENTFERNUNG] die Summe der geflogenen Kilometer, nach
     * [FlightRouteSort.DAUER] die Summe der Flugminuten.
     * Eintraege ohne erfasste Entfernung oder Dauer zaehlen dort mit null, die
     * Strecke bleibt als Balken mit dem Wert 0 sichtbar, weil sie ja geflogen
     * wurde. Sortiert wird absteigend nach dem dargestellten Wert und bei
     * Gleichstand nach der Beschriftung.
     */
    fun flightRouteBars(
        entries: List<LogbookEntry>,
        sort: FlightRouteSort = FlightRouteSort.ANZAHL
    ): List<Bar> {
        val values: Map<String, Int> = when (sort) {
            FlightRouteSort.ANZAHL -> countBy(entries) { routeOf(it) }
            FlightRouteSort.ENTFERNUNG -> distanceBy(entries) { routeOf(it) }
            FlightRouteSort.DAUER -> durationBy(entries) { routeOf(it) }
        }
        return values.map { (label, value) -> Bar(label, value) }
            .sortedWith(compareByDescending<Bar> { it.count }.thenBy { it.label })
    }

    /**
     * Kennzahlen einer einzelnen Strecke: Zahl der Fluege, gesamte Flugzeit in
     * Minuten und gesamte Entfernung in Kilometern. Gezaehlt werden dieselben
     * Eintraege wie bei den Balken der Kachel; fehlende Dauer oder Entfernung
     * geht mit null ein, nicht erfasste Werte verfaelschen das Ergebnis also
     * nicht.
     */
    fun flightRouteStats(entries: List<LogbookEntry>, route: String): FlightRouteStats {
        val matching = entries.filter { routeOf(it) == route }
        return FlightRouteStats(
            flights = matching.size,
            minutes = matching.sumOf { it.flightMinutes ?: 0 },
            km = matching.sumOf { it.distanceKm ?: 0 }
        )
    }

    /**
     * Platz der Strecke in der Rangliste der Flugrouten (1 = vorn), je nach
     * [sort] nach Zahl der Fluege, Kilometern oder Flugdauer. Strecken mit
     * gleichem Wert teilen sich einen Platz, wie bei den Balken und den
     * Raengen der uebrigen Kacheln. 0, wenn die Strecke nicht vorkommt.
     */
    fun flightRouteRank(
        entries: List<LogbookEntry>,
        route: String,
        sort: FlightRouteSort
    ): Int {
        val bars = flightRouteBars(entries, sort)
        val target = bars.firstOrNull { it.label == route } ?: return 0
        return bars.count { it.count > target.count } + 1
    }

    /**
     * Haengt den Balken der Kachel "Flugrouten" die Wertbeschriftung des
     * gewaehlten Messwerts an: die Kilometer, die Flugdauer oder - nach
     * [FlightRouteSort.ANZAHL] - nichts, dann steht einfach die Zahl der
     * Fluege da. So zeigt die Wertbeschriftung immer denselben Messwert wie
     * die Balkenlaenge.
     */
    private fun withFlightRouteValueLabels(
        context: Context,
        bars: List<Bar>,
        sort: FlightRouteSort
    ): List<Bar> = when (sort) {
        FlightRouteSort.ENTFERNUNG -> bars.map {
            it.copy(countLabel = DashboardStats.kmText(context, it.count))
        }
        FlightRouteSort.DAUER -> bars.map {
            it.copy(countLabel = durationText(it.count))
        }
        FlightRouteSort.ANZAHL -> bars
    }

    /**
     * Haengt den Balken der Hangar-Kachel die Wertbeschriftung des gewaehlten
     * Messwerts an: die Kilometer, die Flugdauer oder - nach
     * [HangarSort.ANZAHL] - nichts, dann steht einfach die Zahl der Fluege da.
     * So zeigt die Wertbeschriftung immer denselben Messwert wie die
     * Balkenlaenge.
     */
    private fun withHangarValueLabels(
        context: Context,
        bars: List<Bar>,
        sort: HangarSort
    ): List<Bar> = when (sort) {
        HangarSort.ENTFERNUNG -> bars.map {
            it.copy(countLabel = DashboardStats.kmText(context, it.count))
        }
        HangarSort.DAUER -> bars.map {
            it.copy(countLabel = durationText(it.count))
        }
        HangarSort.ANZAHL -> bars
    }

    /**
     * Summe der Entfernung je Gruppe in Kilometern. Nur Eintraege mit
     * erfasster Entfernung zaehlen; fehlt sie, geht der Eintrag mit null ein,
     * bleibt aber als Gruppe erhalten.
     */
    private fun distanceBy(
        entries: List<LogbookEntry>,
        selector: (LogbookEntry) -> String?
    ): Map<String, Int> {
        val km = LinkedHashMap<String, Int>()
        entries.forEach { entry ->
            val label = selector(entry)?.takeIf(String::isNotBlank) ?: return@forEach
            km[label] = (km[label] ?: 0) + (entry.distanceKm ?: 0)
        }
        return km
    }

    /**
     * Summe der Flugdauer je Gruppe in Minuten. Nur Eintraege mit erfasster
     * Flugzeit zaehlen; fehlt sie, geht der Eintrag mit null ein, bleibt aber
     * als Gruppe erhalten.
     */
    private fun durationBy(
        entries: List<LogbookEntry>,
        selector: (LogbookEntry) -> String?
    ): Map<String, Int> {
        val minutes = LinkedHashMap<String, Int>()
        entries.forEach { entry ->
            val label = selector(entry)?.takeIf(String::isNotBlank) ?: return@forEach
            minutes[label] = (minutes[label] ?: 0) + (entry.flightMinutes ?: 0)
        }
        return minutes
    }

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

    fun durationHistogram(context: Context): List<Bar> =
        durationHistogram(DashboardStats.dashboardEntries(context))

    /**
     * Anzahl der Flüge je voller Stunde. Die x-Achse endet dynamisch: Auf den
     * höchsten belegten Stundenwert folgt noch ein Balken mehr, der als "N+"
     * beschriftet ist und alle längeren Flüge sammelt. Gibt es also nur Flüge
     * bis 14 Stunden, endet die Achse bei "15+" (leer), und die Zahlen dahinter
     * entfallen. Kürzere Flüge landen im 1-Stunden-Balken, mehr als
     * [HOUR_BUCKETS] Stunden immer im letzten Balken. Ohne erfasste Flugzeit
     * wird der Flug nicht gezählt.
     */
    fun durationHistogram(entries: List<LogbookEntry>): List<Bar> {
        val hoursList = entries.mapNotNull { entry ->
            val minutes = entry.flightMinutes
            if (minutes == null || minutes <= 0) {
                null
            } else {
                Math.round(minutes / 60.0).toInt().coerceAtLeast(1)
            }
        }
        if (hoursList.isEmpty()) return emptyList()
        // Ein Balken ueber den hoechsten belegten hinaus, aber nie mehr als
        // HOUR_BUCKETS - der letzte Balken ist immer der Sammel-Balken "N+".
        val lastOccupied = hoursList.max().coerceAtMost(HOUR_BUCKETS)
        val buckets = (lastOccupied + 1).coerceAtMost(HOUR_BUCKETS)
        val counts = IntArray(buckets)
        hoursList.forEach { hours ->
            counts[hours.coerceIn(1, buckets) - 1]++
        }
        return counts.mapIndexed { index, count ->
            val hours = index + 1
            Bar(
                label = if (hours >= buckets) "$hours+" else "$hours",
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
        classSlices(context, classType, DashboardStats.dashboardEntries(context))

    /**
     * @param flightType Reiseart, auf die die Klassen eingeschraenkt werden -
     *   die Auswahl der Kachel "Reiseklasse". `null` zaehlt alle Reisearten.
     */
    fun classSlices(
        context: Context,
        classType: String?,
        entries: List<LogbookEntry>,
        flightType: String? = null
    ): List<Slice> {
        var filtered = entries
        if (flightType != null) {
            val travel = normalizeFlightType(flightType)
            filtered = filtered.filter { normalizeFlightType(it.flightType) == travel }
        }
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
        travelTypeSlices(context, DashboardStats.dashboardEntries(context))

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
        functionSlices(context, DashboardStats.dashboardEntries(context))

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
        continentSlices(context, DashboardStats.dashboardEntries(context))

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
                context.getString(Continents.nameRes(continent)),
                count,
                colors[colorIdx]
            )
        }.sortedWith(compareByDescending<Slice> { it.value }.thenBy { it.label })
    }

    private fun flightsPerPeriod(
        entries: List<LogbookEntry>,
        periodKey: String,
        context: Context
    ): Map<String, Int> {
        val locale = Settings.language(context)
        val english = Settings.getLanguage(context) == Settings.LANG_EN
        val formatter = when {
            periodKey == "all" -> DateTimeFormatter.ofPattern("yyyy", locale)
            periodKey == "this_month" -> DateTimeFormatter.ofPattern(
                if (english) "MMM d" else "d. MMM", locale
            )
            else -> DateTimeFormatter.ofPattern("MMM yyyy", locale)
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
