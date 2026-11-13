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
 *  - [BuddyPodium]: die Top-Reisebuddies als Podest - die ersten drei als
 *    große, seitlich versetzte Kreise mit Medaille, die Plätze 4-6 in einer
 *    Reihe; jeder Kreis trägt Avatar (Foto oder Usericon) und Flip-Zahl.
 *
 * Die Zahlen sind als Text und Format-Funktion hinterlegt, weil die
 * Flip-Uhr beim Count-up jeden Zwischenwert braucht.
 */
sealed interface StoryPage {

    @get:DrawableRes
    val iconRes: Int

    @get:StringRes
    val titleRes: Int

    /** false blendet das Icon oben aus (z. B. Insights-Seiten). */
    val showIcon: Boolean get() = true

    /** Eine große Zahl (mit optionaler zweiter Zahl) als Flip-Uhr. */
    data class HeroNumber(
        override val iconRes: Int,
        override val titleRes: Int,
        override val showIcon: Boolean = true,
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
        override val showIcon: Boolean = true,
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
        override val showIcon: Boolean = true,
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
         * Unterzeile), rechts der Wert als Flip-Uhr mit Suffix. Eine Zeile
         * kann beliebig viele weitere Wert-Zahlen darunter tragen
         * ([stackedValues], z. B. die Zeit in Tagen unter den Stunden) und
         * doppelt so hoch sein ([tall]). Eine Flagge lässt sich direkt hinter
         * dem Label zeigen ([labelFlag], z. B. hinter dem Dreilettercode) oder
         * darunter ([subFlag] - das Label steht dann fett darüber).
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
            val valueFormat: ((Double) -> String)? = null,
            /** Flagge direkt hinter dem Label (z. B. hinter dem Dreilettercode). */
            val labelFlag: String? = null,
            /** Flagge unter dem Label; das Label steht dann fett darüber. */
            val subFlag: String? = null,
            /** Doppelte Zeilenhöhe (für untereinander stehende Werte). */
            val tall: Boolean = false,
            /** Weitere Wert-Zahlen unter [value] (unten rechts). */
            val stackedValues: List<StackedValue> = emptyList()
        ) {

            /** Eine Wert-Zahl unter dem Hauptwert der Zeile (unten rechts). */
            data class StackedValue(
                /** Fertig formatierter Wert. */
                val text: String,
                val suffix: String? = null,
                /** Zielwert für den Count-up; null für reines Flippen. */
                val target: Double? = null,
                /** Formatiert einen Zwischenwert des Count-up. */
                val format: ((Double) -> String)? = null
            )
        }
    }

    /**
     * Übersichtsseite der Insights-Story: oben nur Icon und Erklärungstext,
     * darunter ein Kartenausschnitt mit den Top-Layoverzielen (als Strecken
     * ab der Homebase), darunter die Rangliste der Ziele.
     */
    data class MapRanking(
        override val iconRes: Int,
        override val titleRes: Int,
        override val showIcon: Boolean = true,
        /** Strecken für die Karte (z. B. Homebase -> Ziel), mit Codes. */
        val routes: List<HeroText.RouteData>,
        val groups: List<Ranking.Group>
    ) : StoryPage

    /**
     * Die Top-Reisebuddies als Podest (in der Play-Story): bis zu sechs
     * Buddies, Rang 1-3 als große Kreise, Rang 4-6 in einer Reihe.
     */
    data class BuddyPodium(
        override val iconRes: Int,
        override val titleRes: Int,
        override val showIcon: Boolean = true,
        val buddies: List<Entry>
    ) : StoryPage {

        /**
         * Ein Buddy auf dem Podest: Name, Flugzahl als Flip-Uhr und das
         * Foto (null für das Usericon). [value]/[valueTarget]/[valueFormat]
         * folgen der Konvention der Ranglisten-Zeilen.
         */
        data class Entry(
            val name: String,
            val value: String,
            val suffix: String? = null,
            val valueTarget: Double,
            val valueFormat: (Double) -> String,
            /** Zugeschnittenes Foto des Buddys; null für den Avatar. */
            val photo: android.graphics.Bitmap? = null,
            /**
             * Avatar-Drawable des Buddys ([BuddyAvatars.avatarDrawable]:
             * eigene Config aus dem Editor oder Preset), das gezeigt wird,
             * wenn es kein Foto gibt.
             */
            val avatar: android.graphics.drawable.Drawable? = null
        )
    }
}
