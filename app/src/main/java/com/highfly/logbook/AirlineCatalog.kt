package com.highfly.logbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Log
import org.json.JSONObject

object AirlineCatalog {

    private const val TAG = "AirlineCatalog"

    private var fileByIata: Map<String, String>? = null
    private var nameByIata: Map<String, String>? = null

    private val bitmapCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (fileByIata != null) return
        val files = mutableMapOf<String, String>()
        val names = mutableMapOf<String, String>()
        try {
            val json = context.assets.open("airlines/airlines.json")
                .bufferedReader()
                .use { it.readText() }
            val array = JSONObject(json).getJSONArray("airlines")
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val iata = item.getString("iata")
                if (!item.isNull("file")) {
                    files[iata] = item.getString("file")
                }
                if (!item.isNull("name")) {
                    val name = item.getString("name")
                    if (name.isNotBlank()) names[iata] = name
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Konnte Airline-Katalog nicht laden", e)
        }
        fileByIata = files
        nameByIata = names
    }

    fun airlineName(context: Context, iata: String): String? {
        ensureLoaded(context)
        return nameByIata?.get(iata.uppercase())
    }

    fun loadLogo(context: Context, iata: String): Bitmap? {
        ensureLoaded(context)
        val file = fileByIata?.get(iata.uppercase()) ?: return null
        bitmapCache.get(file)?.let { return it }
        return try {
            val bitmap = context.assets.open("airlines/$file").use {
                BitmapFactory.decodeStream(it)
            }
            if (bitmap != null) bitmapCache.put(file, bitmap)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Logo nicht ladbar: $file", e)
            null
        }
    }
}