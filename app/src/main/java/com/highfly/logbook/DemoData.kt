package com.highfly.logbook

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Der Demodatensatz: eine Sammlung von [LogbookEntry] fuer alle, die die App
 * ohne echte Daten ausprobieren wollen.
 *
 * Zwei Teile: eine feste Reihe von Fluegen mit Hand und Namen, die
 * Sonderfaelle zeigen (Direktflug ueber den Atlantik, Rückflug mit Uebernachtung,
 * Bemerkungen, Reisebuddy) - und darueber eine erzeugte Menge, die den
 * Bestand auf [SIZE] bringt.
 *
 * Der erzeugte Teil ist kein Zufall im Sinne von Flattern: Fester Startwert,
 * also bei jedem Start dieselben Fluege in derselben Reihenfolge. Das ist
 * wichtig, weil ein Fehlerbericht dann bedeutet, dass sich die Erzeugung
 * geaendert hat, und nicht, dass der Zufall anders lief.
 *
 * Der erzeugte Teil deckt bewusst breit ab, damit sich jede Einstellung und
 * jede Kachel pruefen laesst: viele Airlines und Flugzeugtypen auf derselben
 * Strecke, Kennzeichen, die ueber Jahre wiederkommen, Reisearten und Klassen
 * in allen Auspraegungen, Uebernachtungen von zwei bis drei Tagen, Kommentare,
 * Reisebuddies, Eintraege ohne Flugzeugangaben und Fluege, die erst noch
 * bevorstehen.
 */
object DemoData {

    /**
     * Fassung des Datensatzes. Wird sie erhoeht, baut [LogbookRepository] die
     * Demodatenbank beim naechsten Start neu auf. Ohne diese Zahl bliebe der
     * alte Bestand stehen, weil die Demodatenbank nur angelegt, aber nie
     * befuellt wird, wenn sie schon Eintraege hat.
     */
    const val VERSION = 2

    /** So viele Fluege entstehen insgesamt. */
    const val SIZE = 400

    /**
     * Die Eintraege mit ihren Laendern. Ohne [Context] lassen sich die Laender
     * nicht nachschlagen, also nicht in dieser Fassung testbar - die Erzeugung
     * selbst steht in [generatedEntries].
     */
    fun entries(context: Context): List<LogbookEntry> {
        val all = entries()
        return all.map {
            it.copy(
                fromCountry = it.fromCountry ?: AirportData.country(context, it.fromAirport),
                toCountry = it.toCountry ?: AirportData.country(context, it.toAirport)
            )
        }
    }

    /**
     * Die Eintraege ohne Laender, damit die Erzeugung auch ohne Android
     * laeuft. Feste Fluege und erzeugte Fluege zusammen, nach Datum sortiert.
     */
    fun entries(): List<LogbookEntry> = entries(LocalDate.now())

    /**
     * Wie [entries], aber mit festem Stichtag. Nur so laesst sich pruefen, was
     * die App bekommen wird: Mit dem echten Heute waere der Test davon
     * abhaengig, an welchem Tag man ihn laufen laesst.
     */
    internal fun entries(today: LocalDate): List<LogbookEntry> =
        (flights + generatedEntries(today, SIZE - flights.size))
            .sortedBy { it.date }

    private fun flt(
        date: String,
        from: String,
        to: String,
        airline: String,
        flightNumber: String,
        aircraftType: String,
        registration: String,
        distanceKm: Int,
        flightMinutes: Int,
        flightType: String,
        classType: String,
        layover: Boolean = false,
        layoverHours: Int? = null,
        comment: String? = null,
        function: String? = null
    ): LogbookEntry = LogbookEntry(
        date = LocalDate.parse(date),
        flightType = flightType,
        classType = classType,
        fromAirport = from,
        toAirport = to,
        airline = airline,
        flightNumber = flightNumber,
        aircraftType = aircraftType,
        registration = registration,
        distanceKm = distanceKm,
        flightMinutes = flightMinutes,
        layover = layover,
        // Eine Uebernachtung ohne Stunden waere eine halbe Angabe: Die Kachel
        // zeigt die Stunden, also gehoeren beide immer zusammen.
        layoverHours = layoverHours ?: if (layover) 24 else null,
        comment = comment,
        function = function
    )

