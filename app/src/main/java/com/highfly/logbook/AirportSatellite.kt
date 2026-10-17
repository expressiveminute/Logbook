package com.highfly.logbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import org.json.JSONObject

/**
 * Mitgeliefertes Satellitenbild eines Flughafens (Sentinel-2 Cloudless) samt
 * Randkoordinaten (WGS84), um es in die Web-Mercator-Karte zu legen.
 */
data class AirportSatelliteImage(
    val code: String,
    val bitmap: Bitmap,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
)

/**
 * Lädt die mitgelieferten Satellitenbilder der Top-100-Flughäfen aus
 * `assets/airport_satellite/`. Die Bilder erzeugt `tools/build_airport_satellite.py`
 * aus Sentinel-2 Cloudless (2016/2017, CC BY 4.0); die Namensnennung steht als
 * Quellennachweis unter dem Bild.
 */
object AirportSatellite {
    private const val TAG = "AirportSatellite"
    private const val DIR = "airport_satellite"

    /**
     * Ein Bild belegt als RGB_565 rund 0,8 MB. Damit die Seiten nicht den
     * gesamten Speicher füllen, bleiben nur die zuletzt gezeigten im Cache.
     */
    private const val CACHE_ENTRIES = 6

    private val cache = LruCache<String, AirportSatelliteImage>(CACHE_ENTRIES)

    fun load(context: Context, code: String): AirportSatelliteImage? {
        val key = code.uppercase()
        cache.get(key)?.let { return it }
        synchronized(this) {
            cache.get(key)?.let { return it }
            val loaded = loadUncached(context, key) ?: return null
            cache.put(key, loaded)
            return loaded
        }
    }

    private fun loadUncached(context: Context, code: String): AirportSatelliteImage? = try {
        val json = context.assets.open("$DIR/$code.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        // RGB_565 statt ARGB_8888: halbiert den Speicher, Alpha braucht ein
        // Satellitenbild nicht.
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = context.assets.open("$DIR/$code.webp").use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Bild konnte nicht dekodiert werden")
        AirportSatelliteImage(
            code = code,
            bitmap = bitmap,
            minLat = root.getDouble("min_lat"),
            minLon = root.getDouble("min_lon"),
            maxLat = root.getDouble("max_lat"),
            maxLon = root.getDouble("max_lon")
        )
    } catch (e: Exception) {
        Log.w(TAG, "Kein Satellitenbild für $code vorhanden", e)
        null
    }

    /** Quellennachweis von Sentinel-2 Cloudless, CC BY 4.0 verlangt ihn. */
    fun creditRes(): Int = R.string.satellite_credit_cloudless
}
