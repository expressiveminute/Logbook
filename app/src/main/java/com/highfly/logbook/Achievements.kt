package com.highfly.logbook

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Die Erfolge ("Achievements") des Logbuchs: feste Ziele, die ein Flugprofil
 * im Laufe der Zeit erreicht - der n-te Flug, die erste Business-Klasse, die
 * erste Boeing 747, einmal um die Erde.
 *
 * Jeder Erfolg ist eine [Definition] mit Kategorie, Icon und Titel. Ob und
 * wann er erreicht wurde, entscheidet [evaluate] allein aus den Einträgen:
 * Ein Erfolg gilt als erreicht, sobald der auslösende Flug im Logbuch steht,
 * und trägt dessen Datum - so lässt sich der Zeitstrahl der erreichten Erfolge
 * bauen [Reached].
 *
 * Die Auswertung ist bewusst ohne Android-Abhängigkeiten: Was sie von außen
 * braucht - Land und Koordinaten eines Flughafens, die Länderliste eines
 * Kontinents - kommt als [Lookups] herein. So läuft sie im Test ohne Asset-
 * und Datenbankzugriff. Nur [Definition.title] und die
 * Bequemlichkeits-Überladung von [evaluate] mit `Context` greifen auf Android
 * zurück.
 */
object Achievements {

    /** Gruppen der Übersichtsseite, in Anzeige-Reihenfolge. */
    enum class Category(@StringRes val titleRes: Int) {
        FLIGHTS(R.string.ach_category_flights),
        DISTANCE(R.string.ach_category_distance),
        TIME(R.string.ach_category_time),
        ROUTES(R.string.ach_category_routes),
        AIRLINES(R.string.ach_category_airlines),
        AIRCRAFT(R.string.ach_category_aircraft),
        GEOGRAPHY(R.string.ach_category_geography),
        CONTINENTS(R.string.ach_category_continents),
        COUNTRIES(R.string.ach_category_countries),
        CLASSES(R.string.ach_category_classes),
        CREW(R.string.ach_category_crew)
    }

    /** Wie ein Erfolg erreicht wird - steuert die Auswertung in [evaluate]. */
    enum class Kind {
        FLIGHT_COUNT,
        AIRLINE_COUNT,
        AIRCRAFT,
        DISTANCE_TOTAL,
        DISTANCE_SINGLE,
        FLIGHT_HOURS,
        DURATION,
        ROUTE_COUNT,
        EQUATOR,
        PRIME_MERIDIAN,
        CONTINENT_FIRST,
        CONTINENT_ALL,
        CLASS,
        ON_DUTY,
        DEADHEAD,
        LAYOVER,
        FUNCTION
    }

    /** Bordfunktionen, an die ein Erfolg hängt, siehe [crewFunctionOf]. */
    enum class CrewFunction { FLIGHT_ATTENDANT, PURSER_I, PURSER_II }

    /** Ein Punkt auf der Erde, für die Äquator- und Meridian-Erfolge. */
    data class LatLon(val lat: Double, val lon: Double)

    /**
     * Was [evaluate] von außen braucht. Alle drei sind voreingestellt, damit
     * Tests nur das übergeben müssen, was sie gerade prüfen.
     */
    data class Lookups(
        /** ISO-2-Land zu einem IATA-Code, nur wenn der Eintrag keines hat. */
        val countryOf: (String) -> String? = { null },
        /** Länder eines Kontinents für "[Continents]" -> "alle Länder". */
        val countriesOfContinent: (String) -> List<String> = Continents::countriesOf,
        /** Koordinaten eines Flughafens für Äquator und Nullmeridian. */
        val locationOf: (String) -> LatLon? = { null }
    )

