package com.highfly.logbook

import android.content.Context
import android.util.Log
import org.json.JSONObject

object AirportData {

    private const val TAG = "AirportData"

    data class GeoLocation(val lat: Double, val lon: Double)

    private var byIata: Map<String, GeoLocation>? = null

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (byIata != null) return
        val airports = mutableMapOf<String, GeoLocation>()
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
        byIata = airports
    }

    fun location(context: Context, iata: String): GeoLocation? {
        ensureLoaded(context)
        return byIata?.get(iata.uppercase())
    }
}