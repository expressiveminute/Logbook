package com.highfly.logbook

import kotlin.math.roundToInt

/**
 * Gesamtstand der Rubbelkarte: wie viele Länder der Welt besucht sind, als
 * Anteil von allen.
 *
 * Der Nenner ist die Länderliste der App ([CountryChecklist]), also genau die
 * Zahl, die über der Liste neben dem Stift steht. Ein besuchtes Land, das dort
 * nicht vorkommt, zählt nicht mit - sonst stünde neben dem Balken eine andere
 * Zahl als auf der Länderliste.
 *
 * Bewusst ohne Android-Abhängigkeiten, damit der Anteil im Test ohne Asset- und
 * Datenbankzugriff geprüft werden kann. Siehe [ContinentStats] für dieselbe
 * Auswertung je Kontinent.
 */
object WorldProgress {

    /**
     * @param visited besuchte Länder dieser Liste
     * @param total Länder dieser Liste insgesamt
     */
    data class Progress(
        val visited: Int,
        val total: Int
    ) {
        /**
         * Besuchte Länder als Bruchteil von allen, 0 bis 1. Das braucht der
         * Balken. Begrenzt auf 1, damit ein von Hand gebauter [Progress] ihn
         * nicht über den Rand hinaus zeichnen lässt.
         */
        val fraction: Float
            get() = if (total <= 0) 0f else (visited.toFloat() / total).coerceIn(0f, 1f)

        /** Besuchte Länder in Prozent, gerundet. Steht als Zahl neben dem Balken. */
        val percent: Int
            get() = (fraction * 100f).roundToInt()
    }

    /**
     * Zählt die besuchten Länder der Länderliste. Länder ohne gültigen
     * Ländercode fallen weg, sie stehen auch auf der Länderliste nicht.
     *
     * @param countries Länder der App, siehe [CountryShapes]
     * @param visited besuchte Länder als ISO-2
     */
    fun build(
        countries: List<CountryShapes.Country>,
        visited: Collection<String>
    ): Progress {
        val besucht = visited.map { it.trim().uppercase() }.toSet()
        var total = 0
        var anzahl = 0
        for (country in countries) {
            val iso2 = country.iso2.trim().uppercase()
            if (iso2.length != 2) continue
            total++
            if (iso2 in besucht) anzahl++
        }
        return Progress(anzahl, total)
    }
}