    private val flights = listOf(
        // LH 400/401: stark wiederkehrende Long-Haul-Strecke FRA <-> JFK
        flt("2021-06-09", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 480, "On Duty", "Business"),
        flt("2021-06-11", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 455, "On Duty", "Business", layover = true, layoverHours = 48),
        flt("2021-10-20", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 485, "On Duty", "Business"),
        flt("2021-10-22", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 450, "On Duty", "Business", layover = true, layoverHours = 48),
        flt("2022-03-16", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 480, "On Duty", "Business"),
        flt("2022-03-18", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 460, "On Duty", "Business", layover = true, layoverHours = 48),
        flt("2022-07-06", "FRA", "JFK", "LH", "401", "B747-8", "D-ABYA", 6195, 475, "On Duty", "Business", comment = "Neuer Jet"),
        flt("2022-07-08", "JFK", "FRA", "LH", "400", "B747-8", "D-ABYA", 6195, 450, "On Duty", "Business", layover = true, comment = "Erster 747-Rückflug", layoverHours = 48),
        flt("2023-05-15", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 490, "On Duty", "Premium Eco"),
        flt("2023-05-17", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 455, "On Duty", "Premium Eco", layover = true, layoverHours = 48),
        flt("2023-09-13", "FRA", "JFK", "LH", "401", "B747-8", "D-ABYA", 6195, 480, "On Duty", "First", comment = "Upper Deck"),
        flt("2023-09-15", "JFK", "FRA", "LH", "400", "B747-8", "D-ABYA", 6195, 450, "On Duty", "First", layover = true, layoverHours = 48),
        flt("2024-02-27", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 485, "On Duty", "Business"),
        flt("2024-02-29", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 455, "On Duty", "Business", layover = true, layoverHours = 48),
        flt("2024-08-05", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 480, "On Duty", "Business"),
        flt("2024-08-07", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 450, "On Duty", "Business", layover = true, layoverHours = 48),
        flt("2025-01-21", "FRA", "JFK", "LH", "401", "B747-8", "D-ABYA", 6195, 475, "On Duty", "Business"),
        flt("2025-01-23", "JFK", "FRA", "LH", "400", "B747-8", "D-ABYA", 6195, 455, "On Duty", "Business", layover = true, layoverHours = 48),
        flt("2025-06-11", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 485, "On Duty", "Business"),
        flt("2025-06-13", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 450, "On Duty", "Business", layover = true, layoverHours = 48),
        flt("2026-03-10", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 480, "On Duty", "Business"),
        flt("2026-03-12", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 455, "On Duty", "Business", layover = true, layoverHours = 48),

        // Kurzstrecke FRA <-> CDG
        flt("2022-11-04", "FRA", "CDG", "LH", "1032", "A320", "D-AIPP", 450, 75, "On Duty", "Economy"),
        flt("2022-11-05", "CDG", "FRA", "LH", "1033", "A320", "D-AIPP", 450, 75, "On Duty", "Economy"),
        flt("2024-03-28", "FRA", "CDG", "LH", "1032", "A320", "D-AIPP", 450, 70, "Privat", "Economy", comment = "Museumsbesuch"),
        flt("2024-03-30", "CDG", "FRA", "LH", "1033", "A320", "D-AIPP", 450, 75, "Privat", "Economy"),

        // Interkontinental übers Atlantik ohne FRA-Abflug
        flt("2022-04-06", "MUC", "ORD", "LH", "440", "B747-8", "D-ABYT", 7040, 570, "On Duty", "Business"),
        flt("2022-04-08", "ORD", "MUC", "LH", "441", "B747-8", "D-ABYT", 7040, 540, "On Duty", "Business", layover = true, layoverHours = 48),
        flt("2024-11-11", "FRA", "SIN", "SQ", "25", "A350-900", "9V-SWU", 10300, 740, "On Duty", "Business"),
        flt("2024-11-13", "SIN", "FRA", "SQ", "26", "A350-900", "9V-SWU", 10300, 780, "On Duty", "Business", layover = true, layoverHours = 48),
        flt("2025-11-05", "FRA", "DXB", "EK", "46", "A380-800", "A6-EOX", 4900, 390, "On Duty", "First"),
        flt("2025-11-20", "DXB", "FRA", "EK", "45", "A380-800", "A6-EOX", 4900, 415, "On Duty", "First", layover = true, layoverHours = 96),
        flt("2026-06-07", "FRA", "DOH", "QR", "68", "A350-1000", "A7-ANA", 4870, 400, "Deadhead", "Jump", function = "Purser I"),
        flt("2026-06-08", "DOH", "FRA", "QR", "67", "A350-1000", "A7-ANA", 4870, 430, "On Duty", "Jump", layover = true, layoverHours = 24),
        flt("2024-04-05", "FRA", "HND", "NH", "204", "A350-900", "JA905A", 9400, 700, "On Duty", "Business", comment = "Erstmal in Tokyo"),
        flt("2024-04-07", "HND", "FRA", "NH", "203", "A350-900", "JA905A", 9400, 720, "On Duty", "Business", layover = true, layoverHours = 48),

        // Dienstreisen / Kurzstrecken in Europa
        flt("2021-05-04", "MUC", "ZRH", "LH", "2142", "A321", "D-AIRJ", 265, 60, "Dienstreise", "Economy"),
        flt("2021-05-05", "ZRH", "MUC", "LH", "2143", "A321", "D-AIRJ", 265, 60, "Dienstreise", "Economy"),
        flt("2023-02-08", "FRA", "VIE", "OS", "124", "A319", "OE-LBF", 625, 85, "Dienstreise", "Economy"),
        flt("2023-02-09", "VIE", "FRA", "OS", "123", "A319", "OE-LBF", 625, 85, "Dienstreise", "Economy"),
        flt("2025-10-14", "FRA", "VIE", "OS", "124", "A320neo", "OE-LZN", 625, 85, "Deadhead", "Economy", function = "Purser I"),
        flt("2025-10-15", "VIE", "FRA", "OS", "123", "A320neo", "OE-LZN", 625, 85, "On Duty", "Economy"),
        flt("2024-05-20", "FRA", "ZRH", "LX", "1027", "A220-300", "HB-JBE", 315, 60, "Dienstreise", "Economy"),
        flt("2024-05-21", "ZRH", "FRA", "LX", "1026", "A220-300", "HB-JBE", 315, 60, "Dienstreise", "Economy"),
        flt("2026-07-01", "FRA", "ZRH", "LX", "1027", "A221", "HB-JCN", 315, 60, "On Duty", "Economy"),
        flt("2023-06-13", "AMS", "CDG", "KL", "1227", "B737-800", "PH-BXO", 400, 70, "Dienstreise", "Economy"),
        flt("2023-06-14", "CDG", "AMS", "KL", "1228", "B737-800", "PH-BXO", 400, 70, "Dienstreise", "Economy"),
        flt("2026-01-09", "AMS", "CDG", "KL", "1227", "E190", "PH-KZL", 400, 70, "On Duty", "Economy"),
        flt("2021-11-13", "FRA", "ORD", "UA", "945", "B777-300ER", "N2349U", 7040, 550, "On Duty", "Business"),
        flt("2021-11-15", "ORD", "FRA", "UA", "944", "B777-300ER", "N2349U", 7040, 525, "On Duty", "Business", layover = true, layoverHours = 48),
        flt("2022-10-17", "FRA", "EWR", "UA", "961", "B787-9", "N14101", 6200, 480, "On Duty", "Business"),
        flt("2022-10-19", "EWR", "FRA", "UA", "960", "B787-9", "N14101", 6200, 455, "On Duty", "Business", layover = true, layoverHours = 48),

        // British Airways LHR <-> JFK
        flt("2024-10-03", "LHR", "JFK", "BA", "117", "B787-9", "G-XWBA", 5550, 425, "On Duty", "Business"),
        flt("2024-10-05", "JFK", "LHR", "BA", "116", "B787-9", "G-XWBA", 5550, 400, "On Duty", "Business", layover = true, layoverHours = 48),
        flt("2025-08-19", "LHR", "JFK", "BA", "115", "B787-9", "G-XWBA", 5550, 430, "On Duty", "Premium Eco"),
        flt("2025-08-21", "JFK", "LHR", "BA", "114", "B787-9", "G-XWBA", 5550, 405, "On Duty", "Premium Eco", layover = true, layoverHours = 48),

        // Air France CDG <-> JFK
        flt("2023-10-22", "CDG", "JFK", "AF", "6", "A350-900", "F-HPJB", 5840, 445, "On Duty", "Business"),
        flt("2023-10-24", "JFK", "CDG", "AF", "7", "A350-900", "F-HPJB", 5840, 420, "On Duty", "Business", layover = true, layoverHours = 48),

        // Lufthansa-Kurzstrecken FRA <-> LHR mit Variationen
        flt("2023-03-21", "FRA", "LHR", "LH", "900", "A320", "D-AIPP", 655, 90, "On Duty", "Economy"),
        flt("2023-03-22", "LHR", "FRA", "LH", "901", "A320", "D-AIPP", 655, 95, "On Duty", "Economy"),
        flt("2024-09-18", "FRA", "LHR", "LH", "904", "A320", "D-AIJP", 655, 90, "On Duty", "Business"),
        flt("2024-09-19", "LHR", "FRA", "LH", "905", "A320", "D-AIJP", 655, 95, "On Duty", "Business"),
        flt("2026-08-05", "FRA", "LHR", "LH", "910", "A320neo", "D-AIBC", 655, 90, "Dienstreise", "Economy"),
        flt("2026-08-06", "LHR", "FRA", "LH", "911", "A320neo", "D-AIBC", 655, 95, "Dienstreise", "Economy"),
        flt("2022-12-13", "FRA", "LHR", "LH", "912", "A319", "D-AILW", 655, 90, "Deadhead", "Economy", comment = "Hotel-Übernahme", function = "Purser II"),
        flt("2022-12-14", "LHR", "FRA", "LH", "913", "A319", "D-AILW", 655, 95, "On Duty", "Economy"),

        // Private, einmalige Reisen
        flt("2023-08-01", "NUE", "PMI", "FR", "5637", "B737-800", "EI-DAN", 1400, 145, "Privat", "Economy", comment = "Sommerurlaub"),
        flt("2023-08-12", "PMI", "NUE", "FR", "5638", "B737-800", "EI-DAN", 1400, 150, "Privat", "Economy"),
        flt("2024-08-03", "HAJ", "AGP", "FR", "2321", "B737-800", "EI-DEN", 2300, 210, "Privat", "Economy", comment = "Andalusien"),
        flt("2024-08-17", "AGP", "HAJ", "FR", "2322", "B737-800", "EI-DEN", 2300, 215, "Privat", "Economy"),
        flt("2025-07-21", "FRA", "NCE", "LH", "1106", "A320", "D-AIJD", 490, 80, "Privat", "Economy", comment = "Côte d'Azur"),
        flt("2025-07-25", "NCE", "FRA", "LH", "1107", "A320", "D-AIJD", 490, 80, "Privat", "Economy"),
        flt("2026-06-06", "FRA", "GVA", "EW", "788", "A320", "D-ABQN", 590, 85, "Privat", "Economy"),
        flt("2026-06-07", "GVA", "FRA", "EW", "789", "A320", "D-ABQN", 590, 80, "Privat", "Economy"),
    )

