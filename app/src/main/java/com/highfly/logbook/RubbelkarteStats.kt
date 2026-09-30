package com.highfly.logbook

/**
 * Auswertung der eingetragenen Flüge für die Rubbelkarte: Welche Länder sind
 * besucht, welche Flughäfen wurden angeflogen und wie viele Kontinente sind
 * damit abgedeckt. Die Länder, die der Nutzer auf der Länderliste selbst
 * abgehakt hat, kommen über [Summary.plusCountries] dazu, die abgewählten
 * fallen über [Summary.minusCountries] wieder heraus.
 *
 * Bewusst ohne Android-Abhängigkeiten, damit die Zuordnung im Test ohne
 * Asset- und Datenbankzugriff geprüft werden kann. Die Auflösung eines
 * Flughafens zu seinem Land wird deshalb als Funktion übergeben.
 */
object RubbelkarteStats {

    /**
     * @param countries besuchte Länder als ISO-2, alphabetisch sortiert
     * @param airports besuchte Flughäfen als IATA, alphabetisch sortiert
     * @param continents besuchte Kontinente in der Reihenfolge von [Continents.ORDER]
     * @param flightsCount Anzahl der ausgewerteten Flüge
     */
    data class Summary(
        val countries: List<String>,
        val airports: List<String>,
        val continents: List<String>,
        val flightsCount: Int
    ) {
        val isEmpty: Boolean get() = countries.isEmpty()

        /**
         * Fasst die Länder aus [extra] hinzu - das sind die auf der Länderliste
         * selbst abgehakten Länder. Länderliste und Kontinente werden neu
         * bestimmt, Flughäfen und Flugzahl bleiben unberührt, denn sie stammen
         * ausschliesslich aus den Einträgen.
         */
        fun plusCountries(extra: Collection<String>): Summary {
            if (extra.isEmpty()) return this
            val merged = (countries + extra.mapNotNull { normalizeOrNull(it) })
                .distinct()
                .sorted()
            return copy(countries = merged, continents = continentsOf(merged))
        }

        /**
         * Nimmt die Länder aus [excluded] aus der Karte - das sind die Länder,
         * die der Nutzer auf der Länderliste abgewählt hat. Sie gelten nicht
         * mehr als besucht, obwohl ihre Flugeinträge weiterhin bestehen.
         * Flughäfen und Flugzahl bleiben unberührt, denn sie stammen
         * ausschliesslich aus den Einträgen.
         */
        fun minusCountries(excluded: Collection<String>): Summary {
            if (excluded.isEmpty()) return this
            val removed = excluded.mapNotNull { normalizeOrNull(it) }.toSet()
            val reduced = countries.filterNot { removed.contains(it) }
            if (reduced.size == countries.size) return this
            return copy(countries = reduced, continents = continentsOf(reduced))
        }
    }

    /**
     * Ermittelt alle Länder, in die geflogen wurde. Abflug- und Zielflughafen
     * zählen gleichermaßen, weil auch der Startpunkt des ersten Fluges schon
     * ein besuchtes Land ist.
     *
     * @param countryOf ordnet einem IATA-Code sein ISO-2-Land zu, null bei Unbekanntem
     */
    fun summarize(
        entries: List<LogbookEntry>,
        countryOf: (String) -> String?
    ): Summary {
        val countries = sortedSetOf<String>()
        val airports = sortedSetOf<String>()
        for (entry in entries) {
            val from = entry.fromAirport.trim().uppercase()
            val to = entry.toAirport.trim().uppercase()
            if (from.isNotEmpty()) {
                airports.add(from)
                (entry.fromCountry ?: countryOf(from))?.let { countries.add(normalize(it)) }
            }
            if (to.isNotEmpty()) {
                airports.add(to)
                (entry.toCountry ?: countryOf(to))?.let { countries.add(normalize(it)) }
            }
        }
        val visited = countries.filter { it.length == 2 }
        return Summary(
            countries = visited,
            airports = airports.toList(),
            continents = continentsOf(visited),
            flightsCount = entries.size
        )
    }

    /** Besuchte Länder auf ihre Kontinente abbilden, in fester Reihenfolge. */
    private fun continentsOf(visited: List<String>): List<String> =
        Continents.ORDER.filter { continent ->
            visited.any { Continents.continentOf(it) == continent }
        }

    /**
     * Mittelpunkt der besuchten Länder als Längen-/Breitengrad. Wird zur
     * Zentrierung der Kugel im Hochformat verwendet, damit möglichst viele
     * besuchte Länder gleichzeitig sichtbar sind.
     *
     * Gewichtet wird nach der Breitengrad-Ausdehnung, nicht nach ganzen
     * Bounding-Boxen: Länder wie die USA oder Russland reichen über die
     * Datumslinie und hätten sonst eine riesige, aber irreführende Fläche.
     */
    fun centerOf(
        countries: List<CountryShapes.Country>,
        visited: Set<String>
    ): Pair<Double, Double>? {
        var lonSum = 0.0
        var latSum = 0.0
        var weight = 0.0
        for (country in countries) {
            if (visited.isEmpty() || visited.contains(country.iso2)) {
                val w = country.latSpan.coerceAtLeast(MIN_WEIGHT)
                lonSum += country.labelLon * w
                latSum += country.labelLat.coerceIn(-60.0, 70.0) * w
                weight += w
            }
        }
        if (weight <= 0.0) return null
        return Pair(lonSum / weight, latSum / weight)
    }

    private fun normalize(iso2: String): String =
        iso2.trim().uppercase().filter { it in 'A'..'Z' }

    /** Wie [normalize], aber null für alles, was kein Land sein kann. */
    private fun normalizeOrNull(iso2: String): String? {
        val value = normalize(iso2)
        return if (value.length == 2) value else null
    }

    private const val MIN_WEIGHT = 0.5
}
