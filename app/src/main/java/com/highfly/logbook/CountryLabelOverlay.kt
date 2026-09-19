package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.json.JSONArray
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max

/**
 * Draws country names on the offline world map.
 *
 * Labels are grouped into 4 importance ranks (0 = most important) so that
 * large countries appear at low zoom while small countries only appear when
 * zoomed in far enough. Each country stores its point at the geographic
 * center of its main landmass, so the name does not sit on the capital.
 *
 * When the anchor point lies on or close to the edge of the visible area
 * (e.g. only part of the country is in view after panning), the label is
 * clamped into the current viewport instead of being cut off.
 */
class CountryLabelOverlay(
    private val countries: List<Country>,
    private val labelColor: Int,
    private val collision: LabelCollision,
    private val shadowColor: Int = Color.TRANSPARENT
) : Overlay() {

    class Country(
        val name: String,
        val point: GeoPoint,
        val rank: Int,
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double
    )

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        // This overlay is drawn first among the label overlays, so it starts a
        // fresh frame for the shared collision map.
        collision.beginFrame()
        if (countries.isEmpty()) return
        val density = mapView.context.resources.displayMetrics.density
        val zoom = mapView.zoomLevelDouble
        if (zoom < 2.0) return

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = 10.5f * density
            isFakeBoldText = true
            alpha = if (zoom >= 5.0) 210 else 160
        }
        val fm = label.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val pad = 3f * density
        val staggerStep = textHeight + 4f * density

        val width = mapView.width.toFloat()
        val height = mapView.height.toFloat()

        // A country is only considered "visible" when its bounding box still
        // overlaps the current viewport (with a small margin).
        val vp = mapView.projection.boundingBox
        val vpMargin = 2.0
        val vpLonWest = vp.lonWest - vpMargin
        val vpLonEast = vp.lonEast + vpMargin
        val vpLatSouth = vp.latSouth - vpMargin
        val vpLatNorth = vp.latNorth + vpMargin

        // Far-away countries (anchor more than this many screen sizes off the
        // viewport) are not pulled back into view, otherwise the screen edges
        // would fill up with unrelated labels.
        val farCap = max(width, height) * 2f

        /**
         * Tries to place the label, clamping its rectangle into the viewport
         * when the anchor lies on/near the edge so the name stays readable.
         * Returns the drawn position [x, baseline] or null when no free spot
         * was found.
         */
        fun placeLabel(sx: Float, sy: Float, textWidth: Float): FloatArray? {
            var left = sx - textWidth / 2f
            var baseline = sy
            left = left.coerceIn(pad, width - pad - textWidth)
            baseline = baseline.coerceIn(pad + textHeight, height - pad)
            return collision.place(
                left,
                baseline,
                textWidth,
                textHeight,
                pad,
                baselineShiftY = staggerStep,
                maxShiftLines = 3,
                mirrorTextLeft = null,
                screenWidth = width,
                screenHeight = height
            )
        }

        countries.forEach { country ->
            val minZoom = when (country.rank) {
                0 -> 2.0
                1 -> 3.5
                2 -> 5.0
                else -> 7.0
            }
            if (zoom < minZoom) return@forEach

            // Skip the label when the country itself is outside the viewport.
            if (country.maxLat < vpLatSouth) return@forEach
            if (country.minLat > vpLatNorth) return@forEach
            if (country.maxLon < vpLonWest) return@forEach
            if (country.minLon > vpLonEast) return@forEach
            if (country.minLon > country.maxLon) return@forEach

            val px = mapView.projection.toPixels(country.point, null)
            val sx = px.x.toFloat()
            val sy = px.y.toFloat()

            // The country is only partially in view: give up when the anchor
            // is farther away than a couple of screen sizes.
            if (sx < -farCap || sx > width + farCap) return@forEach
            if (sy < -farCap || sy > height + farCap) return@forEach

            val textWidth = label.measureText(country.name)
            val placed = placeLabel(sx, sy, textWidth) ?: return@forEach
            val drawX = placed[0]
            val drawY = placed[1]

            if (shadowColor != Color.TRANSPARENT) {
                val shadowPaint = Paint(label).apply { color = shadowColor; alpha = 180 }
                canvas.drawText(country.name, drawX + 1f, drawY + 1f, shadowPaint)
            }
            canvas.drawText(country.name, drawX, drawY, label)
        }
    }

    companion object {
        fun load(context: Context): List<Country> {
            return try {
                val text = context.assets.open("countries.json")
                    .bufferedReader()
                    .use { it.readText() }
                val array = JSONArray(text)
                val result = ArrayList<Country>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val lat = obj.getDouble("lat")
                    val lon = obj.getDouble("lon")
                    val bbox = obj.optJSONArray("bx")
                    result.add(
                        Country(
                            name = obj.getString("n"),
                            point = GeoPoint(lat, lon),
                            rank = obj.optInt("r", 2),
                            minLon = if (bbox != null) bbox.getDouble(0) else lon,
                            minLat = if (bbox != null) bbox.getDouble(1) else lat,
                            maxLon = if (bbox != null) bbox.getDouble(2) else lon,
                            maxLat = if (bbox != null) bbox.getDouble(3) else lat
                        )
                    )
                }
                result
            } catch (e: Exception) {
                Log.e("CountryLabelOverlay", "Ländernamen konnten nicht geladen werden", e)
                emptyList()
            }
        }
    }
}