    // ---------------------------------------------------------------------
    // Erzeugter Teil
    // ---------------------------------------------------------------------

    /**
     * Fester Startwert. Aendert man die Streckenliste oder die Gewichte,
     * aendert sich der Datensatz - das ist beabsichtigt und der Grund fuer
     * [VERSION].
     */
    private const val SEED = 20261004L

    /** Ab hier geht der erzeugte Teil; davor liegen die festen Fluge. */
    private val FIRST_MONTH: YearMonth = YearMonth.of(2021, 1)

    /**
     * Gewicht je Jahr: Spaetere Jahre wiegen schwerer. Wer laenger im Dienst
     * ist, hat mehr Flugege - und die Filter der Kachelzeile ("letzte 12
     * Monate", "dieses Jahr") sollen ueberhaupt etwas zu zeigen bekommen.
     */
    private val YEAR_WEIGHT = mapOf(
        2021 to 4, 2022 to 5, 2023 to 6, 2024 to 7, 2025 to 8, 2026 to 9
    )

    /**
     * So viele geplante Flugege liegen in der Zukunft. Sie sind kein Zufall,
     * sondern pruefen den Schnitt aus [UpcomingEntries], das blaue "i" am
     * Dashboards und die Gruppe "Upcoming" in der Liste.
     */
    const val UPCOMING_FLIGHTS = 8

    /** Staette, von der aus eingesetzt wird. */
    private val BASES = listOf("FRA", "FRA", "FRA", "FRA", "MUC", "DUS", "HAM", "BER")

    private val CLASSES_DUTY = listOf("Business", "Business", "Premium Eco", "Economy", "First")
    private val CLASSES_PASSENGER = listOf("Economy", "Economy", "Premium Eco", "Business")
    private val CLASSES_CREW_SEAT = listOf("Jump", "Jump", "Economy")
    private val FUNCTIONS = listOf("Purser I", "Purser I", "Flugbegleiter", "Flugbegleiter", "Purser II")

    /** Reisearten mit Anteil; der Rest ist Dienstreise oder Privat. */
    private val FLIGHT_TYPES = listOf(
        "On Duty", "On Duty", "On Duty", "On Duty", "On Duty", "On Duty",
        "Dienstreise", "Dienstreise",
        "Privat", "Privat",
        "Deadhead", "Ferry", "Ground Transfer"
    )

