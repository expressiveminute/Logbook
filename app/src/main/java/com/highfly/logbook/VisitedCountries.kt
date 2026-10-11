package com.highfly.logbook

import java.text.Collator

/**
 * Länderliste des Dashboards: alle Länder, in denen laut Fluglogbuch geflogen
 * wurde - Abflug- und Zielflughafen zählen gleichermaßen, wie bei der
 * Rubbelkarte. Jedes Land nennt seinen Anzeigenamen in der Sprache der App,
 * die Zahl der angeflogenen Flughäfen und die Zahl der Flüge, nach der die
 * Liste sortiert ist: die meistbesuchten Länder stehen oben.
 *
 * Bewusst ohne Android-Abhängigkeiten, damit die Auswertung im Test ohne
 * Asset- und Datenbankzugriff geprüft werden kann. Namen und Flaggen kommen
 * aus [CountryShapes] beziehungsweise [AirportData.flagEmoji], die Zuordnung
 * eines Flughafens zu seinem Land wird als Funktion übergeben.
 */
object VisitedCountries {

    data class Item(
        val iso2: String,
        /** Anzeigename in der Sprache der App, sonst der Ländercode. */
        val name: String,
        /** Flagge als Emoji, sie folgt dem Ländercode. */
        val flag: String,
        /** Zahl der verschiedenen Flughäfen, die in diesem Land angeflogen wurden. */
        val airports: Int,
        /** Zahl der Flugeinträge, die das Land berühren – das Ranglisten-Kriterium. */
        val flights: Int
    )

    /**
     * Baut die Liste der besuchten Länder: zuerst die meistbesuchten, das
     * heißt absteigend nach [Item.flights], bei Gleichstand nach Anzeigename.
     *
     * @param countries Länder samt Namen, siehe [CountryShapes]
     * @param entries Flugeinträge des Logbuchs
     * @param german deutsche statt englischer Namen
     * @param collator Sortierung bei gleicher Flugzahl
     * @param countryOf ordnet einem IATA-Code sein ISO-2-Land zu. Sie wird nur
     *   genutzt, wenn der Eintrag das Land nicht gespeichert hat.
     */
    fun build(
        countries: List<CountryShapes.Country>,
        entries: List<LogbookEntry>,
        german: Boolean,
        collator: Collator,
        countryOf: (String) -> String?
    ): List<Item> {
        val nameByIso = countries.associate { it.iso2 to (if (german) it.nameDe else it.nameEn) }
        val airportsByCountry = HashMap<String, MutableSet<String>>()
        val flightsByCountry = HashMap<String, Int>()

        for (entry in entries) {
            val from = entry.fromAirport.trim().uppercase()
            val to = entry.toAirport.trim().uppercase()
            val fromIso = iso2Of(entry.fromCountry?.takeIf { it.isNotBlank() } ?: countryOf(from))
            val toIso = iso2Of(entry.toCountry?.takeIf { it.isNotBlank() } ?: countryOf(to))
            if (from.isNotEmpty() && fromIso != null) {
                airportsByCountry.getOrPut(fromIso) { LinkedHashSet() }.add(from)
                flightsByCountry[fromIso] = (flightsByCountry[fromIso] ?: 0) + 1
            }
            if (to.isNotEmpty() && toIso != null) {
                airportsByCountry.getOrPut(toIso) { LinkedHashSet() }.add(to)
                // Ein Inlandsflug berührt sein Land nur einmal, wie bei der
                // Länderseite von [CountryDetailModel].
                if (toIso != fromIso) {
                    flightsByCountry[toIso] = (flightsByCountry[toIso] ?: 0) + 1
                }
            }
        }

        return airportsByCountry.map { (iso2, airports) ->
            val name = nameByIso[iso2].orEmpty().ifBlank { iso2 }
            Item(
                iso2 = iso2,
                name = name,
                flag = AirportData.flagEmoji(iso2),
                airports = airports.size,
                flights = flightsByCountry[iso2] ?: 0
            )
        }.sortedWith(
            compareByDescending<Item> { it.flights }.thenBy(collator) { it.name }
        )
    }

    /**
     * Platz des Landes in der Rangliste. Länder mit gleich vielen Flügen
     * teilen sich einen Platz (1, 2, 2, 4 …), wie bei den Balken der
     * Dashboard-Kacheln.
     */
    fun rankOf(item: Item, items: List<Item>): Int =
        items.count { it.flights > item.flights } + 1

    private fun iso2Of(value: String?): String? {
        val code = value?.trim()?.uppercase() ?: return null
        return if (code.length == 2 && code.all { it in 'A'..'Z' }) code else null
    }
}