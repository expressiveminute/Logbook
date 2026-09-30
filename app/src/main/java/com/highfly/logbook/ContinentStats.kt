package com.highfly.logbook

import kotlin.math.roundToInt

/**
 * Auswertung für die Kontinent-Kacheln unter der Rubbelkarte: je Kontinent,
 * wie viele Länder besucht sind und wie viele es dort überhaupt gibt.
 *
 * Grundlage ist dieselbe Länderliste wie auf der Länderliste
 * ([CountryChecklist]), damit die Summe der Kacheln zur Zahl der Länder in der
 * App passt. Ein Land zählt nur, wenn es in dieser Liste steht - ein
 * zurückgebliebener Code aus den Einstellungen darf keinen Zähler hochtreiben.
 *
 * Ohne Android-Abhängigkeiten, damit die Zuordnung im Test ohne Asset- und
 * Datenbankzugriff geprüft werden kann.
 */
object ContinentStats {

    /**
     * @param continent Kontinent als Schlüssel aus [Continents]
     * @param visited besuchte Länder dieses Kontinents
     * @param total Länder dieses Kontinents insgesamt
     */
    data class Progress(
        val continent: String,
        val visited: Int,
        val total: Int
    ) {
        /**
         * Besuchte Länder als Bruchteil von allen, 0 bis 1. Das braucht der
         * Ring in der Kachel. Begrenzt auf 1, damit ein von Hand gebautes
         * [Progress] den Ring nicht mehrfach umkreisen lässt.
         */
        val fraction: Float
            get() = if (total <= 0) 0f else (visited.toFloat() / total).coerceIn(0f, 1f)

        /** Besuchte Länder in Prozent, gerundet. Steht in der Mitte des Rings. */
        val percent: Int
            get() = (fraction * 100f).roundToInt()
    }

    /**
     * Zählt die besuchten Länder je Kontinent. Geliefert wird nur, was es in
     * [countries] überhaupt gibt, absteigend nach der Zahl der Länder: Der
     * Kontinent mit den meisten Ländern steht links, die kleineren folgen nach
     * rechts. Bei gleicher Anzahl zählt [Continents.ORDER] als Reihenfolge,
     * damit die Kachelzeile sich beim Blättern nicht verschiebt.
     *
     * @param countries Länder der App, siehe [CountryShapes]
     * @param visited besuchte Länder als ISO-2
     */
    fun build(
        countries: List<CountryShapes.Country>,
        visited: Collection<String>
    ): List<Progress> {
        val besucht = visited.map { it.trim().uppercase() }.toSet()
        val gesamt = HashMap<String, Int>()
        val besuchtZahl = HashMap<String, Int>()
        for (country in countries) {
            val iso2 = country.iso2.trim().uppercase()
            if (iso2.length != 2) continue
            // Ohne Kontinent bleibt das Land aussen vor: unbewohnte
            // Nebenländer wie Pitcairn oder die Falklandinseln gehören zu
            // keinem Kontinent und würden in keiner Kachel auftauchen.
            val continent = Continents.continentOf(iso2) ?: continue
            gesamt[continent] = (gesamt[continent] ?: 0) + 1
            if (iso2 in besucht) {
                besuchtZahl[continent] = (besuchtZahl[continent] ?: 0) + 1
            }
        }
        return Continents.ORDER.mapNotNull { continent ->
            // [Continents.UNKNOWN] steht zwar in der Reihenfolge, ist aber nie
            // ein Ergebnis von [Continents.continentOf] und damit hier ohne
            // Eintrag - es wird deshalb wie ein Kontinent ohne Länder
            // übersprungen.
            val anzahl = gesamt[continent] ?: return@mapNotNull null
            Progress(continent, besuchtZahl[continent] ?: 0, anzahl)
        }.sortedWith(compareByDescending<Progress> { it.total }
            .thenBy { Continents.ORDER.indexOf(it.continent) })
    }
}
