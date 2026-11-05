package com.highfly.logbook

/**
 * Auswertung der Flugeinträge für die Bundesstaaten (USA) und Bundesländer
 * (Deutschland): Welche Verwaltungseinheiten wurden angeflogen und in welchen
 * der beiden Länder gab es überhaupt einen Flug.
 *
 * Ein Staat gilt als "erreicht", wenn der Abflug- oder Zielflughafen eines
 * Eintrags in ihm liegt; die Zuordnung Flughafen -> Region ("US-CA") kommt
 * als Funktion herein ([AirportData.region]). Ob das Land selbst angeflogen
 * wurde, entscheidet zusätzlich [flownCountries] - ein Flughafen ohne
 * Regionseintrag (z. B. ein geschlossener) darf das Land nicht unsichtbar
 * machen, in das es trotzdem einen Flug gab.
 *
 * Bewusst ohne Android-Abhängigkeiten, damit die Zuordnung im Test geprüft
 * werden kann.
 */
object StateStats {

    /** Länder, die eigene Kacheln in der Kachelzeile der Rubbelkarte bekommen. */
    val TILE_COUNTRIES = listOf("DE", "US")

    /**
     * @param states angeflogene Regionen als ISO-3166-2 ("US-CA"), sortiert
     * @param countries Länder mit mindestens einem Flug aus {US, DE}
     */
    data class Summary(
        val states: Set<String>,
        val countries: Set<String>
    )

    /**
     * Ermittelt die angeflogenen Bundesstaaten/-länder.
     *
     * @param entries Flugeinträge
     * @param regionOf ordnet einem IATA-Code seine Region zu, null bei Unbekanntem
     * @param flownCountries Länder aus den Flugeinträgen als ISO-2 (siehe
     *   [RubbelkarteStats.summarize]); nur US/DE werden übernommen
     * @param manualStates selbst abgehakte Staaten (ISO-3166-2), siehe
     *   [Settings.getManualStates] - sie zählen wie angeflogene mit
     * @param hiddenStates abgewählte angeflogene Staaten, siehe
     *   [Settings.getHiddenStates] - sie zählen nicht mehr mit
     */
    fun summarize(
        entries: List<LogbookEntry>,
        regionOf: (String) -> String?,
        flownCountries: Collection<String> = emptySet(),
        manualStates: Collection<String> = emptySet(),
        hiddenStates: Collection<String> = emptySet()
    ): Summary {
        val states = sortedSetOf<String>()
        val countries = sortedSetOf<String>()
        for (entry in entries) {
            for (code in listOf(entry.fromAirport, entry.toAirport)) {
                val iata = code.trim().uppercase()
                if (iata.isEmpty()) continue
                val region = normalizeRegion(regionOf(iata)) ?: continue
                if (region.substringBefore('-') !in TILE_COUNTRIES) continue
                states.add(region)
                countries.add(region.substringBefore('-'))
            }
        }
        // Ein Flug in ein Land, dessen Flughafen keine Region trägt, zählt
        // das Land trotzdem als erreicht - die Staatengrenzen sollen dann
        // sichtbar sein.
        for (country in flownCountries) {
            val iso2 = country.trim().uppercase()
            if (iso2 in TILE_COUNTRIES) countries.add(iso2)
        }
        // Selbst abgehakte Staaten zählen wie angeflogene, abgewählte
        // angeflogene fallen wieder heraus.
        for (code in manualStates) {
            normalizeRegion(code)?.let { if (it.substringBefore('-') in TILE_COUNTRIES) states.add(it) }
        }
        for (code in hiddenStates) {
            normalizeRegion(code)?.let { states.remove(it) }
        }
        return Summary(states, countries)
    }

    /**
     * Kachel-Fortschritt für ein Land mit eigenen Staaten: wie viele seiner
     * Verwaltungseinheiten angeflogen sind. Das Ergebnis hat als "continent"
     * den ISO-2-Code des Landes, damit es in derselben Kachelzeile wie die
     * Kontinente landen kann.
     */
    fun tileProgress(
        allStates: List<StateShapes.State>,
        countryIso2: String,
        visitedStates: Set<String>
    ): ContinentStats.Progress {
        val mine = allStates.filter { it.iso2 == countryIso2.uppercase() }
        val besucht = visitedStates.map { it.trim().uppercase() }.toSet()
        val visited = mine.count { it.code.uppercase() in besucht }
        return ContinentStats.Progress(countryIso2.uppercase(), visited, mine.size)
    }

    /** "us-ca" -> "US-CA", sonst null. */
    fun normalizeRegion(value: String?): String? {
        if (value == null) return null
        val parts = value.trim().uppercase().split('-')
        if (parts.size != 2) return null
        val country = parts[0].filter { it in 'A'..'Z' }
        val region = parts[1].filter { it in 'A'..'Z' || it in '0'..'9' }
        if (country.length != 2 || region.isEmpty()) return null
        return "$country-$region"
    }
}
