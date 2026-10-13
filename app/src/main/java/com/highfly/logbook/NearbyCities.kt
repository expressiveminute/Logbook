package com.highfly.logbook

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Ein Ankerort aus `cities_near.json`: eine Stadt im Umkreis von 100 km um die
 * Flughäfen Frankfurt (FRA) und München (MUC).
 *
 * Auf der CO₂-Ausgleichskarte ist der Ausschnitt oft sehr eng; diese Orte geben
 * dann Anhaltspunkte, wo die Waldfläche liegt. Die Liste ist bewusst auf die
 * beiden Flughäfen beschränkt und wird nur von dieser einen Karte geladen.
 */
class NearbyCity(
    val lat: Double,
    val lon: Double,
    val population: Int,
    val deName: String,
    val enName: String
) {
    /** Anzeigename entsprechend der eingestellten Städtesprache. */
    fun displayName(language: String): String = when (language) {
        Settings.CITY_LANG_DE -> deName
        Settings.CITY_LANG_EN -> enName.ifEmpty { deName }
        else -> deName.ifEmpty { enName }
    }
}

/** Lädt die Ankerorte einmalig aus dem gebündelten Asset, ohne Netzwerkzugriff. */
object NearbyCities {

    private const val TAG = "NearbyCities"
    private const val ASSET = "cities_near.json"

    @Volatile
    private var cached: List<NearbyCity>? = null

    /** Lädt die Orte einmalig und teilt das Ergebnis zwischen allen Instanzen. */
    fun load(context: Context): List<NearbyCity> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = loadUncached(context)
            cached = parsed
            return parsed
        }
    }

    /** Format je Eintrag: `[lat, lon, Einwohner, deutscherName, englischerName?]`. */
    private fun loadUncached(context: Context): List<NearbyCity> = try {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val root = JSONArray(text)
        val result = ArrayList<NearbyCity>(root.length())
        for (i in 0 until root.length()) {
            val row = root.getJSONArray(i)
            val de = row.getString(3)
            result.add(
                NearbyCity(
                    lat = row.getDouble(0),
                    lon = row.getDouble(1),
                    population = row.optInt(2, 0),
                    deName = de,
                    enName = if (row.length() > 4) row.getString(4) else de
                )
            )
        }
        result
    } catch (e: Exception) {
        Log.e(TAG, "Ankerorte konnten nicht geladen werden", e)
        emptyList()
    }
}
