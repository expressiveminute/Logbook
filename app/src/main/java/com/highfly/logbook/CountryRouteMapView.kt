package com.highfly.logbook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.util.AttributeSet
import android.view.ViewTreeObserver
import com.google.android.material.color.MaterialColors
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot

/**
 * Karte zur Länderseite des Dashboards: ein Ausschnitt der offline gezeichneten
 * Weltkarte, auf das gewählte Land gezoomt.
 *
 * Das Land wird mit der Akzentfarbe hervorgehoben. Die angeflogenen Flughäfen
 * stehen als Badges mit ihrem IATA-Code. Verbindungen innerhalb des Landes
 * werden als ganze Großkreis-Linien gezeichnet; Verbindungen ins Ausland nur
 * als kurze Linie direkt am Flughafen des Landes, die nach außen schnell
 * verblasst - so ist sichtbar, dass von dort ins Ausland geflogen wurde, ohne
 * dass der ganze Strich über die Karte läuft.
 *
 * Anders als [LayoverCountryMapView] ist diese Karte frei bedienbar: Der
 * Nutzer kann zoomen und verschieben, darf aber nicht weiter herauszoomen als
 * die Ansicht, die das Land anfangs füllt.
 */
class CountryRouteMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MapView(context, attrs) {

    /** Eine geflogene Verbindung als Großkreis-Bogen, [points] schon entfaltet. */
    data class RouteLine(val points: List<GeoPoint>, val count: Int)

    private var country: CountryShapes.Country? = null
    private var airports: List<CountryDetailModel.AirportMarker> = emptyList()
    private var domestic: List<RouteLine> = emptyList()
    private var international: List<RouteLine> = emptyList()
    private var focus: CountryDetailModel.GeoBox? = null

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
        setMultiTouchControls(true)
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

    /** Setzt Land, Flughäfen und Verbindungen für den Ausschnitt. */
    fun setData(
        country: CountryShapes.Country?,
        airports: List<CountryDetailModel.AirportMarker>,
        domestic: List<RouteLine>,
        international: List<RouteLine>
    ) {
        this.country = country
        this.airports = airports
        this.domestic = domestic
        this.international = international
        fitted = false
        rebuildOverlays()
        fitWhenReady()
    }

    /** Setzt den Fokus für [zoomToFocus]; null blendet den Knopf aus. */
    fun setFocus(box: CountryDetailModel.GeoBox?) {
        focus = box
    }

    /**
     * Zoomt animiert auf alle angeflogenen Flughäfen des Landes. Die Box
     * kommt aus [CountryDetailModel.focusBounds] und umschließt nur die
     * Flughäfen - nicht das ganze Land mit seinen Überseegebieten, deren
     * Umrisse den Ausschnitt sonst winzig machten. Weiter hereinzoomen kann
     * der Nutzer danach selbst.
     */
    fun zoomToFocus() {
        val box = focus ?: return
        if (width <= 0 || height <= 0) return
        zoomToBoundingBox(
            BoundingBox(box.maxLat, box.maxLon, box.minLat, box.minLon),
            true,
            (24 * resources.displayMetrics.density).toInt()
        )
        invalidate()
    }

    /** Land, Grenzen und danach Hervorhebung, Linien und Badges als Overlays. */
    private fun rebuildOverlays() {
        overlayManager.overlays().clear()
        if (landOverlay.isLoaded) overlayManager.overlays().add(landOverlay)
        if (borderOverlay.isLoaded) overlayManager.overlays().add(borderOverlay)

        overlayManager.overlays().add(
            CountryDataOverlay(
                country = country,
                airports = airports,
                domestic = domestic,
                international = international,
                accent = accentColor,
                density = resources.displayMetrics.density
            )
        )
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

    /** Führt den Ausschnitt nach dem ersten Layout auf das Land zusammen. */
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
        val box = bounds() ?: return
        fitted = true
        zoomToBoundingBox(box, false, (24 * resources.displayMetrics.density).toInt())
        // Nicht weiter herauszoomen als die eingangs gezeigte Füllung: Der
        // Ausschnitt ist auf das Land gerichtet, herauszoomen brächte nur
        // fremde Teile der Weltkarte ins Bild.
        minZoomLevel = zoomLevelDouble
        invalidate()
    }

