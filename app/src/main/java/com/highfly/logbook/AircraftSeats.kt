package com.highfly.logbook

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Maximale Sitzplatzanzahl der gängigen Flugzeugfamilien aus
 * `assets/aircraft/aircraft_seats.json` (Single-Class-/High-Density-Maximum,
 * zertifiziert). Grundlage für die geschätzte Passagierzahl der Kachel
 * "Passagiere": Pro Flug gilt die Sitzplatzanzahl des eingetragenen Typs,
 * ein unbekannter Typ bekommt den Durchschnitt des Datensatzes.
 *
 * Neben den Familiennamen ("A320", "B737") kennt der Datensatz Aliase - die
 * üblichen Kurzcodes ("320", "738", "AT72") -, die [Catalog.seatsFor] auf die
 * Familie abbildet.
 *
 * Geladen wird einmalig, der Bestand ändert sich nicht.
 */
object AircraftSeats {

    private const val TAG = "AircraftSeats"
    private const val ASSET = "aircraft/aircraft_seats.json"

    /** Sitzplätze je Familie und Alias-Code je Familie. */
    data class Catalog(
        val seats: Map<String, Int>,
        val aliases: Map<String, String>
    ) {

        /** Familiennamen, längste zuerst - für die Präfix-Suche. */
        private val familiesByLength: List<String> by lazy {
            seats.keys.sortedByDescending { it.length }
        }

        /** Aliase, längstes zuerst - für die Präfix-Suche. */
        private val aliasesByLength: List<Map.Entry<String, String>> by lazy {
            aliases.entries.sortedByDescending { it.key.length }
        }

        /**
         * Mittlere Sitzplatzanzahl über alle Familien - der Rückfall für
         * unbekannte Typen. Ist der Datensatz leer (defektes Asset), greift
         * [DEFAULT_AVERAGE_SEATS].
         */
        val averageSeats: Double =
            if (seats.isEmpty()) DEFAULT_AVERAGE_SEATS.toDouble() else seats.values.average()

        /**
         * Sitzplätze zum eingetragenen Typ, oder null, wenn der Typ
         * unbekannt ist. Erkannt werden Familien ("A320"), Varianten
         * ("A350-900", "A321neo", "CRJ900"), Hersteller davor ("Airbus A320",
         * "Boeing 737-800") und die Alias-Codes ("320", "738", "737 MAX",
         * "B738").
         */
        fun seatsFor(type: String?): Int? {
            val norm = normalize(type)
            if (norm.isEmpty()) return null
            // Familie als Präfix, längste zuerst: "A350-900" → A350.
            familiesByLength.firstOrNull { norm.startsWith(it) }?.let { return seats[it] }
            // Alias exakt: "738" → B737.
            aliases[norm]?.let { return seats[it] }
            // Alias als Präfix, längstes zuerst: "737 MAX" → B737.
            aliasesByLength.firstOrNull { norm.startsWith(it.key) }?.let {
                return seats[it.value]
            }
            // Alias auf dem Ziffernteil: "B738" → "738" → B737.
            val digits = norm.dropWhile { it.isLetter() }
            if (digits.isNotEmpty() && digits != norm) {
                aliases[digits]?.let { return seats[it] }
                aliasesByLength.firstOrNull { digits.startsWith(it.key) }?.let {
                    return seats[it.value]
                }
            }
            return null
        }

        companion object {
            /** Rückfall-Durchschnitt, wenn der Datensatz keine Familien hat. */
            const val DEFAULT_AVERAGE_SEATS = 200
        }
    }

    /** Herstellerwörter, die vor einem Typ stehen dürfen ("Airbus A320"). */
    private val MANUFACTURER_WORDS = listOf(
        "AIRBUS", "BOEING", "BOMBARDIER", "CANADAIR", "EMBRAER",
        "DEHAVILLAND", "REGIONAL", "JET"
    )

    /**
     * Normalisiert einen Typ: Grossbuchstaben, nur Buchstaben und Ziffern,
     * führende Herstellerwörter entfernt ("Boeing 737-800" → "737800").
     */
    private fun normalize(type: String?): String {
        var norm = type?.trim()?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()
        var stripped = true
        while (stripped) {
            stripped = false
            for (word in MANUFACTURER_WORDS) {
                if (norm.startsWith(word)) {
                    norm = norm.removePrefix(word)
                    stripped = true
                    break
                }
            }
        }
        return norm
    }

    @Volatile
    private var cached: Catalog? = null

    /** Lädt das Asset einmalig und teilt es zwischen allen Aufrufern. */
    fun load(context: Context): Catalog {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = try {
                val text = context.assets.open(ASSET)
                    .bufferedReader()
                    .use { it.readText() }
                val root = JSONObject(text)
                val seatsJson = root.getJSONObject("seats")
                val seats = HashMap<String, Int>(seatsJson.length())
                val seatKeys = seatsJson.keys()
                while (seatKeys.hasNext()) {
                    val family = seatKeys.next()
                    seats[family] = seatsJson.getInt(family)
                }
                val aliases = HashMap<String, String>()
                root.optJSONObject("aliases")?.let { aliasesJson ->
                    val aliasKeys = aliasesJson.keys()
                    while (aliasKeys.hasNext()) {
                        val code = aliasKeys.next()
                        aliases[code] = aliasesJson.getString(code)
                    }
                }
                Catalog(seats, aliases)
            } catch (e: Exception) {
                Log.e(TAG, "Flugzeug-Sitzplätze konnten nicht geladen werden", e)
                Catalog(emptyMap(), emptyMap())
            }
            cached = parsed
            return parsed
        }
    }
}
