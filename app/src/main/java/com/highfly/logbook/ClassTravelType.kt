package com.highfly.logbook

/**
 * Die Reiseart, nach der die Kachel "Reiseklasse" ihre Reiseklassen
 * aufschluesselt. Angeboten wird dieselbe Auswahl wie im Formular
 * "Neuer Flug": vier Kacheln in der ersten Zeile, und unter "Deadhead" eine
 * zweite Zeile, die Deadhead, Ferry und Ground Transfer trennt.
 *
 * Anders als im Formular muss hier immer eine Reiseart gewaehlt sein, denn die
 * Kachel zeigt nie "alle Klassen". Standard ist [DEFAULT] ("Privat"), damit die
 * Kachel von sich aus die privaten Fluege zeigt.
 *
 * Die Auswahl liegt in den Einstellungen, weil die Dashboard-Kachel dieselbe
 * Reiseart als Vorschau zeichnet.
 */
object ClassTravelType {

    const val DEFAULT = ChartData.PRIVATE_CANONICAL

    /** Erste Zeile: Privat, On Duty, Deadhead, Dienstreise. */
    val TOP_LEVEL: List<String> = listOf(
        ChartData.PRIVATE_CANONICAL,
        ChartData.ON_DUTY_CANONICAL,
        ChartData.DEADHEAD_CANONICAL,
        ChartData.DUTY_TRAVEL_CANONICAL,
    )

    /**
     * Zweite Zeile unter "Deadhead". Die drei Kacheln gehören alle zur obersten
     * Kachel "Deadhead", unterscheiden aber die genaue Reiseart.
     */
    val DEADHEAD_DETAIL: List<String> = listOf(
        ChartData.DEADHEAD_CANONICAL,
        ChartData.FERRY_CANONICAL,
        ChartData.GROUND_TRANSFER_CANONICAL,
    )

    private const val PRIVATE_TOP_INDEX = 0
    private const val DEADHEAD_TOP_INDEX = 2

    /** Unbekannte oder fehlende Werte fallen auf [DEFAULT] zurueck. */
    fun normalize(value: String?): String =
        value?.takeIf { it in ChartData.TRAVEL_TYPE_CANONICALS } ?: DEFAULT

    /**
     * Index der ersten Zeile fuer eine kanonische Reiseart. Ferry und Ground
     * Transfer liegen unter der Kachel "Deadhead".
     */
    fun topIndexOf(canonical: String): Int =
        if (canonical in DEADHEAD_DETAIL) {
            DEADHEAD_TOP_INDEX
        } else {
            TOP_LEVEL.indexOf(canonical).takeIf { it >= 0 } ?: PRIVATE_TOP_INDEX
        }

    /**
     * Index der zweiten Zeile (Deadhead/Ferry/Ground Transfer) oder -1, wenn die
     * Reiseart nicht zu "Deadhead" gehoert.
     */
    fun detailIndexOf(canonical: String): Int = DEADHEAD_DETAIL.indexOf(canonical)
}
