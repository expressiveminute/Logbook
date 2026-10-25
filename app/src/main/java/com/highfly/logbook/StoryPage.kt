package com.highfly.logbook

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * Eine Seite der Story ("Play" oder "Insights"). Es gibt drei Typen:
 *
 *  - [HeroNumber]: eine große Zahl als Flip-Uhr, optional mit einer zweiten
 *    kleineren Zahl daneben/ darunter (z. B. Flüge gesamt und dieses Jahr).
 *    Die Zahl zählt beim Auftritt der Seite von 0 hoch.
 *  - [HeroText]: ein großer Text (z. B. die Stammstrecke) mit Unterzeile.
 *  - [Ranking]: eine Rangliste, optional in Gruppen (z. B. Privat und
 *    On Duty). Jede Zeile zeigt ihren Wert als kleine Flip-Uhr.
 *
 * Die Zahlen sind als Text und Format-Funktion hinterlegt, weil die
 * Flip-Uhr beim Count-up jeden Zwischenwert braucht.
 */
sealed interface StoryPage {

    @get:DrawableRes
    val iconRes: Int

    @get:StringRes
    val titleRes: Int

    /** Eine große Zahl (mit optionaler zweiter Zahl) als Flip-Uhr. */
    data class HeroNumber(
        override val iconRes: Int,
        override val titleRes: Int,
        /** Beschriftung über der großen Zahl. */
        @StringRes val labelRes: Int,
        /** Zielwert des Count-up. */
        val target: Double,
        /** Formatiert einen Zwischenwert des Count-up. */
        val format: (Double) -> String,
        @StringRes val unitRes: Int? = null,
        /** Zweite, kleinere Zahl (z. B. "Dieses Jahr"). */
        @StringRes val subLabelRes: Int? = null,
        val subTarget: Double? = null,
        val subFormat: ((Double) -> String)? = null,
        /** Fußzeile unter allem (z. B. Schätzungshinweis). */
        val footnote: String? = null
    ) : StoryPage

    /** Ein großer Text mit Unterzeile. */
    data class HeroText(
        override val iconRes: Int,
        override val titleRes: Int,
        val text: String,
        val subText: String?,
        /** Optionale Route für eine animierte Karte darunter. */
        val route: RouteData? = null
    ) : StoryPage {

        /** Eine Flugstrecke für die Karte unter dem Text. */
        data class RouteData(
            val fromLat: Double,
            val fromLon: Double,
            val toLat: Double,
            val toLon: Double,
            val fromCode: String,
            val toCode: String
        )
    }

    /** Eine Rangliste, optional in Gruppen (z. B. Privat und On Duty). */
    data class Ranking(
        override val iconRes: Int,
        override val titleRes: Int,
        val groups: List<Group>
    ) : StoryPage {

        /** Eine Gruppe von Zeilen mit gemeinsamer Beschriftung. */
        data class Group(
            /** Beschriftung der Gruppe, z. B. "Privat"; null für eine einzelne Gruppe. */
            @StringRes val labelRes: Int?,
            val rows: List<Row>
        )

        /**
         * Eine Zeile: Rang über [label] (mit optionaler Flagge und
         * Unterzeile), rechts der Wert als Flip-Uhr mit Suffix.
         */
        data class Row(
            val label: String,
            /** Fertig formatierter Wert, z. B. "12" oder "6.300". */
            val value: String,
            val suffix: String? = null,
            val subLabel: String? = null,
            val flag: String? = null,
            /**
             * Zielwert für die Count-up-Animation der Flip-Uhr. Ist er gesetzt,
             * zählt die Zahl beim Auftritt von 0 hoch (wie die Hero-Zahl);
             * sonst erscheint sie nur per Flip. Beides braucht [valueFormat].
             */
            val valueTarget: Double? = null,
            /** Formatiert einen Zwischenwert des Count-up. */
            val valueFormat: ((Double) -> String)? = null
        )
    }
}
