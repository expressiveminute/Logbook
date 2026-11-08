package com.highfly.logbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Flaggen der Bundesstaaten/-länder (Deutschland/USA) als PNG-Assets unter
 * `assets/state_flags/<ISO-3166-2>.png`, z. B. `DE-BY.png` - heruntergeladen
 * von Wikimedia Commons (gemeinfrei) mit `tools/build_state_flags.py`.
 *
 * Die Bitmaps werden in einem begrenzten Cache gehalten; unbekannte Codes
 * landen in einer Negativ-Menge, damit fehlende Dateien nicht bei jeder Zeile
 * erneut gelesen werden.
 */
object StateFlags {

    private const val DIR = "state_flags"

    private val cache = LruCache<String, Bitmap>(24)
    private val missing = HashSet<String>()

    /** Lädt die Flagge zum ISO-3166-2-Code ("DE-BY"), oder null wenn es keine gibt. */
    fun load(context: Context, code: String): Bitmap? {
        val key = code.trim().uppercase()
        cache.get(key)?.let { return it }
        synchronized(missing) { if (key in missing) return null }
        val bytes = try {
            context.assets.open("$DIR/$key.png").use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bitmap != null) {
            cache.put(key, bitmap)
        } else {
            synchronized(missing) { missing.add(key) }
        }
        return bitmap
    }
}
