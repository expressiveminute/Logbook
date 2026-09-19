package com.highfly.logbook

import android.content.Context
import android.util.Log
import org.json.JSONObject

object AirportData {

    private const val TAG = "AirportData"

    data class GeoLocation(val lat: Double, val lon: Double)

    private var byIata: Map<String, GeoLocation>? = null
    private var countryByIata: Map<String, String>? = null

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (byIata != null && countryByIata != null) return
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
        byIata = airports
        countryByIata = countries
    }

    fun location(context: Context, iata: String): GeoLocation? {
        ensureLoaded(context)
        return byIata?.get(iata.uppercase())
    }

    fun country(context: Context, iata: String): String? {
        ensureLoaded(context)
        return countryByIata?.get(iata.uppercase())
    }

    fun flagEmoji(iso2: String): String {
        val letters = iso2.uppercase().filter { it in 'A'..'Z' }.take(2)
        if (letters.length != 2) return ""
        val first = 0x1F1E6 + (letters[0] - 'A')
        val second = 0x1F1E6 + (letters[1] - 'A')
        return String(intArrayOf(first, second), 0, 2)
    }
}