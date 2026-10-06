package com.highfly.logbook

/**
 * Fluggesellschaft und Flugnummer stehen in einem einzigen Kästchen, getrennt
 * durch ein Leerzeichen: "LH 400". Der Trenner ist wichtig, weil die Datenbank
 * zwei getrennte Felder hält und beim Bearbeiten eines gespeicherten Eintrags
 * wieder auseinandergefuehrt werden muessen - ohne Trenner ist nicht mehr zu
 * erkennen, wo die Fluggesellschaft endet und die Flugnummer beginnt.
 *
 * Solange der Nutzer tippt, ist der Trenner noch nicht getippt. Dann gilt die
 * Buchstabenfolge am Anfang als Fluggesellschaft, alles dahinter als Nummer. Das
 * funktioniert auch fuer Bestandsdaten, deren Fluggesellschaft laenger als
 * drei Zeichen ist ("Lufthansa 1172"), ohne sie zu zerschneiden.
 */
object FlightCombined {

    private const val SEPARATOR = ' '

    /** Die hoechstens [MAX_CODE_LENGTH] Buchstaben am Anfang gelten als Code. */
    private const val MAX_CODE_LENGTH = 3

    /**
     * Beide Felder fuer das Kästchen zusammenfuehren. Was fehlt, bleibt weg -
     * ein Eintrag ohne Flugnummer soll nicht als "LH " im Kästchen stehen.
     */
    fun format(airline: String?, flightNumber: String?): String {
        val code = airline?.trim().orEmpty()
        val number = flightNumber?.trim().orEmpty()
        return when {
            code.isEmpty() -> number
            number.isEmpty() -> code
            else -> "$code$SEPARATOR$number"
        }
    }

    /**
     * Das Kästchen wieder in die beiden Felder zerlegen: Fluggesellschaft in
     * Grossbuchstaben, Flugnummer so wie getippt. Ohne Trenner zaehlt die
     * Buchstabenfolge am Anfang als Fluggesellschaft.
     */
    fun split(combined: String): Pair<String, String> {
        val text = combined.trim()
        if (text.isEmpty()) return Pair("", "")
        val separator = text.indexOfFirst { it.isWhitespace() }
        if (separator >= 0) {
            return Pair(
                text.substring(0, separator).uppercase(),
                text.substring(separator + 1).trim()
            )
        }
        val letters = text.takeWhile { it.isLetter() }
        return Pair(letters.uppercase(), text.substring(letters.length).trim())
    }

    /**
     * Vorschlag fuer die Flugnummer des Rueckflugs: die Nummer des Hinflugs wird
     * eins hoeher, damit nicht zwei Eintraege dieselbe Nummer bekommen. Eine
     * selbst eingetippte Nummer hat Vorrag. Eine Nummer ohne Ziffern und eine
     * leere Angabe bleiben unveraendert.
     */
    fun nextNumber(current: String?): String {
        val trimmed = current?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val digits = trimmed.takeLastWhile { it.isDigit() }
        if (digits.isEmpty()) return trimmed
        val prefix = trimmed.dropLast(digits.length)
        val incremented = digits.toLongOrNull()?.plus(1)?.toString() ?: digits
        return prefix + incremented.padStart(digits.length, '0')
    }

    /**
     * Die Fluggesellschaft am Anfang des Kästchens wird gross geschrieben, damit
     * dort auch dann "LH" und nicht "lh" steht. Zusaetzlich kommt die
     * Cursorposition zurueck: Nach den ersten beiden Zeichen steht er am
     * Leerzeichenanfang, damit die Flugnummer direkt daneben getippt wird.
     * Laenger als zwei Zeichen lange Gesellschaften (z. B. "Lufthansa") und ein
     * schon vorhandenes Leerzeichen lassen den Cursor am Ende stehen.
     */
    fun capitalizeTyped(combined: String): Pair<String, Int> {
        val letters = combined.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return combined to combined.length
        val text = letters.uppercase() + combined.substring(letters.length)
        val caret = if (letters.length <= 2 && !text.contains(SEPARATOR)) {
            letters.length
        } else {
            text.length
        }
        return text to caret
    }

    /**
     * Was beim Tippen im Kästchen steht. Nach zwei oder drei Buchstaben wird
     * das Leerzeichen von selbst gesetzt, sobald die erste Ziffer folgt, damit
     * der Nutzer es nicht selbst tippen muss. Fuehrende Leerzeichen fallen weg.
     */
    fun normalizeTyped(combined: String): String {
        val text = combined.trimStart()
        if (text.any { it.isWhitespace() }) return text
        val letters = text.takeWhile { it.isLetter() }
        return if (letters.length in 1..MAX_CODE_LENGTH && letters.length < text.length) {
            letters + SEPARATOR + text.substring(letters.length)
        } else {
            text
        }
    }
}