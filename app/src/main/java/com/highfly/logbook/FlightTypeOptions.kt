package com.highfly.logbook

import android.content.Context

/**
 * Der zweite Filter der Kachelzeile ueber dem Dashboard: Welche Fluege ueberhaupt
 * in die Kacheln gezaehlt werden. "Nur Dienstlich" fasst alles zusammen, was
 * beruflich unterwegs ist - On Duty, Deadhead, Ferry, Ground Transfer und
 * Dienstreise -, "Nur Privat" genau die als Privat gespeicherten Eintraege.
 *
 * Die Zuordnung steht bewusst ueber [ChartData.normalizeFlightType] und nicht
 * ueber die Beschriftungen: Ein Eintrag kann in englischer Sprache erfasst
 * worden sein und traegt dann "Duty Travel" statt "Dienstreise".
 */
object FlightTypeOptions {

    const val KEY_ALL = "all"
    const val KEY_DUTY = "duty"
    const val KEY_PRIVATE = "private"

    /** Reihenfolge des Menues: erst das ganze Spektrum, danach die beiden Filter. */
    fun keys(): List<String> = listOf(KEY_ALL, KEY_DUTY, KEY_PRIVATE)

    fun label(context: Context, key: String): String = when (key) {
        KEY_DUTY -> context.getString(R.string.dashboard_filter_only_duty)
        KEY_PRIVATE -> context.getString(R.string.dashboard_filter_only_private)
        else -> context.getString(R.string.dashboard_filter_all_flights)
    }

    /** Unbekannter Schlüssel gilt wie "Alle Flüge" - ein Filter darf nie ausfallen. */
    fun normalize(value: String?): String =
        if (keys().contains(value)) value!! else KEY_ALL

    fun matches(entry: LogbookEntry, key: String): Boolean = when (key) {
        KEY_DUTY -> ChartData.normalizeFlightType(entry.flightType) in ChartData.DUTY_CANONICALS
        KEY_PRIVATE -> ChartData.normalizeFlightType(entry.flightType) == ChartData.PRIVATE_CANONICAL
        else -> true
    }

    /**
     * Ein Eintrag ohne Reiseart zählt bei "Nur Dienstlich" und "Nur Privat"
     * nicht mit: Er gehört zu keiner der beiden Gruppen, die das Menü anbietet.
     */
    fun filter(entries: List<LogbookEntry>, key: String): List<LogbookEntry> =
        if (key == KEY_ALL) entries else entries.filter { matches(it, key) }
}