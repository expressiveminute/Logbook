package com.highfly.logbook

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Payload des QR-Codes auf der Bordkarte ([BoardingPassFragment]) und dessen
 * Dekodierung beim Scannen ([BoardingPassScanFragment]). Reine Logik ohne
 * Android-Abhängigkeit, damit sie unit-getestet werden kann ([QrPayloadTest]).
 *
 * Format: `LOGBOOK1|` gefolgt von einem flachen JSON-Objekt mit kurzen
 * Schlüsseln. Bewusst kein org.json, damit die Unit-Tests ohne Android-Stubs
 * laufen; das Objekt ist flach (nur String/Zahl/Boolean/null), deshalb genuegt
 * ein kleiner eigener Parser.
 *
 * Nicht enthalten: `id` (lokal), `fromCountry`/`toCountry` (werden aus den
 * Flughäfen neu berechnet) und `travelBuddy` (Datenschutz - Namen anderer
 * Personen werden nicht über den QR-Code geteilt). Der Kommentar (`cm`)
 * ist dagegen drin.
 */
object QrPayload {

    /** Präfix, an dem der Scanner erkennt, dass es ein Logbook-QR-Code ist. */
    const val PREFIX = "LOGBOOK1|"

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** Serialisiert einen Eintrag als QR-Text. Reisebuddies bleiben aussen vor. */
    fun encode(entry: LogbookEntry): String {
        val sb = StringBuilder(PREFIX).append('{')
        fun str(key: String, value: String?) {
            if (value.isNullOrBlank()) return
            if (sb.last() != '{') sb.append(',')
            sb.append('"').append(key).append("\":")
            appendEscaped(sb, value)
        }
        fun num(key: String, value: Int?) {
            if (value == null) return
            if (sb.last() != '{') sb.append(',')
            sb.append('"').append(key).append("\":").append(value)
        }
        fun bool(key: String, value: Boolean) {
            if (!value) return
            if (sb.last() != '{') sb.append(',')
            sb.append('"').append(key).append("\":true")
        }
        str("d", entry.date.format(DATE_FORMAT))
        str("ft", entry.flightType)
        str("ct", entry.classType)
        str("seat", entry.seat)
        str("from", entry.fromAirport)
        str("to", entry.toAirport)
        str("al", entry.airline)
        str("fn", entry.flightNumber)
        str("at", entry.aircraftType)
        str("reg", entry.registration)
        num("km", entry.distanceKm)
        num("min", entry.flightMinutes)
        bool("lo", entry.layover)
        num("loh", entry.layoverHours)
        str("fun", entry.function)
        str("cm", entry.comment)
        sb.append('}')
        return sb.toString()
    }

    /**
     * Dekodiert einen gescannten Text. Liefert null, wenn das Präfix fehlt,
     * das JSON kaputt ist oder Pflichtfelder (Datum, FROM/TO) fehlen bzw.
     * ungültig sind. Unbekannte Schlüssel werden übersprungen, damit künftige
     * Versionen des Payloads abwärtskompatibel bleiben.
     */
    fun decode(text: String?): LogbookEntry? {
        if (text == null || !text.startsWith(PREFIX)) return null
        val map = try {
            parseFlatObject(text.substring(PREFIX.length))
        } catch (e: Exception) {
            return null
        } ?: return null

        val date = try {
            LocalDate.parse(map["d"] as? String ?: return null, DATE_FORMAT)
        } catch (e: Exception) {
            return null
        }
        val from = (map["from"] as? String)?.trim().orEmpty()
        val to = (map["to"] as? String)?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) return null

        return LogbookEntry(
            date = date,
            flightType = (map["ft"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            classType = (map["ct"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            seat = (map["seat"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            fromAirport = from,
            toAirport = to,
            airline = (map["al"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            flightNumber = (map["fn"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            aircraftType = (map["at"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            registration = (map["reg"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            distanceKm = (map["km"] as? Number)?.toInt(),
            flightMinutes = (map["min"] as? Number)?.toInt(),
            layover = map["lo"] as? Boolean == true,
            layoverHours = (map["loh"] as? Number)?.toInt(),
            function = (map["fun"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            // travelBuddy wird absichtlich nicht dekodiert (Datenschutz).
            travelBuddy = null,
            comment = (map["cm"] as? String)?.takeIf { it.isNotEmpty() }
        )
    }

    private fun appendEscaped(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    /**
     * Parst ein flaches JSON-Objekt (`{"k":"v","n":1,"b":true,"x":null}`) in
     * eine Map. Wirft IllegalArgumentException bei kaputter Eingabe.
     */
    private fun parseFlatObject(json: String): Map<String, Any?>? {
        var i = 0
        fun skipWs() {
            while (i < json.length && json[i].isWhitespace()) i++
        }
        fun expect(c: Char) {
            skipWs()
            if (i >= json.length || json[i] != c) throw IllegalArgumentException("erwartet '$c' bei $i")
            i++
        }
        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < json.length) {
                val c = json[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= json.length) throw IllegalArgumentException("Escape am Ende")
                        when (val e = json[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                if (i + 4 > json.length) throw IllegalArgumentException("kurzes \\u")
                                sb.append(json.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("unbekanntes Escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("String nicht geschlossen")
        }
        fun expectWord(word: String) {
            if (!json.startsWith(word, i)) throw IllegalArgumentException("erwartet $word bei $i")
            i += word.length
        }
        fun parseValue(): Any? {
            skipWs()
            if (i >= json.length) throw IllegalArgumentException("Wert fehlt")
            return when (val c = json[i]) {
                '"' -> parseString()
                't' -> { expectWord("true"); true }
                'f' -> { expectWord("false"); false }
                'n' -> { expectWord("null"); null }
                else -> {
                    val start = i
                    while (i < json.length && (json[i].isDigit() || json[i] == '-' || json[i] == '+' ||
                            json[i] == '.' || json[i] == 'e' || json[i] == 'E')) i++
                    if (start == i) throw IllegalArgumentException("Wert bei $i")
                    return json.substring(start, i).toDoubleOrNull()
                        ?: throw IllegalArgumentException("Zahl kaputt bei $start")
                }
            }
        }

        skipWs()
        expect('{')
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (i < json.length && json[i] == '}') {
            i++
        } else {
            while (true) {
                val key = parseString()
                expect(':')
                map[key] = parseValue()
                skipWs()
                if (i < json.length && json[i] == ',') {
                    i++
                    continue
                }
                expect('}')
                break
            }
        }
        skipWs()
        if (i != json.length) throw IllegalArgumentException("Rest bei $i")
        return map
    }
}
