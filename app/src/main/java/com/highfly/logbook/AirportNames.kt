package com.highfly.logbook

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Flughafenname, Stadt und - wo vorhanden - deutsche Stadt zu einem IATA-Code.
 * Koordinaten stehen in [AirportData], das Land in `airport_countries.json` -
 * nur die menschenlesbaren Felder kommen aus dem Namens-Asset.
 */
object AirportNames {

    private const val TAG = "AirportNames"
    private const val ASSET = "airports/airport_names.json"

    /**
     * @param cityDe Deutsche Schreibweise der Stadt, wenn sie sich vom
     *   [city]-Feld unterscheidet (z. B. "München" zu "Munich"). Sonst `null`.
     */
    data class Info(val name: String?, val city: String?, val cityDe: String? = null)

    @Volatile
    private var cached: Map<String, Info>? = null

    /** Lädt das Namens-Asset einmalig und teilt es zwischen allen Aufrufern. */
    fun load(context: Context): Map<String, Info> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = try {
                val text = context.assets.open(ASSET)
                    .bufferedReader()
                    .use { it.readText() }
                val root = JSONObject(text)
                val result = HashMap<String, Info>(root.length())
                val keys = root.keys()
                while (keys.hasNext()) {
                    val iata = keys.next()
                    val fields = root.getJSONObject(iata)
                    result[iata] = Info(
                        name = fields.optString("n").takeIf(String::isNotBlank),
                        city = fields.optString("c").takeIf(String::isNotBlank),
                        cityDe = fields.optString("d").takeIf(String::isNotBlank)
                    )
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Flughafennamen konnten nicht geladen werden", e)
                emptyMap()
            }
            cached = parsed
            return parsed
        }
    }

    /** Name, Stadt und deutsche Stadt zum IATA-Code; `null` bei unbekanntem Code. */
    fun get(context: Context, iata: String): Info? =
        load(context)[iata.uppercase()]
}