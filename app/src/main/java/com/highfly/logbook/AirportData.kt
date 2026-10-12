package com.highfly.logbook

import android.content.Context
import android.util.Log
import org.json.JSONObject

object AirportData {

    private const val TAG = "AirportData"
    private const val CLOSED_ASSET = "airports/airports_closed.json"

    data class GeoLocation(val lat: Double, val lon: Double)

    private var byIata: Map<String, GeoLocation>? = null
    private var countryByIata: Map<String, String>? = null
    private var elevationByIataM: Map<String, Int>? = null
    private var icaoByIata: Map<String, String>? = null

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (byIata != null && countryByIata != null &&
            elevationByIataM != null && icaoByIata != null
        ) {
            return
        }
        val airports = mutableMapOf<String, GeoLocation>()
        val countries = mutableMapOf<String, String>()
        try {
            val json = context.assets.open("airports/airports.json")
                .bufferedReader()
                .use { it.readText() }
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val iata = keys.next()
                val coords = root.getJSONArray(iata)
                airports[iata] = GeoLocation(
                    coords.getDouble(0),
                    coords.getDouble(1)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Konnte Airport-Daten nicht laden", e)
        }
        try {
            val json = context.assets.open("airports/airports_countries.json")
                .bufferedReader()
                .use { it.readText() }
            val croot = JSONObject(json)
            val ckeys = croot.keys()
            while (ckeys.hasNext()) {
                val iata = ckeys.next()
                countries[iata] = croot.getString(iata)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Konnte Flughafen-Länder nicht laden", e)
        }
        // Geschlossene Flughäfen stehen unter "X" + damaligem Dreilettercode
        // (z. B. "XMUC" für München-Riem). Sie liegen in einem eigenen Asset,
        // damit der Generator der offenen Bestände sie nicht überschreibt.
        try {
            val json = context.assets.open(CLOSED_ASSET)
                .bufferedReader()
                .use { it.readText() }
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                val entry = root.getJSONObject(code)
                val lat = entry.optDouble("lat", Double.NaN)
                val lon = entry.optDouble("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    airports[code] = GeoLocation(lat, lon)
                }
                entry.optString("iso").takeIf(String::isNotBlank)?.let { countries[code] = it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Konnte geschlossene Flughäfen nicht laden", e)
        }
        // Höhe über N.N. je IATA-Code (in Metern) für die "Flughäfen"-Kachel:
        // sie zeigt den niedrigsten und den höchsten Flughafen des Datensatzes.
        val elevations = mutableMapOf<String, Int>()
        try {
            val json = context.assets.open("airports/airport_elevation.json")
                .bufferedReader()
                .use { it.readText() }
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val iata = keys.next()
                elevations[iata] = root.optInt(iata)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Konnte Flughafen-Höhen nicht laden", e)
        }
        byIata = airports
        countryByIata = countries
        elevationByIataM = elevations
        icaoByIata = loadIcao(context)
    }

    fun location(context: Context, iata: String): GeoLocation? {
        ensureLoaded(context)
        return byIata?.get(iata.uppercase())
    }

    fun country(context: Context, iata: String): String? {
        ensureLoaded(context)
        return countryByIata?.get(iata.uppercase())
    }

    /** Höhe des Flughafens über N.N. in Metern; `null` bei unbekannter Höhe. */
    fun elevationMeters(context: Context, iata: String): Int? {
        ensureLoaded(context)
        return elevationByIataM?.get(iata.uppercase())
    }

    /** ICAO-Code des Flughafens; `null`, wenn kein ICAO bekannt ist. */
    fun icao(context: Context, iata: String): String? {
        ensureLoaded(context)
        return icaoByIata?.get(iata.uppercase())
    }

    /**
     * Alle gespeicherten Flughafencodes (offene und geschlossene). Grundlage
     * für die Suche der Flughäfen-Kachel, die auch nie angeflogene Flughäfen
     * findet.
     */
    fun iataCodes(context: Context): Set<String> {
        ensureLoaded(context)
        return byIata?.keys ?: emptySet()
    }

    /** Lädt die ICAO-Codes je IATA-Code aus dem eigenen Asset. */
    private fun loadIcao(context: Context): Map<String, String> {
        val icao = mutableMapOf<String, String>()
        try {
            val json = context.assets.open("airports/airport_icao.json")
                .bufferedReader()
                .use { it.readText() }
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val iata = keys.next()
                icao[iata] = root.getString(iata)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Konnte Flughafen-ICAO-Codes nicht laden", e)
        }
        return icao
    }

    /**
     * Sieht der Code wie ein geschlossener Flughafen aus: vier Zeichen, die mit
     * "X" beginnen? Ein echter IATA-Code hat immer genau drei Zeichen, deshalb
     * kollidiert "X"+Code nie mit einem offenen Flughafen. Reine Formprüfung,
     * ohne Abgleich mit dem Asset.
     */
    fun isClosedCode(iata: String): Boolean =
        iata.length == 4 && iata[0].uppercaseChar() == 'X'

    fun flagEmoji(iso2: String): String {
        val letters = iso2.uppercase().filter { it in 'A'..'Z' }.take(2)
        if (letters.length != 2) return ""
        val first = 0x1F1E6 + (letters[0] - 'A')
        val second = 0x1F1E6 + (letters[1] - 'A')
        return String(intArrayOf(first, second), 0, 2)
    }
}