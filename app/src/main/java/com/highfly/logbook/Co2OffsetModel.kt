package com.highfly.logbook

/**
 * Auswertung für die Seite "CO₂-Ausgleich": Ermittelt das Land, aus dem am
 * häufigsten abgeflogen wurde, und darin den Flughafen mit den meisten
 * Verbindungen. Aus den CO₂-Emissionen (siehe [Co2Calculator]) wird die Zahl
 * der Bäume zum Ausgleich und daraus die nötige Pflanzfläche berechnet.
 *
 * Bewusst ohne Android-Abhängigkeiten, damit die Auswertung im Test ohne
 * Asset- und Datenbankzugriff geprüft werden kann. Länder und Koordinaten
 * werden als Funktionen übergeben.
 */
object Co2OffsetModel {

    /** Pflanzdichte für Bäume -> Hektar: Mischwald, Pflanzabstand rund 3 m. */
    const val TREES_PER_HECTARE = 1000.0

    /** Ein Ground Transfer (Auto, Zug, Bus) ist kein Flug und zählt nicht mit. */
    private const val GROUND_TRANSFER = "Ground Transfer"

    data class AirportUse(
        val iata: String,
        val lat: Double,
        val lon: Double,
        /** Verschiedene andere Flughäfen, mit denen hier verbunden wurde. */
        val connections: Int,
        /** Einträge, die hier starten oder landen. */
        val flights: Int
    )

    data class Result(
        /** Abflugland mit den meisten Abflügen, als ISO-2. */
        val iso2: String,
        /** Meistgenutzter Flughafen dieses Landes. */
        val airport: AirportUse,
        /** CO₂-Emissionen aller Flüge in Tonnen. */
        val tonnes: Double,
        /** Bäume, die diese Emissionen pro Jahr binden würden. */
        val treesPerYear: Int,
        /** Pflanzfläche in Hektar bei [TREES_PER_HECTARE]. */
        val hectares: Double
    )

    /**
     * @param entries Flugeinträge des Logbuchs, bereits auf die gewählte
     *   Periode und Flugart gefiltert (wie auf dem Dashboard)
     * @param countryOf ordnet einem IATA-Code sein ISO-2-Land zu; genutzt,
     *   wenn der Eintrag das Land nicht gespeichert hat
     * @param location ordnet einem IATA-Code seine Koordinaten zu, null bei
     *   unbekanntem Flughafen
     */
    fun analyze(
        entries: List<LogbookEntry>,
        countryOf: (String) -> String?,
        location: (String) -> AirportData.GeoLocation?
    ): Result? {
        val flights = entries.filter { isFlying(it) }
        if (flights.isEmpty()) return null

        val departureCountry = mostDepartureCountry(flights, countryOf) ?: return null
        val airport = mostUsedAirport(flights, departureCountry, countryOf, location)
            ?: return null

        val co2 = Co2Calculator.details(flights)
        return Result(
            iso2 = departureCountry,
            airport = airport,
            tonnes = co2.tonnes,
            treesPerYear = co2.treesPerYear,
            hectares = co2.treesPerYear / TREES_PER_HECTARE
        )
    }

    /** Land mit den meisten Abflügen; bei Gleichstand alphabetisch frühester ISO-2. */
    private fun mostDepartureCountry(
        entries: List<LogbookEntry>,
        countryOf: (String) -> String?
    ): String? {
        val departures = LinkedHashMap<String, Int>()
        for (entry in entries) {
            val from = entry.fromAirport.trim().uppercase()
            if (from.isEmpty()) continue
            val iso = iso2Of(entry.fromCountry?.takeIf { it.isNotBlank() } ?: countryOf(from))
                ?: continue
            departures[iso] = (departures[iso] ?: 0) + 1
        }
        return departures.entries
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
            ?.key
    }

    /**
     * Meistgenutzter Flughafen des Landes: meiste verschiedene Verbindungen,
     * bei Gleichstand meiste Einträge (Start oder Landung), dann alphabetisch
     * frühester Code.
     */
    private fun mostUsedAirport(
        entries: List<LogbookEntry>,
        iso2: String,
        countryOf: (String) -> String?,
        location: (String) -> AirportData.GeoLocation?
    ): AirportUse? {
        val uses = LinkedHashMap<String, MutableAirportUse>()
        for (entry in entries) {
            val from = entry.fromAirport.trim().uppercase()
            val to = entry.toAirport.trim().uppercase()
            if (from.isEmpty() || to.isEmpty()) continue
            val fromIso = iso2Of(entry.fromCountry?.takeIf { it.isNotBlank() } ?: countryOf(from))
            val toIso = iso2Of(entry.toCountry?.takeIf { it.isNotBlank() } ?: countryOf(to))
            if (fromIso == iso2) addUse(uses, from, to, location)
            if (toIso == iso2) addUse(uses, to, from, location)
        }
        return uses.values
            .filter { it.location != null }
            .maxWithOrNull(
                compareBy<MutableAirportUse> { it.connections.size }
                    .thenBy { it.flights }
                    .thenByDescending { it.iata }
            )?.toUse()
    }

    private fun addUse(
        uses: MutableMap<String, MutableAirportUse>,
        code: String,
        other: String,
        location: (String) -> AirportData.GeoLocation?
    ) {
        val use = uses.getOrPut(code) {
            MutableAirportUse(code, location(code))
        }
        if (other != code) use.connections += other
        use.flights++
    }

    private class MutableAirportUse(
        val iata: String,
        var location: AirportData.GeoLocation?,
        val connections: MutableSet<String> = LinkedHashSet(),
        var flights: Int = 0
    ) {
        fun toUse() = AirportUse(
            iata = iata,
            lat = location!!.lat,
            lon = location!!.lon,
            connections = connections.size,
            flights = flights
        )
    }

    private fun isFlying(entry: LogbookEntry): Boolean =
        ChartData.normalizeFlightType(entry.flightType) != GROUND_TRANSFER

    private fun iso2Of(value: String?): String? {
        val code = value?.trim()?.uppercase() ?: return null
        return if (code.length == 2 && code.all { it in 'A'..'Z' }) code else null
    }
}