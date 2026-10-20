package com.highfly.logbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Log
import org.json.JSONObject

/**
 * Katalog aller in der App hinterlegten Fluggesellschaften aus
 * `assets/airlines/airlines.json`: IATA- und ICAO-Code, Name und - wo
 * vorhanden - die Datei des Logos unter `assets/airlines/liveries/`. Geladen
 * wird einmalig, der Bestand ändert sich nicht.
 *
 * Gesucht wird über IATA-Code, ICAO-Code und Name, damit "LH", "DLH" und
 * "Lufthansa" dieselbe Airline finden; die Wertung der Treffer steht ohne
 * Android-Abhängigkeiten in [AirlineStats].
 */
object AirlineCatalog {

    private const val TAG = "AirlineCatalog"

    /** Ein Eintrag des Katalogs. [file] ist der Pfad relativ zu `assets/airlines/`. */
    data class Info(
        val iata: String,
        val icao: String?,
        val name: String,
        val file: String?
    )

    private var fileByIata: Map<String, String>? = null
    private var nameByIata: Map<String, String>? = null
    private var infos: List<Info>? = null
    private var infoByIata: Map<String, Info>? = null
    private var infoByIcao: Map<String, Info>? = null
    private var infoByName: Map<String, Info>? = null

    private val bitmapCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (infos != null) return
        val files = mutableMapOf<String, String>()
        val names = mutableMapOf<String, String>()
        val all = mutableListOf<Info>()
        val byIata = mutableMapOf<String, Info>()
        val byIcao = mutableMapOf<String, Info>()
        val byName = mutableMapOf<String, Info>()
        try {
            val json = context.assets.open("airlines/airlines.json")
                .bufferedReader()
                .use { it.readText() }
            val array = JSONObject(json).getJSONArray("airlines")
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val iata = item.getString("iata")
                val icao = if (item.isNull("icao")) null
                else item.getString("icao").trim().takeIf { it.isNotEmpty() }
                val name = item.getString("name").trim()
                val file = if (item.isNull("file")) null else item.getString("file")
                if (file != null) {
                    files[iata] = file
                }
                if (name.isNotEmpty()) {
                    names[iata] = name
                }
                val info = Info(iata, icao, name, file)
                all += info
                byIata[iata] = info
                if (icao != null) {
                    byIcao.putIfAbsent(icao, info)
                }
                if (name.isNotEmpty()) {
                    byName.putIfAbsent(name.lowercase(), info)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Konnte Airline-Katalog nicht laden", e)
        }
        fileByIata = files
        nameByIata = names
        infos = all.sortedBy { it.iata }
        infoByIata = byIata
        infoByIcao = byIcao
        infoByName = byName
    }

    /** Ausgeschriebener Name zum IATA-Code, z. B. "LH" → "Lufthansa". */
    fun name(context: Context, iata: String): String? {
        ensureLoaded(context)
        return nameByIata?.get(iata.uppercase())
    }

    /** Der Katalogeintrag zu einem IATA- oder ICAO-Code, sonst null. */
    fun info(context: Context, code: String): Info? {
        ensureLoaded(context)
        val key = code.trim().uppercase()
        if (key.isEmpty()) return null
        return infoByIata?.get(key) ?: infoByIcao?.get(key)
    }

    /** Alle Katalogeinträge, nach IATA-Code sortiert. */
    fun all(context: Context): List<Info> {
        ensureLoaded(context)
        return infos.orEmpty()
    }

    /**
     * Sucht [query] in IATA-Code, ICAO-Code und Name. Exakte und beginnende
     * Treffer auf den Codes stehen oben, danach der Name; bei gleicher
     * Wertigkeit alphabetisch nach IATA-Code. Ohne Eingabe leer.
     *
     * @param limit Obergrenze, damit eine kurze Eingabe nicht die ganze Liste
     *   von über eintausend Airlines schreibt
     */
    fun search(context: Context, query: String, limit: Int): List<Info> {
        if (query.isBlank()) return emptyList()
        return all(context)
            .asSequence()
            .map { it to AirlineStats.searchScore(it.iata, it.icao, it.name, query) }
            .filter { (_, score) -> score != AirlineStats.NO_MATCH }
            .sortedWith(compareBy({ it.second }, { it.first.iata }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * Löst einen gespeicherten Airline-Text auf: erst über IATA- und ICAO-Code,
     * zuletzt über den Namen (Gross-/Kleinschreibung egal). Unbekannt bleibt
     * null - die Airline landet dann als eigener Rohtext in der Rangliste.
     */
    fun resolve(context: Context, raw: String): AirlineStats.Resolved? {
        val info = info(context, raw)
            ?: run {
                ensureLoaded(context)
                val name = raw.trim().lowercase()
                if (name.isEmpty()) null else infoByName?.get(name)
            }
            ?: return null
        return AirlineStats.Resolved(code = info.iata, icao = info.icao, name = info.name)
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
