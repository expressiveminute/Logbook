package com.highfly.logbook

import androidx.annotation.StringRes

/**
 * Wer hinter dem Logbuch steht: Besatzung oder Fluggast.
 *
 * Die Rolle ist eine globale Einstellung des Nutzers (siehe [Settings]) und
 * liegt als Schluessel in den SharedPreferences. Sie ist bewusst nur eine
 * grobe Einordnung und keine Eigenschaft des einzelnen Flugs: Eine Person ist
 * entweder Besatzung oder Fluggast.
 *
 * Die App ist fuer die Besatzung gebaut: Wer [CREW] waehlt, schaltet alle
 * Funktionalitaeten frei - auch die privaten und dienstlichen Fluege, denn ein
 * Besatzungsmitglied reist ebenso als Fluggast mit. Wer [PASSENGER] waehlt,
 * reist nur privat oder dienstlich.
 *
 * Aus der Rolle leitet die App ab, was fuer den Nutzer ueberhaupt Sinn ergibt -
 * die waehlbaren Reisearten, das Feld "Funktion" und die Layover-Angabe. Neue
 * Funktionen sollen gegen diese Definition arbeiten und nicht gegen den
 * gespeicherten Text.
 *
 *  - [CREW] arbeitet an Bord. Fluege sind Dienst (On Duty, Deadhead, Ferry,
 *    Ground Transfer), es gibt eine Bordfunktion (Purser, Flugbegleitung) und
 *    eine bevorzugte Arbeitsposition; zwischen zwei Fluegen liegt ein Layover.
 *    Daneben fliegt die Besatzung auch privat und dienstlich.
 *  - [PASSENGER] reist als Fluggast mit, privat oder dienstlich. Es gibt keine
 *    Bordfunktion, kein Layover und keine bevorzugte Arbeitsposition. Diese
 *    Dinge werden weder eingegeben noch angezeigt - in keinem Formular, in
 *    keiner Kachel und in keinem Filter.
 *
 * Beim Umschalten zwischen den Rollen bleiben die erfassten Eintraege
 * unveraendert. Es aendert sich nur, was der Nutzer sieht und neu eingeben
 * kann; [Settings.setRole] schreibt allein den Rollen-Schluessel und fasst die
 * Datenbank nicht an.
 */
enum class Role(val key: String, @StringRes val labelRes: Int) {

    CREW("crew", R.string.role_crew),
    PASSENGER("passenger", R.string.role_passenger);

    /** Besatzung an Bord - Gegenstueck zu [isPassenger]. */
    val isCrew: Boolean get() = this == CREW

    /** Fluggast an Bord - Gegenstueck zu [isCrew]. */
    val isPassenger: Boolean get() = this == PASSENGER

    /** Ob die Rolle ein Layover kennt. Nur die Besatzung hat welches. */
    val showsLayover: Boolean get() = isCrew

    /** Ob die Rolle eine Bordfunktion kennt. Nur die Besatzung hat eine. */
    val showsFunction: Boolean get() = isCrew

    /**
     * Die Reisearten, die diese Rolle waehlen darf, in kanonischer Schreibweise
     * und in der Reihenfolge des Formulars "Neuer Flug". Die Besatzung darf
     * alles, der Fluggast nur privat und dienstlich.
     */
    val travelTypes: List<String>
        get() = if (isCrew) {
            ChartData.TRAVEL_TYPE_CANONICALS
        } else {
            listOf(ChartData.PRIVATE_CANONICAL, ChartData.DUTY_TRAVEL_CANONICAL)
        }

    /**
     * Ob diese Rolle eine Reiseart waehlen darf. Bereits gespeicherte Eintraege
     * bleiben beim Rollenwechsel erhalten, deshalb betrifft das nur die Auswahl
     * im Formular und in den Filtern - nicht die Anzeige vorhandener Daten.
     */
    fun allowsTravelType(canonical: String?): Boolean =
        canonical == null || canonical in travelTypes

    companion object {

        /** Ohne gespeicherte Wahl gilt Besatzung, wie seit jeher. */
        val DEFAULT: Role = CREW

        /** Reihenfolge in den Einstellungen: Besatzung zuerst. */
        val selectable: List<Role> = listOf(CREW, PASSENGER)

        /**
         * Liest die Rolle aus einem gespeicherten Schluessel. Unbekannte oder
         * fehlende Werte ergeben [DEFAULT], damit der Aufrufer nie ohne Rolle
         * dasteht.
         */
        fun fromKey(value: String?): Role =
            entries.firstOrNull { it.key == value } ?: DEFAULT
    }
}
