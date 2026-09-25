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
    }

    @Volatile
    private var levels: List<CountryBorders.Level>? = null

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
        val parsed = CountryBorders.load(context)
        if (parsed != null) {
            levels = parsed
            isLoaded = true
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val loaded = levels ?: return
        if (!isLoaded) return

        val zoom = mapView.zoomLevelDouble
        if (zoom < loaded.first().minZoom) return
        val level = CountryBorders.selectLevel(loaded, zoom)

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
}