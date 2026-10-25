package com.highfly.logbook

import android.content.Context
import java.util.Locale

/**
 * Baut aus den Auswertungen ([StoryStats]) die Seiten der beiden Stories.
 * Welche Seiten erscheinen, hängt vom Datensatz und von der Rolle ab: leere
 * Abschnitte fallen weg, die Passagier-Seite erscheint nur der Besatzung.
 * Ist am Ende nichts übrig, liefert der Builder eine einzelne Leerseite.
 *
 * Die Formatierung der Zahlen folgt der App-Sprache ([Settings.language]):
 * Tausendertrennzeichen und Dezimalkomma richten sich danach.
 */
object StoryPages {

    /** Eine Story: Titel und Seiten. */
    data class Story(
        val titleRes: Int,
        val pages: List<StoryPage>
    )

    /** Die Play-Story: die Highlights des ganzen Logbuchs. */
    fun play(
        context: Context,
        stats: StoryStats.PlayStats,
        role: Role
    ): Story {
        val locale = Settings.language(context)
        val pages = mutableListOf<StoryPage>()

        // 1. Flüge überhaupt und dieses Jahr.
        if (stats.totalFlights > 0) {
            pages += StoryPage.HeroNumber(
                iconRes = R.drawable.ic_flight,
                titleRes = R.string.story_play_flights_title,
                labelRes = R.string.story_play_flights_total,
                target = stats.totalFlights.toDouble(),
                format = groupedInt(locale),
                subLabelRes = R.string.story_play_flights_ytd,
                subTarget = stats.ytdFlights.toDouble(),
                subFormat = groupedInt(locale)
            )
        }

        // 2. Die drei häufigsten Ziele, privat und (bei Crew) On Duty.
        val destinationGroups = rankingGroups(
            locale,
            stats.topDestinationsPrivate,
            stats.topDestinationsOnDuty,
            flightsOf = { it.flights },
            role = role,
            suffix = "×",
            labelOf = { it.code },
            flagOf = { null }
        )
        if (destinationGroups.isNotEmpty()) {
            pages += StoryPage.Ranking(
                iconRes = R.drawable.ic_airports,
                titleRes = R.string.story_play_destinations_title,
                groups = destinationGroups
            )
        }

        // 3. Die drei häufigsten Länder, privat und (bei Crew) On Duty.
        val countryGroups = rankingGroups(
            locale,
            stats.topCountriesPrivate,
            stats.topCountriesOnDuty,
            flightsOf = { it.flights },
            role = role,
            suffix = "×",
            labelOf = { it.name },
            flagOf = { it.flag }
        )
        if (countryGroups.isNotEmpty()) {
            pages += StoryPage.Ranking(
                iconRes = R.drawable.ic_countries,
                titleRes = R.string.story_play_countries_title,
                groups = countryGroups
            )
        }

        // 4. Die Top-Reisebuddies.
        if (stats.topBuddies.isNotEmpty()) {
            pages += StoryPage.Ranking(
                iconRes = R.drawable.ic_reisebuddy,
                titleRes = R.string.story_play_buddies_title,
                groups = listOf(
                    StoryPage.Ranking.Group(
                        labelRes = null,
                        rows = stats.topBuddies.map { buddy ->
                            StoryPage.Ranking.Row(
                                label = buddy.name,
                                value = String.format(locale, "%,d", buddy.flights),
                                suffix = "×",
                                valueTarget = buddy.flights.toDouble(),
                                valueFormat = groupedInt(locale)
                            )
                        }
                    )
                )
            )
        }

        // 5. Häufigster und weitester Flugzeugtyp.
        val aircraftRows = mutableListOf<StoryPage.Ranking.Row>()
        stats.mostFlownAircraft?.let { aircraft ->
            aircraftRows += StoryPage.Ranking.Row(
                label = aircraft.type,
                value = String.format(locale, "%,d", aircraft.flights),
                suffix = "×",
                subLabel = context.getString(R.string.story_play_aircraft_most_flown),
                valueTarget = aircraft.flights.toDouble(),
                valueFormat = groupedInt(locale)
            )
        }
        stats.furthestAircraft?.let { aircraft ->
            aircraftRows += StoryPage.Ranking.Row(
                label = aircraft.type,
                value = String.format(locale, "%,d", aircraft.km),
                suffix = context.getString(R.string.flight_distance_hint),
                subLabel = context.getString(R.string.story_play_aircraft_furthest),
                valueTarget = aircraft.km.toDouble(),
                valueFormat = groupedInt(locale)
            )
        }
        if (aircraftRows.isNotEmpty()) {
            pages += StoryPage.Ranking(
                iconRes = R.drawable.ic_hangar,
                titleRes = R.string.story_play_aircraft_title,
                groups = listOf(StoryPage.Ranking.Group(null, aircraftRows))
            )
        }

        // 6. Die meistgeflogene Strecke.
        stats.topRoute?.let { route ->
            val fromGeo = AirportData.location(context, route.from)
            val toGeo = AirportData.location(context, route.to)
            pages += StoryPage.HeroText(
                iconRes = R.drawable.ic_arrow_route,
                titleRes = R.string.story_play_route_title,
                text = "${route.from}–${route.to}",
                subText = context.getString(R.string.story_play_route_sub, route.flights),
                route = if (fromGeo != null && toGeo != null) {
                    StoryPage.HeroText.RouteData(
                        fromLat = fromGeo.lat,
                        fromLon = fromGeo.lon,
                        toLat = toGeo.lat,
                        toLon = toGeo.lon,
                        fromCode = route.from,
                        toCode = route.to
                    )
                } else null
            )
        }

        // 7. Geschätzte Passagierzahl - nur die Besatzung befördert welche.
        if (role.isCrew && stats.passengersTotal > 0) {
            pages += StoryPage.HeroNumber(
                iconRes = R.drawable.ic_passengers,
                titleRes = R.string.story_play_passengers_title,
                labelRes = R.string.story_play_passengers_title,
                target = PassengersCalculator
                    .roundToHundreds(stats.passengersTotal)
                    .toDouble(),
                format = groupedInt(locale),
                footnote = context.getString(R.string.story_play_passengers_footnote)
            )
        }

        // 8. Auswertung der Reiseklassen.
        if (stats.classShares.isNotEmpty()) {
            pages += StoryPage.Ranking(
                iconRes = R.drawable.ic_seat,
                titleRes = R.string.story_play_classes_title,
                groups = listOf(
                    StoryPage.Ranking.Group(
                        labelRes = null,
                        rows = stats.classShares.map { share ->
                            StoryPage.Ranking.Row(
                                label = classLabel(context, share.classCanonical),
                                value = String.format(locale, "%,d", share.flights),
                                suffix = "×",
                                valueTarget = share.flights.toDouble(),
                                valueFormat = groupedInt(locale)
                            )
                        }
                    )
                )
            )
        }

        // 9. Wie weit geflogen.
        if (stats.totalKm > 0) {
            pages += StoryPage.HeroNumber(
                iconRes = R.drawable.ic_distance_arrow,
                titleRes = R.string.story_play_distance_title,
                labelRes = R.string.story_play_distance_title,
                target = stats.totalKm.toDouble(),
                format = groupedInt(locale),
                unitRes = R.string.flight_distance_hint
            )
        }

        // 10. Wie viel Zeit im Flieger.
        if (stats.totalMinutes > 0) {
            pages += StoryPage.HeroNumber(
                iconRes = R.drawable.ic_time,
                titleRes = R.string.story_play_time_title,
                labelRes = R.string.story_play_time_title,
                target = stats.totalMinutes / 60.0,
                format = oneDecimal(locale),
                unitRes = R.string.story_unit_hours
            )
        }

        // 11. Wie viel kompensiert werden müsste.
        if (stats.co2Tonnes > 0) {
            pages += StoryPage.HeroNumber(
                iconRes = R.drawable.ic_co2,
                titleRes = R.string.story_play_co2_title,
                labelRes = R.string.story_play_co2_title,
                target = stats.co2Tonnes,
                format = oneDecimal(locale),
                unitRes = R.string.story_unit_tonnes,
                footnote = context.getString(
                    R.string.story_play_co2_footnote,
                    String.format(locale, "%,d", stats.co2Trees)
                )
            )
        }

        return Story(
            titleRes = R.string.dashboard_story,
            pages = pages.ifEmpty { listOf(emptyPage(context)) }
        )
    }