    /**
     * Ein Erfolg. Je nach [kind] ist nur eines der Zusatzfelder gesetzt:
     * [number] die Zahl eines Meilensteins (Flüge, Fluggesellschaften,
     * Kilometer, Stunden, Strecken), [continent] der Kontinent,
     * [classCanonical] die Reiseklasse, [crewFunction] die Bordfunktion,
     * [aircraftToken] das gesuchte Stück im Flugzeugtyp, [minMinutes] und
     * [maxMinutes] die Grenzen eines Dauer-Erfolgs.
     */
    data class Definition(
        val id: String,
        val category: Category,
        @StringRes val titleRes: Int,
        @DrawableRes val iconRes: Int,
        val kind: Kind,
        val number: Int? = null,
        val continent: String? = null,
        val classCanonical: String? = null,
        val crewFunction: CrewFunction? = null,
        val aircraftToken: String? = null,
        val minMinutes: Int? = null,
        val maxMinutes: Int? = null,
        /**
         * String-Ressource eines Arguments für [titleRes] ("%1$s"), sonst
         * null. Für "alle Länder: <Kontinent>" ist das der Kontinentname, für
         * die Reiseklassen der Name der Klasse.
         */
        @StringRes val labelRes: Int? = null,
        /** Nur für die Besatzung sichtbar. */
        val crewOnly: Boolean = false
    ) {
        /**
         * Titel in der Sprache der App. Eine einzelne Zahl ("10. Flug",
         * "40.075 km geflogen") wird über [number] mit Tausendertrennzeichen
         * eingesetzt, ein Wort-Argument ("Alle Länder: Europa") über
         * [labelRes].
         */
        fun title(context: Context): String = when {
            labelRes != null -> context.getString(titleRes, context.getString(labelRes))
            number != null -> context.getString(
                titleRes,
                String.format(Settings.language(context), "%,d", number)
            )
            else -> context.getString(titleRes)
        }
    }

    /** Ein erreichter Erfolg samt auslösendem Flug. */
    data class Reached(
        val definition: Definition,
        val date: LocalDate,
        val entry: LogbookEntry
    )

    /**
     * Ergebnis einer Auswertung: [definitions] sind alle für die Rolle
     * gültigen Erfolge, [reached] die erreichten in zeitlich aufsteigender
     * Reihenfolge (der erste Erfolg zuerst).
     */
    data class Result(
        val definitions: List<Definition>,
        val reached: List<Reached>
    ) {
        val reachedCount: Int get() = reached.size
        val total: Int get() = definitions.size

        /** Anteil der erreichten Erfolge, 0..1. */
        val fraction: Float get() = if (total == 0) 0f else reachedCount.toFloat() / total

        /** Anteil in Prozent, gerundet. */
        val percent: Int get() = (fraction * 100f).roundToInt()

        val reachedIds: Set<String> get() = reached.map { it.definition.id }.toSet()

        /** Die erreichten Erfolge, der jüngste zuerst - für den Zeitstrahl. */
        fun reachedNewestFirst(): List<Reached> = reached.sortedWith(
            compareByDescending<Reached> { it.date }
                .thenByDescending { it.entry.id ?: 0L }
        )
    }

    /** Flugmeilensteine: das 1., 10., 50. … 5000. Flug. */
    private val FLIGHT_MILESTONES = listOf(
        1, 10, 50, 100, 250, 500, 1000, 1250, 1500, 2000, 2500, 5000
    )

    /** Wie viele verschiedene Fluggesellschaften geflogen wurden. */
    private val AIRLINE_MILESTONES = listOf(10, 25, 50)

    /** Flugzeugtypen mit eigenem Erfolg: das gesuchte Stück im Typ. */
    private val AIRCRAFT_MILESTONES = listOf(
        AircraftMilestone("aircraft_a380", "380", R.string.ach_aircraft_a380),
        AircraftMilestone("aircraft_b747", "747", R.string.ach_aircraft_b747)
    )

    /** Einmal um die Erde, und der erste echte Ultra-Langstreckenflug. */
    private const val EARTH_CIRCUMFERENCE_KM = 40_075
    private const val ULTRA_LONG_HAUL_KM = 10_000

    /** 1.000 Stunden in der Luft. */
    private const val THOUSAND_HOURS = 1_000

