package com.highfly.logbook

import java.time.LocalDate
import java.time.YearMonth

/**
 * Reine Auswertung hinter den beiden Stories ("Play" auf dem Dashboard und
 * "Insights" auf der Layover-Seite). Die Story zeigt die Ergebnisse als
 * animierte Seiten; diese Klasse rechnet sie, ohne Android-Abhängigkeiten,
 * damit [StoryStatsTest] sie prüfen kann.
 *
 * Grundlage ist derselbe Datensatz wie überall: die geflogenen Einträge
 * ([LogbookRepository.getFlownEntries]). Anders als das Dashboard gilt weder
 * der Flugart- noch der Zeitraum-Filter - eine Story blickt auf das ganze
 * Logbuch, denn die Highlights sollen das bisher Erreichte feiern.
 *
 * Was die Auswertung von außen braucht (Land und Anzeigename eines Landes),
 * kommt als [Lookups] herein - wie bei [Achievements.Lookups], mit
 * Voreinstellung, damit Tests nur das Nötige übergeben.
 *
 * Konventionen, die überall gelten:
 *  - "Flüge" zählt jeden Eintrag, ein Ground Transfer eingeschlossen (wie
 *    die Kachel "Flüge"). Distanz, Zeit, Ziele, Länder, Typen und Routen
 *    zählen nur echte Flüge ([DashboardStats.isFlying]).
 *  - Die Homebase steht nicht in den Ziel-Rankings: der Heimatflughafen ist
 *    kein Ziel, und jede Rückkehr landet dort - er würde jede Liste
 *    anführen. Dasselbe gilt für das Land der Homebase in den Länder-Rankings
 *    (privat, On Duty und Layover): das Heimatland würde sonst immer auf
 *    Platz 1 landen, weil jeder Flug dort beginnt oder endet.
 *  - Eine Route ist ungerichtet: MUC–HAM und HAM–MUC sind dieselbe Strecke,
 *    weil man sie in beide Richtungen "in- und auswendig" kennt.
 */
object StoryStats {

    /**
     * Was die Auswertung von außen braucht. Beide Funktionen sind
     * voreingestellt, damit Tests nur das übergeben müssen, was sie prüfen.
     */
    data class Lookups(
        /** ISO-2-Land zu einem IATA-Code, nur wenn der Eintrag keines hat. */
        val countryOf: (String) -> String? = { null },
        /** Anzeigename zu einem ISO-2-Land in der Sprache der App. */
        val countryNameOf: (String) -> String? = { null }
    )

    /** Ein Ziel-Flughafen mit Flugzahl, absteigend sortiert. */
    data class DestinationRank(
        val code: String,
        val flights: Int,
        val lastDate: LocalDate? = null
    )

    /** Ein Land mit Flugzahl, absteigend sortiert. */
    data class CountryRank(
        val iso2: String,
        val name: String,
        val flag: String,
        val flights: Int
    )

    /** Ein Reisebuddy mit Flugzahl, absteigend sortiert. */
    data class BuddyRank(val name: String, val flights: Int)

    /** Ein Flugzeugtyp mit Flugzahl und Strecke. */
    data class AircraftRank(val type: String, val flights: Int, val km: Int)

    /** Eine Strecke (ungerichtet) mit Flugzahl. */
    data class RouteRank(val from: String, val to: String, val flights: Int)

    /** Eine Reiseklasse mit Flugzahl, in Anzeige-Reihenfolge der Kachel. */
    data class ClassShare(val classCanonical: String, val flights: Int)

    /** Ein Layover-Ziel mit Zahl und Gesamtdauer in Stunden. */
    data class LayoverDestination(
        val code: String,
        val count: Int,
        val hours: Int,
        val lastDate: LocalDate? = null
    )

    /** Ein Land mit gesamter Layoverzeit in Stunden. */
    data class LayoverCountry(
        val iso2: String,
        val name: String,
        val flag: String,
        val hours: Int
    )

    /** Ein Reisebuddy auf Layover-Flügen mit Flugzahl. */
    data class LayoverBuddy(val name: String, val count: Int)

