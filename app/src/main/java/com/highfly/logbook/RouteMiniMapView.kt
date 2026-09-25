package com.highfly.logbook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Kleiner Kartenausschnitt für eine einzelne Flugstrecke.
 *
 * Die Ansicht projiziert die Strecke grob äquirektangelförmig in das
 * zugewiesene Rechteck, zeichnet darüber die Küstenlinien und zuletzt die
 * Strecke selbst. Die Küstendaten werden einmalig im Hintergrund geladen und
 * von allen Instanzen geteilt, damit die Liste ohne Verzögerung aufbaut.
 */
class RouteMiniMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        const val TAG = "RouteMiniMapView"
        const val LAND_ASSET = "world_land.json"

        /** Stützstellen des Großkreises zwischen Abflug und Ankunft. */
        const val ARC_POINTS = 48

        /** Mindestausdehnung des Kartenausschnitts in Grad. */
        const val MIN_SPAN_DEG = 2.0

        /** Rand um die Strecke als Anteil ihrer Ausdehnung. */
        const val BBOX_PADDING = 0.35

        const val CORNER_RADIUS_DP = 16f

        /** Abstand des Flughafenschilds zum Punkt und zum Kartenrand. */
        /** Abstand zweier sich überlappender Badges. */
        const val LABEL_GAP = 7f
        const val LABEL_MARGIN = 4f

        /** Sichtbarer Anteil des Kartenausschnitts, auf dem Grenzen gelten. */
        const val BORDER_VIEW_PADDING = 0.15

        /** Höchstzahl beschrifteter Städte je Kartenausschnitt. */
        const val CITY_LABEL_COUNT = 3

        /** Mindestabstand zweier Ortsnamen in Pixeln der jeweiligen Dichte. */
        const val CITY_MIN_DISTANCE_DP = 46f

        @Volatile
        private var landRings: List<FloatArray>? = null

        @Volatile
        private var borderLevels: List<CountryBorders.Level> = emptyList()

        @Volatile
        private var cities: List<MapCity> = emptyList()

        @Volatile
        private var loading = false

        private val detailLock = Any()

        /**
         * Views, die noch auf die Kartendaten warten. Schwach gehalten, damit
         * die Liste keine Views dauerhaft festhält, und mit invalidate()
         * benachrichtigt, sobald die Daten vorliegen.
         */
        private val waitingViews = mutableListOf<WeakReference<RouteMiniMapView>>()
    }

    /** Kartenausschnitt in Grad: alle Werte beziehen sich auf die Kartenmitte. */
    private class Projection(
        val centerLon: Double,
        val centerLat: Double,
        val lonFactor: Double,
        val scale: Double,
        val viewWidth: Float,
        val viewHeight: Float
    ) {
        fun x(lon: Double): Float =
            (viewWidth / 2f + (lon - centerLon) * lonFactor * scale).toFloat()

        fun y(lat: Double): Float =
            (viewHeight / 2f - (lat - centerLat) * scale).toFloat()

        fun lonRange(): ClosedFloatingPointRange<Double> {
            val half = viewWidth / (2.0 * lonFactor * scale)
            return (centerLon - half)..(centerLon + half)
        }

        fun latRange(): ClosedFloatingPointRange<Double> {
            val half = viewHeight / (2.0 * scale)
            return (centerLat - half)..(centerLat + half)
        }
    }

    private val density get() = resources.displayMetrics.density

    private val cornerRadius = CORNER_RADIUS_DP * density

    private val isDarkTheme = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDarkTheme) Color.rgb(20, 30, 40) else Color.rgb(174, 202, 226)
    }
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDarkTheme) Color.rgb(52, 65, 76) else Color.rgb(222, 226, 220)
    }
    private val coastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = if (isDarkTheme) Color.rgb(75, 90, 102) else Color.rgb(150, 160, 152)
    }
    private val routeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val airportHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val airportPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 8.5f, resources.displayMetrics
        )
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.WHITE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        color = if (isDarkTheme) Color.rgb(120, 132, 144) else Color.rgb(128, 140, 130)
    }
    private val cityDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDarkTheme) Color.rgb(150, 162, 174) else Color.rgb(90, 104, 96)
    }
    private val cityLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 8.5f, resources.displayMetrics
        )
        color = if (isDarkTheme) Color.rgb(226, 232, 240) else Color.rgb(38, 44, 40)
    }
    private val cityLabelShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = cityLabelPaint.textSize
        color = if (isDarkTheme) Color.argb(160, 12, 18, 24) else Color.argb(170, 255, 255, 255)
    }

    private val bounds = RectF()
    private val clipPath = Path()
    private val landPath = Path()
    private val routePath = Path()
    private val borderPath = Path()
    private val badgeCollision = LabelCollision()

    /** Stadt mit entrolltem Längengrad, passend zur Kartenzentrale. */
    private class PlacedCity(val xLon: Double, val city: MapCity)

    /** Strecke als aufgeschnittene Längengrade (Datumslinie entrollt). */
    private var arc: FloatArray? = null
    private var projection: Projection? = null
    private var fromLabel: String? = null
    private var toLabel: String? = null
    private var cityLanguage: String = Settings.CITY_LANG_NATIVE

    /** Puffer zum Entrollen der Ringe, wird wiederverwendet. */
    private var ringBuffer = FloatArray(0)

    init {
        routePaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary
        )
        routeHaloPaint.color = Color.argb(90, 255, 255, 255)
        airportPaint.color = routePaint.color
        airportHaloPaint.color = Color.WHITE
        labelPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnPrimary
        )
        labelBgPaint.color = routePaint.color
    }

    /**
     * Setzt die darzustellende Strecke samt Flughafencodes, die auf der Karte
     * an den Endpunkten beschriftet werden. Fehlende Koordinaten bleiben ohne
     * Inhalt, dann wird nur der Kartenhintergrund gezeichnet.
     */
    fun setRoute(
        from: AirportData.GeoLocation,
        to: AirportData.GeoLocation,
        fromCode: String,
        toCode: String
    ) {
        arc = buildArc(from, to)
        fromLabel = fromCode
        toLabel = toCode
        cityLanguage = Settings.getCityLabelLanguage(context)
        projection = null
        requestDetails()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        projection = null
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (bounds.isEmpty) bounds.set(0f, 0f, w, h)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, oceanPaint)

        val points = arc ?: return
        val projection = projection ?: buildProjection(points, w, h)?.also { this.projection = it }
            ?: return

        val rings = landRings
        if (rings == null) requestDetails()

        val startRect = badgeBounds(points, projection, 0, fromLabel)
        val endRect = badgeBounds(points, projection, points.size - 2, toLabel)

        canvas.save()
        canvas.clipPath(clipPath)
        if (rings != null) drawLand(canvas, rings, projection)
        drawBorders(canvas, projection)
        drawCities(canvas, projection, startRect, endRect)
        drawRoute(canvas, points, projection)
        drawBadges(canvas, points, projection, startRect, endRect)
        canvas.restore()
    }

    /**
     * Lädt Küsten, Landesgrenzen und Städte einmalig im Hintergrund. Alle
     * Views, die noch ohne Daten dastehen, werden anschließend zum Neuzeichnen
     * veranlasst - sonst bliebe in einer Liste nur die zuerst angelegte Karte
     * gefüllt.
     */
    private fun requestDetails() {
        if (landRings != null) return
        val appContext = context.applicationContext
        synchronized(detailLock) {
            if (waitingViews.none { it.get() === this }) {
                waitingViews.add(WeakReference(this))
            }
            if (loading) return
            loading = true
        }
        Thread {
            val rings = loadRings(appContext) ?: emptyList()
            val borders = CountryBorders.load(appContext) ?: emptyList()
            val cityList = MapCities.load(appContext)
            val views = synchronized(detailLock) {
                loading = false
                landRings = rings
                borderLevels = borders
                cities = cityList
                waitingViews.toList().also { waitingViews.clear() }
            }
            views.forEach { reference ->
                val view = reference.get() ?: return@forEach
                view.post { view.invalidate() }
            }
        }.start()
    }

    private fun buildArc(
        from: AirportData.GeoLocation,
        to: AirportData.GeoLocation
    ): FloatArray {
        val points = GeoMath.greatCircleArc(from, to, ARC_POINTS)
        val out = FloatArray(points.size * 2)
        var reference = points.first().longitude
        for (i in points.indices) {
            val lon = unwrapNear(points[i].longitude, reference)
            out[i * 2] = lon.toFloat()
            out[i * 2 + 1] = points[i].latitude.toFloat()
            reference = lon
        }
        return out
    }

    private fun buildProjection(points: FloatArray, w: Float, h: Float): Projection? {
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        for (i in points.indices step 2) {
            val lon = points[i].toDouble()
            val lat = points[i + 1].toDouble()
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
        }
        if (minLon == Double.MAX_VALUE) return null

        // Etwas Luft um die Strecke, damit sie nicht am Kartenrand klebt.
        val lonPad = max(MIN_SPAN_DEG, (maxLon - minLon) * BBOX_PADDING)
        val latPad = max(MIN_SPAN_DEG, (maxLat - minLat) * BBOX_PADDING)
        minLon -= lonPad
        maxLon += lonPad
        minLat = max(minLat - latPad, -85.0)
        maxLat = min(maxLat + latPad, 85.0)

        val centerLon = (minLon + maxLon) / 2.0
        val centerLat = (minLat + maxLat) / 2.0
        // Breitengrade werden am Breitenkreis gestaucht, sonst wirken Strecken
        // in hohen Breiten überdehnt.
        val lonFactor = cos(Math.toRadians(centerLat)).coerceAtLeast(0.05)
        val scale = min(
            w / ((maxLon - minLon) * lonFactor),
            h / (maxLat - minLat)
        )
        if (!scale.isFinite() || scale <= 0.0) return null
        return Projection(centerLon, centerLat, lonFactor, scale, w, h)
    }

    private fun drawLand(canvas: Canvas, rings: List<FloatArray>, projection: Projection) {
        val visibleLons = projection.lonRange()
        val visibleLats = projection.latRange()
        for (ring in rings) {
            val count = ring.size / 2
            if (count < 3) continue
            ensureRingBuffer(count * 2)

            // Ring an der Datumslinie aufschneiden, damit er zusammenhängend
            // bleibt, und danach in Richtung der Kartenmitte verschieben.
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var lonSum = 0.0
            var previous = ring[0].toDouble()
            ringBuffer[0] = previous.toFloat()
            for (i in 0 until count) {
                val lon = if (i == 0) {
                    previous
                } else {
                    unwrapNear(ring[i * 2].toDouble(), previous).also { previous = it }
                }
                val lat = ring[i * 2 + 1].toDouble()
                ringBuffer[i * 2] = lon.toFloat()
                ringBuffer[i * 2 + 1] = lat.toFloat()
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                lonSum += lon
            }
            if (maxLat < visibleLats.start || minLat > visibleLats.endInclusive) continue

            val shift = 360.0 * round(
                (projection.centerLon - lonSum / count) / 360.0
            )
            if (maxLon + shift < visibleLons.start || minLon + shift > visibleLons.endInclusive) continue

            landPath.reset()
            for (i in 0 until count) {
                val x = projection.x(ringBuffer[i * 2].toDouble() + shift)
                val y = projection.y(ringBuffer[i * 2 + 1].toDouble())
                if (i == 0) landPath.moveTo(x, y) else landPath.lineTo(x, y)
            }
            landPath.close()
            canvas.drawPath(landPath, landPaint)
            canvas.drawPath(landPath, coastPaint)
        }
    }

    private fun drawRoute(canvas: Canvas, points: FloatArray, projection: Projection) {
        routePath.reset()
        for (i in points.indices step 2) {
            val x = projection.x(points[i].toDouble())
            val y = projection.y(points[i + 1].toDouble())
            if (i == 0) routePath.moveTo(x, y) else routePath.lineTo(x, y)
        }
        canvas.drawPath(routePath, routeHaloPaint)
        canvas.drawPath(routePath, routePaint)
    }

    /**
     * Zeichnet die Landesgrenzen. Verwendet wird die feinste Detailstufe, weil
     * der Ausschnitt einer Flugstrecke immer nah herangezoomt ist. Linien, deren
     * Bounding-Box nicht im Ausschnitt liegt, werden vorher verworfen.
     */
    private fun drawBorders(canvas: Canvas, projection: Projection) {
        val levels = borderLevels
        if (levels.isEmpty()) return
        val level = levels.last()

        val lons = projection.lonRange()
        val lats = projection.latRange()
        // Etwas Luft nach außen, damit an den Kartenrändern keine Linie fehlt.
        val lonPad = (lons.endInclusive - lons.start) * BORDER_VIEW_PADDING
        val latPad = (lats.endInclusive - lats.start) * BORDER_VIEW_PADDING
        val lonWest = lons.start - lonPad
        val lonEast = lons.endInclusive + lonPad
        val latSouth = lats.start - latPad
        val latNorth = lats.endInclusive + latPad

        // Projektionsrahmen leicht über die View hinaus, damit entrollte Linien
        // am Rand nicht als Endpunkt abgeschnitten werden.
        val marginX = width * 0.5f
        val marginY = height * 0.5f
        borderPath.reset()
        var drawn = 0

        for (l in 0 until level.lineCount) {
            if (level.bbox[l * 4 + 2] < lonWest || level.bbox[l * 4] > lonEast) continue
            if (level.bbox[l * 4 + 3] < latSouth || level.bbox[l * 4 + 1] > latNorth) continue

            val start = level.starts[l]
            val count = level.counts[l]
            if (count < 2) continue
            val coords = level.coords

            var started = false
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            for (i in 0 until count) {
                val lon = unwrapNear(coords[(start + i) * 2].toDouble(), projection.centerLon)
                val lat = coords[(start + i) * 2 + 1].toDouble()
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
            }
            if (maxLon < lonWest || minLon > lonEast) continue

            for (i in 0 until count) {
                val lon = unwrapNear(coords[(start + i) * 2].toDouble(), projection.centerLon)
                val lat = coords[(start + i) * 2 + 1].toDouble()
                val x = projection.x(lon).coerceIn(-marginX, width + marginX)
                val y = projection.y(lat).coerceIn(-marginY, height + marginY)
                if (started) {
                    borderPath.lineTo(x, y)
                } else {
                    borderPath.moveTo(x, y)
                    started = true
                }
            }
            if (started) drawn++
        }

        if (drawn > 0) canvas.drawPath(borderPath, borderPaint)
    }

    /**
     * Beschriftet die zwei bis drei wichtigsten Städte im Ausschnitt. Die Auswahl
     * bevorzugt Hauptstädte und große Städte und hält einen Mindestabstand zu
     * den Flughafenschildern sowie untereinander frei.
     */
    private fun drawCities(
        canvas: Canvas,
        projection: Projection,
        startRect: RectF?,
        endRect: RectF?
    ) {
        val list = cities
        if (list.isEmpty()) return
        val lons = projection.lonRange()
        val lats = projection.latRange()
        val language = cityLanguage

        val visible = list.filter { city ->
            // Längengrade werden wie beim Kartenausschnitt entrollt, sonst
            // fehlen Orte an der Datumslinie.
            val lon = unwrapNear(city.lon, projection.centerLon)
            lon in lons && city.lat in lats
        }.sortedBy { it.importance() }

        val minDistance = CITY_MIN_DISTANCE_DP * density
        val chosen = ArrayList<PlacedCity>(CITY_LABEL_COUNT)
        for (city in visible) {
            if (chosen.size >= CITY_LABEL_COUNT) break
            val x = projection.x(unwrapNear(city.lon, projection.centerLon))
            val y = projection.y(city.lat)
            if (chosen.none { previous ->
                    val px = projection.x(previous.xLon)
                    val py = projection.y(previous.city.lat)
                    hypot(px - x, py - y) < minDistance
                }
            ) {
                chosen.add(PlacedCity(unwrapNear(city.lon, projection.centerLon), city))
            }
        }
        if (chosen.isEmpty()) return

        // Die Flughafenschilder liegen über den Städtenamen und werden zuerst
        // reserviert, damit kein Ortsname darunter verschwindet.
        badgeCollision.beginFrame()
        startRect?.let { badgeCollision.reserve(it.left, it.top, it.right, it.bottom) }
        endRect?.let { badgeCollision.reserve(it.left, it.top, it.right, it.bottom) }

        val fm = cityLabelPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val pad = 3f * density
        val gap = 4f * density
        for (placed in chosen) {
            val city = placed.city
            val name = city.displayName(language)
            if (name.isEmpty()) continue
            val x = projection.x(placed.xLon)
            val y = projection.y(city.lat)
            val textWidth = cityLabelPaint.measureText(name)
            val placed = badgeCollision.place(
                x + gap,
                y - gap,
                textWidth,
                textHeight,
                pad,
                baselineShiftY = textHeight + 2f * density,
                maxShiftLines = 2,
                mirrorTextLeft = x - gap - textWidth,
                screenWidth = width.toFloat(),
                screenHeight = height.toFloat()
            ) ?: continue

            val radius = when {
                city.isCapital -> 2.4f
                city.rank == 0 -> 2.3f
                city.rank == 1 -> 2.1f
                city.rank == 2 -> 1.9f
                else -> 1.7f
            } * density
            cityDotPaint.alpha = 150
            canvas.drawCircle(x, y, radius, cityDotPaint)
            cityLabelShadowPaint.isUnderlineText = city.isCapital
            canvas.drawText(name, placed[0] + 1f, placed[1] + 1f, cityLabelShadowPaint)
            cityLabelPaint.isUnderlineText = city.isCapital
            canvas.drawText(name, placed[0], placed[1], cityLabelPaint)
        }
    }

    /**
     * Zeigt Abflug- und Ankunftsflughafen als Badge im Kästchen direkt auf dem
     * Punkt, wie auf der Weltkarte. Fehlt ein Code, bleibt der Punkt als
     * runder Marker stehen; überlappen sich zwei Badges, wandert das zweite.
     */
    private fun badgeBounds(
        points: FloatArray,
        projection: Projection,
        index: Int,
        code: String?
    ): RectF? {
        if (code.isNullOrEmpty()) return null
        val rect = labelBounds(
            projection.x(points[index].toDouble()),
            projection.y(points[index + 1].toDouble()),
            code
        )
        if (index == 0) return rect

        val other = labelBounds(
            projection.x(points[0].toDouble()),
            projection.y(points[1].toDouble()),
            fromLabel.orEmpty()
        )
        if (other == null || !other.intersect(rect)) return rect
        val shift = rect.height() + LABEL_GAP * density
        rect.offset(
            0f,
            if (rect.bottom + shift <= height - LABEL_MARGIN * density) shift else -shift
        )
        return rect
    }

    private fun drawBadges(
        canvas: Canvas,
        points: FloatArray,
        projection: Projection,
        startRect: RectF?,
        endRect: RectF?
    ) {
        val last = points.size - 2
        if (startRect == null) {
            drawAirportMarker(
                canvas,
                projection.x(points[0].toDouble()),
                projection.y(points[1].toDouble())
            )
        } else {
            drawBadge(canvas, startRect, fromLabel.orEmpty())
        }
        if (endRect == null) {
            drawAirportMarker(
                canvas,
                projection.x(points[last].toDouble()),
                projection.y(points[last + 1].toDouble())
            )
        } else {
            drawBadge(canvas, endRect, toLabel.orEmpty())
        }
    }

    /** Badge zentriert über dem Flughafenpunkt, innerhalb des Ausschnitts. */
    private fun labelBounds(x: Float, y: Float, code: String): RectF {
        val paddingH = 4f * density
        val paddingV = 2f * density
        val boxWidth = labelPaint.measureText(code) + 2 * paddingH
        val boxHeight = -labelPaint.ascent() + labelPaint.descent() + 2 * paddingV
        val margin = LABEL_MARGIN * density

        var left = x - boxWidth / 2
        if (left < margin) left = margin
        if (left + boxWidth > width - margin) left = width - margin - boxWidth
        var top = y - boxHeight / 2
        if (top < margin) top = margin
        if (top + boxHeight > height - margin) top = height - margin - boxHeight

        return RectF(left, top, left + boxWidth, top + boxHeight)
    }

    private fun drawBadge(canvas: Canvas, rect: RectF, code: String) {
        val radius = rect.height() / 2f
        canvas.drawRoundRect(rect, radius, radius, labelBgPaint)
        canvas.drawRoundRect(rect, radius, radius, labelRingPaint)
        val fm = labelPaint.fontMetrics
        canvas.drawText(
            code,
            rect.centerX() - labelPaint.measureText(code) / 2,
            rect.centerY() - (fm.ascent + fm.descent) / 2,
            labelPaint
        )
    }

    private fun drawAirportMarker(canvas: Canvas, x: Float, y: Float) {
        val radius = 3.5f * density
        canvas.drawCircle(x, y, radius + 1.5f * density, airportHaloPaint)
        canvas.drawCircle(x, y, radius, airportPaint)
    }

    private fun ensureRingBuffer(size: Int) {
        if (ringBuffer.size >= size) return
        ringBuffer = FloatArray(size)
    }

    /**
     * Verschiebt einen Längengrad auf die Seite der Datumslinie, die näher an
     * [reference] liegt. Dadurch entstehen keine Sprünge über den Kartenrand.
     */
    private fun unwrapNear(lon: Double, reference: Double): Double {
        var value = lon
        while (value - reference > 180.0) value -= 360.0
        while (reference - value > 180.0) value += 360.0
        return value
    }

    private fun loadRings(context: Context): List<FloatArray>? = try {
        val json = context.assets.open(LAND_ASSET).bufferedReader().use { it.readText() }
        val features = JSONObject(json).getJSONArray("features")
        val rings = mutableListOf<FloatArray>()
        for (i in 0 until features.length()) {
            val geometry = features.getJSONObject(i).getJSONObject("geometry")
            when (geometry.getString("type")) {
                "Polygon" -> parseRing(geometry.getJSONArray("coordinates").getJSONArray(0), rings)
                "MultiPolygon" -> {
                    val polygons = geometry.getJSONArray("coordinates")
                    for (j in 0 until polygons.length()) {
                        parseRing(polygons.getJSONArray(j).getJSONArray(0), rings)
                    }
                }
            }
        }
        rings
    } catch (e: Exception) {
        Log.e(TAG, "Konnte Küstendaten nicht laden", e)
        null
    }

    private fun parseRing(ring: org.json.JSONArray, out: MutableList<FloatArray>) {
        val count = ring.length()
        if (count < 3) return
        val arr = FloatArray(count * 2)
        for (i in 0 until count) {
            val point = ring.getJSONArray(i)
            arr[i * 2] = point.getDouble(0).toFloat()
            arr[i * 2 + 1] = point.getDouble(1).toFloat()
        }
        out.add(arr)
    }
}