    /** Reisearten, bei denen eine Crew-Position eingetragen wird. */
    private val CREW_TYPES = setOf("On Duty", "Deadhead", "Ferry")

    /** Reisearten, die es auf langen Strecken nicht gibt. */
    private val SHORT_ONLY_TYPES = setOf("Ferry", "Ground Transfer")

    /** Flugzeuge, die auch ueber Kontinente fliegen. */
    private val LONG_HAUL = setOf(
        "A330-300", "A350-900", "A350-1000", "A380-800", "B747-8", "B767-300ER",
        "B777-200ER", "B777-300ER", "B787-8", "B787-9", "B787-10"
    )

    private val BUDDIES = listOf(
        "Anna", "Thomas", "Julia", "Markus", "Sarah", "Stefan", "Katrin", "Daniel", "Nina"
    )

    private val COMMENTS = listOf(
        "Technik am Boden", "Schnee, Verspätung", "Kurzfristig eingesetzt",
        "Sonderaktion", "Letzte Reihe", "Neues Flugzeug", "Wechsel im Cockpit",
        "Überführung", "Rückholflug", "Anschluss knapp", "Sommerurlaub",
        "Verwandte besucht", "Schulklasse unterwegs", "Hotel-Übernahme",
        "Tagestour", "Krankgeschrieben, Ersatz"
    )

    /** Eine Strecke mit ihren Flightnummern und der ueblichen Flugdauer. */
    private data class Route(
        val from: String,
        val to: String,
        val distanceKm: Int,
        val minutes: Int,
        val airlines: List<String>
    )

    /**
     * Ein Flugzeug mit Kennzeichen. Das Kennzeichen gehoert zum Flugzeug, nicht
     * zum Flug: [FLEET] traegt deshalb zu jedem Kennzeichen den Namen des
     * Betreibers, damit auf derselben Strecke verschiedene Airlines auch
     * verschiedene Kennzeichen tragen und dasselbe Kennzeichen ueber Jahre
     * wieder auftaucht.
     */
    private data class Airframe(
        val airline: String,
        val type: String,
        val registration: String
    ) {
        val isLongHaul: Boolean get() = type in LONG_HAUL
    }

    /**
     * Die Flotten der beteiligten Airlines. Je Airline mehrere Flugzeuge, auf
     * verschiedenen Strecken eingesetzt, damit die Kacheln fuer
     * Flugzeugtyp und Kennzeichen etwas zu zeigen haben.
     */
    private val FLEET = listOf(
        // Lufthansa
        af("LH", "A350-900", "D-AIXA"),
        af("LH", "A350-900", "D-AIXB"),
        af("LH", "A350-900", "D-AIXF"),
        af("LH", "A350-1000", "D-AIMA"),
        af("LH", "A380-800", "D-AIMC"),
        af("LH", "B747-8", "D-ABYA"),
        af("LH", "B747-8", "D-ABYP"),
        af("LH", "B777-300ER", "D-ABVK"),
        af("LH", "B787-9", "D-AIPA"),
        af("LH", "B787-9", "D-AIPD"),
        af("LH", "A330-300", "D-AIKJ"),
        af("LH", "A320neo", "D-AINB"),
        af("LH", "A320neo", "D-AINO"),
        af("LH", "A321neo", "D-AIRH"),
        af("LH", "A321neo", "D-AIRQ"),
        af("LH", "A320", "D-AIJB"),
        af("LH", "A320", "D-AILP"),
        af("LH", "A321", "D-AIRS"),
        af("LH", "A319", "D-AILN"),
        af("LH", "A319", "D-AILM"),
        af("LH", "A220-300", "D-ACPA"),
        af("LH", "A220-300", "D-ACQG"),
        af("LH", "E190", "D-AECD"),
        // Air France
        af("AF", "A350-900", "F-HPJB"),
        af("AF", "A350-900", "F-HPYK"),
        af("AF", "A380-800", "F-HPJA"),
        af("AF", "B777-300ER", "F-GSQK"),
        af("AF", "B787-9", "F-HBJI"),
        af("AF", "A320neo", "F-HKXG"),
        af("AF", "A320", "F-HKXB"),
        af("AF", "A321neo", "F-HEPJ"),
        // KLM
        af("KL", "A350-900", "PH-BVA"),
        af("KL", "A350-900", "PH-BVB"),
        af("KL", "B787-9", "PH-BHC"),
        af("KL", "E190", "PH-EZA"),
        af("KL", "E190", "PH-EZF"),
        af("KL", "A320neo", "PH-BHA"),
        // British Airways
        af("BA", "A380-800", "G-XLEA"),
        af("BA", "B787-9", "G-ZBKA"),
        af("BA", "B787-10", "G-ZBLB"),
        af("BA", "A320neo", "G-TTNG"),
        af("BA", "A320", "G-EUUK"),
        // Austrian Airlines
        af("OS", "A320", "OE-LBZ"),
        af("OS", "A320neo", "OE-LBI"),
        af("OS", "A321neo", "OE-LWG"),
        af("OS", "A220-300", "OE-LTK"),
        // Swiss
        af("LX", "A220-300", "HB-JBA"),
        af("LX", "A220-300", "HB-JBE"),
        af("LX", "A321neo", "HB-JIO"),
        af("LX", "A320neo", "HB-JJK"),
        af("LX", "B777-300ER", "HB-JNA"),
        // SAS
        af("SK", "A320neo", "SE-RTA"),
        af("SK", "A320neo", "SE-RTD"),
        af("SK", "A321neo", "SE-ROA"),
        af("SK", "A320", "SE-RKA"),
        // ITA Airways
        af("AZ", "A220-300", "I-DVBI"),
        af("AZ", "A220-300", "I-DVBF"),
        af("AZ", "A330-300", "I-DAIS"),
        af("AZ", "A320neo", "I-ADPA"),
        af("AZ", "A320", "I-ADGA"),
        // Ryanair
        af("FR", "B737-800", "EI-DAN"),
        af("FR", "B737-800", "EI-DEN"),
        af("FR", "B737-800", "EI-DIM"),
        af("FR", "B737-800", "EI-EBR"),
        // Vueling
        af("VY", "A320neo", "EC-MXG"),
        af("VY", "A320neo", "EC-MXI"),
        af("VY", "A321neo", "EC-MXJ"),
        af("VY", "A320", "EC-KXD"),
        // Eurowings
        af("EW", "A320", "D-AEBG"),
        af("EW", "A320", "D-AEBL"),
        af("EW", "A320neo", "D-ABNW"),
        af("EW", "A319", "D-AEBA"),
        // Turkish Airlines
        af("TK", "A350-900", "TC-LGA"),
        af("TK", "A350-900", "TC-LGB"),
        af("TK", "A321neo", "TC-LJH"),
        af("TK", "A321neo", "TC-LJI"),
        // Aeromexico
        af("AM", "B787-9", "XA-VHA"),
        af("AM", "B787-9", "XA-VJB"),
        af("AM", "A320", "XA-MBA"),
        // South African Airways
        af("SA", "A330-300", "ZS-SXC"),
        af("SA", "A330-300", "ZS-SXI"),
        af("SA", "A320", "ZS-SLV"),
        // Singapore Airlines
        af("SQ", "A350-1000", "9V-SQA"),
        af("SQ", "A350-900", "9V-SWA"),
        af("SQ", "A350-900", "9V-SMU"),
        af("SQ", "B787-10", "9V-SYA"),
        af("SQ", "A320", "9V-SMA"),
        // ANA
        af("NH", "A350-900", "JA901A"),
        af("NH", "B787-9", "JA02AJ"),
        af("NH", "B787-9", "JA03AJ"),
        af("NH", "B777-300ER", "JA897A"),
        // Emirates
        af("EK", "A380-800", "A6-EOQ"),
        af("EK", "A380-800", "A6-EDG"),
        af("EK", "B777-300ER", "A6-EWF"),
        af("EK", "A320", "A6-EOM"),
        // Qatar Airways
        af("QR", "A350-1000", "A7-ANA"),
        af("QR", "A350-1000", "A7-APD"),
        af("QR", "A350-900", "A7-ALH"),
        af("QR", "A320", "A7-ALC"),
        // United Airlines
        af("UA", "B787-9", "N13920"),
        af("UA", "B787-9", "N32904"),
        af("UA", "B777-200ER", "N33103"),
        af("UA", "B737-800", "N32566"),
        af("UA", "B737-900", "N33431"),
        // Delta
        af("DL", "A350-900", "N502DN"),
        af("DL", "A350-900", "N506DN"),
        af("DL", "A350-900", "N509DN"),
        af("DL", "B767-300ER", "N1408X"),
        af("DL", "B737-800", "N3724B"),
        // American Airlines
        af("AA", "B777-300ER", "N783AN"),
        af("AA", "B787-8", "N897AN"),
        af("AA", "B787-9", "N934AN"),
        af("AA", "B737-800", "N908NN"),
        af("AA", "A321", "N765AN"),
        // LATAM
        af("LA", "B787-9", "CC-BGA"),
        af("LA", "B787-9", "CC-BTA"),
        af("LA", "B767-300ER", "CC-BDJ"),
        af("LA", "A320", "CC-AWA"),
    )

