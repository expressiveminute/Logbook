package com.highfly.logbook

import android.content.Context
import android.util.Log
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * Landesumrisse aus dem gebündelten Binär-Asset, damit die Rubbelkarte
 * einzelne Länder farblich hervorheben kann.
 *
 * Gegenüber [CountryBorders] (nur Grenzlinien, beliebig vieler Zoomstufen)
 * liefert dieses Asset pro Land die zugehörige Fläche - gebaut von
 * `tools/build_country_shapes.py` aus den Natural-Earth-Länderdaten.
 */
object CountryShapes {

    const val ASSET = "country_shapes.bin"

    private const val TAG = "CountryShapes"
    private const val MAGIC = 0x50485343 // Bytes "C","S","H","P" (little-endian int)
    private const val VERSION = 2

    /**
     * Koordinaten liegen als signed 32-Bit-Wert in Einheiten von 1/1440 Grad im
     * Asset - das sind rund 77 m Auflösung und reichen bis zum stärksten Zoom
     * der Kugel (~50 m/Pixel). 1440 · 180 = 259200 passt in einen int32.
     */
    private const val UNITS_PER_DEGREE = 1440f

    /**
     * Ein Land mit allen zugehörigen Ringen. Die Ringe werden ohne
     * Entartungsprüfung übernommen: Die Vereinfachung im Build-Schritt kann
     * Ringe erzeugen, die sich selbst schneiden - gefüllt und als Linie gezeichnet
     * sieht man davon nichts.
     */
    class Country(
        val iso2: String,
        val nameDe: String,
        val nameEn: String,
        /** Ankerpunkt für die Beschriftung: Breite, Länge. */
        val labelLat: Double,
        val labelLon: Double,
        /** Bounding-Box in Grad: minLon, minLat, maxLon, maxLat. */
        val bbox: DoubleArray,
        /** Je Ring Längengrad und Breitengrad abwechselnd. */
        val rings: List<FloatArray>,
        /**
         * Pro Ring (parallel zu [rings]) eine Maske der gemeinsamen Grenzpunkte:
         * Punkte, die im Asset in mindestens zwei Ländern vorkommen. Die
         * Dezimierung hält diese Punkte fest ([GeoMath.decimateUnitsSafe]), damit
         * gemeinsame Grenzen in beiden Ländern bit-identisch bleiben und keine
         * Lücken zwischen den Länderflächen klaffen.
         */
        val sharedMasks: List<BooleanArray> = emptyList()
    ) {
        val minLon: Double get() = bbox[0]
        val minLat: Double get() = bbox[1]
        val maxLon: Double get() = bbox[2]
        val maxLat: Double get() = bbox[3]

        /**
         * Ausdehnung in Breitengrad. Bewusst nicht die Bounding-Box-Fläche:
         * Länder wie die USA, Russland oder Neuseeland reichen über die
         * Datumslinie und hätten sonst eine viel zu große Fläche.
         */
        val latSpan: Double get() = maxLat - minLat

        /**
         * Ringe als Einheitsvektoren auf der Kugel: x, y, z je Punkt,
         * verflacht. Einmalig gerechnet und app-weit geteilt, weil die Umrisse
         * dieselben bleiben: Dreht der Nutzer die Rubbelkarte, projiziert der
         * Aufbau jeden Punkt per Matrixmultiplikation ([GeoMath.Globe.unitPoint])
         * statt der sin/cos-Rechnung in [GeoMath.Globe.point] - bei ~80.000
         * Punkten pro Neuaufbau ist das der Unterschied zwischen flüssigem
         * und ruckelndem Drehen.
         */
        val unitRings: List<DoubleArray> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            rings.map { ring ->
                val count = ring.size / 2
                val out = DoubleArray(count * 3)
                for (i in 0 until count) {
                    val lat = Math.toRadians(ring[i * 2 + 1].toDouble())
                    val lon = Math.toRadians(ring[i * 2].toDouble())
                    val cosLat = cos(lat)
                    out[i * 3] = cosLat * cos(lon)
                    out[i * 3 + 1] = cosLat * sin(lon)
                    out[i * 3 + 2] = sin(lat)
                }
                out
            }
        }
    }

    @Volatile
    private var cached: List<Country>? = null

    /**
     * Liest das Asset ein, oder null, wenn es nicht lesbar ist. Das Ergebnis
     * wird zwischengespeichert, weil die Rubbelkarte und die Kachel der
     * Übersicht dieselben Umrisse teilen.
     */
    fun load(context: Context): List<Country>? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = try {
                context.assets.open(ASSET).use { parse(it.readBytes()) }
            } catch (e: Exception) {
                Log.e(TAG, "Länderumrisse konnten nicht geladen werden", e)
                null
            }
            if (parsed != null) cached = parsed
            return parsed
        }
    }

    /** Sichtbar für Tests: interpretiert den Inhalt des Assets. */
    fun parse(bytes: ByteArray): List<Country> = try {
        parseChecked(bytes)
    } catch (e: BufferUnderflowException) {
        // Ein abgeschnittenes Asset ist wahrscheinlicher als ein Programmier-
        // fehler; der Aufrufer kann mit einer IOException besser umgehen als mit
        // einer rohen BufferUnderflowException.
        throw IOException("Länderumriss-Datei ist unvollständig", e)
    }

    private fun parseChecked(bytes: ByteArray): List<Country> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        if (magic != MAGIC) {
            throw IOException("Ungültiges Länderumriss-Format (magic=$magic)")
        }
        val version = buf.get().toInt()
        if (version != VERSION) {
            throw IOException("Nicht unterstützte Länderumriss-Version ($version)")
        }
        buf.get() // reserviert
        val countryCount = buf.short.toInt() and 0xFFFF

        data class Head(
            val iso2: String,
            val nameDe: String,
            val nameEn: String,
            val labelLat: Double,
            val labelLon: Double,
            val bbox: DoubleArray,
            val firstRing: Int,
            val ringCount: Int
        )

        val heads = ArrayList<Head>(countryCount)
        for (i in 0 until countryCount) {
            val iso2 = String(
                byteArrayOf(buf.get(), buf.get()),
                Charsets.US_ASCII
            )
            val firstRing = readUInt(buf)
            val ringCount = readUInt(buf)
            // Anker und Bounding-Box stehen im Asset als float32, nicht als
            // double - sonst verschöbe sich der ganze Rest des Dateiinhalts.
            val labelLat = buf.float.toDouble()
            val labelLon = buf.float.toDouble()
            val bbox = DoubleArray(4) { buf.float.toDouble() }
            val nameDe = readString(buf)
            val nameEn = readString(buf)
            heads.add(
                Head(iso2, nameDe, nameEn, labelLat, labelLon, bbox, firstRing, ringCount)
            )
        }

        val ringCount = readUInt(buf)
        val ringPoints = IntArray(ringCount)
        val ringOffsets = IntArray(ringCount)
        for (i in 0 until ringCount) {
            ringPoints[i] = readUInt(buf)
            ringOffsets[i] = readUInt(buf)
        }
        val totalPoints = if (ringCount == 0) 0 else ringOffsets[ringCount - 1] + ringPoints[ringCount - 1]
        val coords = FloatArray(totalPoints * 2)
        for (p in 0 until totalPoints) {
            coords[p * 2] = buf.int / UNITS_PER_DEGREE
            coords[p * 2 + 1] = buf.int / UNITS_PER_DEGREE
        }

        // Gemeinsame Grenzpunkte bestimmen: ein Vertex (auf das 1/1440-Grad-
        // Raster gerundet), der in mindestens zwei Ländern vorkommt. Der
        // Builder hat diese Punkte in beiden Ländern bit-identisch erhalten.
        val keyUsers = HashMap<Long, Int>()
        for (head in heads) {
            val local = HashSet<Long>()
            for (r in head.firstRing until head.firstRing + head.ringCount) {
                val offset = ringOffsets[r]
                val count = ringPoints[r]
                for (p in offset until offset + count) {
                    local.add(packKey(coords[p * 2], coords[p * 2 + 1]))
                }
            }
            for (key in local) keyUsers[key] = (keyUsers[key] ?: 0) + 1
        }
        val sharedKeys = HashSet<Long>()
        for ((key, users) in keyUsers) if (users >= 2) sharedKeys.add(key)

        val countries = ArrayList<Country>(heads.size)
        for (head in heads) {
            if (head.firstRing + head.ringCount > ringCount) {
                throw IOException("Ring-Index überschreitet Ringzahl")
            }
            val rings = ArrayList<FloatArray>(head.ringCount)
            val masks = ArrayList<BooleanArray>(head.ringCount)
            for (r in 0 until head.ringCount) {
                val index = head.firstRing + r
                val offset = ringOffsets[index]
                val count = ringPoints[index]
                if (offset < 0 || count < 0 || offset + count > totalPoints) {
                    throw IOException("Ring-Koordinaten überschreiten Punktzahl")
                }
                if (count < 3) continue
                rings.add(coords.copyOfRange(offset * 2, (offset + count) * 2))
                val mask = BooleanArray(count)
                for (p in 0 until count) {
                    if (sharedKeys.contains(packKey(coords[(offset + p) * 2], coords[(offset + p) * 2 + 1]))) {
                        mask[p] = true
                    }
                }
                masks.add(mask)
            }
            countries.add(
                Country(
                    iso2 = head.iso2,
                    nameDe = head.nameDe,
                    nameEn = head.nameEn,
                    labelLat = head.labelLat,
                    labelLon = head.labelLon,
                    bbox = head.bbox,
                    rings = rings,
                    sharedMasks = masks
                )
            )
        }

        val sentinel = buf.float
        if (sentinel != -90.0f) {
            Log.w(TAG, "Länderumriss-Sentinel nicht gefunden (offset-Bug?)")
        }
        return countries
    }

    /** Packt einen 1/1440-Grad-Vertex (aus float-Koordinaten gerundet) in einen Long-Schlüssel. */
    private fun packKey(lonDeg: Float, latDeg: Float): Long {
        val x = Math.round(lonDeg * UNITS_PER_DEGREE)
        val y = Math.round(latDeg * UNITS_PER_DEGREE)
        return (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    }

    private fun readUInt(buf: ByteBuffer): Int {
        val v = buf.int.toLong() and 0xFFFFFFFFL
        if (v > Int.MAX_VALUE) {
            throw IOException("Wert zu groß")
        }
        return v.toInt()
    }

    private fun readString(buf: ByteBuffer): String {
        val length = buf.get().toInt() and 0xFF
        if (length == 0) return ""
        val bytes = ByteArray(length)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