    /** Dauergrenzen in Minuten: über 10 bzw. 15 Stunden, unter einer Stunde. */
    private const val DURATION_OVER_10H_MIN = 10 * 60
    private const val DURATION_OVER_15H_MIN = 15 * 60
    private const val DURATION_UNDER_1H_MIN = 60

    /** So oft dieselbe Strecke, bis der Erfolg zählt. */
    private const val ROUTE_REPEAT = 10

    /** Reiseklassen mit eigenem Erfolg: je die kanonische Schreibweise. */
    private val CLASS_MILESTONES = listOf("Premium Eco", "Business", "First")

    private data class AircraftMilestone(
        val id: String,
        val token: String,
        @StringRes val titleRes: Int
    )

    /**
     * Alle Erfolge in Anzeige-Reihenfolge. Kontinente und Länder entstehen aus
     * [Continents], damit ein neuer Kontinent dort sofort einen Erfolg bekommt.
     */
    val DEFINITIONS: List<Definition> = buildList {
        FLIGHT_MILESTONES.forEach { count ->
            add(
                Definition(
                    id = "flight_$count",
                    category = Category.FLIGHTS,
                    titleRes = R.string.ach_flight_milestone,
                    iconRes = R.drawable.ic_flight,
                    kind = Kind.FLIGHT_COUNT,
                    number = count
                )
            )
        }
        AIRLINE_MILESTONES.forEach { count ->
            add(
                Definition(
                    id = "airlines_$count",
                    category = Category.AIRLINES,
                    titleRes = R.string.ach_airlines,
                    iconRes = R.drawable.ic_airlines,
                    kind = Kind.AIRLINE_COUNT,
                    number = count
                )
            )
        }
        AIRCRAFT_MILESTONES.forEach { milestone ->
            add(
                Definition(
                    id = milestone.id,
                    category = Category.AIRCRAFT,
                    titleRes = milestone.titleRes,
                    iconRes = R.drawable.ic_aircraft,
                    kind = Kind.AIRCRAFT,
                    aircraftToken = milestone.token
                )
            )
        }
        add(
            Definition(
                id = "distance_earth",
                category = Category.DISTANCE,
                titleRes = R.string.ach_distance_total,
                iconRes = R.drawable.ic_distance,
                kind = Kind.DISTANCE_TOTAL,
                number = EARTH_CIRCUMFERENCE_KM
            )
        )
        add(
            Definition(
                id = "distance_ultra",
                category = Category.DISTANCE,
                titleRes = R.string.ach_distance_single,
                iconRes = R.drawable.ic_distance_arrow,
                kind = Kind.DISTANCE_SINGLE,
                number = ULTRA_LONG_HAUL_KM
            )
        )
        add(
            Definition(
                id = "hours_1000",
                category = Category.TIME,
                titleRes = R.string.ach_flight_hours,
                iconRes = R.drawable.ic_time,
                kind = Kind.FLIGHT_HOURS,
                number = THOUSAND_HOURS
            )
        )
        add(
            Definition(
                id = "duration_over_10h",
                category = Category.TIME,
                titleRes = R.string.ach_duration_over_10h,
                iconRes = R.drawable.ic_time,
                kind = Kind.DURATION,
                minMinutes = DURATION_OVER_10H_MIN
            )
        )
        add(
            Definition(
                id = "duration_over_15h",
                category = Category.TIME,
                titleRes = R.string.ach_duration_over_15h,
                iconRes = R.drawable.ic_time,
                kind = Kind.DURATION,
                minMinutes = DURATION_OVER_15H_MIN
            )
        )
        add(
            Definition(
                id = "duration_under_1h",
                category = Category.TIME,
                titleRes = R.string.ach_duration_under_1h,
                iconRes = R.drawable.ic_time,
                kind = Kind.DURATION,
                maxMinutes = DURATION_UNDER_1H_MIN
            )
        )
        add(
            Definition(
                id = "route_repeat",
                category = Category.ROUTES,
                titleRes = R.string.ach_route_repeat,
                iconRes = R.drawable.ic_routes,
                kind = Kind.ROUTE_COUNT,
                number = ROUTE_REPEAT
            )
        )
        add(
            Definition(
                id = "equator",
                category = Category.GEOGRAPHY,
                titleRes = R.string.ach_equator,
                iconRes = R.drawable.ic_earth,
                kind = Kind.EQUATOR
            )
        )
        add(
            Definition(
                id = "prime_meridian",
                category = Category.GEOGRAPHY,
                titleRes = R.string.ach_prime_meridian,
                iconRes = R.drawable.ic_world_map,
                kind = Kind.PRIME_MERIDIAN
            )
        )
        Continents.ORDER.filterNot { it == Continents.UNKNOWN }.forEach { continent ->
            add(
                Definition(
                    id = "continent_first_$continent",
                    category = Category.CONTINENTS,
                    titleRes = Continents.nameRes(continent),
                    iconRes = R.drawable.ic_earth,
                    kind = Kind.CONTINENT_FIRST,
                    continent = continent
                )
            )
            add(
                Definition(
                    id = "continent_all_$continent",
                    category = Category.COUNTRIES,
                    titleRes = R.string.ach_continent_all,
                    iconRes = R.drawable.ic_countries,
                    kind = Kind.CONTINENT_ALL,
                    continent = continent,
                    labelRes = Continents.nameRes(continent)
                )
            )
        }
        CLASS_MILESTONES.forEach { canonical ->
            add(
                Definition(
                    id = "class_${canonical.lowercase().replace(' ', '_')}",
                    category = Category.CLASSES,
                    titleRes = R.string.ach_class,
                    iconRes = R.drawable.ic_class,
                    kind = Kind.CLASS,
                    classCanonical = canonical,
                    labelRes = classLabelRes(canonical)
                )
            )
        }
        add(
            Definition(
                id = "crew_on_duty",
                category = Category.CREW,
                titleRes = R.string.ach_crew_on_duty,
                iconRes = R.drawable.ic_work,
                kind = Kind.ON_DUTY,
                crewOnly = true
            )
        )
        add(
            Definition(
                id = "crew_deadhead",
                category = Category.CREW,
                titleRes = R.string.ach_crew_deadhead,
                iconRes = R.drawable.ic_seat,
                kind = Kind.DEADHEAD,
                crewOnly = true
            )
        )
        add(
            Definition(
                id = "crew_layover",
                category = Category.CREW,
                titleRes = R.string.ach_crew_layover,
                iconRes = R.drawable.ic_umbrella,
                kind = Kind.LAYOVER,
                crewOnly = true
            )
        )
        add(
            Definition(
                id = "crew_function_flight_attendant",
                category = Category.CREW,
                titleRes = R.string.ach_crew_flight_attendant,
                iconRes = R.drawable.ic_function,
                kind = Kind.FUNCTION,
                crewFunction = CrewFunction.FLIGHT_ATTENDANT,
                crewOnly = true
            )
        )
        add(
            Definition(
                id = "crew_function_purser_i",
                category = Category.CREW,
                titleRes = R.string.ach_crew_purser_i,
                iconRes = R.drawable.ic_function,
                kind = Kind.FUNCTION,
                crewFunction = CrewFunction.PURSER_I,
                crewOnly = true
            )
        )
        add(
            Definition(
                id = "crew_function_purser_ii",
                category = Category.CREW,
                titleRes = R.string.ach_crew_purser_ii,
                iconRes = R.drawable.ic_function,
                kind = Kind.FUNCTION,
                crewFunction = CrewFunction.PURSER_II,
                crewOnly = true
            )
        )
    }

