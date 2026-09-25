package com.highfly.logbook

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Ein Ort aus `cities.json` für Kartenbeschriftungen.
 *
 * Je Eintrag liefert das Asset Breitengrad, Längengrad, Rang, Hauptstadt-Kennzeichen
 * sowie den deutschen und englischen Namen.
 */
class MapCity(
    val lat: Double,
    val lon: Double,
    val rank: Int,
    val isCapital: Boolean,
    val nativeName: String,
    val deName: String,
    val enName: String
) {
    /** Anzeigename entsprechend der in den Einstellungen gewählten Sprache. */
    fun displayName(language: String): String = when (language) {
        Settings.CITY_LANG_DE -> deName
        Settings.CITY_LANG_EN -> enName
        else -> nativeName
    }

    /** Bedeutung: Hauptstädte zuerst, dann nach Rang (0 = größte Stadt). */
    fun importance(): Int = (if (isCapital) 0 else 10) + rank
}

/** Lädt die Städteliste aus dem gebündelten Asset, ohne Netzwerkzugriff. */
object MapCities {

    private const val TAG = "MapCities"
    private const val ASSET = "cities.json"

    @Volatile
    private var cached: List<MapCity>? = null

    /** Lädt die Städte einmalig und teilt das Ergebnis zwischen allen Karten. */
    fun load(context: Context): List<MapCity> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = loadUncached(context)
            cached = parsed
            return parsed
        }
    }

    private fun loadUncached(context: Context): List<MapCity> = try {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val result = ArrayList<MapCity>(root.length())
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val coords = root.getJSONArray(name)
            result.add(
                MapCity(
                    lat = coords.getDouble(0),
                    lon = coords.getDouble(1),
                    rank = coords.optInt(2, 3),
                    isCapital = coords.optInt(3, 0) == 1,
                    nativeName = name,
                    deName = if (coords.length() > 4) coords.getString(4) else name,
                    enName = if (coords.length() > 5) coords.getString(5) else name
                )
            )
        }
        result
    } catch (e: Exception) {
        Log.e(TAG, "Städte konnten nicht geladen werden", e)
        emptyList()
    }
}
