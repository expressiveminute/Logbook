package com.highfly.logbook

import android.content.Context
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Landesgrenzen aus dem gebündelten Binär-Asset, vereinfachte Natural-Earth-
 * Grenzlinien in mehreren Detailstufen (Zoom 1-5, 5-7, 7-10).
 *
 * Wird von der Weltkarte und den Mini-Karten der Discovery-Seite geteilt, damit
 * das Format nur an einer Stelle beschrieben ist.
 */
object CountryBorders {

    const val ASSET = "country_borders.bin"

    private const val TAG = "CountryBorders"
    private const val MAGIC = 0x444E4243 // Bytes "C","B","N","D" (little-endian int)
    private const val VERSION = 1

    /**
     * Eine Detailstufe: alle Linien mit Bounding-Box und gemeinsamen
     * Koordinatenpuffer. [coords] enthält je Punkt Längengrad und Breitengrad.
     */
    class Level(
        val minZoom: Double,
        val maxZoom: Double,
        val lineCount: Int,
        val bbox: FloatArray,   // lineCount * 4: minLon, minLat, maxLon, maxLat
        val starts: IntArray,   // lineCount: Punktindex in coords
        val counts: IntArray,   // lineCount: Anzahl Lon/Lat-Paare
        val coords: FloatArray  // coords: Lon, Lat je Punkt
    )

    @Volatile
    private var cached: List<Level>? = null

    /**
     * Liest das Asset ein, oder null, wenn es nicht lesbar ist. Das Ergebnis
     * wird zwischengespeichert, weil Weltkarte und Mini-Karten dieselben
     * Grenzen teilen.
     */
    fun load(context: Context): List<Level>? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = try {
                context.assets.open(ASSET).use { parse(it.readBytes()) }
            } catch (e: Exception) {
                Log.e(TAG, "Ländergrenzen konnten nicht geladen werden", e)
                null
            }
            if (parsed != null) cached = parsed
            return parsed
        }
    }

    /** Detailstufe, die zum Zoomgrad passt. */
    fun selectLevel(levels: List<Level>, zoom: Double): Level {
        for (level in levels) {
            if (zoom >= level.minZoom && zoom < level.maxZoom) return level
        }
        return if (zoom < levels.first().minZoom) levels.first() else levels.last()
    }

    /** Sichtbar für Tests: interpretiert den Inhalt des Assets. */
    fun parse(bytes: ByteArray): List<Level> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        if (magic != MAGIC) {
            throw IOException("Ungültiges Grenz-Format (magic=$magic)")
        }
        val version = buf.get().toInt()
        if (version != VERSION) {
            throw IOException("Nicht unterstützte Grenz-Version ($version)")
        }
        val levelCount = buf.get().toInt()
        val result = ArrayList<Level>(levelCount)

        for (i in 0 until levelCount) {
            val minZoom = buf.double
            val maxZoom = buf.double
            val lineCount = readUInt(buf)
            val totalPoints = readUInt(buf)

            val bbox = FloatArray(lineCount * 4)
            val starts = IntArray(lineCount)
            val counts = IntArray(lineCount)
            val coords = FloatArray(totalPoints * 2)

            var pointOffset = 0
            for (l in 0 until lineCount) {
                bbox[l * 4] = buf.float
                bbox[l * 4 + 1] = buf.float
                bbox[l * 4 + 2] = buf.float
                bbox[l * 4 + 3] = buf.float

                val pointCount = readUInt(buf)
                if (pointOffset + pointCount > totalPoints) {
                    throw IOException("Grenz-Koordinaten überschreiten Punktzahl")
                }
                starts[l] = pointOffset
                counts[l] = pointCount
                for (p in 0 until pointCount) {
                    coords[pointOffset * 2] = buf.float
                    coords[pointOffset * 2 + 1] = buf.float
                    pointOffset++
                }
            }

            result.add(
                Level(
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    lineCount = lineCount,
                    bbox = bbox,
                    starts = starts,
                    counts = counts,
                    coords = coords
                )
            )
        }

        val sentinel = buf.float
        if (sentinel != -90.0f) {
            Log.w(TAG, "Grenz-Sentinel nicht gefunden (offset-Bug?)")
        }
        return result
    }

    private fun readUInt(buf: ByteBuffer): Int {
        val v = buf.int.toLong() and 0xFFFFFFFFL
        if (v > Int.MAX_VALUE) {
            throw IOException("Wert zu groß")
        }
        return v.toInt()
    }
}
