package com.highfly.logbook

import java.text.Collator

/**
 * Länderliste der Rubbelkarte: alle Länder der Welt als Checkliste von oben
 * nach unten. Abgehakt ist ein Land, wenn laut eines Flugeintrags in ihm
 * geflogen wurde oder wenn der Nutzer es selbst abgehakt hat.
 *
 * Die Länder aus den Flugeinträgen sind nicht abwählbar - sie stehen wegen der
 * Einträge auf der Karte und ein abwesender Flug wäre eine falsche Aussage.
 * Sie tragen stattdessen ein Flugzeugsymbol, damit der Unterschied zwischen
 * "geflogen" und "von Hand abgehakt" sichtbar bleibt.
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
        /** Aus einem Flugeintrag abgeleitet, also nicht selbst abwählbar. */
        val fromFlight: Boolean,
        val checked: Boolean
    ) {
        val toggleable: Boolean get() = !fromFlight
    }

    /**
     * Baut die vollständige Länderliste, absteigend nach Anzeigename.
     *
     * @param countries Länder samt Namen, siehe [CountryShapes]
     * @param flightIso2 Länder aus den Flugeinträgen als ISO-2
     * @param manualIso2 selbst abgehakte Länder als ISO-2
     * @param german deutsche statt englischer Namen
     * @param collator Sortierung in der Sprache der App
     */
    fun build(
        countries: List<CountryShapes.Country>,
        flightIso2: Collection<String>,
        manualIso2: Collection<String>,
        german: Boolean,
        collator: Collator
    ): List<Item> {
        val fromFlight = flightIso2.mapNotNull { iso2Of(it) }.toSet()
        val manual = manualIso2.mapNotNull { iso2Of(it) }.toSet()
        return countries
            .map { country ->
                val iso2 = iso2Of(country.iso2) ?: return@map null
                val name = if (german) country.nameDe else country.nameEn
                Item(
                    iso2 = iso2,
                    name = name.ifBlank { iso2 },
                    flag = AirportData.flagEmoji(iso2),
                    fromFlight = fromFlight.contains(iso2),
                    checked = fromFlight.contains(iso2) || manual.contains(iso2)
                )
            }
            .filterNotNull()
            .sortedWith(compareBy(collator) { it.name })
    }

    /** Anzahl der abgehakten Länder, für die Zusammenfassung über der Liste. */
    fun checkedCount(items: List<Item>): Int = items.count { it.checked }

    private fun iso2Of(value: String): String? {
        val code = value.trim().uppercase()
        return if (code.length == 2 && code.all { it in 'A'..'Z' }) code else null
    }
}