    private fun classLabelRes(canonical: String): Int = when (canonical) {
        "Premium Eco" -> R.string.class_premium_economy
        "Business" -> R.string.class_business
        else -> R.string.class_first
    }

    /** Die Erfolge, die zur Rolle passen. Der Fluggast hat keine Besatzung. */
    fun definitionsFor(role: Role): List<Definition> =
        DEFINITIONS.filter { !it.crewOnly || role.isCrew }

    /**
     * Wertet die Erfolge über [entries] aus. Die Reihenfolge der Einträge ist
     * beliebig; für "der n-te Flug" und "der erste Flug nach X" zählt allein
     * Datum und Kennung. Länder und Koordinaten kommen über [lookups].
     */
    fun evaluate(
        entries: List<LogbookEntry>,
        role: Role = Role.CREW,
        lookups: Lookups = Lookups()
    ): Result {
        val definitions = definitionsFor(role)
        val chrono = entries.sortedWith(compareBy({ it.date }, { it.id ?: 0L }))
        val flying = chrono.filter { DashboardStats.isFlying(it) }
        val reached = mutableListOf<Reached>()
        val done = mutableSetOf<String>()

        fun reach(definition: Definition, entry: LogbookEntry) {
            if (done.add(definition.id)) {
                reached.add(Reached(definition, entry.date, entry))
            }
        }

        // Flugmeilensteine: der n-te Eintrag der zeitlichen Reihenfolge.
        definitions.filter { it.kind == Kind.FLIGHT_COUNT }.forEach { definition ->
            val count = definition.number ?: return@forEach
            chrono.getOrNull(count - 1)?.let { reach(definition, it) }
        }

        // Fluggesellschaften: sobald so viele verschiedene zusammenkommen.
        val airlineDefs = definitions.filter { it.kind == Kind.AIRLINE_COUNT }
        val airlines = mutableSetOf<String>()
        chrono.forEach { entry ->
            val name = entry.airline?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            if (!airlines.add(name)) return@forEach
            airlineDefs.filter { (it.number ?: Int.MAX_VALUE) <= airlines.size }
                .forEach { reach(it, entry) }
        }

        // Flugzeugtypen: der erste Flug mit dem gesuchten Typ.
        val aircraftDefs = definitions.filter { it.kind == Kind.AIRCRAFT }
        chrono.forEach { entry ->
            val type = entry.aircraftType?.takeIf { it.isNotBlank() } ?: return@forEach
            aircraftDefs.forEach { definition ->
                val token = definition.aircraftToken ?: return@forEach
                if (type.contains(token)) reach(definition, entry)
            }
        }

        // Distanz: der erste einzelne Ultra-Langstreckenflug …
        definitions.filter { it.kind == Kind.DISTANCE_SINGLE }.forEach { definition ->
            val threshold = definition.number ?: return@forEach
            flying.firstOrNull { (it.distanceKm ?: 0) >= threshold }
                ?.let { reach(definition, it) }
        }
        // … und die Summe aller geflogenen Kilometer.
        val distanceTotalDefs = definitions.filter { it.kind == Kind.DISTANCE_TOTAL }
        var totalKm = 0L
        flying.forEach { entry ->
            totalKm += (entry.distanceKm ?: 0).toLong()
            val total = totalKm
            distanceTotalDefs.forEach { definition ->
                val threshold = definition.number ?: return@forEach
                if (total >= threshold.toLong()) reach(definition, entry)
            }
        }

        // Flugstunden und Flugdauer, nur echte Flüge (kein Ground Transfer).
        val hourDefs = definitions.filter { it.kind == Kind.FLIGHT_HOURS }
        var totalMinutes = 0L
        flying.forEach { entry ->
            totalMinutes += (entry.flightMinutes ?: 0).toLong()
            val minutes = totalMinutes
            hourDefs.forEach { definition ->
                val hours = definition.number ?: return@forEach
                if (minutes >= hours.toLong() * 60L) reach(definition, entry)
            }
        }
        val durationDefs = definitions.filter { it.kind == Kind.DURATION }
        if (durationDefs.isNotEmpty()) {
            flying.forEach { entry ->
                val minutes = entry.flightMinutes ?: return@forEach
                durationDefs.forEach { definition ->
                    if (definition.matchesDuration(minutes)) reach(definition, entry)
                }
            }
        }

        // Strecken: sobald dieselbe Strecke oft genug geflogen wurde.
        val routeDefs = definitions.filter { it.kind == Kind.ROUTE_COUNT }
        val routeCounts = HashMap<String, Int>()
        chrono.forEach { entry ->
            val route = "${entry.fromAirport}-${entry.toAirport}"
            val count = (routeCounts[route] ?: 0) + 1
            routeCounts[route] = count
            routeDefs.filter { (it.number ?: Int.MAX_VALUE) <= count }
                .forEach { reach(it, entry) }
        }

        // Äquator und Nullmeridian: der erste Flug, dessen Strecke sie quert.
        val equator = definitions.firstOrNull { it.kind == Kind.EQUATOR }
        val primeMeridian = definitions.firstOrNull { it.kind == Kind.PRIME_MERIDIAN }
        if (equator != null || primeMeridian != null) {
            chrono.forEach { entry ->
                val from = lookups.locationOf(entry.fromAirport) ?: return@forEach
                val to = lookups.locationOf(entry.toAirport) ?: return@forEach
                if (equator != null && crossesEquator(from, to)) reach(equator, entry)
                if (primeMeridian != null && crossesPrimeMeridian(from, to)) {
                    reach(primeMeridian, entry)
                }
            }
        }

        // Reiseklassen: der erste Eintrag der jeweiligen Klasse.
        definitions.filter { it.kind == Kind.CLASS }.forEach { definition ->
            if (definition.classCanonical == null) return@forEach
            chrono.firstOrNull {
                ChartData.normalizeClassType(it.classType) == definition.classCanonical
            }?.let { reach(definition, it) }
        }

        // Besatzung: On Duty, Deadhead, Layover und Bordfunktion.
        val onDuty = definitions.firstOrNull { it.kind == Kind.ON_DUTY }
        val deadhead = definitions.firstOrNull { it.kind == Kind.DEADHEAD }
        val layover = definitions.firstOrNull { it.kind == Kind.LAYOVER }
        val functions = definitions.filter { it.kind == Kind.FUNCTION }

        chrono.forEach { entry ->
            if (onDuty != null &&
                ChartData.normalizeFlightType(entry.flightType) == ChartData.ON_DUTY_CANONICAL
            ) {
                reach(onDuty, entry)
            }
            if (deadhead != null &&
                ChartData.normalizeFlightType(entry.flightType) == ChartData.DEADHEAD_CANONICAL
            ) {
                reach(deadhead, entry)
            }
            if (layover != null && entry.layover) reach(layover, entry)
            val function = crewFunctionOf(entry.function)
            if (function != null) {
                functions.firstOrNull { it.crewFunction == function }?.let { reach(it, entry) }
            }
        }

        // Kontinente: der erste Flug, der einen Kontinent berührt; und der
        // Flug, der den letzten fehlenden Staat eines Kontinents beisteuert.
        val firstByContinent = definitions.filter { it.kind == Kind.CONTINENT_FIRST }
            .associateBy { it.continent }
        val allByContinent = definitions.filter { it.kind == Kind.CONTINENT_ALL }
            .associateBy { it.continent }
        val visitedCountries = mutableSetOf<String>()

        chrono.forEach { entry ->
            val countries = countriesOf(entry, lookups.countryOf)
            val continents = countries.mapNotNull { Continents.continentOf(it) }.toSet()
            continents.forEach { continent ->
                firstByContinent[continent]?.let { reach(it, entry) }
            }
            visitedCountries.addAll(countries)
            continents.forEach { continent ->
                val definition = allByContinent[continent] ?: return@forEach
                if (definition.id in done) return@forEach
                val all = lookups.countriesOfContinent(continent)
                if (all.isNotEmpty() && all.all { it in visitedCountries }) {
                    reach(definition, entry)
                }
            }
        }

        return Result(definitions, reached)
    }

