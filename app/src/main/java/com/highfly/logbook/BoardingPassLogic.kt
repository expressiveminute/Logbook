package com.highfly.logbook


/**
 * Reine Logik der Bordkarte ([BoardingPassFragment]) ohne Android-Abhängigkeit
 * beyond Ressourcen-Konstanten, damit sie unit-getestet werden kann
 * ([BoardingPassLogicTest]).
 */
object BoardingPassLogic {

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Schlüssel des Lufthansa-Schemas: dort trägt die Bordkarte die Akzentfarbe. */
    const val LH_KEY = "airline_lh"

    /**
     * Welche Markenfarbe der Hintergrund der Bordkarte trägt: bei Lufthansa die
     * Akzentfarbe (Gold), bei allen anderen Airline-Schemas die Primärfarbe.
     * Ohne Airline-Schema (klassische Akzentfarben) liefert die Seite kein
     * Schema und faellt auf die Theme-Farben zurueck.
     */
    fun backgroundKind(schemeKey: String?): String =
        if (schemeKey == LH_KEY) "accent" else "primary"

    /**
     * Ein-Buchstaben-Code der Reiseklasse fuer die Kachel auf der Bordkarte:
     * Y = Economy, E = Premium Eco, C = Business, F = First (J = Jump).
     * Ohne Reiseklasse null - die Kachel bleibt dann weg.
     */
    fun classLetter(classType: String?): String? = when (ChartData.normalizeClassType(classType)) {
        "Economy" -> "Y"
        "Premium Eco" -> "E"
        "Business" -> "C"
        "First" -> "F"
        "Jump" -> "J"
        else -> null
    }

    /** Index der Reiseklasse in [ClassColorSchemes.colorsFor], fuer die Kachelfarbe. */
    fun classColorIndex(classType: String?): Int? =
        ChartData.CLASS_CANONICALS.indexOf(ChartData.normalizeClassType(classType)).takeIf { it >= 0 }

    /** Hintergrundfarbe der Reiseart-Kachel, zur Reiseart passend. */
    fun typeColorRes(flightType: String?): Int? = when (ChartData.normalizeFlightType(flightType)) {
        ChartData.PRIVATE_CANONICAL -> R.color.type_private_bg
        ChartData.ON_DUTY_CANONICAL -> R.color.type_on_duty_bg
        ChartData.DEADHEAD_CANONICAL -> R.color.type_deadhead_bg
        ChartData.FERRY_CANONICAL -> R.color.type_ferry_bg
        ChartData.GROUND_TRANSFER_CANONICAL -> R.color.type_ground_transfer_bg
        ChartData.DUTY_TRAVEL_CANONICAL -> R.color.type_duty_travel_bg
        else -> null
    }

    /**
     * Ob die Reiseart eine Bordfunktion erlaubt, die als eigene Kachel daneben
     * steht: On Duty, Deadhead, Dienstreise und Ferry ja, Privat nicht (dort
     * steht stattdessen der Sitzplatz in der Kachel).
     */
    fun showsFunctionChip(flightType: String?): Boolean =
        when (ChartData.normalizeFlightType(flightType)) {
            ChartData.ON_DUTY_CANONICAL,
            ChartData.DEADHEAD_CANONICAL,
            ChartData.DUTY_TRAVEL_CANONICAL,
            ChartData.FERRY_CANONICAL -> true
            else -> false
        }

    fun isPrivate(flightType: String?): Boolean =
        ChartData.normalizeFlightType(flightType) == ChartData.PRIVATE_CANONICAL

    /**
     * Text der Zusatzkachel neben der Reiseart: bei dienstlichen Reisearten
     * die Funktions-Kurzform, bei Privat der Sitzplatz. Null, wenn keiner
     * angezeigt werden soll (dann rückt die Reiseart-Kachel nach rechts).
     */
    fun extraChipText(flightType: String?, function: String?, seat: String?): String? = when {
        showsFunctionChip(flightType) -> functionShortForm(function)
        isPrivate(flightType) -> seat?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }

    /**
     * Kurzform der Bordfunktion fuer die Kachel: Purser I -> "P1",
     * Purser II -> "P2", Flugbegleiter -> "FB". Sonstige Freitext-Funktionen
     * fallen auf die Anfangsbuchstaben zurueck (max. zwei Zeichen).
     */
    fun functionShortForm(function: String?): String? {
        val value = function?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when (Achievements.crewFunctionOf(value)) {
            Achievements.CrewFunction.PURSER_I -> "P1"
            Achievements.CrewFunction.PURSER_II -> "P2"
            Achievements.CrewFunction.FLIGHT_ATTENDANT -> "FB"
            null -> value.split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString("") { it.first().uppercase() }
                .take(2)
        }
    }

    /**
     * Textfarbe auf der Bordkarte: bei Lufthansa bewusst Weiss auf dem
     * goldenen Akzent-Hintergrund, bei allen anderen Schemata Schwarz oder
     * Weiss nach Kontrast ([onColor]).
     */
    fun textColorFor(schemeKey: String?, bg: Int): Int =
        if (schemeKey == LH_KEY) WHITE else onColor(bg)

    /**
     * Textfarbe auf der Bordkarte: Schwarz oder Weiss - je nachdem, welcher
     * Kontrast zum Hintergrund ([bg]) groesser ist (wie die on_color-Regel
     * im Generator der Airline-Schemas).
     */
    fun onColor(bg: Int): Int {
        // Bit-Operationen statt Color.red/...: In Unit-Tests ist android.graphics.Color
        // nicht gemockt.
        val r = ((bg shr 16) and 0xFF) / 255.0
        val g = ((bg shr 8) and 0xFF) / 255.0
        val b = (bg and 0xFF) / 255.0
        fun f(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val luminance = 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
        return if (luminance > 0.179) BLACK else WHITE
    }

    /** Flugzeit als "x h y min". */
    fun flightTimeText(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return "$h h $m min"
    }
}
