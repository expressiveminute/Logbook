package com.highfly.logbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import org.json.JSONObject

/**
 * Mitgeliefertes Foto eines Flugzeugtyps samt Attribution (Autor, Lizenz,
 * Quell-URL), siehe [AircraftPhoto]. Pro Typ gibt es bis zu zwei Fotos:
 * die Herstellervariante (Werkslackierung/Prototyp) und optional die
 * Bemalung des Launch Customers (Erstkunde).
 */
data class AircraftPhoto(
    val type: String,
    val kind: Kind,
    val bitmap: Bitmap,
    val author: String,
    val license: String,
    val licenseUrl: String,
    val sourceUrl: String,
    val modified: Boolean
) {
    /** Fotoart: Herstellervariante oder Launch-Customer-Bemalung. */
    enum class Kind {
        MANUFACTURER,
        LAUNCH;

        companion object {
            fun fromString(value: String?): Kind =
                if (value == "launch") LAUNCH else MANUFACTURER
        }
    }
}

/**
 * Lädt die mitgelieferten Flugzeugtyp-Fotos aus `assets/aircraft/photos/`.
 * Die Bilder samt Attribution erzeugt `tools/build_aircraft_photos.py` von
 * Wikimedia Commons (Lizenzen: Public Domain, CC0, CC BY, GFDL); die
 * Namensnennung steht als Credit-Zeile unter dem Bild. Pro Typ steht in
 * `photos.json` ein Eintrag mit einem `photos`-Array - das erste Bild ist
 * die Herstellervariante (`<CODE>.webp`), optional folgt die Bemalung des
 * Launch Customers (`<CODE>_launch.webp`). Der Code ist derselbe kanonische
 * Schlüssel wie in [AircraftFacts].
 */
object AircraftPhotos {
    private const val TAG = "AircraftPhotos"
    private const val DIR = "aircraft/photos"

    /**
     * Ein Typ belegt als RGB_565 rund 2 MB (zwei Fotos à 1280 px breit).
     * Damit die Detailseiten nicht den gesamten Speicher füllen, bleiben nur
     * die zuletzt gezeigten Typen im Cache.
     */
    private const val CACHE_ENTRIES = 6

    private val cache = LruCache<String, List<AircraftPhoto>>(CACHE_ENTRIES)

    /** Attribution je Typ-Code, einmalig geladen. */
    private var entriesByType: Map<String, JSONObject>? = null

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (entriesByType != null) return
        val entries = mutableMapOf<String, JSONObject>()
        try {
            val json = context.assets.open("$DIR/photos.json")
                .bufferedReader()
                .use { it.readText() }
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                entries[code] = root.getJSONObject(code)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Konnte Foto-Attribution nicht laden", e)
        }
        entriesByType = entries
    }

    /**
     * Lädt alle Fotos zum kanonischen Typ-Code; leer, wenn keines hinterlegt
     * ist (die Fotokachel bleibt dann weg). Das erste Foto ist immer die
     * Herstellervariante, danach folgt optional die Launch-Customer-Bemalung.
     */
    fun load(context: Context, type: String): List<AircraftPhoto> {
        val key = type.trim()
        if (key.isEmpty()) return emptyList()
        ensureLoaded(context)
        cache.get(key)?.let { return it }
        synchronized(this) {
            cache.get(key)?.let { return it }
            val loaded = loadUncached(context, key)
            cache.put(key, loaded)
            return loaded
        }
    }

    private fun loadUncached(context: Context, type: String): List<AircraftPhoto> {
        val entry = entriesByType?.get(type) ?: return emptyList()
        val photosArray = entry.optJSONArray("photos") ?: return emptyList()
        val result = mutableListOf<AircraftPhoto>()
        for (i in 0 until photosArray.length()) {
            val photo = photosArray.optJSONObject(i) ?: continue
            val file = photo.optString("file", "$type.webp")
            try {
                // RGB_565 statt ARGB_8888: halbiert den Speicher, Alpha braucht
                // ein Foto nicht.
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bitmap = context.assets.open("$DIR/$file").use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: continue
                result.add(
                    AircraftPhoto(
                        type = type,
                        kind = AircraftPhoto.Kind.fromString(photo.optString("kind", "manufacturer")),
                        bitmap = bitmap,
                        author = photo.getString("author"),
                        license = photo.getString("license"),
                        licenseUrl = photo.getString("licenseUrl"),
                        sourceUrl = photo.getString("sourceUrl"),
                        modified = photo.optBoolean("modified", false)
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Kein Foto für $type ladbar ($file)", e)
            }
        }
        return result
    }
}
