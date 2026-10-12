package com.highfly.logbook

import android.content.Context

/**
 * Durchsuchbarer Bestand aller von der App gespeicherten Flughäfen. Die
 * Flughäfen-Kachel sucht damit nicht nur die angeflogenen Flughäfen, sondern
 * jeden Eintrag aus den Assets - gefunden wird über IATA-Code, ICAO-Code,
 * Stadt und Name.
 *
 * Der Bestand wird einmalig aufgebaut und gemerkt: Er ändert sich nicht, und
 * die Suche soll schon beim Tippen über alle knapp neuntausend Einträge
 * laufen können.
 */
object AirportDirectory {

    /**
     * @param cityDe Deutsche Schreibweise der Stadt, wenn sie sich von [city]
     *   unterscheidet (z. B. "München" zu "Munich"). Sonst `null`.
     */
    data class Entry(
        val iata: String,
        val icao: String?,
        val name: String?,
        val city: String?,
        val cityDe: String? = null
    ) {
        /** Anzeigename der Stadt in der gerade gültigen Sprache. */
        val displayCity: String?
            get() = cityDe ?: city
    }

    @Volatile
    private var cached: List<Entry>? = null

    /** Alle gespeicherten Flughäfen, nach IATA-Code sortiert. */
    fun all(context: Context): List<Entry> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val names = AirportNames.load(context)
            val codes = LinkedHashSet<String>(AirportData.iataCodes(context))
            codes += names.keys
            val entries = codes.map { code ->
                val info = names[code]
                Entry(
                    iata = code,
                    icao = AirportData.icao(context, code),
                    name = info?.name,
                    city = info?.city,
                    cityDe = info?.cityDe
                )
            }.sortedBy { it.iata }
            cached = entries
            return entries
        }
    }

    /**
     * Sucht [query] in IATA, ICAO, Stadt und Name. Exakte und beginnende
     * Treffer auf den Codes stehen oben, danach die Städte, zuletzt reine
     * Teiltreffer; bei gleicher Wertigkeit alphabetisch. Ohne Eingabe leer.
     *
     * @param limit Obergrenze, damit eine kurze Eingabe nicht tausende Zeilen
     *   in das Diagramm schreibt
     */
    fun search(context: Context, query: String, limit: Int): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return all(context)
            .asSequence()
            .filter { matches(it, q) }
            .sortedWith(compareBy({ score(it, q) }, { it.iata }))
            .take(limit)
            .toList()
    }

    private fun matches(entry: Entry, q: String): Boolean =
        entry.iata.lowercase().contains(q) ||
            entry.icao?.lowercase()?.contains(q) == true ||
            entry.city?.lowercase()?.contains(q) == true ||
            entry.cityDe?.lowercase()?.contains(q) == true ||
            entry.name?.lowercase()?.contains(q) == true

    /** Kleiner ist besser: 0 exakter Code, 5 reiner Teiltreffer im Namen. */
    private fun score(entry: Entry, q: String): Int {
        val iata = entry.iata.lowercase()
        val icao = entry.icao?.lowercase()
        val city = entry.displayCity?.lowercase()
        return when {
            iata == q || icao == q -> 0
            iata.startsWith(q) || icao?.startsWith(q) == true -> 1
            city?.startsWith(q) == true -> 2
            city?.contains(q) == true -> 3
            entry.name?.lowercase()?.startsWith(q) == true -> 4
            else -> 5
        }
    }
}
