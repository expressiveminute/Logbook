package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fully offline country border overlay.
 *
 * Reads simplified Natural Earth 1:10m international boundary lines (with
 * multiple levels of detail) from a bundled binary asset and strokes them
 * directly onto the canvas. No network access is ever performed.
 */
class OfflineBorderOverlay(
    private val context: Context
) : Overlay() {

    private companion object {
        const val TAG = "OfflineBorderOverlay"
        const val ASSET = "country_borders.bin"
        const val MAGIC = 0x444E4243 // bytes "C","B","N","D" (little-endian int)
        const val VERSION = 1
    }

    private class Level(
        val minZoom: Double,
        val maxZoom: Double,
        val lineCount: Int,
        val bbox: FloatArray,   // lineCount * 4: minLon, minLat, maxLon, maxLat
        val starts: IntArray,   // lineCount: point offset into coords
        val counts: IntArray,   // lineCount: number of lon/lat pairs
        val coords: FloatArray  // combined: lon, lat per point
    )

    @Volatile
    private var levels: List<Level>? = null

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
    }

    // Reusable objects to minimise allocation during draw loops.
    private val reuseGeo = GeoPoint(0.0, 0.0)
    private val reusePoint = Point()
    private val sharedPath = Path()

    @Volatile
    var isLoaded: Boolean = false
        private set

    fun setColor(color: Int) {
        borderPaint.color = color
    }

    /**
     * Parses the bundled binary asset. Must be called from a background thread;
     * it will block until the data has been read.
     */
    fun load() {
        val parsed = try {
            context.assets.open(ASSET).use { stream ->
                parse(stream.readBytes())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ländergrenzen konnten nicht geladen werden", e)
            null
        }
        if (parsed != null) {
            levels = parsed
            isLoaded = true
        }
    }

    private fun parse(bytes: ByteArray): List<Level> {
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

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val loaded = levels ?: return
        if (!isLoaded) return

        val zoom = mapView.zoomLevelDouble
        if (zoom < loaded.first().minZoom) return
        val level = selectLevel(loaded, zoom)

        val projection = mapView.projection

        // Visible geographic area, slightly enlarged to hide clipping edges.
        val vp = projection.boundingBox
        val marginDeg = 2.0
        val vpLonWest = vp.lonWest - marginDeg
        val vpLonEast = vp.lonEast + marginDeg
        val vpLatSouth = vp.latSouth - marginDeg
        val vpLatNorth = vp.latNorth + marginDeg

        // Clamp projected points to a padded viewport in pixel space (see
        // OfflineLandOverlay for why).
        val clip = Rect()
        canvas.getClipBounds(clip)
        val pad = (maxOf(clip.width(), clip.height()) * 1.0f + 50f).toFloat()
        val minX = clip.left.toFloat() - pad
        val maxX = clip.right.toFloat() + pad
        val minY = clip.top.toFloat() - pad
        val maxY = clip.bottom.toFloat() + pad

        sharedPath.reset()

        var addedLines = 0
        for (l in 0 until level.lineCount) {
            val bMinLon = level.bbox[l * 4]
            val bMinLat = level.bbox[l * 4 + 1]
            val bMaxLon = level.bbox[l * 4 + 2]
            val bMaxLat = level.bbox[l * 4 + 3]

            if (bMaxLon < vpLonWest) continue
            if (bMinLon > vpLonEast) continue
            if (bMaxLat < vpLatSouth) continue
            if (bMinLat > vpLatNorth) continue

            val start = level.starts[l]
            val count = level.counts[l]

            var started = false
            for (i in 0 until count) {
                val idx = start + i
                reuseGeo.longitude = level.coords[idx * 2].toDouble()
                reuseGeo.latitude = level.coords[idx * 2 + 1].toDouble()
                projection.toPixels(reuseGeo, reusePoint)
                val x = reusePoint.x.toFloat().coerceIn(minX, maxX)
                val y = reusePoint.y.toFloat().coerceIn(minY, maxY)
                if (!started) {
                    sharedPath.moveTo(x, y)
                    started = true
                } else {
                    sharedPath.lineTo(x, y)
                }
            }
            if (started) {
                addedLines++
            }
        }

        if (addedLines > 0) {
            canvas.drawPath(sharedPath, borderPaint)
        }
    }

    private fun selectLevel(levels: List<Level>, zoom: Double): Level {
        for (level in levels) {
            if (zoom >= level.minZoom && zoom < level.maxZoom) {
                return level
            }
        }
        return if (zoom < levels.first().minZoom) levels.first()
        else levels.last()
    }
}