    /**
     * Die Strecken: von, nach, Kilometer, Flugzeit in Minuten, Airlines, die
     * sie fliegen. Der erste Airlines-Eintrag ist der Hauptcarrier und kommt
     * am haeufigsten vor - ein Logbook gehoert schliesslich zu einer Airline.
     *
     * Kilometer und Zeiten sind gerundete Grossordnungen, keine
     * Flugroutenberechnung: Sie muessen plausibel sein, nicht exakt.
     */
    private val ROUTES = listOf(
        Route("FRA", "CDG", 450, 75, listOf("LH", "AF", "KL")),
        Route("FRA", "AMS", 400, 70, listOf("LH", "KL", "EW")),
        Route("FRA", "BER", 280, 65, listOf("LH", "EW")),
        Route("FRA", "MUC", 300, 65, listOf("LH", "EW")),
        Route("FRA", "DUS", 190, 50, listOf("LH", "EW")),
        Route("FRA", "HAM", 440, 55, listOf("LH")),
        Route("FRA", "BSL", 280, 60, listOf("LH", "EW")),
        Route("FRA", "ZRH", 315, 60, listOf("LH", "LX")),
        Route("FRA", "VIE", 625, 85, listOf("LH", "OS")),
        Route("FRA", "GVA", 590, 85, listOf("LH", "EW")),
        Route("FRA", "BRU", 320, 60, listOf("LH", "KL")),
        Route("FRA", "PRG", 380, 65, listOf("LH", "EW")),
        Route("FRA", "MXP", 350, 60, listOf("LH", "AZ", "FR")),
        Route("FRA", "WAW", 700, 80, listOf("LH", "AZ")),
        Route("FRA", "CPH", 660, 90, listOf("LH", "SK", "FR")),
        Route("FRA", "NCE", 490, 80, listOf("LH", "EW")),
        Route("FRA", "FCO", 940, 95, listOf("LH", "AZ", "FR")),
        Route("FRA", "LHR", 655, 90, listOf("LH", "BA")),
        Route("FRA", "BCN", 1150, 110, listOf("LH", "VY", "EW")),
        Route("FRA", "ARN", 1050, 110, listOf("LH", "SK", "AZ")),
        Route("FRA", "HEL", 1400, 120, listOf("LH", "AZ")),
        Route("FRA", "BUD", 750, 85, listOf("LH", "FR")),
        Route("FRA", "ATH", 1450, 135, listOf("LH", "AZ")),
        Route("FRA", "MAD", 1450, 130, listOf("LH", "VY")),
        Route("FRA", "OSL", 1150, 115, listOf("LH", "SK")),
        Route("FRA", "LIS", 1750, 165, listOf("LH", "FR")),
        Route("FRA", "IST", 1800, 165, listOf("LH", "TK")),
        Route("FRA", "PMI", 1400, 145, listOf("LH", "FR", "EW")),
        Route("FRA", "AGP", 2300, 210, listOf("LH", "FR", "EW")),
        Route("FRA", "KEF", 2600, 210, listOf("LH", "EK")),
        Route("FRA", "TLV", 2800, 220, listOf("LH", "AF")),
        Route("FRA", "CAI", 3550, 270, listOf("LH", "TK")),
        Route("FRA", "DOH", 4870, 400, listOf("LH", "QR")),
        Route("FRA", "DXB", 4900, 390, listOf("LH", "EK", "QR")),
        Route("FRA", "NBO", 6700, 480, listOf("LH", "EK")),
        Route("FRA", "JFK", 6195, 480, listOf("LH", "AA", "DL", "BA", "AF")),
        Route("FRA", "BOS", 6050, 465, listOf("LH", "DL")),
        Route("FRA", "YYZ", 5900, 465, listOf("LH")),
        Route("FRA", "EWR", 6200, 485, listOf("LH", "UA", "AA")),
        Route("FRA", "IAD", 6700, 525, listOf("LH", "UA")),
        Route("FRA", "ORD", 7040, 570, listOf("LH", "UA", "AA")),
        Route("FRA", "MIA", 7500, 570, listOf("LH", "AA", "DL")),
        Route("FRA", "SEA", 8100, 615, listOf("LH", "UA")),
        Route("FRA", "JNB", 8300, 630, listOf("LH", "SA")),
        Route("FRA", "SFO", 9200, 660, listOf("LH", "UA")),
        Route("FRA", "ICN", 8700, 690, listOf("LH", "NH")),
        Route("FRA", "BKK", 8600, 660, listOf("LH", "SQ")),
        Route("FRA", "PEK", 7800, 630, listOf("LH", "NH")),
        Route("FRA", "HKG", 9200, 720, listOf("LH", "SQ")),
        Route("FRA", "HND", 9400, 705, listOf("LH", "NH")),
        Route("FRA", "NRT", 9350, 720, listOf("LH", "NH")),
        Route("FRA", "LAX", 9600, 690, listOf("LH", "UA")),
        Route("FRA", "SIN", 10300, 765, listOf("LH", "SQ")),
        Route("FRA", "MEX", 9900, 735, listOf("LH", "AM")),
        Route("FRA", "GRU", 9900, 720, listOf("LH", "LA")),
        Route("FRA", "CPT", 9900, 690, listOf("LH", "SA")),
        Route("FRA", "LIM", 10700, 780, listOf("LH", "LA")),
        Route("FRA", "BOG", 9300, 705, listOf("LH", "LA")),
        Route("FRA", "SCL", 11800, 840, listOf("LH", "LA")),
        Route("FRA", "EZE", 11600, 810, listOf("LH", "LA", "AA")),
        Route("MUC", "FRA", 300, 65, listOf("LH", "EW")),
        Route("MUC", "CDG", 570, 85, listOf("LH", "AF", "EW")),
        Route("MUC", "IST", 2050, 180, listOf("LH", "TK")),
        Route("MUC", "BCN", 1450, 165, listOf("LH", "VY")),
        Route("MUC", "ATH", 1770, 175, listOf("LH", "AZ")),
        Route("MUC", "DXB", 4800, 380, listOf("LH", "EK", "QR")),
        Route("MUC", "BOS", 6300, 480, listOf("LH", "DL")),
        Route("MUC", "JFK", 6850, 510, listOf("LH", "UA", "DL")),
        Route("MUC", "ORD", 7040, 570, listOf("LH", "UA", "AA")),
        Route("MUC", "SFO", 9150, 675, listOf("LH", "UA")),
        Route("MUC", "LAX", 10100, 735, listOf("LH", "UA")),
        Route("DUS", "FRA", 190, 50, listOf("LH", "EW")),
        Route("DUS", "LHR", 480, 80, listOf("LH", "BA")),
        Route("DUS", "CDG", 480, 80, listOf("LH", "AF", "KL")),
        Route("DUS", "BCN", 1450, 165, listOf("LH", "VY", "EW")),
        Route("DUS", "LIS", 1900, 180, listOf("LH", "FR")),
        Route("DUS", "KEF", 2200, 195, listOf("LH", "EK")),
        Route("HAM", "FRA", 440, 55, listOf("LH")),
        Route("HAM", "CPH", 420, 60, listOf("LH", "SK", "EW")),
        Route("HAM", "MAD", 1750, 165, listOf("LH", "VY")),
        Route("HAM", "CDG", 600, 85, listOf("LH", "AF", "KL")),
        Route("HAM", "IST", 2200, 195, listOf("LH", "TK")),
        Route("BER", "FRA", 280, 65, listOf("LH", "EW")),
        Route("BER", "MUC", 500, 75, listOf("LH", "EW")),
        Route("BER", "CDG", 880, 100, listOf("LH", "AF", "EW")),
        Route("BER", "BCN", 1450, 165, listOf("LH", "VY", "EW")),
        Route("BER", "ATH", 1770, 175, listOf("LH", "AZ")),
        Route("BER", "LIS", 1900, 185, listOf("LH", "FR")),
    )

