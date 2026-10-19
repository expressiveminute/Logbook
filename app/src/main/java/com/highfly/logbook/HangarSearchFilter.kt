package com.highfly.logbook

/**
 * Reine Umformung der Eingabe im Hangar-Suchfeld, eingesetzt vom
 * Eingabefilter in TileDetailFragment. Sie steht ohne Android-Abhängigkeiten
 * hier, damit die Fälle des Filters - auch das Löschen des letzten Zeichens -
 * als Unit-Test prüfbar sind.
 *
 * Beginnt die Suche mit "D", läuft sie im Registrierungsmodus: Die Eingabe
 * wird komplett gross geschrieben und nach einem einzelnen "D" steht direkt
 * ein Strich (tippt der Nutzer "d", wird daraus "D-"). Löscht er den Strich
 * wieder, wird er nicht erneut eingesetzt, gross bleibt die Eingabe
 * trotzdem. Beginnt die Suche mit einem anderen Buchstaben (z. B. einem
 * Flugzeugtyp wie "A320"), bleibt die Eingabe so, wie der Nutzer tippt, damit
 * Kleinbuchstaben wie das "neo" erhalten bleiben. Das Filtern der Listen ist
 * ohnehin case-insensitiv.
 */
object HangarSearchFilter {

    /**
     * Wandelt eine Eingabe um. [input] ist der neu eingegebene Text, [dest]
     * der bisherige Feldinhalt; an den Stellen [dstart] bis [dend] wird er
     * ersetzt. Gibt null zurück, wenn die Eingabe unverändert bleiben darf.
     *
     * Eine leere Eingabe (Löschen) ist immer erlaubt - auch wenn dabei der
     * ganze Text verschwindet und das Feld wieder leer wird. Ohne diese
     * Regel stürzt das Löschen des letzten Zeichens ab, denn aus einem
     * leeren Text liesse sich der erste Buchstabe nicht mehr bestimmen.
     */
    fun transform(input: String, dest: String, dstart: Int, dend: Int): String? {
        if (input.isEmpty()) return null
        // Ersetzt die Eingabe den ganzen bisherigen Text, verhält sie sich
        // wie in einem leeren Feld: Der erste Buchstabe entscheidet den Modus.
        val freshStart = dest.isEmpty() || (dstart == 0 && dend == dest.length)
        return when {
            freshStart -> when {
                input.length == 1 && input[0].uppercaseChar() == 'D' -> "D-"
                else -> input.first().uppercaseChar() + input.substring(1)
            }
            // Registrierungsmodus: Der Text beginnt mit "D", also alles gross
            // schreiben - mit oder ohne Strich.
            dest.startsWith("D") -> input.uppercase()
            // Sonst (z. B. Flugzeugtyp wie "A320neo"): Eingabe unveraendert.
            else -> input
        }
    }
}
