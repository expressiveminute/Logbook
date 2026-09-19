package com.highfly.logbook

import java.time.LocalDate

object LogbookCsv {

    private val HEADER = listOf(
        "date", "flightType", "classType", "fromAirport", "toAirport",
        "airline", "flightNumber", "aircraftType", "registration",
        "distanceKm", "flightMinutes", "layover", "comment", "function"
    )

    fun toCsv(entries: List<LogbookEntry>): String = buildString {
        appendLine(HEADER.joinToString(","))
        entries.forEach { entry ->
            appendLine(
                listOf(
                    entry.date.toString(),
                    entry.flightType.orEmpty(),
                    entry.classType.orEmpty(),
                    entry.fromAirport,
                    entry.toAirport,
                    entry.airline.orEmpty(),
                    entry.flightNumber.orEmpty(),
                    entry.aircraftType.orEmpty(),
                    entry.registration.orEmpty(),
                    entry.distanceKm?.toString().orEmpty(),
                    entry.flightMinutes?.toString().orEmpty(),
                    if (entry.layover) "1" else "0",
                    entry.comment.orEmpty(),
                    entry.function.orEmpty()
                ).joinToString(",") { escape(it) }
            )
        }
    }

    fun fromCsv(content: String): List<LogbookEntry> {
        val text = content.removePrefix("\uFEFF")
        val entries = mutableListOf<LogbookEntry>()
        parse(text).forEachIndexed { index, fields ->
            if (fields.size < 5) return@forEachIndexed
            if (index == 0 && fields[0].equals("date", ignoreCase = true)) return@forEachIndexed
            val date = try {
                LocalDate.parse(fields[0])
            } catch (e: Exception) {
                return@forEachIndexed
            }
            val fromAirport = fields.getOrNull(3)?.nullIfBlank()
                ?: return@forEachIndexed
            val toAirport = fields.getOrNull(4)?.nullIfBlank()
                ?: return@forEachIndexed
            entries += LogbookEntry(
                date = date,
                flightType = fields.getOrNull(1)?.nullIfBlank(),
                classType = fields.getOrNull(2)?.nullIfBlank(),
                fromAirport = fromAirport,
                toAirport = toAirport,
                airline = fields.getOrNull(5)?.nullIfBlank(),
                flightNumber = fields.getOrNull(6)?.nullIfBlank(),
                aircraftType = fields.getOrNull(7)?.nullIfBlank(),
                registration = fields.getOrNull(8)?.nullIfBlank(),
                distanceKm = fields.getOrNull(9)?.toIntOrNull(),
                flightMinutes = fields.getOrNull(10)?.toIntOrNull(),
                layover = fields.getOrNull(11) == "1",
                comment = fields.getOrNull(12)?.nullIfBlank(),
                function = fields.getOrNull(13)?.nullIfBlank()
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