    /**
     * Ein Ziel samt seiner "Sympathie"-Bewertung: wie oft man dort war, in
     * wie vielen verschiedenen Monaten und wann zuletzt. Grundlage für die
     * Lieblings- und unbeliebten Ziele der Insights-Story.
     */
    data class DestinationAffinity(
        val code: String,
        val flights: Int,
        val months: Int,
        val lastDate: LocalDate
    )

    /** Alle Kennzahlen der Play-Story. */
    data class PlayStats(
        /** Flüge überhaupt, jeder Eintrag zählt (Ground Transfer inklusive). */
        val totalFlights: Int,
        /** Flüge im laufenden Kalenderjahr. */
        val ytdFlights: Int,
        val topDestinationsPrivate: List<DestinationRank>,
        val topDestinationsOnDuty: List<DestinationRank>,
        val topCountriesPrivate: List<CountryRank>,
        val topCountriesOnDuty: List<CountryRank>,
        val topBuddies: List<BuddyRank>,
        /** Häufigst geflogener Typ; null, wenn kein Eintrag einen nennt. */
        val mostFlownAircraft: AircraftRank?,
        /** Typ mit der größten Strecke; null, wenn keine Strecke erfasst ist. */
        val furthestAircraft: AircraftRank?,
        /** Meistgeflogene Strecke; null, wenn es keine Flüge gibt. */
        val topRoute: RouteRank?,
        /** Geschätzte Passagiere über alle Jahre (nur On-Duty-Flüge). */
        val passengersTotal: Long,
        val classShares: List<ClassShare>,
        val totalKm: Int,
        val totalMinutes: Int,
        val co2Tonnes: Double,
        /** Bäume, die die Emissionen ein Jahr binden müssten. */
        val co2Trees: Int
    )

    /** Alle Kennzahlen der Insights-Story. */
    data class InsightsStats(
        val topLayoverByCount: List<LayoverDestination>,
        val topLayoverByHours: List<LayoverDestination>,
        val topLayoverCountries: List<LayoverCountry>,
        val topLayoverBuddies: List<LayoverBuddy>,
        val favoriteDestinations: List<DestinationAffinity>,
        val dislikedDestinations: List<DestinationAffinity>
    )

    private const val TOP_LIMIT = 3
    private const val TOP_COUNTRY_LIMIT = 5

    /**
     * Die Play-Story: die Highlights des Logbuchs. [entries] sind die
     * geflogenen Einträge, [seatsCatalog] und [loadFactorPercent] die
     * Grundlage der Passagier-Schätzung (siehe [PassengersCalculator]),
     * [homebase] der meistgenutzte Flughafen ([Homebase]), der nicht als
     * Ziel zählt - und dessen Land nicht in den Länder-Rankings. [today] ist
     * der Referenztag für "dieses Jahr".
     */
    fun playStats(
        entries: List<LogbookEntry>,
        seatsCatalog: AircraftSeats.Catalog,
        loadFactorPercent: Int,
        homebase: String? = null,
        today: LocalDate = LocalDate.now(),
        lookups: Lookups = Lookups()
    ): PlayStats {
        val flying = DashboardStats.flyingEntries(entries)
        val home = homebase?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        val homeCountry = home?.let(lookups.countryOf)
            ?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        val co2 = Co2Calculator.details(flying)

        return PlayStats(
            totalFlights = entries.size,
            ytdFlights = entries.count { it.date.year == today.year },
            topDestinationsPrivate = topDestinations(
                flying.filter { ChartData.normalizeFlightType(it.flightType) == ChartData.PRIVATE_CANONICAL },
                home
            ),
            topDestinationsOnDuty = topDestinations(
                flying.filter { ChartData.normalizeFlightType(it.flightType) == ChartData.ON_DUTY_CANONICAL },
                home
            ),
            topCountriesPrivate = topCountries(
                flying.filter { ChartData.normalizeFlightType(it.flightType) == ChartData.PRIVATE_CANONICAL },
                lookups,
                homeCountry
            ),
            topCountriesOnDuty = topCountries(
                flying.filter { ChartData.normalizeFlightType(it.flightType) == ChartData.ON_DUTY_CANONICAL },
                lookups,
                homeCountry
            ),
            topBuddies = TravelBuddyStats.build(entries)
                .map { BuddyRank(it.name, it.flights) }
                .take(TOP_LIMIT),
            mostFlownAircraft = aircraftRanks(flying).maxWithOrNull(
                compareBy({ it.flights }, { it.type })
            ),
            furthestAircraft = aircraftRanks(flying)
                .filter { it.km > 0 }
                .maxWithOrNull(compareBy({ it.km }, { it.type })),
            topRoute = topRoute(flying),
            passengersTotal = PassengersCalculator
                .summary(entries, loadFactorPercent, seatsCatalog)
                .totalPassengers,
            classShares = classShares(entries),
            totalKm = flying.sumOf { it.distanceKm ?: 0 },
            totalMinutes = DashboardStats.flightMinutes(entries),
            co2Tonnes = co2.tonnes,
            co2Trees = co2.treesPerYear
        )
    }

