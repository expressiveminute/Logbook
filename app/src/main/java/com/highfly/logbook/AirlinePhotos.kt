package com.highfly.logbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import org.json.JSONObject

/**
 * Mitgeliefertes Foto eines Fliegers in der Bemalung einer Airline samt
 * Attribution (Autor, Lizenz, Quell-URL), siehe [AirlinePhoto]. Airlines der
 * Lufthansa Group haben mehrere Fotos, die in der App als Galerie gewischt
 * werden.
 */
data class AirlinePhoto(
    val iata: String,
    val bitmap: Bitmap,
    val author: String,
    val license: String,
    val licenseUrl: String,
    val sourceUrl: String,
    val modified: Boolean
)

/**
 * Lädt die mitgelieferten Airline-Fotos der Top 250 aus
 * `assets/airlines/photos/`. Die Bilder samt Attribution erzeugt
 * `tools/build_airline_photos.py` von Wikimedia Commons (Lizenzen: Public
 * Domain, CC0, CC BY, GFDL); die Namensnennung steht als Credit-Zeile unter
 * dem Bild. Pro Airline steht in `photos.json` ein `photos`-Array, jede Datei
 * mit eigener Attribution und ihrem Dateinamen (`<IATA>.webp`, bei einer
 * Galerie zusätzlich `<IATA>_2.webp`, `<IATA>_3.webp`).
 */
object AirlinePhotos {
    private const val TAG = "AirlinePhotos"
    private const val DIR = "airlines/photos"

    /**
     * Eine Airline belegt als RGB_565 rund 2 MB pro Foto (1280 px breit), eine
     * Galerie entsprechend mehr. Damit die Detailseiten nicht den gesamten
     * Speicher füllen, bleiben nur die zuletzt gezeigten Airlines im Cache.
     */
    private const val CACHE_ENTRIES = 4

    private val cache = LruCache<String, List<AirlinePhoto>>(CACHE_ENTRIES)

    /** Attribution je IATA-Code, einmalig geladen. */
    private var entriesByIata: Map<String, JSONObject>? = null

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (entriesByIata != null) return
        val entries = mutableMapOf<String, JSONObject>()
        try {
            val json = context.assets.open("$DIR/photos.json")
                .bufferedReader()
                .use { it.readText() }
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val iata = keys.next()
                entries[iata.uppercase()] = root.getJSONObject(iata)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Konnte Foto-Attribution nicht laden", e)
        }
        entriesByIata = entries
    }

    /**
     * Lädt die Fotos einer Airline zum IATA-Code; leer, wenn keines
     * hinterlegt ist (die Kachel bleibt dann weg). Meist ist es ein Foto,
     * bei einer Galerie (Lufthansa Group) bis zu drei.
     */
    fun load(context: Context, iata: String): List<AirlinePhoto> {
        val key = iata.trim().uppercase()
        if (key.isEmpty()) return emptyList()
        ensureLoaded(context)
        cache.get(key)?.let { return it }
        synchronized(this) {
            cache.get(key)?.let { return it }
            val entry = entriesByIata?.get(key) ?: return emptyList()
            val loaded = loadUncached(context, key, entry)
            if (loaded.isEmpty()) return emptyList()
            cache.put(key, loaded)
            return loaded
        }
    }

    private fun loadUncached(
        context: Context,
        iata: String,
        entry: JSONObject
    ): List<AirlinePhoto> {
        // Aelteres Schema ohne photos-Array: die flachen Felder sind das
        // eine Foto unter `<IATA>.webp`.
        val photos = entry.optJSONArray("photos")
        val specs = if (photos != null) {
            (0 until photos.length()).mapNotNull { index ->
                photos.optJSONObject(index)?.let { photo ->
                    photo.optString("file", "$iata.webp") to photo
                }
            }
        } else {
            listOf("$iata.webp" to entry)
        }
        val loaded = mutableListOf<AirlinePhoto>()
        for ((file, photo) in specs) {
            loadOne(context, iata, file, photo)?.let { loaded.add(it) }
        }
        return loaded
    }

    private fun loadOne(
        context: Context,
        iata: String,
        file: String,
        photo: JSONObject
    ): AirlinePhoto? = try {
        // RGB_565 statt ARGB_8888: halbiert den Speicher, Alpha braucht ein
        // Foto nicht.
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = context.assets.open("$DIR/$file").use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Bild konnte nicht dekodiert werden")
        AirlinePhoto(
            iata = iata,
            bitmap = bitmap,
            author = photo.getString("author"),
            license = photo.getString("license"),
            licenseUrl = photo.getString("licenseUrl"),
            sourceUrl = photo.getString("sourceUrl"),
            modified = photo.optBoolean("modified", false)
        )
    } catch (e: Exception) {
        Log.w(TAG, "Kein Foto für $iata ladbar ($file)", e)
        null
    }
}