    private fun af(airline: String, type: String, registration: String) =
        Airframe(airline, type, registration)

    private fun <T> pick(rng: Random, values: List<T>): T = values[rng.nextInt(values.size)]

    /**
     * [count] zusaetzliche Flugege: [count] in der Vergangenheit bis [today]
     * und [UPCOMING_FLIGHTS] geplante danach.
     *
     * Die Verteilung ueber die Jahre entsteht aus [YEAR_WEIGHT], der Rest wird
     * auf die Monate verteilt. Jeder Monat bekommt mindestens zwei Flugege,
     * damit die Monats- und Jahresansicht nirgends leer bleibt.
     */
    internal fun generatedEntries(
        today: LocalDate,
        count: Int,
        upcomingCount: Int = UPCOMING_FLIGHTS
    ): List<LogbookEntry> {
        val rng = Random(SEED)
        val current = YearMonth.from(today)
        val months = monthsBetween(FIRST_MONTH, current)
        val weights = months.map { YEAR_WEIGHT[it.year] ?: 4 }
        val totalWeight = weights.sum()
        // Die Quote gilt fuer die Vergangenheit, die geplanten Flugege kommen
        // obendrauf - sonst waere der Datensatz groesser als gewollt.
        val pastCount = (count - upcomingCount).coerceAtLeast(0)
        val quota = weights.map { pastCount * it / totalWeight }.toMutableList()
        // Der Rest aus der Ganzzahldivision wird den fruehen Monaten gegeben,
        // sonst kaeme der Datensatz auf ein paar Fluege zu kurz.
        var rest = (pastCount - quota.sum()).coerceAtLeast(0)
        for (i in quota.indices) {
            if (rest <= 0) break
            quota[i] += 1
            rest -= 1
        }
        // Der laufende Monat kann seine Quote nicht einloesen, weil erst wenige
        // Tage vergangen sind. Was dort nicht passt, wandert in die Vormonate -
        // sonst kaemen am Ende ein Dutzend Flugege an einem einzigen Tag
        // zusammen.
        if (months.lastOrNull() == current) {
            var overflow = quota[quota.lastIndex] - today.dayOfMonth
            quota[quota.lastIndex] = today.dayOfMonth
            for (i in quota.lastIndex - 1 downTo 0) {
                if (overflow <= 0) break
                val room = months[i].lengthOfMonth() - quota[i]
                val move = min(overflow, room)
                quota[i] += move
                overflow -= move
            }
        }

        val past = mutableListOf<LogbookEntry>()
        months.forEachIndexed { index, month ->
            var left = quota[index]
            if (left <= 0) return@forEachIndexed
            val elapsed = if (month == current) today.dayOfMonth else month.lengthOfMonth()
            val days = daysFor(left, elapsed, rng)
            var dayIndex = 0
            while (left > 0) {
                val date = month.atDay(days[dayIndex.coerceAtMost(days.lastIndex)])
                val wanted = legs(rng).coerceAtMost(left)
                val made = trip(rng, date, wanted)
                past += made
                // Nicht mit der gewollten Zahl abziehen: Bricht eine Rotation
                // frueher ab, faellt sonst eine ganze Monatsquote weg.
                left -= made.size
                dayIndex++
            }
        }

        val upcoming = mutableListOf<LogbookEntry>()
        var offset = 4
        while (upcoming.size < upcomingCount) {
            val date = today.plusDays(offset.toLong())
            // Meist ein einzelner geplanter Flug, gelegentlich Hin und Rueck -
            // beides kommt im echten Buch vor und beides soll hier vorkommen.
            val legs = if (rng.nextInt(100) < 65) 1 else 2
            upcoming += trip(rng, date, legs)
            offset += 6 + rng.nextInt(21)
        }

        return (past + upcoming).sortedBy { it.date }
    }

