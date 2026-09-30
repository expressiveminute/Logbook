package com.highfly.logbook

import java.text.Collator

/**
 * Länderliste der Rubbelkarte: alle Länder der Welt als Checkliste von oben
 * nach unten. Abgehakt ist ein Land, wenn laut eines Flugeintrags in ihm
 * geflogen wurde und es nicht abgewählt ist, oder wenn der Nutzer es selbst
 * abgehakt hat.
 *
 * Angeflogene Länder lassen sich abwählen: Sie stehen dann wie ein
 * unangeflogenes Land ab - ohne Haken, ohne Zählung - behalten aber ihr
 * Flugzeugsymbol als Merkzeichen, dass der Flug im Logbuch steht. Erst wenn
 * der Nutzer sie wieder anhakt oder neu anfliegt, zählen sie wieder.
 *
 * Bewusst ohne Android-Abhängigkeiten, damit Sortierung und Zustand im Test
 * ohne Asset- und Datenbankzugriff geprüft werden können. Die Länder selbst
 * kommen aus [CountryShapes], die Sprache der Namen entscheidet [german], und
 * [collator] sortiert sie in der jeweiligen Sprache.
 */
object CountryChecklist {

    data class Item(
        val iso2: String,
        /** Anzeigename in der Sprache der App. */
        val name: String,
        /** Flagge als Emoji, sie folgt dem Ländercode. */
        val flag: String,
        /** Aus einem Flugeintrag abgeleitet, erkennbar am Flugzeugsymbol. */
        val fromFlight: Boolean,
        /** Aktiv für die Karte: angehakt oder angeflogen und nicht abgewählt. */
        val checked: Boolean
    )

    /**
     * Baut die vollständige Länderliste, absteigend nach Anzeigename.
     *
     * @param countries Länder samt Namen, siehe [CountryShapes]
     * @param flightIso2 Länder aus den Flugeinträgen als ISO-2
     * @param manualIso2 selbst abgehakte Länder als ISO-2
     * @param hiddenIso2 abgewählte Länder als ISO-2, siehe [Settings.getHiddenCountries]
     * @param german deutsche statt englischer Namen
     * @param collator Sortierung in der Sprache der App
     * @param continent beschränkt die Liste auf diesen Kontinent, siehe
     *   [Continents]. Leer oder unbekannt zeigt die ganze Welt - das ist die
     *   Länderliste hinter dem Stift, die Kacheln der Rubbelkarte blenden auf
     *   denselben Wegen einen Kontinent heraus.
     */
    fun build(
        countries: List<CountryShapes.Country>,
        flightIso2: Collection<String>,
        manualIso2: Collection<String>,
        german: Boolean,
        collator: Collator,
        continent: String? = null,
        hiddenIso2: Collection<String> = emptySet()
    ): List<Item> {
        val fromFlight = flightIso2.mapNotNull { iso2Of(it) }.toSet()
        val manual = manualIso2.mapNotNull { iso2Of(it) }.toSet()
        val hidden = hiddenIso2.mapNotNull { iso2Of(it) }.toSet()
        return countries
            .map { country ->
                val iso2 = iso2Of(country.iso2) ?: return@map null
                val name = if (german) country.nameDe else country.nameEn
                val flown = fromFlight.contains(iso2)
                Item(
                    iso2 = iso2,
                    name = name.ifBlank { iso2 },
                    flag = AirportData.flagEmoji(iso2),
                    fromFlight = flown,
                    checked = (flown || manual.contains(iso2)) && !hidden.contains(iso2)
                )
            }
            .filterNotNull()
            .filter { item -> belongsTo(item.iso2, continent) }
            .sortedWith(compareBy(collator) { it.name })
    }

    /**
     * Gehört das Land zum gesuchten Kontinent? Ohne [continent] passt jedes
     * Land, ein unbekannter Name ebenso - sonst stünde auf der Seite nichts,
     * was sich erklären ließe.
     */
    fun belongsTo(iso2: String, continent: String?): Boolean {
        val wanted = continent?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return Continents.continentOf(iso2) == wanted
    }

    /** Anzahl der abgehakten Länder, für die Zusammenfassung über der Liste. */
    fun checkedCount(items: List<Item>): Int = items.count { it.checked }

    private fun iso2Of(value: String): String? {
        val code = value.trim().uppercase()
        return if (code.length == 2 && code.all { it in 'A'..'Z' }) code else null
    }
}