    /**
     * Wie [evaluate], aber über die geflogenen Einträge des Logbuchs, mit den
     * Länder- und Flughafendaten der App. Für "alle Länder eines Kontinents"
     * zählen die Länder aus `CountryShapes` - dieselbe Liste wie auf der
     * Länder-Seite, damit sich Erfolg und Kachel nicht widersprechen. Läuft
     * wegen der Datenbank- und Asset-Zugriffe nicht auf dem Hauptfaden.
     */
    fun evaluate(context: Context, role: Role = Settings.getRole(context)): Result {
        val app = context.applicationContext
        val byContinent = CountryShapes.load(app)
            ?.groupBy { Continents.continentOf(it.iso2) ?: Continents.UNKNOWN }
            ?.mapValues { (_, countries) -> countries.map { it.iso2 } }
        val lookups = Lookups(
            countryOf = { code -> AirportData.country(app, code) },
            countriesOfContinent = { continent -> byContinent?.get(continent).orEmpty() },
            locationOf = { code ->
                AirportData.location(app, code)?.let { LatLon(it.lat, it.lon) }
            }
        )
        return evaluate(LogbookRepository.getFlownEntries(), role, lookups)
    }

    /** Ob die Dauer in Minuten zu einem Dauer-Erfolg passt. */
    private fun Definition.matchesDuration(minutes: Int): Boolean = when {
        minMinutes != null -> minutes > minMinutes
        maxMinutes != null -> minutes in 1 until maxMinutes
        else -> false
    }