    /** Die Insights-Story: die Layover-Auswertung samt Lieblingszielen. */
    fun insights(
        context: Context,
        stats: StoryStats.InsightsStats
    ): Story {
        val locale = Settings.language(context)
        val pages = mutableListOf<StoryPage>()

        // 1. Die häufigsten Layover-Ziele - ohne Dauer, dafür die Flagge des
        //    Landes direkt hinter dem Dreilettercode.
        if (stats.topLayoverByCount.isNotEmpty()) {
            pages += StoryPage.Ranking(
                iconRes = R.drawable.ic_umbrella,
                titleRes = R.string.story_insights_layover_count_title,
                groups = listOf(
                    StoryPage.Ranking.Group(
                        labelRes = null,
                        rows = stats.topLayoverByCount.map { dest ->
                            StoryPage.Ranking.Row(
                                label = dest.code,
                                value = String.format(locale, "%,d", dest.count),
                                suffix = "×",
                                labelFlag = dest.flag,
                                valueTarget = dest.count.toDouble(),
                                valueFormat = groupedInt(locale)
                            )
                        }
                    )
                )
            )
        }

        // 2. Die Layover-Ziele mit der meisten Zeit: doppelt hohe Zeilen,
        //    oben rechts die Stunden, darunter unten rechts die Tage.
        if (stats.topLayoverByHours.isNotEmpty()) {
            pages += StoryPage.Ranking(
                iconRes = R.drawable.ic_time,
                titleRes = R.string.story_insights_layover_hours_title,
                groups = listOf(
                    StoryPage.Ranking.Group(
                        labelRes = null,
                        rows = stats.topLayoverByHours.map { dest ->
                            StoryPage.Ranking.Row(
                                label = dest.code,
                                value = String.format(locale, "%,d", dest.hours),
                                suffix = context.getString(R.string.story_unit_hours),
                                valueTarget = dest.hours.toDouble(),
                                valueFormat = groupedInt(locale),
                                tall = true,
                                stackedValues = layoverTimeValues(context, locale, dest.hours)
                            )
                        }
                    )
                )
            )
        }

        // 3. Die Top-fünf Länder mit der gesamten Layoverzeit: wie Seite 2
        //    mit Stunden oben rechts und Tagen darunter unten rechts, dazu der
        //    Ländername fett oben und die Flagge darunter (statt davor).
        if (stats.topLayoverCountries.isNotEmpty()) {
            pages += StoryPage.Ranking(
                iconRes = R.drawable.ic_countries,
                titleRes = R.string.story_insights_layover_countries_title,
                groups = listOf(
                    StoryPage.Ranking.Group(
                        labelRes = null,
                        rows = stats.topLayoverCountries.map { country ->
                            StoryPage.Ranking.Row(
                                label = country.name,
                                value = String.format(locale, "%,d", country.hours),
                                suffix = context.getString(R.string.story_unit_hours),
                                subFlag = country.flag,
                                valueTarget = country.hours.toDouble(),
                                valueFormat = groupedInt(locale),
                                tall = true,
                                stackedValues = layoverTimeValues(context, locale, country.hours)
                            )
                        }
                    )
                )
            )
        }

        // 4. Der häufigste Reisebuddy im Layover.
        if (stats.topLayoverBuddies.isNotEmpty()) {
            pages += StoryPage.Ranking(
                iconRes = R.drawable.ic_reisebuddy,
                titleRes = R.string.story_insights_layover_buddies_title,
                groups = listOf(
                    StoryPage.Ranking.Group(
                        labelRes = null,
                        rows = stats.topLayoverBuddies.map { buddy ->
                            StoryPage.Ranking.Row(
                                label = buddy.name,
                                value = String.format(locale, "%,d", buddy.count),
                                suffix = "×",
                                valueTarget = buddy.count.toDouble(),
                                valueFormat = groupedInt(locale)
                            )
                        }
                    )
                )
            )
        }

        // 5. Die Top-fünf Lieblingsziele (häufig und regelmäßig besucht) -
        //    nur Ziele, die als Layover markiert sind. Der Untertitel nennt
        //    den durchschnittlichen Abstand zwischen zwei Besuchen.
        if (stats.favoriteDestinations.isNotEmpty()) {
            pages += StoryPage.Ranking(
                iconRes = R.drawable.ic_heart,
                titleRes = R.string.story_insights_favorites_title,
                groups = listOf(
                    StoryPage.Ranking.Group(
                        labelRes = null,
                        rows = stats.favoriteDestinations.map { dest ->
                            StoryPage.Ranking.Row(
                                label = dest.code,
                                value = String.format(locale, "%,d", dest.flights),
                                suffix = "×",
                                subLabel = if (dest.flights > 0) {
                                    context.getString(
                                        R.string.story_row_avg_months,
                                        LayoverTimeScale.trimmedDecimal(
                                            dest.months.toDouble() / dest.flights,
                                            locale
                                        )
                                    )
                                } else null,
                                valueTarget = dest.flights.toDouble(),
                                valueFormat = groupedInt(locale)
                            )
                        }
                    )
                )
            )
        }

        // 6. Die Top-fünf Ziele, die man weniger mag (selten, lange her) -
        //    ebenfalls nur Layover-Ziele, ohne Monatsangabe.
        if (stats.dislikedDestinations.isNotEmpty()) {
            pages += StoryPage.Ranking(
                iconRes = R.drawable.ic_heart_broken,
                titleRes = R.string.story_insights_disliked_title,
                groups = listOf(
                    StoryPage.Ranking.Group(
                        labelRes = null,
                        rows = stats.dislikedDestinations.map { dest ->
                            StoryPage.Ranking.Row(
                                label = dest.code,
                                value = String.format(locale, "%,d", dest.flights),
                                suffix = "×",
                                valueTarget = dest.flights.toDouble(),
                                valueFormat = groupedInt(locale)
                            )
                        }
                    )
                )
            )
        }

        return Story(
            titleRes = R.string.story_insights_title,
            pages = pages.ifEmpty { listOf(emptyPage(context)) }
        )
    }