    /**
     * Die Insights-Story: die Layover-Auswertung. Die ersten vier Punkte
     * gelten den Layover-Zielen ([LogbookEntry.layover]); die Lieblings-
     * und unbeliebten Ziele gelten allen angeflogenen Zielen - sie sind die
     * Ausnahme, denn auch wer nie ein Layover erfasst hat (die Rolle Fluggast
     * kennt keines), soll in der Story etwas sehen.
     */
    fun insightsStats(
        entries: List<LogbookEntry>,
        homebase: String? = null,
        lookups: Lookups = Lookups()
    ): InsightsStats {
        val flying = DashboardStats.flyingEntries(entries)
        val home = homebase?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        val homeCountry = home?.let(lookups.countryOf)
            ?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        val layovers = entries.filter { it.layover }

        val byDestination = layoverDestinations(layovers)
        val affinities = destinationAffinities(flying, home)

        return InsightsStats(
            topLayoverByCount = byDestination
                .sortedWith(compareByDescending<LayoverDestination> { it.count }
                    .thenBy { it.code })
                .take(TOP_COUNTRY_LIMIT),
            topLayoverByHours = byDestination
                .sortedWith(compareByDescending<LayoverDestination> { it.hours }
                    .thenByDescending { it.count }
                    .thenBy { it.code })
                .take(TOP_COUNTRY_LIMIT),
            topLayoverCountries = layoverCountries(layovers, lookups, homeCountry)
                .take(TOP_COUNTRY_LIMIT),
            topLayoverBuddies = TravelBuddyStats.build(layovers)
                .map { LayoverBuddy(it.name, it.flights) }
                .take(TOP_LIMIT),
            favoriteDestinations = affinities
                .filter { it.flights >= 2 }
                .sortedWith(
                    compareByDescending<DestinationAffinity> { it.flights + it.months }
                        .thenByDescending { it.lastDate }
                        .thenBy { it.code }
                )
                .take(TOP_COUNTRY_LIMIT),
            dislikedDestinations = affinities
                .sortedWith(
                    compareBy<DestinationAffinity> { it.flights }
                        .thenBy { it.months }
                        .thenBy { it.lastDate }
                        .thenBy { it.code }
                )
                .take(TOP_COUNTRY_LIMIT)
        )
    }

    /**
     * Die häufigsten Ziele (Zielflughafen) einer Eintragsliste, ohne die
     * Homebase. Sortiert nach Flugzahl, bei Gleichstand nach Code.
     */
    private fun topDestinations(
        entries: List<LogbookEntry>,
        homebase: String?
    ): List<DestinationRank> {
        val counts = HashMap<String, Int>()
        val lastDates = HashMap<String, LocalDate>()
        for (entry in entries) {
            val code = entry.toAirport.trim().uppercase()
            if (code.isEmpty() || code == homebase) continue
            counts[code] = (counts[code] ?: 0) + 1
            val bisher = lastDates[code]
            if (bisher == null || entry.date > bisher) lastDates[code] = entry.date
        }
        return counts.map { (code, flights) ->
            DestinationRank(code, flights, lastDates[code])
        }.sortedWith(compareByDescending<DestinationRank> { it.flights }.thenBy { it.code })
            .take(TOP_LIMIT)
    }

