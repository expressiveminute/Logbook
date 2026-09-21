package com.highfly.logbook

import java.time.LocalDate

object DemoData {

    fun entries(): List<LogbookEntry> = flights

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
        comment = comment,
        function = function
    )

    private val flights = listOf(
        // LH 400/401: stark wiederkehrende Long-Haul-Strecke FRA <-> JFK
        flt("2021-06-09", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 480, "On Duty", "Business"),
        flt("2021-06-11", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 455, "On Duty", "Business", layover = true),
        flt("2021-10-20", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 485, "On Duty", "Business"),
        flt("2021-10-22", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 450, "On Duty", "Business", layover = true),
        flt("2022-03-16", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 480, "On Duty", "Business"),
        flt("2022-03-18", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 460, "On Duty", "Business", layover = true),
        flt("2022-07-06", "FRA", "JFK", "LH", "401", "B747-8", "D-ABYA", 6195, 475, "On Duty", "Business", comment = "Neuer Jet"),
        flt("2022-07-08", "JFK", "FRA", "LH", "400", "B747-8", "D-ABYA", 6195, 450, "On Duty", "Business", layover = true, comment = "Erster 747-Rückflug"),
        flt("2023-05-15", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 490, "On Duty", "Premium Eco"),
        flt("2023-05-17", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 455, "On Duty", "Premium Eco", layover = true),
        flt("2023-09-13", "FRA", "JFK", "LH", "401", "B747-8", "D-ABYA", 6195, 480, "On Duty", "First", comment = "Upper Deck"),
        flt("2023-09-15", "JFK", "FRA", "LH", "400", "B747-8", "D-ABYA", 6195, 450, "On Duty", "First", layover = true),
        flt("2024-02-27", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 485, "On Duty", "Business"),
        flt("2024-02-29", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 455, "On Duty", "Business", layover = true),
        flt("2024-08-05", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 480, "On Duty", "Business"),
        flt("2024-08-07", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 450, "On Duty", "Business", layover = true),
        flt("2025-01-21", "FRA", "JFK", "LH", "401", "B747-8", "D-ABYA", 6195, 475, "On Duty", "Business"),
        flt("2025-01-23", "JFK", "FRA", "LH", "400", "B747-8", "D-ABYA", 6195, 455, "On Duty", "Business", layover = true),
        flt("2025-06-11", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 485, "On Duty", "Business"),
        flt("2025-06-13", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 450, "On Duty", "Business", layover = true),
        flt("2026-03-10", "FRA", "JFK", "LH", "401", "A350-900", "D-AIXA", 6195, 480, "On Duty", "Business"),
        flt("2026-03-12", "JFK", "FRA", "LH", "400", "A350-900", "D-AIXA", 6195, 455, "On Duty", "Business", layover = true),

        // Kurzstrecke FRA <-> CDG
        flt("2022-11-04", "FRA", "CDG", "LH", "1032", "A320", "D-AIPP", 450, 75, "On Duty", "Economy"),
        flt("2022-11-05", "CDG", "FRA", "LH", "1033", "A320", "D-AIPP", 450, 75, "On Duty", "Economy"),
        flt("2024-03-28", "FRA", "CDG", "LH", "1032", "A320", "D-AIPP", 450, 70, "Privat", "Economy", comment = "Museumsbesuch"),
        flt("2024-03-30", "CDG", "FRA", "LH", "1033", "A320", "D-AIPP", 450, 75, "Privat", "Economy"),

        // Interkontinental übers Atlantik ohne FRA-Abflug
        flt("2022-04-06", "MUC", "ORD", "LH", "440", "B747-8", "D-ABYT", 7040, 570, "On Duty", "Business"),
        flt("2022-04-08", "ORD", "MUC", "LH", "441", "B747-8", "D-ABYT", 7040, 540, "On Duty", "Business", layover = true),
        flt("2024-11-11", "FRA", "SIN", "SQ", "25", "A350-900", "9V-SWU", 10300, 740, "On Duty", "Business"),
        flt("2024-11-13", "SIN", "FRA", "SQ", "26", "A350-900", "9V-SWU", 10300, 780, "On Duty", "Business", layover = true),
        flt("2025-11-05", "FRA", "DXB", "EK", "46", "A380-800", "A6-EOX", 4900, 390, "On Duty", "First"),
        flt("2025-11-20", "DXB", "FRA", "EK", "45", "A380-800", "A6-EOX", 4900, 415, "On Duty", "First", layover = true),
        flt("2026-06-07", "FRA", "DOH", "QR", "68", "A350-1000", "A7-ANA", 4870, 400, "Deadhead", "Jump", function = "Purser I"),
        flt("2026-06-08", "DOH", "FRA", "QR", "67", "A350-1000", "A7-ANA", 4870, 430, "On Duty", "Jump", layover = true),
        flt("2024-04-05", "FRA", "HND", "NH", "204", "A350-900", "JA905A", 9400, 700, "On Duty", "Business", comment = "Erstmal in Tokyo"),
        flt("2024-04-07", "HND", "FRA", "NH", "203", "A350-900", "JA905A", 9400, 720, "On Duty", "Business", layover = true),

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
        flt("2021-11-15", "ORD", "FRA", "UA", "944", "B777-300ER", "N2349U", 7040, 525, "On Duty", "Business", layover = true),
        flt("2022-10-17", "FRA", "EWR", "UA", "961", "B787-9", "N14101", 6200, 480, "On Duty", "Business"),
        flt("2022-10-19", "EWR", "FRA", "UA", "960", "B787-9", "N14101", 6200, 455, "On Duty", "Business", layover = true),

        // British Airways LHR <-> JFK
        flt("2024-10-03", "LHR", "JFK", "BA", "117", "B787-9", "G-XWBA", 5550, 425, "On Duty", "Business"),
        flt("2024-10-05", "JFK", "LHR", "BA", "116", "B787-9", "G-XWBA", 5550, 400, "On Duty", "Business", layover = true),
        flt("2025-08-19", "LHR", "JFK", "BA", "115", "B787-9", "G-XWBA", 5550, 430, "On Duty", "Premium Eco"),
        flt("2025-08-21", "JFK", "LHR", "BA", "114", "B787-9", "G-XWBA", 5550, 405, "On Duty", "Premium Eco", layover = true),

        // Air France CDG <-> JFK
        flt("2023-10-22", "CDG", "JFK", "AF", "6", "A350-900", "F-HPJB", 5840, 445, "On Duty", "Business"),
        flt("2023-10-24", "JFK", "CDG", "AF", "7", "A350-900", "F-HPJB", 5840, 420, "On Duty", "Business", layover = true),

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
}