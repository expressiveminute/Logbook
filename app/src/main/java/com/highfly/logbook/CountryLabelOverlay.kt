package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.util.Log
import org.json.JSONArray
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max
import kotlin.math.min

/**
 * Draws country names on the offline world map.
 *
 * A name is only drawn when it actually fits into its country on screen. The
 * country grows with the zoom, so every name appears by itself as soon as
 * there is room for it - big countries early, small ones only when zoomed in
 * far. That replaces a hand-maintained table of zoom steps per country, which
 * either hid big names or spilled small ones over their neighbours.
 *
 * Each country stores its point at the geographic center of its main
 * landmass, so the name does not sit on the capital. When the anchor point
 * lies on or close to the edge of the visible area (e.g. only part of the
 * country is in view after panning), the label is clamped into the current
 * viewport instead of being cut off.
 *
 * The `rank` from the data is kept for the import but no longer decides when a
 * name appears - the size on screen does that better.
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

        // Wird von jedem Land einmal gebraucht und sofort wieder überschrieben,
        // deshalb nicht 255 Stück anlegen.
        val ecke = Point()

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
            // Skip the label when the country itself is outside the viewport.
            if (country.maxLat < vpLatSouth) return@forEach
            if (country.minLat > vpLatNorth) return@forEach
            if (country.maxLon < vpLonWest) return@forEach
            if (country.minLon > vpLonEast) return@forEach

            val px = mapView.projection.toPixels(country.point, null)
            val sx = px.x.toFloat()
            val sy = px.y.toFloat()

            // The country is only partially in view: give up when the anchor
            // is farther away than a couple of screen sizes.
            if (sx < -farCap || sx > width + farCap) return@forEach
            if (sy < -farCap || sy > height + farCap) return@forEach

            val textWidth = label.measureText(country.name)
            // Der Name muss wirklich ins Land passen, sonst steht er als Fremd-
            // koerper ueber dem Nachbarn. Das Land waechst mit dem Zoom, also
            // kommt jeder Name von selbst erst dann, wenn er Platz hat.
            if (!passtInsLand(country, textWidth, textHeight, mapView, ecke)) return@forEach
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
        /** Breitengradgrenze der Mercator-Projektion, dahinter wird es unendlich. */
        private const val LAT_LIMIT = 85.0511287

        /**
         * Passt der Name in das Land? Entscheidend sind zwei Rechtecke in
         * Bildschirmmaessen: der Name (in Pixeln gemessen) und das Land, so
         * breit wie es gerade gezeichnet wird.
         *
         * Der Name wird nur gezeigt, wenn er in **beide** Richtungen hineinpasst.
         * Das Land waechst beim Zoomen, jeder Name kommt damit von selbst genau
         * so spaet wie er sollte - ohne eine Liste von Hand gepflegter Stufen
         * je Land.
         */
        @JvmStatic
        fun passtInsLand(
            textBreite: Float,
            textHoehe: Float,
            landBreite: Float,
            landHoehe: Float
        ): Boolean {
            // `isFinite` statt `> 0`: ein von der Projektion geliefertes NaN oder
            // unendlich grosses Land ist kein Platz, sondern ein Rechenfehler -
            // danach wuerde der Name an einer Stelle stehen, die es nicht gibt.
            if (!landBreite.isFinite() || !landHoehe.isFinite()) return false
            if (!textBreite.isFinite() || !textHoehe.isFinite()) return false
            if (!(landBreite > 0f) || !(landHoehe > 0f)) return false
            if (!(textBreite > 0f)) return false
            return textBreite <= landBreite && textHoehe <= landHoehe
        }

        /**
         * Bildschirmrechteck des Landes ueber seine geografische Bounding-Box,
         * oder null, wenn die Box nicht brauchbar ist (Datumswechsel, Laenge
         * ausserhalb der Projektion).
         */
        private fun passtInsLand(
            country: Country,
            textBreite: Float,
            textHoehe: Float,
            mapView: MapView,
            ecke: Point
        ): Boolean {
            // Ein Land quer ueber den Datumswechsel hat keine zusammenhaengende
            // Box; mit [LON_MIN] oben abgeschnitten hat es zwei, der Name
            // waere dann an zwei Orten gleichzeitig - besser gar keiner.
            if (country.minLon > country.maxLon) return false
            val nord = min(country.maxLat, LAT_LIMIT)
            val sued = max(country.minLat, -LAT_LIMIT)
            if (nord < sued) return false

            val obenLinks = mapView.projection.toPixels(
                GeoPoint(nord, country.minLon), ecke
            )
            val obenRechts = mapView.projection.toPixels(
                GeoPoint(sued, country.maxLon), ecke
            )
            val links = min(obenLinks.x, obenRechts.x).toFloat()
            val rechts = max(obenLinks.x, obenRechts.x).toFloat()
            val hoehe = max(obenLinks.y, obenRechts.y) - min(obenLinks.y, obenRechts.y)

            return passtInsLand(textBreite, textHoehe, rechts - links, hoehe.toFloat())
        }
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