    /**
     * Baut die Gruppen einer Rangliste: die privaten Einträge als erste
     * Gruppe, die On-Duty-Einträge als zweite - nur bei der Rolle Besatzung.
     * Label und Flagge der Zeile kommen aus dem jeweiligen Eintrag.
     */
    private fun <T> rankingGroups(
        locale: Locale,
        privateItems: List<T>,
        onDutyItems: List<T>,
        flightsOf: (T) -> Int,
        role: Role,
        suffix: String,
        labelOf: (T) -> String,
        flagOf: (T) -> String?
    ): List<StoryPage.Ranking.Group> {
        fun rowsOf(items: List<T>) = items.map { item ->
            StoryPage.Ranking.Row(
                label = labelOf(item),
                value = String.format(locale, "%,d", flightsOf(item)),
                suffix = suffix,
                flag = flagOf(item),
                valueTarget = flightsOf(item).toDouble(),
                valueFormat = groupedInt(locale)
            )
        }
        val groups = mutableListOf<StoryPage.Ranking.Group>()
        if (privateItems.isNotEmpty()) {
            groups += StoryPage.Ranking.Group(
                labelRes = R.string.flight_type_private,
                rows = rowsOf(privateItems)
            )
        }
        if (role.isCrew && onDutyItems.isNotEmpty()) {
            groups += StoryPage.Ranking.Group(
                labelRes = R.string.flight_type_on_duty,
                rows = rowsOf(onDutyItems)
            )
        }
        return groups
    }