    /**
     * Die häufigsten Länder einer Eintragsliste, wie die Länderseite
     * ([VisitedCountries.build]): Abflug und Ziel berühren das Land, ein
     * Inlandsflug zählt nur einmal. Das Land steht im Eintrag, sonst liefert
     * [Lookups.countryOf] es über den Flughafencode. Das Heimatland der
     * Homebase ([homeCountry]) zählt nicht mit.
     */
    private fun topCountries(
        entries: List<LogbookEntry>,
        lookups: Lookups,
        homeCountry: String? = null
    ): List<CountryRank> {
        val flightsByCountry = HashMap<String, Int>()
        for (entry in entries) {
            val from = entry.fromAirport.trim().uppercase()
            val to = entry.toAirport.trim().uppercase()
            val fromIso = iso2Of(entry.fromCountry, from, lookups.countryOf)
            val toIso = iso2Of(entry.toCountry, to, lookups.countryOf)
            if (from.isNotEmpty() && fromIso != null && fromIso != homeCountry) {
                flightsByCountry[fromIso] = (flightsByCountry[fromIso] ?: 0) + 1
            }
            if (to.isNotEmpty() && toIso != null && toIso != fromIso && toIso != homeCountry) {
                flightsByCountry[toIso] = (flightsByCountry[toIso] ?: 0) + 1
            }
        }
        return flightsByCountry.map { (iso2, flights) ->
            CountryRank(
                iso2 = iso2,
                name = lookups.countryNameOf(iso2) ?: iso2,
                flag = AirportData.flagEmoji(iso2),
                flights = flights
            )
        }.sortedWith(compareByDescending<CountryRank> { it.flights }.thenBy { it.name })
            .take(TOP_LIMIT)
    }

    /** Flugzahl und Strecke je Flugzeugtyp, ohne Einträge ohne Typ. */
    private fun aircraftRanks(entries: List<LogbookEntry>): List<AircraftRank> {
        val counts = HashMap<String, Int>()
        val kms = HashMap<String, Int>()
        for (entry in entries) {
            val type = entry.aircraftType?.trim()?.takeIf(String::isNotEmpty) ?: continue
            counts[type] = (counts[type] ?: 0) + 1
            kms[type] = (kms[type] ?: 0) + (entry.distanceKm ?: 0)
        }
        return counts.map { (type, flights) -> AircraftRank(type, flights, kms[type] ?: 0) }
    }

    /**
     * Die meistgeflogene Strecke als ungerichtetes Paar: MUC–HAM und HAM–MUC
     * zählen zusammen, angezeigt wird das Paar alphabetisch (HAM–MUC).
     */
    private fun topRoute(entries: List<LogbookEntry>): RouteRank? {
        val counts = HashMap<List<String>, Int>()
        for (entry in entries) {
            val from = entry.fromAirport.trim().uppercase()
            val to = entry.toAirport.trim().uppercase()
            if (from.isEmpty() || to.isEmpty()) continue
            val pair = listOf(from, to).sorted()
            counts[pair] = (counts[pair] ?: 0) + 1
        }
        val best = counts.maxWithOrNull(
            compareBy({ it.value }, { it.key[0] }, { it.key[1] })
        ) ?: return null
        return RouteRank(best.key[0], best.key[1], best.value)
    }

    /**
     * Reiseklassen-Verteilung wie die Kachel "Reiseklasse": die kanonischen
     * Klassen in Anzeige-Reihenfolge, nur Klassen mit mindestens einem Flug.
     * Unbekannte Klassen bleiben wie dort unberücksichtigt.
     */
    private fun classShares(entries: List<LogbookEntry>): List<ClassShare> =
        ChartData.CLASS_CANONICALS.map { canonical ->
            ClassShare(canonical, entries.count {
                ChartData.normalizeClassType(it.classType) == canonical
            })
        }.filter { it.flights > 0 }

