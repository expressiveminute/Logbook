package com.highfly.logbook

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Geschätzte Passagierzahlen der Kachel "Passagiere": Pro Flug die maximale
 * Sitzplatzanzahl des eingetragenen Flugzeugtyps (siehe [AircraftSeats])
 * mal der eingestellten Auslastung. Nur Flüge der Reiseart "On Duty" zählen -
 * bei Deadhead, Dienstreise, Privat und Ground Transfer ist die Besatzung
 * nicht im Flugdienst, die Passagiere werden dem Flug nicht zugerechnet; ein
 * unbekannter Typ bekommt den Durchschnitt des Datensatzes.
 *
 * Die Zahlen sind Schätzungen: Die Sitzplätze sind die zertifizierten
 * Single-Class-Maxima, die reale Bestuhlung der Airline liegt meist darunter,
 * und die Auslastung ist ein Mittelwert. Deshalb werden Summen auf volle
 * Tausender gerundet ([roundToThousands]) - sie sollen keine Genauigkeit auf
 * Hunderter oder Einer vortäuschen.
 *
 * Grundlage ist dieselbe Auswahl wie die Kachel "Höhenstrahlung": der
 * Flugart-Filter gilt, der Zeitraum-Filter des Dashboards nicht - die Kachel
 * zählt alle Jahre, die Detailseite listet sie einzeln.
 */
object PassengersCalculator {

    /** Standard-Auslastung in Prozent. */
    const val DEFAULT_LOAD_FACTOR_PERCENT = 80

    /** Kleinste einstellbare Auslastung in Prozent. */
    const val MIN_LOAD_FACTOR_PERCENT = 50

    /** Grösste einstellbare Auslastung in Prozent. */
    const val MAX_LOAD_FACTOR_PERCENT = 100

    /** Schrittweite der Auslastung in Prozent. */
    const val LOAD_FACTOR_STEP_PERCENT = 5

    /** Passagiere und Flüge eines Kalenderjahrs. */
    data class YearPassengers(
        val year: Int,
        val passengers: Long,
        val flights: Int
    )

    /** Werte für Kachel und Detailseite: Gesamtsumme, Durchschnitt, Jahre. */
    data class Summary(
        val totalPassengers: Long,
        val flights: Int,
        val averagePerFlight: Long,
        val years: List<YearPassengers>
    )

    /**
     * Auslastung auf den nächsten gültigen Wert: begrenzt auf 50..100 und
     * abgerundet auf 5er-Schritte.
     */
    fun normalizeLoadFactor(percent: Int): Int =
        (percent.coerceIn(MIN_LOAD_FACTOR_PERCENT, MAX_LOAD_FACTOR_PERCENT) /
            LOAD_FACTOR_STEP_PERCENT) * LOAD_FACTOR_STEP_PERCENT

    /**
     * Ob der Eintrag als "On Duty" geflogen wurde. Nur diese Flüge zählen:
     * Bei Deadhead, Dienstreise, Privat und Ground Transfer ist die Besatzung
     * nicht im Flugdienst. Die Reiseart wird über
     * [ChartData.normalizeFlightType] verglichen, damit auch anders
     * geschriebene Einträge („on duty") richtig erkannt werden; ein Eintrag
     * ohne Reiseart zählt nicht.
     */
    fun isOnDuty(entry: LogbookEntry): Boolean =
        ChartData.normalizeFlightType(entry.flightType) == ChartData.ON_DUTY_CANONICAL

    /**
     * Geschätzte Passagiere eines Flugs: maximale Sitzplatzanzahl des Typs
     * (Durchschnitt des Datensatzes bei unbekanntem Typ) mal Auslastung.
     * Nur ein Flug als "On Duty" zählt, alles andere ergibt 0.
     */
    fun passengersPerFlight(
        entry: LogbookEntry,
        loadFactorPercent: Int,
        catalog: AircraftSeats.Catalog
    ): Long {
        if (!isOnDuty(entry)) return 0
        val seats = catalog.seatsFor(entry.aircraftType)?.toDouble()
            ?: catalog.averageSeats
        return (seats * normalizeLoadFactor(loadFactorPercent) / 100.0).roundToLong()
    }

    /**
     * Passagiere je Kalenderjahr, neustes Jahr zuerst. Nur Flüge als
     * "On Duty" zählen - ein Jahr ohne solchen Flug erscheint nicht.
     */
    fun byYear(
        entries: List<LogbookEntry>,
        loadFactorPercent: Int,
        catalog: AircraftSeats.Catalog
    ): List<YearPassengers> {
        val onDuty = entries.filter { isOnDuty(it) }
        return onDuty.groupBy { it.date.year }
            .map { (year, yearEntries) ->
                YearPassengers(
                    year = year,
                    passengers = yearEntries.sumOf {
                        passengersPerFlight(it, loadFactorPercent, catalog)
                    },
                    flights = yearEntries.size
                )
            }
            .sortedByDescending { it.year }
    }

    /** Kachel- und Detailwerte über alle Jahre. */
    fun summary(
        entries: List<LogbookEntry>,
        loadFactorPercent: Int,
        catalog: AircraftSeats.Catalog
    ): Summary {
        val years = byYear(entries, loadFactorPercent, catalog)
        val total = years.sumOf { it.passengers }
        val flights = years.sumOf { it.flights }
        return Summary(
            totalPassengers = total,
            flights = flights,
            averagePerFlight = if (flights > 0) {
                (total.toDouble() / flights).roundToLong()
            } else {
                0
            },
            years = years
        )
    }

    /**
     * Rundet auf volle Tausender. Die Schätzung (Maximalbestuhlung ×
     * Auslastung) trägt eine echte Ungenauigkeit, deshalb zeigen Summen
     * keine Scheingenauigkeit auf Hunderter oder Einer.
     */
    fun roundToThousands(value: Long): Long =
        (value / 1000.0).roundToLong() * 1000

    /** Passagierzahl mit Tausendertrennzeichen der App-Sprache. */
    fun passengersText(value: Long, locale: Locale = Locale.GERMANY): String =
        String.format(locale, "%,d", value)
}
