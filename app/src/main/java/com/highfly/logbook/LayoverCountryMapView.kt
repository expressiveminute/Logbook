package com.highfly.logbook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewTreeObserver
import com.google.android.material.color.MaterialColors
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Kartenausschnitt für die Layover-Detailseite: ein echtes Stück der Weltkarte.
 *
 * Statt einer eigenen, vereinfachten Projektion verwendet die Ansicht dieselbe
 * osmdroid-Karte wie [WorldMapFragment] mit denselben Offline-Overlays für
 * Land und Grenzen. Damit stimmt der Ausschnitt exakt mit der Weltkarte überein
 * - es ist wirklich dieselbe Karte, nur auf das Ziel-Land gezoomt.
 *
 * Das Land wird mit der Akzentfarbe hervorgehoben, der Flughafen als Punkt mit
 * IATA-Badge markiert. Gesten sind deaktiviert, damit die umgebende Seite
 * normal scrollt.
 */
class LayoverCountryMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MapView(context, attrs) {

    private var excerptCountry: CountryShapes.Country? = null
    private var excerptMarker: AirportData.GeoLocation? = null
    private var excerptCode: String? = null

    private var fitted = false
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        removeOnGlobalLayoutListener()
        fitToCountry()
    }

    private val landOverlay = OfflineLandOverlay(context)
    private val borderOverlay = OfflineBorderOverlay(context)
    private var loadThread: Thread? = null

    private val accentColor by lazy {
        MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary
        )
    }

    /** Farbe für den Landhintergrund, wie die Weltkarte sie im Dunkelmodus nutzt. */
    private val isDark = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    init {
        // Vollständig offline: keine Netzwerk-Kacheln, nur die eigenen Overlays.
        setUseDataConnection(false)
        overlayManager.tilesOverlay.isEnabled = false
        overlayManager.tilesOverlay.setLoadingBackgroundColor(Color.TRANSPARENT)
        overlayManager.tilesOverlay.setLoadingLineColor(Color.TRANSPARENT)
        tileProvider.clearTileCache()

        setBuiltInZoomControls(false)
        setMultiTouchControls(false)
        setVerticalMapRepetitionEnabled(false)
        setHorizontalMapRepetitionEnabled(false)
        setScrollableAreaLimitDouble(
            BoundingBox(85.05, 180.0, -85.05, -180.0)
        )
        minZoomLevel = 1.0
        maxZoomLevel = WorldMapFragment.MAX_ZOOM
        controller.setZoom(1.0)
        controller.setCenter(GeoPoint(25.0, 0.0))

        val ocean = if (isDark) Color.rgb(20, 30, 40) else Color.rgb(174, 202, 226)
        val land = if (isDark) Color.rgb(52, 65, 76) else Color.rgb(222, 226, 220)
        val coast = if (isDark) Color.rgb(75, 90, 102) else Color.rgb(150, 160, 152)
        landOverlay.setColors(ocean, land, coast)

        val borderColor = if (isDark) Color.rgb(120, 132, 144) else Color.rgb(128, 140, 130)
        borderOverlay.setColor(borderColor)

        startLoading()
    }

    /**
     * Die Karte ist ein statischer Ausschnitt: Weder Multitouch noch Gesten
     * (Pan, Zoom, Doppeltipp) sollen den Scroll der umgebenden Seite stören.
     * Deshalb wird der komplette Touch-Durchlauf unterbunden.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    /**
     * Setzt Land, Flughafenposition und IATA-Code für den Ausschnitt. Ohne
     * gültige Angaben bleibt die Land-/Grenzenkarte ohne Hervorhebung.
     */
    fun setData(country: CountryShapes.Country?, marker: AirportData.GeoLocation?, code: String?) {
        excerptCountry = country
        excerptMarker = marker
        excerptCode = code
        fitted = false
        rebuildOverlays()
        fitWhenReady()
    }

    /** Land, Grenzen und danach Hervorhebung + Marker als Overlays. */
    private fun rebuildOverlays() {
        overlayManager.overlays().clear()
        if (landOverlay.isLoaded) overlayManager.overlays().add(landOverlay)
        if (borderOverlay.isLoaded) overlayManager.overlays().add(borderOverlay)

        val country = excerptCountry
        val marker = excerptMarker
        if (country != null && marker != null) {
            overlayManager.overlays().add(
                CountryExcerptOverlay(
                    country = country,
                    markerLat = marker.lat,
                    markerLon = marker.lon,
                    code = excerptCode.orEmpty(),
                    accent = accentColor
                )
            )
        }
        invalidate()
    }

    private fun startLoading() {
        loadThread = Thread {
            landOverlay.load()
            borderOverlay.load()
            post {
                rebuildOverlays()
                fitToCountry()
            }
        }.apply { start() }
    }

    /**
     * Führt den Ausschnitt nach dem ersten Layout auf das Land zusammen: die
     * Karte bleibt auf dem Flughafen zentriert, das Land füllt den Platz.
     */
    private fun fitWhenReady() {
        if (width <= 0 || height <= 0) {
            viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        } else {
            fitToCountry()
        }
    }

    private fun removeOnGlobalLayoutListener() {
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
    }

    private fun fitToCountry() {
        if (fitted || width <= 0 || height <= 0) return
        val box = boundsOfCountry() ?: return
        fitted = true
        zoomToBoundingBox(box, false, (24 * resources.displayMetrics.density).toInt())
        invalidate()
    }

    /**
     * Bounding-Box des Landes für [zoomToBoundingBox]. Längengrade, die über
     * die Datumslinie reichen (Fidschi, Russlands Osten), werden um den
     * Flughafen herum gelegt und danach auf die sichtbare Welt [-180, 180]
     * beschnitten, damit die Box nie fast 360 Grad breit wirkt. Der Box-Mitte
     * gehört der Flughafen, die Hervorhebung wird wie alle Landoverlays mit
     * den rohen Längengraden gezeichnet.
     */
    private fun boundsOfCountry(): BoundingBox? {
        val country = excerptCountry ?: return null
        val reference = excerptMarker?.lon
            ?: (country.minLon + country.maxLon) / 2.0
        var west = Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        for (ring in country.rings) {
            var i = 0
            while (i + 1 < ring.size) {
                val lon = unwind(ring[i].toDouble(), reference)
                if (lon < west) west = lon
                if (lon > east) east = lon
                i += 2
            }
        }
        if (west == Double.MAX_VALUE) return null
        // Mindestausdehnung in Längengrad für sehr kleine Länder und
        // Datumslinien-Randfälle, damit die Karte etwas zeigt.
        if (east - west < 1.0) east = west + 1.0
        west = west.coerceIn(-180.0, 180.0)
        east = east.coerceIn(-180.0, 180.0)
        if (west >= east) east = (west + 1.0).coerceAtMost(180.0)
        val north = country.maxLat.coerceIn(-85.0, 85.0)
        val south = country.minLat.coerceIn(-85.0, 85.0)
        return BoundingBox(north, east, south, west)
    }

    /** Verschiebt einen Längengrad nahe an [reference], über die Datumslinie. */
    private fun unwind(lon: Double, reference: Double): Double {
        var value = lon
        while (value - reference > 180.0) value -= 360.0
        while (reference - value > 180.0) value += 360.0
        return value
    }

    override fun onDetach() {
        loadThread?.interrupt()
        loadThread = null
        super.onDetach()
    }

    /**
     * Zeichnet das Land in Akzentfarbe und den Flughafen als Punkt mit
     * IATA-Badge. Die Ringe werden wie die Landoverlays über dieselbe
     * Projektion gezeichnet, also deckungsgleich mit der Weltkarte.
     */
    private class CountryExcerptOverlay(
        private val country: CountryShapes.Country,
        private val markerLat: Double,
        private val markerLon: Double,
        private val code: String,
        private val accent: Int
    ) : Overlay() {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(
                Color.red(accent),
                Color.green(accent),
                Color.blue(accent)
            )
            alpha = 70
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.FILL
        }
        private val reusePoint = Point()

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val projection = mapView.projection

            // Ringe mit ihren rohen Längengraden projizieren - genau wie die
            // Landoverlays der Weltkarte, damit die Hervorhebung exakt auf dem
            // Land des Weltkarten-Ausschnitts liegt.
            val countryPath = Path()
            for (ring in country.rings) {
                var i = 0
                var started = false
                while (i + 1 < ring.size) {
                    val lon = ring[i].toDouble()
                    val lat = ring[i + 1].toDouble()
                    projection.toPixels(GeoPoint(lat, lon), reusePoint)
                    val x = reusePoint.x.toFloat()
                    val y = reusePoint.y.toFloat()
                    if (started) countryPath.lineTo(x, y) else countryPath.moveTo(x, y)
                    started = true
                    i += 2
                }
                if (started) countryPath.close()
            }
            if (!countryPath.isEmpty) {
                canvas.drawPath(countryPath, fill)
                canvas.drawPath(countryPath, stroke)
            }

            projection.toPixels(GeoPoint(markerLat, markerLon), reusePoint)
            val cx = reusePoint.x.toFloat()
            val cy = reusePoint.y.toFloat()
            val density = mapView.context.resources.displayMetrics.density

            // Flughafen-Badge direkt auf dem Punkt, wie auf der Weltkarte.
            val code = code
            if (code.isEmpty()) {
                val radius = 3.5f * density
                canvas.drawCircle(cx, cy, radius + 1.5f * density, halo)
                canvas.drawCircle(cx, cy, radius, badgeBg)
                return
            }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                isFakeBoldText = true
                textSize = (if (code.length >= 4) 8.5f else 10f) * density
            }
            val paddingH = 4f * density
            val paddingV = 2f * density
            val textWidth = label.measureText(code)
            val textHeight = label.descent() - label.ascent()
            val badgeWidth = textWidth + 2 * paddingH
            val badgeHeight = textHeight + 2 * paddingV
            val margin = 4f * density
            var badgeLeft = cx - badgeWidth / 2
            badgeLeft = badgeLeft.coerceIn(
                margin, mapView.width.toFloat() - margin - badgeWidth
            )
            var badgeTop = cy - badgeHeight / 2
            badgeTop = badgeTop.coerceIn(
                margin, mapView.height.toFloat() - margin - badgeHeight
            )
            val badge = RectF(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + badgeHeight)
            canvas.drawRoundRect(badge, badgeHeight / 2, badgeHeight / 2, badgeBg)
            canvas.drawText(
                code,
                badge.centerX() - textWidth / 2,
                badge.centerY() - (label.ascent() + label.descent()) / 2,
                label
            )
        }
    }
}