    /** Layover-Ziele: Zahl und Gesamtdauer je Zielflughafen. */
    private fun layoverDestinations(entries: List<LogbookEntry>): List<LayoverDestination> {
        val counts = HashMap<String, Int>()
        val hours = HashMap<String, Int>()
        val lastDates = HashMap<String, LocalDate>()
        for (entry in entries) {
            val code = entry.toAirport.trim().uppercase()
            if (code.isEmpty()) continue
            counts[code] = (counts[code] ?: 0) + 1
            hours[code] = (hours[code] ?: 0) + (entry.layoverHours ?: 0)
            val bisher = lastDates[code]
            if (bisher == null || entry.date > bisher) lastDates[code] = entry.date
        }
        return counts.map { (code, count) ->
            LayoverDestination(code, count, hours[code] ?: 0, lastDates[code])
        }
    }

    /**
     * Länder mit gesamter Layoverzeit: alle Layoverstunden, deren Ziel im
     * selben Land liegt, zusammengezählt. Das Land steht im Eintrag, sonst
     * liefert [Lookups.countryOf] es über den Flughafencode. Das Heimatland
     * der Homebase ([homeCountry]) zählt nicht mit.
     */
    private fun layoverCountries(
        entries: List<LogbookEntry>,
        lookups: Lookups,
        homeCountry: String? = null
    ): List<LayoverCountry> {
        val hoursByCountry = HashMap<String, Int>()
        for (entry in entries) {
            val code = entry.toAirport.trim().uppercase()
            if (code.isEmpty()) continue
            val iso2 = iso2Of(entry.toCountry, code, lookups.countryOf) ?: continue
            if (iso2 == homeCountry) continue
            hoursByCountry[iso2] =
                (hoursByCountry[iso2] ?: 0) + (entry.layoverHours ?: 0)
        }
        return hoursByCountry.map { (iso2, hours) ->
            LayoverCountry(
                iso2 = iso2,
                name = lookups.countryNameOf(iso2) ?: iso2,
                flag = AirportData.flagEmoji(iso2),
                hours = hours
            )
        }.sortedWith(compareByDescending<LayoverCountry> { it.hours }.thenBy { it.name })
    }

    /**
     * Sympathie-Werte aller Ziele (ohne Homebase): Flugzahl, Zahl der
     * verschiedenen Monate, in denen man dort war, und das jüngste Datum.
     * Grundlage für die Lieblings- und unbeliebten Ziele.
     */
    private fun destinationAffinities(
        entries: List<LogbookEntry>,
        homebase: String?
    ): List<DestinationAffinity> {
        val flights = HashMap<String, Int>()
        val months = HashMap<String, MutableSet<YearMonth>>()
        val lastDates = HashMap<String, LocalDate>()
        for (entry in entries) {
            val code = entry.toAirport.trim().uppercase()
            if (code.isEmpty() || code == homebase) continue
            flights[code] = (flights[code] ?: 0) + 1
            months.getOrPut(code) { HashSet() }.add(YearMonth.from(entry.date))
            val bisher = lastDates[code]
            if (bisher == null || entry.date > bisher) lastDates[code] = entry.date
        }
        return flights.map { (code, count) ->
            DestinationAffinity(code, count, months[code]?.size ?: 0, lastDates.getValue(code))
        }
    }

    /**
     * ISO-2-Land eines Flugs: das gespeicherte Land des Eintrags, sonst die
     * Auflösung über den Flughafencode. Leer oder unbekannt ergibt null.
     */
    private fun iso2Of(
        stored: String?,
        airport: String,
        countryOf: (String) -> String?
    ): String? {
        val value = stored?.trim()?.takeIf(String::isNotBlank) ?: countryOf(airport)
        return value?.trim()?.takeIf(String::isNotBlank)
    }
}