    /** Ein Vorzeichenwechsel der Breite quert den Äquator. */
    private fun crossesEquator(from: LatLon, to: LatLon): Boolean = from.lat * to.lat < 0.0

    /**
     * Ein Vorzeichenwechsel der Länge quert den Nullmeridian. Die zweite
     * Bedingung schliesst die Datumsgrenze aus: 170° und -170° liegen
     * beiderseits von ±180°, nicht des Nullmeridians.
     */
    private fun crossesPrimeMeridian(from: LatLon, to: LatLon): Boolean =
        from.lon * to.lon < 0.0 && abs(from.lon - to.lon) < 180.0

    /**
     * Die ISO-2-Länder eines Eintrags: Abflug und Ziel zählen gleichermaßen,
     * wie bei der Rubbelkarte. Ohne gespeichertes Land wird `countryOf`
     * befragt; ein unbekannter Flughafen trägt nichts bei.
     */
    private fun countriesOf(
        entry: LogbookEntry,
        countryOf: (String) -> String?
    ): List<String> = listOfNotNull(
        iso2Of(entry.fromCountry, entry.fromAirport, countryOf),
        iso2Of(entry.toCountry, entry.toAirport, countryOf)
    ).distinct()

    private fun iso2Of(
        stored: String?,
        airport: String,
        countryOf: (String) -> String?
    ): String? {
        val value = stored?.takeIf { it.isNotBlank() } ?: countryOf(airport)
        val code = value?.trim()?.uppercase() ?: return null
        return if (code.length == 2 && code.all { it in 'A'..'Z' }) code else null
    }

    /**
     * Ordnet den gespeicherten Freitext der Bordfunktion einer der bekannten
     * Funktionen zu. Der Text wird in der Sprache erfasst, in der der Nutzer
     * gerade schreibt; verglichen wird deshalb über Wortstämme, die in beiden
     * Sprachen gleich sind ("Purser") bzw. die deutsche und englische
     * Schreibweise abdecken.
     */
    fun crewFunctionOf(text: String?): CrewFunction? {
        val value = text?.trim()?.lowercase() ?: return null
        if (value.isEmpty()) return null
        if (value.contains("purser")) {
            return if (value.contains("ii")) CrewFunction.PURSER_II else CrewFunction.PURSER_I
        }
        if (value.contains("flugbegleit") ||
            value.contains("flight attendant") ||
            value.contains("cabin")
        ) {
            return CrewFunction.FLIGHT_ATTENDANT
        }
        return null
    }
}