    /** Die Leerseite, wenn es noch nichts zu erzählen gibt. */
    private fun emptyPage(context: Context) = StoryPage.HeroText(
        iconRes = R.drawable.ic_info,
        titleRes = R.string.story_empty_title,
        text = context.getString(R.string.story_empty_sub),
        subText = null
    )

    /** Anzeigename einer kanonischen Reiseklasse (wie die Kachel). */
    private fun classLabel(context: Context, canonical: String): String = when (canonical) {
        "Economy" -> context.getString(R.string.class_economy)
        "Premium Eco" -> context.getString(R.string.class_premium_economy)
        "Business" -> context.getString(R.string.class_business)
        "First" -> context.getString(R.string.class_first)
        "Jump" -> context.getString(R.string.class_jump)
        else -> canonical
    }

    /** Formatierung einer ganzen Zahl mit Tausendertrennzeichen. */
    private fun groupedInt(locale: Locale): (Double) -> String =
        { value -> String.format(locale, "%,d", value.toLong()) }

    /** Formatierung mit einer Nachkommastelle. */
    private fun oneDecimal(locale: Locale): (Double) -> String =
        { value -> String.format(locale, "%,.1f", value) }

    /**
     * Formatierung einer Zeit-Zahl (Tage, Monate): höchstens eine
     * Nachkommastelle, ganze ohne (wie [LayoverTimeScale.daysText]).
     */
    private fun trimmedFormat(locale: Locale): (Double) -> String =
        { value -> LayoverTimeScale.trimmedDecimal(value, locale) }

    /**
     * Die Zeit eines Layover-Werts als gestapelte Flip-Zahlen unter den
     * Stunden: die Tage, darunter die Monate - die Monate aber nur, wenn es
     * mehr als ein halber Monat ist (ab 361 h).
     */
    private fun layoverTimeValues(
        context: Context,
        locale: Locale,
        hours: Int
    ): List<StoryPage.Ranking.Row.StackedValue> {
        val days = StoryPage.Ranking.Row.StackedValue(
            text = LayoverTimeScale.daysText(hours, locale),
            suffix = context.getString(R.string.story_unit_days),
            target = hours / LayoverTimeScale.DAY_HOURS.toDouble(),
            format = trimmedFormat(locale)
        )
        val months = hours / LayoverTimeScale.MONTH_HOURS.toDouble()
        if (months <= 0.5) return listOf(days)
        return listOf(
            days,
            StoryPage.Ranking.Row.StackedValue(
                text = LayoverTimeScale.trimmedDecimal(months, locale),
                suffix = context.getString(R.string.story_unit_months),
                target = months,
                format = trimmedFormat(locale)
            )
        )
    }
}
