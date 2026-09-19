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
 * Fully offline world land overlay.
 *
 * Reads a sub-sampled Natural Earth 1:10m land dataset (with multiple levels of
 * detail) from a bundled binary asset, then renders ocean + land polygons
 * directly onto the canvas. No network access is ever performed.
 */
class OfflineLandOverlay(
    private val context: Context
) : Overlay() {

    private companion object {
        const val TAG = "OfflineLandOverlay"
        const val ASSET = "world_land_lod.bin"
        const val MAGIC = 0x444C424C // bytes "L","B","L","D" (little-endian int)
        const val VERSION = 1

        // Cap of projected points per ring to keep interaction smooth.
        const val MAX_POINTS_PER_RING = 3000
        const val MAX_POINTS_PER_RING_LO = 2000
    }

    private class Level(
        val minZoom: Double,
        val maxZoom: Double,
        val ringCount: Int,
        val bbox: FloatArray,   // ringCount * 4: minLon, minLat, maxLon, maxLat
        val starts: IntArray,   // ringCount: point offset into coords
        val counts: IntArray,   // ringCount: number of lon/lat pairs
        val coords: FloatArray  // combined: lon, lat per point
    )

    @Volatile
    private var levels: List<Level>? = null

    private val oceanPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val coastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f
    }

    // Reusable objects to minimise allocation during draw loops.
    private val reuseGeo = GeoPoint(0.0, 0.0)
    private val reusePoint = Point()
    private val sharedPath = Path()

    @Volatile
    var isLoaded: Boolean = false
        private set

    fun setColors(ocean: Int, land: Int, coast: Int) {
        oceanPaint.color = ocean
        landPaint.color = land
        coastPaint.color = coast
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
            Log.e(TAG, "Landdaten konnten nicht geladen werden", e)
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
            throw IOException("Ungültiges Landdaten-Format (magic=$magic)")
        }
        val version = buf.get().toInt()
        if (version != VERSION) {
            throw IOException("Nicht unterstützte Landdaten-Version ($version)")
        }
        val levelCount = buf.get().toInt()
        val result = ArrayList<Level>(levelCount)

        for (i in 0 until levelCount) {
            val minZoom = buf.double
            val maxZoom = buf.double
            val ringCount = readUInt(buf)
            val totalPoints = readUInt(buf)

            val bbox = FloatArray(ringCount * 4)
            val starts = IntArray(ringCount)
            val counts = IntArray(ringCount)
            val coords = FloatArray(totalPoints * 2)

            var pointOffset = 0
            for (r in 0 until ringCount) {
                bbox[r * 4] = buf.float
                bbox[r * 4 + 1] = buf.float
                bbox[r * 4 + 2] = buf.float
                bbox[r * 4 + 3] = buf.float

                val pointCount = readUInt(buf)
                requiresPointSpace(totalPoints, pointOffset + pointCount)
                starts[r] = pointOffset
                counts[r] = pointCount
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
                    ringCount = ringCount,
                    bbox = bbox,
                    starts = starts,
                    counts = counts,
                    coords = coords
                )
            )
        }

        // Sentinel value written by the tool to catch offset bugs.
        val sentinel = buf.float
        if (sentinel != -90.0f) {
            Log.w(TAG, "Landdaten-Sentinel nicht gefunden (offset-Bug?)")
        }
        return result
    }

    private fun requiresPointSpace(totalPoints: Int, added: Int) {
        if (added > totalPoints) {
            throw IOException("Landdaten-Koordinaten überschreiten Punktzahl")
        }
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
        val level = selectLevel(loaded, zoom)

        // Ocean background covering the whole map area.
        canvas.drawColor(oceanPaint.color)

        val projection = mapView.projection

        // Visible geographic area, slightly enlarged to hide clipping edges.
        val vp = projection.boundingBox
        val marginDeg = 2.0
        val vpLonWest = vp.lonWest - marginDeg
        val vpLonEast = vp.lonEast + marginDeg
        val vpLatSouth = vp.latSouth - marginDeg
        val vpLatNorth = vp.latNorth + marginDeg

        // Clamp projected points to a padded viewport in pixel space. Without this,
        // large rings spanning the globe project to coordinates of several million
        // pixels which can cause Skia to render horizontal/vertical lines across the
        // visible area (notably when zoomed in on islands or small regions).
        val clip = Rect()
        canvas.getClipBounds(clip)
        val pad = (maxOf(clip.width(), clip.height()) * 2.0f + 100f).toFloat()
        val minX = clip.left.toFloat() - pad
        val maxX = clip.right.toFloat() + pad
        val minY = clip.top.toFloat() - pad
        val maxY = clip.bottom.toFloat() + pad

        sharedPath.reset()
        sharedPath.fillType = Path.FillType.EVEN_ODD

        var addedRings = 0
        for (r in 0 until level.ringCount) {
            val bMinLon = level.bbox[r * 4]
            val bMinLat = level.bbox[r * 4 + 1]
            val bMaxLon = level.bbox[r * 4 + 2]
            val bMaxLat = level.bbox[r * 4 + 3]

            // Viewport culling in geographic space.
            if (bMaxLon < vpLonWest) continue
            if (bMinLon > vpLonEast) continue
            if (bMaxLat < vpLatSouth) continue
            if (bMinLat > vpLatNorth) continue

            val start = level.starts[r]
            val count = level.counts[r]

            // Subsample very long rings so zoomed-out frames stay smooth.
            val budget = if (zoom < 6.0) MAX_POINTS_PER_RING_LO else MAX_POINTS_PER_RING
            val stride = if (count > budget) ((count + budget - 1) / budget).coerceAtLeast(1) else 1

            var started = false
            var i = 0
            while (i < count) {
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
                i += stride
            }
            // Always include the ring's final point to keep the polygon intact.
            if (count > 0 && (count - 1) % stride != 0) {
                val idx = start + count - 1
                reuseGeo.longitude = level.coords[idx * 2].toDouble()
                reuseGeo.latitude = level.coords[idx * 2 + 1].toDouble()
                projection.toPixels(reuseGeo, reusePoint)
                sharedPath.lineTo(
                    reusePoint.x.toFloat().coerceIn(minX, maxX),
                    reusePoint.y.toFloat().coerceIn(minY, maxY)
                )
            }
            if (started) {
                sharedPath.close()
                addedRings++
            }
        }

        if (addedRings > 0) {
            canvas.drawPath(sharedPath, landPaint)
            canvas.drawPath(sharedPath, coastPaint)
        }
    }

    private fun selectLevel(levels: List<Level>, zoom: Double): Level {
        for (level in levels) {
            if (zoom >= level.minZoom && zoom < level.maxZoom) {
                return level
            }
        }
        // Fall back to the closest level.
        return if (zoom < levels.first().minZoom) levels.first()
        else levels.last()
    }
}