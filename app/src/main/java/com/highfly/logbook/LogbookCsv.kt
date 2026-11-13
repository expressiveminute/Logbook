package com.highfly.logbook

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object LogbookCsv {

    /** Datumsformat in der CSV-Datei: deutsch dd.MM.yyyy. */
    val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Frueheres ISO-Format yyyy-MM-dd, wird beim Import noch akzeptiert. */
    private val DATE_FORMAT_LEGACY: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** Formatiert ein Datum fuer die CSV-Datei (dd.MM.yyyy). */
    fun formatDate(date: LocalDate): String = DATE_FORMAT.format(date)

    /**
     * Liest ein Datum aus der CSV-Datei: dd.MM.yyyy, als Rueckfall das alte
     * ISO-Format yyyy-MM-dd (Bestandsdateien bleiben importierbar). Sonst null.
     */
    fun parseDate(value: String): LocalDate? {
        val text = value.trim()
        if (text.isEmpty()) return null
        return try {
            LocalDate.parse(text, DATE_FORMAT)
        } catch (e: Exception) {
            try {
                LocalDate.parse(text, DATE_FORMAT_LEGACY)
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Spalten der CSV-Datei in fester Reihenfolge: Datum, Reiseart, FROM, TO,
     * Layover, Flugzeugtyp, Registrierung, Airline, Flugnummer, Funktion,
     * Kommentar - dahinter der Rest. [fromCsv] liest die Spalten ueber die
     * Kopfzeile (Name -> Position), sodael aeltere Dateien mit anderer
     * Spaltenreihenfolge weiterhin korrekt gelesen werden; ohne Kopfzeile
     * gilt die Position dieser Reihenfolge.
     */
    private val HEADER = listOf(
        "date", "flightType", "fromAirport", "toAirport", "layover",
        "aircraftType", "registration", "airline", "flightNumber",
        "function", "comment",
        "classType", "distanceKm", "flightMinutes", "travelBuddy", "layoverHours"
    )

    fun toCsv(entries: List<LogbookEntry>): String = buildString {
        appendLine(HEADER.joinToString(","))
        entries.forEach { entry ->
            appendLine(
                listOf(
                    formatDate(entry.date),
                    entry.flightType.orEmpty(),
                    entry.fromAirport,
                    entry.toAirport,
                    if (entry.layover) "1" else "0",
                    entry.aircraftType.orEmpty(),
                    entry.registration.orEmpty(),
                    entry.airline.orEmpty(),
                    entry.flightNumber.orEmpty(),
                    entry.function.orEmpty(),
                    entry.comment.orEmpty(),
                    entry.classType.orEmpty(),
                    entry.distanceKm?.toString().orEmpty(),
                    entry.flightMinutes?.toString().orEmpty(),
                    entry.travelBuddy.orEmpty(),
                    entry.layoverHours?.toString().orEmpty()
                ).joinToString(",") { escape(it) }
            )
        }
    }

    fun fromCsv(content: String): List<LogbookEntry> {
        val text = content.removePrefix("\uFEFF")
        val rows = parse(text)
        if (rows.isEmpty()) return emptyList()
        // Kopfzeile erkennen: Spalten ueber den Namen zuordnen ( alte und
        // neue Reihenfolge ), sonst Position aus [HEADER].
        val first = rows[0]
        val headerIndex: Map<String, Int>? =
            if (first.isNotEmpty() && first[0].equals("date", ignoreCase = true)) {
                first.mapIndexed { i, name -> name.lowercase() to i }.toMap()
            } else {
                null
            }
        val dataRows = if (headerIndex != null) rows.drop(1) else rows
        val entries = mutableListOf<LogbookEntry>()
        for (fields in dataRows) {
            if (fields.size < 5) continue
            fun col(name: String): String? {
                val index = headerIndex?.get(name.lowercase()) ?: HEADER.indexOf(name)
                return fields.getOrNull(index)?.nullIfBlank()
            }
            val date = parseDate(col("date").orEmpty()) ?: continue
            val fromAirport = col("fromAirport") ?: continue
            val toAirport = col("toAirport") ?: continue
            entries += LogbookEntry(
                date = date,
                flightType = col("flightType"),
                fromAirport = fromAirport,
                toAirport = toAirport,
                layover = col("layover") == "1",
                aircraftType = col("aircraftType"),
                registration = col("registration"),
                airline = col("airline"),
                flightNumber = col("flightNumber"),
                function = col("function"),
                comment = col("comment"),
                classType = col("classType"),
                distanceKm = col("distanceKm")?.toIntOrNull(),
                flightMinutes = col("flightMinutes")?.toIntOrNull(),
                travelBuddy = col("travelBuddy"),
                layoverHours = col("layoverHours")?.toIntOrNull()
            )
        }
        return entries
    }

    private fun String.nullIfBlank(): String? = if (isBlank()) null else this

    private fun escape(value: String): String {
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return "\"" + value.replace("\"", "\"\"") + "\""
        }
        return value
    }

    private fun parse(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                c == '"' && inQuotes && i + 1 < content.length && content[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                c == '\n' && !inQuotes -> {
                    fields += current.toString()
                    rows += fields.toList()
                    fields.clear()
                    current.setLength(0)
                }
                c == '\r' && !inQuotes -> Unit
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty() || fields.isNotEmpty()) {
            fields += current.toString()
            rows += fields.toList()
        }
        return rows
    }
}