    /**
     * Bounding-Box für [zoomToBoundingBox]: die des Landes, sonst - wenn das
     * Land keine Umrisse hat - die der angeflogenen Flughäfen. Längengrade,
     * die über die Datumslinie reichen, werden um den Ankerpunkt des Landes
     * herum gelegt und danach auf die sichtbare Welt [-180, 180] beschnitten,
     * damit die Box nie fast 360 Grad breit wirkt.
     */
    private fun bounds(): BoundingBox? {
        val shape = country
        if (shape != null) {
            val reference = shape.labelLon
            var west = Double.MAX_VALUE
            var east = -Double.MAX_VALUE
            for (ring in shape.rings) {
                var i = 0
                while (i + 1 < ring.size) {
                    val lon = unwind(ring[i].toDouble(), reference)
                    if (lon < west) west = lon
                    if (lon > east) east = lon
                    i += 2
                }
            }
            if (west == Double.MAX_VALUE) return null
            if (east - west < 1.0) east = west + 1.0
            west = west.coerceIn(-180.0, 180.0)
            east = east.coerceIn(-180.0, 180.0)
            if (west >= east) east = (west + 1.0).coerceAtMost(180.0)
            val north = shape.maxLat.coerceIn(-85.0, 85.0)
            val south = shape.minLat.coerceIn(-85.0, 85.0)
            return BoundingBox(north, east, south, west)
        }

        if (airports.isEmpty()) return null
        val reference = airports.first().lon
        var west = Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        var south = Double.MAX_VALUE
        for (airport in airports) {
            val lon = unwind(airport.lon, reference)
            if (lon < west) west = lon
            if (lon > east) east = lon
            if (airport.lat > north) north = airport.lat
            if (airport.lat < south) south = airport.lat
        }
        if (east - west < 1.0) east = west + 1.0
        if (north - south < 1.0) north = south + 1.0
        west = west.coerceIn(-180.0, 180.0)
        east = east.coerceIn(-180.0, 180.0)
        if (west >= east) east = (west + 1.0).coerceAtMost(180.0)
        return BoundingBox(
            north.coerceIn(-85.0, 85.0),
            east,
            south.coerceIn(-85.0, 85.0),
            west
        )
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
     * Zeichnet Land, Verbindungen und Flughafen-Badges. Die Ringe des Landes
     * werden wie die Landoverlays über dieselbe Projektion gezeichnet, also
     * deckungsgleich mit der Weltkarte.
     */
    private class CountryDataOverlay(
        private val country: CountryShapes.Country?,
        private val airports: List<CountryDetailModel.AirportMarker>,
        private val domestic: List<RouteLine>,
        private val international: List<RouteLine>,
        private val accent: Int,
        private val density: Float
    ) : Overlay() {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            alpha = 55
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            alpha = ROUTE_ALPHA
            style = Paint.Style.STROKE
            strokeWidth = 1.3f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val stubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            alpha = 255
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val badgeLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f * density
            isFakeBoldText = true
        }
        private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            alpha = 235
            style = Paint.Style.FILL
        }
        private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            alpha = 200
        }
        private val reusePoint = Point()

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val projection = mapView.projection

            drawCountry(canvas, projection)
            drawRoutes(canvas, mapView, projection)
            drawAirports(canvas, mapView, projection)
        }

        /** Land in Akzentfarbe hervorheben, wenn Umrisse vorliegen. */
        private fun drawCountry(canvas: Canvas, projection: org.osmdroid.views.Projection) {
            val shape = country ?: return
            val path = Path()
            for (ring in shape.rings) {
                var i = 0
                var started = false
                while (i + 1 < ring.size) {
                    val lon = ring[i].toDouble()
                    val lat = ring[i + 1].toDouble()
                    projection.toPixels(GeoPoint(lat, lon), reusePoint)
                    val x = reusePoint.x.toFloat()
                    val y = reusePoint.y.toFloat()
                    if (started) path.lineTo(x, y) else path.moveTo(x, y)
                    started = true
                    i += 2
                }
                if (started) path.close()
            }
            if (path.isEmpty) return
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }

        /** Inlandsrouten ganz, Auslandsflüge als kurze, verblassende Linie. */
        private fun drawRoutes(
            canvas: Canvas,
            mapView: MapView,
            projection: org.osmdroid.views.Projection
        ) {
            for (route in domestic) {
                drawFullLine(canvas, projection, route.points)
            }
            if (international.isEmpty()) return
            val stubLen = (minOf(mapView.width, mapView.height) * STUB_SCREEN_FRACTION)
                .coerceIn(MIN_STUB_DP * density, MAX_STUB_DP * density)
            for (route in international) {
                drawFadingStub(canvas, projection, route.points, stubLen)
            }
        }

        private fun drawFullLine(
            canvas: Canvas,
            projection: org.osmdroid.views.Projection,
            points: List<GeoPoint>
        ) {
            if (points.size < 2) return
            val path = Path()
            var started = false
            for (point in points) {
                projection.toPixels(point, reusePoint)
                val x = reusePoint.x.toFloat()
                val y = reusePoint.y.toFloat()
                if (started) path.lineTo(x, y) else path.moveTo(x, y)
                started = true
            }
            canvas.drawPath(path, routePaint)
        }

        /**
         * Die ersten ein paar Prozent des Bogens als Linie, deren Deckkraft
         * nach außen auf null fällt. Die Länge wird an der Bildschirmgröße
         * gemessen: So bleibt der Stummel bei jedem Zoom sichtbar, während die
         * ganze Strecke bewusst nicht gezeichnet wird.
         */
        private fun drawFadingStub(
            canvas: Canvas,
            projection: org.osmdroid.views.Projection,
            points: List<GeoPoint>,
            stubLen: Float
        ) {
            if (points.size < 2) return
            projection.toPixels(points[0], reusePoint)
            var prevX = reusePoint.x.toFloat()
            var prevY = reusePoint.y.toFloat()
            var covered = 0f
            for (i in 1 until points.size) {
                projection.toPixels(points[i], reusePoint)
                val x = reusePoint.x.toFloat()
                val y = reusePoint.y.toFloat()
                val dx = x - prevX
                val dy = y - prevY
                val segment = hypot(dx, dy)
                if (segment <= 0f) continue
                val next = covered + segment
                if (next >= stubLen || i == points.lastIndex) {
                    // Endpunkt interpolieren, damit der Stummel nicht über das
                    // Ziel hinausläuft und alle Abschnitte gleichmäßig dimmen.
                    val t = ((stubLen - covered) / segment).coerceIn(0f, 1f)
                    drawSegment(canvas, prevX, prevY, prevX + dx * t, prevY + dy * t, 1f)
                    return
                }
                drawSegment(canvas, prevX, prevY, x, y, next / stubLen)
                prevX = x
                prevY = y
                covered = next
            }
        }

        /** Ein Abschnitt des Stummels; am Start voll, am Ende durchsichtig. */
        private fun drawSegment(
            canvas: Canvas,
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            fade: Float
        ) {
            stubPaint.alpha = (STUB_START_ALPHA * (1f - fade.coerceIn(0f, 1f))).toInt()
            canvas.drawLine(x1, y1, x2, y2, stubPaint)
        }

        /** Die angeflogenen Flughäfen als Badges mit IATA-Code. */
        private fun drawAirports(
            canvas: Canvas,
            mapView: MapView,
            projection: org.osmdroid.views.Projection
        ) {
            if (airports.isEmpty()) return
            badgeLabel.color = readableOn(accent)
            val fm = badgeLabel.fontMetrics
            val textHeight = fm.descent - fm.ascent
            val padX = 3f * density
            val padY = 1.5f * density

            // Nach Code geordnet, damit sich überlappende Badges stabil legen.
            for (airport in airports) {
                projection.toPixels(GeoPoint(airport.lat, airport.lon), reusePoint)
                val sx = reusePoint.x.toFloat()
                val sy = reusePoint.y.toFloat()
                val textWidth = badgeLabel.measureText(airport.iata)
                val badgeWidth = textWidth + 2 * padX
                val badgeHeight = textHeight + 2 * padY
                val left = sx - badgeWidth / 2
                val top = sy - badgeHeight / 2
                val radius = badgeHeight / 2
                canvas.drawRoundRect(left, top, left + badgeWidth, top + badgeHeight, radius, radius, badgeFill)
                canvas.drawRoundRect(left, top, left + badgeWidth, top + badgeHeight, radius, radius, badgeRing)
                canvas.drawText(
                    airport.iata,
                    sx - textWidth / 2,
                    sy - (fm.descent + fm.ascent) / 2,
                    badgeLabel
                )
            }
        }

        private fun readableOn(bg: Int): Int {
            val r = Color.red(bg) / 255f
            val g = Color.green(bg) / 255f
            val b = Color.blue(bg) / 255f
            val luminance = 0.299f * r + 0.587f * g + 0.114f * b
            return if (luminance > 0.5f) Color.rgb(43, 43, 43) else Color.WHITE
        }
    }

    private companion object {
        /** Deckkraft der Inlandsrouten. */
        const val ROUTE_ALPHA = 200

        /** Deckkraft der Auslandsflüge direkt am Flughafen. */
        const val STUB_START_ALPHA = 235

        /** Länge des Stummels als Anteil der kleineren Bildschirmkante. */
        const val STUB_SCREEN_FRACTION = 0.36f

        /** Unter- und Obergrenze des Stummels in dp. */
        const val MIN_STUB_DP = 66f
        const val MAX_STUB_DP = 240f
    }
}