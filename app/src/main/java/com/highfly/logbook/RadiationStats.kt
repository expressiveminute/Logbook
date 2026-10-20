package com.highfly.logbook

import java.time.LocalDate

/**
 * Jahreswerte der Höhenstrahlung aus [RadiationCalculator]: die Dosis je
 * Kalenderjahr über die erfassten Flüge, absteigend nach Jahr (neuestes
 * Jahr oben). Grundlage sind alle Einträge, die die Kachel bekommt - der
 * Zeitraum-Filter des Dashboards gilt hier nicht, die Kachel zeigt das
 * laufende Jahr, die Detailseite alle Jahre.
 */
object RadiationStats {

    /** Dosis und Flugzeit eines Kalenderjahrs. */
    data class YearDose(
        val year: Int,
        val uSv: Double,
        val minutes: Int
    )

    /** Werte für Kachel und Detailseite: laufendes Jahr, Gesamtsumme, Jahre. */
    data class Summary(
        val year: Int,
        val yearUSv: Double,
        val totalUSv: Double,
        val years: List<YearDose>
    )

    /**
     * Dosis je Kalenderjahr, neuestes Jahr zuerst. Einträge ohne Dosis
     * (z. B. ohne Flugzeit und Strecke) tragen ein Jahr ohne Wert nicht in
     * die Liste ein - ein Jahr ohne geflogenen Flug erscheint nicht.
     */
    fun byYear(
        entries: List<LogbookEntry>,
        lookup: (String) -> AirportData.GeoLocation?
    ): List<YearDose> {
        val flying = DashboardStats.flyingEntries(entries)
        return flying.groupBy { it.date.year }
            .map { (year, yearEntries) ->
                YearDose(
                    year = year,
                    uSv = RadiationCalculator.totalUSv(yearEntries, lookup),
                    minutes = yearEntries.sumOf { it.flightMinutes ?: 0 }
                )
            }
            .sortedByDescending { it.year }
    }

    /**
     * Kachel- und Detailwerte: Dosis des laufenden Jahrs ([today] nur für
     * Tests), Gesamtsumme über alle Jahre und die Jahresliste.
     */
    fun summary(
        entries: List<LogbookEntry>,
        lookup: (String) -> AirportData.GeoLocation?,
        today: LocalDate = LocalDate.now()
    ): Summary {
        val years = byYear(entries, lookup)
        return Summary(
            year = today.year,
            yearUSv = years.firstOrNull { it.year == today.year }?.uSv ?: 0.0,
            totalUSv = years.sumOf { it.uSv },
            years = years
        )
    }
}