    /**
     * Die Tage eines Monats, an denen eingesetzt wird: [flights] Tage,
     * gleichmaessig ueber [span] verteilt und leicht gewuerfelt, damit nichts
     * stoichiisch am Monatsanfang oder -ende klebt. Ein Einsatz pro Tag ist das
     * Aeusserste - alles dichter waere unglaubwuerdig.
     */
    private fun daysFor(flights: Int, span: Int, rng: Random): List<Int> {
        val usable = span.coerceIn(1, usableMonthDays)
        val count = flights.coerceIn(1, usable)
        val step = usable.toDouble() / count
        val days = (0 until count).map { i ->
            val middle = (i + 0.5) * step
            val jitter = if (count > 3) rng.nextInt(3) - 1 else 0
            (middle + jitter).toInt().coerceIn(1, usable)
        }
        return days.distinct()
    }

    /** Woerterlaenge: nicht kleiner als ein Tag, damit die Liste je Monat passt. */
    private val usableMonthDays = 28

    private fun monthsBetween(from: YearMonth, to: YearMonth): List<YearMonth> {
        val result = mutableListOf<YearMonth>()
        var month = from
        while (!month.isAfter(to)) {
            result += month
            month = month.plusMonths(1)
        }
        return result
    }

    /** Wie viele Etappen ein Einsatz hat: Hin und Rueck, einzelner Flug oder Rotation. */
    private fun legs(rng: Random): Int = when (rng.nextInt(100)) {
        in 0 until 44 -> 2
        in 44 until 62 -> 1
        in 62 until 88 -> 3
        else -> 4
    }

    /**
     * Ein Einsatz ab [start]: aufeinanderfolgende Etappen, jede von der
     * Station der vorigen. Zwischen zwei Etappen steht die Crew meist noch
     * ein bis drei Tage am Ort - diese Standtage sind die Uebernachtungen der
     * App, mit ihren Stunden.
     *
     * Auf einer Hin- und Rueckfahrt bleibt dasselbe Flugzeug am Boden und
     * fliegt zurueck, das Kennzeichen ist also auf beiden Etappen gleich.
     */
    private fun trip(rng: Random, start: LocalDate, legs: Int): List<LogbookEntry> {
        var airport = pick(rng, BASES)
        var date = start
        var plane: Airframe? = null
        val result = mutableListOf<LogbookEntry>()
        repeat(legs) { leg ->
            // Von hier aus fliegt nichts mehr, wenn die Strecke fehlt: Eine
            // Etappe weniger ist besser als eine erfundene Verbindung.
            val route = nextRoute(rng, airport) ?: return result
            val staying = if (leg == 0) 0 else if (rng.nextInt(100) < 25) 0 else 1 + rng.nextInt(12)
            date = date.plusDays(staying.toLong())
            // Auf der Hin- und Rueckfahrt bleibt dasselbe Flugzeug am Boden.
            plane = plane ?: pickAirframe(rng, route)
            result += leg(rng, route, date, plane!!, staying > 0 && leg > 0)
            airport = route.to
            if (legs != 2) plane = null
        }
        return result
    }

    /** Alle Strecken, die von [airport] starten. */
    private fun routesFrom(airport: String): List<Route> =
        ROUTES.filter { it.from == airport }

    /**
     * Die naechste Etappe ab [airport].
     *
     * Von den Ausgangsfeldern aus gibt es eigene Strecken in beide Richtungen;
     * ein Ziel wie ZRH hat aber nur eine Zeile, nämlich die Hinfahrt. Fuer die
     * Rueckfahrt wird die Strecke gespiegelt - derselbe Flug, nur umgekehrt -
     * statt eine zweite Zeile zu pflegen, die auseinanderlaufen koennte. Ohne
     * das bricht jede Rotation nach der ersten Etappe ab, und im Datensatz
     * kaemen fast nur Einzelfluge vor.
     */
    private fun nextRoute(rng: Random, airport: String): Route? {
        val direct = routesFrom(airport)
        if (direct.isNotEmpty()) return pick(rng, direct)
        val inbound = ROUTES.filter { it.to == airport }
        if (inbound.isEmpty()) return null
        val out = pick(rng, inbound)
        return Route(out.to, out.from, out.distanceKm, out.minutes, out.airlines)
    }

    /**
     * Das Flugzeug zu einer Strecke: aus der Flotte der fliegenden Airline und
     * passend zur Reichweite. Fuellt die Flotte keine passende Klasse, wird
     * das Flugzeug der Airline genommen - die Strecke bleibt unveraendert,
     * denn ein plausibles Kennzeichen ist besser als keines.
     */
    private fun pickAirframe(rng: Random, route: Route): Airframe {
        val airline = pick(rng, route.airlines)
        val ofAirline = FLEET.filter { it.airline == airline }
        if (ofAirline.isEmpty()) return pick(rng, FLEET)
        val wanted = route.distanceKm > 4000
        val suitable = ofAirline.filter { it.isLongHaul == wanted }
        return pick(rng, suitable.ifEmpty { ofAirline })
    }

    /**
     * Ein einzelner Flug. Die Felder, die ein Eintrag haben kann, werden
     * einzeln gewuerfelt: Nicht jede Flugung hat ein Kennzeichen bekannt, nicht
     * jeder Kommentar ist gesetzt, und die seltenen Faelle sollen selten
     * bleiben.
     */
    private fun leg(
        rng: Random,
        route: Route,
        date: LocalDate,
        airframe: Airframe,
        isLayover: Boolean
    ): LogbookEntry {
        // Privat und Dienstreise kommen auf jeder Strecke vor, eine Ueberfuehrung
        // oder ein Shuttletransport auf Langstrecke nicht.
        val drawn = pick(rng, FLIGHT_TYPES)
        val flightType =
            if (drawn in SHORT_ONLY_TYPES && route.distanceKm > 1500) "On Duty" else drawn
        val classType = when (flightType) {
            "Privat" -> pick(rng, CLASSES_PASSENGER)
            "Dienstreise" -> pick(rng, CLASSES_PASSENGER)
            "Deadhead", "Ferry", "Ground Transfer" -> pick(rng, CLASSES_CREW_SEAT)
            else -> pick(rng, CLASSES_DUTY)
        }
        // Flugzeugangaben fehlen manchmal: Ausweise, charter, kleine
        // Rundfluege - die Kacheln muessen das auch ohne Angabe zeigen.
        val knownAircraft = rng.nextInt(100) >= 4
        // Die Flugzeit schwankt um den Planwert, aber nicht beliebig: Die Bahn
        // liegt in Schritten von fuenf Minuten, wie es eine Flugplanung auch tut.
        val minutes = max(20, route.minutes + rng.nextInt(-12, 13) / 5 * 5)
        return LogbookEntry(
            date = date,
            flightType = flightType,
            classType = classType,
            fromAirport = route.from,
            toAirport = route.to,
            airline = airframe.airline,
            flightNumber = flightNumber(airframe.airline, route),
            aircraftType = if (knownAircraft) airframe.type else null,
            registration = if (knownAircraft) airframe.registration else null,
            distanceKm = route.distanceKm,
            flightMinutes = minutes,
            layover = isLayover,
            layoverHours = if (isLayover) 8 + rng.nextInt(64) else null,
            function = if (flightType in CREW_TYPES && rng.nextInt(100) < 85) pick(rng, FUNCTIONS) else null,
            travelBuddy = if (rng.nextInt(100) < 22) buddies(rng) else null,
            comment = if (rng.nextInt(100) < 14) pick(rng, COMMENTS) else null
        )
    }

    /**
     * Die Flugnummer zu einer Strecke. Aus Strecke und Airline berechnet, damit
     * sie stabil bleibt und nicht bei jedem Start wechselt; der
     * Hinweg und der Rueckweg unterscheiden sich um eins.
     */
    private fun flightNumber(airline: String, route: Route): String {
        val hash = route.from.hashCode() * 31 + route.to.hashCode() + airline.hashCode()
        val number = 100 + (hash and 0x7FFF) % 800
        return number.toString()
    }

    /**
     * Reisebuddies als Freitext, wie ihn die App erwartet. Ein Name kommt
     * deutlich haeufiger vor: Die Kachel zeigt die meistgeflogenen Namen, das
     * braucht einen Spitzenreiter, sonst sieht man nur eine Liste gleicher
     * Zahlen.
     */
    private fun buddies(rng: Random): String {
        val first = if (rng.nextInt(100) < 40) BUDDIES.first() else pick(rng, BUDDIES)
        return if (rng.nextInt(100) < 35) "$first, ${pick(rng, BUDDIES)}" else first
    }
}