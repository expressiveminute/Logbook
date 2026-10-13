package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.util.AttributeSet
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import com.google.android.material.color.MaterialColors
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Karte der Seite "CO₂-Ausgleich": offline gezeichneter Ausschnitt der Welt-
 * karte, zentriert auf den Flughafen des Abfluglands. Um den Flughafen liegt
 * die nötige Pflanzfläche als grüner Kreis – **maßstabsgetreu in Metern**: Der
 * Radius wird aus der Fläche in Hektar berechnet und über die Projektion in
 * Bildschirmpixel umgerechnet. Er wächst also nicht mit dem Zoom, sondern
 * bleibt an den echten Maßstab gebunden. Auf einer Länderansicht ist 1 ha
 * deshalb ein Punkt; [zoomToForest] holt die Fläche ins Bild.
 *
 * Die darauf gezeichneten Bäume sind reine Optik: Sie zeigen, dass die Fläche
 * bewaldet wäre, haben aber keine Zählfunktion. Die Zahl der Symbole ist fest
 * ([DECORATIVE_TREES]) und steht in keinem Verhältnis zur echten Baumzahl.
 *
 * Weil der Ausschnitt um den Flughafen sehr eng ist, reicht er oft über keine
 * Stadt hinweg. Die Ankerorte aus `cities_near.json` liegen dann außerhalb des
 * Bildes; sie werden als Hinweis mit Pfeil und Entfernung an den Bildrand
 * gesetzt ([drawEdgeHints]), damit trotzdem ablesbar bleibt, wo etwas liegt.
 */
class Co2OffsetMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MapView(context, attrs) {

    private val landOverlay = OfflineLandOverlay(context)
    private val borderOverlay = OfflineBorderOverlay(context)

    /** Amtliches Luftbild des Flughafens (nur FRA und MUC); sonst leer. */
    private val orthophotoOverlay = OrthophotoOverlay()
    private var orthophotoRequest = 0

    private var loadThread: Thread? = null
    private var fitted = false

    private var country: CountryShapes.Country? = null
    private var airportCode: String = ""
    private var airportLat = 0.0
    private var airportLon = 0.0
    private var hectares = 0.0

    /** Ankerorte aus `cities_near.json`: Städte im Umkreis von FRA und MUC. */
    private var nearbyCities: List<NearbyCity> = emptyList()

    /** Sprache für die Namen der Ankerorte. */
    private var cityLanguage: String = Settings.CITY_LANG_NATIVE

    /** Sprache für die Zahlen des Maßbands. */
    private var scaleLocale: Locale = Locale.GERMANY

    /** Zeigt die Karte gerade auf die Waldfläche (statt auf das ganze Land)? */
    private var forestFocused = true

    /** Fortschritt der Wald-Animation, 0 bis 1; 0, wenn noch nichts wächst. */
    private var progress = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ANIMATION_DURATION_MS
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        removeOnGlobalLayoutListener()
        fitToAirport()
    }

    private val isDark = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val accentColor by lazy {
        MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary
        )
    }

    init {
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
        maxZoomLevel = MAX_DETAIL_ZOOM
        controller.setZoom(1.0)
        controller.setCenter(GeoPoint(25.0, 0.0))

        val ocean = if (isDark) Color.rgb(20, 30, 40) else Color.rgb(174, 202, 226)
        val land = if (isDark) Color.rgb(52, 65, 76) else Color.rgb(222, 226, 220)
        val coast = if (isDark) Color.rgb(75, 90, 102) else Color.rgb(150, 160, 152)
        landOverlay.setColors(ocean, land, coast)
        borderOverlay.setColor(
            if (isDark) Color.rgb(120, 132, 144) else Color.rgb(128, 140, 130)
        )

        startLoading()
    }

    /**
     * Setzt Land, Flughafen und Pflanzfläche (in Hektar). Mit [fit] richtet sich
     * die Karte neu auf die Waldfläche aus (erster Aufbau); ohne [fit] bleibt
     * der Maßstab stehen und nur der Mittelpunkt folgt dem Flughafen – so ändert
     * die Fläche beim Wechsel des Zeitraums sichtbar ihre Größe.
     */
    fun setScene(
        country: CountryShapes.Country?,
        code: String,
        lat: Double,
        lon: Double,
        hectares: Double,
        fit: Boolean
    ) {
        this.country = country
        airportCode = code
        airportLat = lat
        airportLon = lon
        this.hectares = hectares
        cityLanguage = Settings.getCityLabelLanguage(context)
        scaleLocale = Settings.language(context)
        progress = 0f
        animator.cancel()
        loadOrthophoto(code)
        rebuildOverlays()
        if (fit) {
            fitted = false
            forestFocused = true
            fitWhenReady()
        } else {
            controller.setCenter(GeoPoint(airportLat, airportLon))
            invalidate()
            playIfIdle()
        }
    }

    /** Wechselt zwischen Wald- und Landesansicht. Liefert true, wenn danach die
     * Waldfläche zu sehen ist (für das Symbol des Zoom-Knopfs). */
    fun toggleForestZoom(): Boolean {
        if (forestFocused) zoomToCountry() else zoomToForest()
        return forestFocused
    }

    /** Spielt das Wachsen der Bäume ab (zurück auf null und von vorn). */
    fun playAnimation() {
        animator.cancel()
        progress = 0f
        if (width > 0 && height > 0) {
            animator.start()
        }
        invalidate()
    }

    /** Startet die Animation, wenn sie noch nicht läuft (z. B. nach dem Layout). */
    private fun playIfIdle() {
        if (!animator.isRunning) playAnimation()
    }

    override fun onDetach() {
        animator.cancel()
        loadThread?.interrupt()
        loadThread = null
        super.onDetach()
    }

    private fun startLoading() {
        loadThread = Thread {
            landOverlay.load()
            borderOverlay.load()
            val cities = NearbyCities.load(context.applicationContext)
            post {
                nearbyCities = cities
                rebuildOverlays()
                fitToAirport()
                playIfIdle()
            }
        }.apply { start() }
    }

    private fun fitWhenReady() {
        if (width <= 0 || height <= 0) {
            viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        } else {
            fitToAirport()
        }
    }

    private fun removeOnGlobalLayoutListener() {
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
    }

    /**
     * Erste Ausrichtung: Zuerst das Land einpassen (daraus ergibt sich die
     * untere Zoomgrenze), dann maßstabsgetreu auf die Waldfläche zoomen – sie
     * ist das eigentliche Thema der Seite.
     */
    private fun fitToAirport() {
        if (fitted || width <= 0 || height <= 0) return
        fitted = true
        zoomToBoundingBox(countryBox(), false, (ZOOM_PADDING_DP * resources.displayMetrics.density).toInt())
        // Nicht weiter herauszoomen als die Landesansicht: Die Szene bleibt auf
        // den Flughafen gerichtet.
        minZoomLevel = zoomLevelDouble
        zoomToForest(animated = false)
        invalidate()
        playIfIdle()
    }

    /** Maßstabsgetreue Waldfläche ins Bild holen. */
    fun zoomToForest(animated: Boolean = true) {
        if (hectares <= 0.0 || width <= 0 || height <= 0) return
        val halfLat = radiusDegreesLat()
        val halfLon = halfLat / cos(Math.toRadians(airportLat)).coerceAtLeast(0.05)
        val margin = FOREST_ZOOM_MARGIN
        val box = BoundingBox(
            (airportLat + halfLat * margin).coerceAtMost(85.0),
            (airportLon + halfLon * margin).coerceAtMost(180.0),
            (airportLat - halfLat * margin).coerceAtLeast(-85.0),
            (airportLon - halfLon * margin).coerceAtLeast(-180.0)
        )
        forestFocused = true
        zoomToBoundingBox(box, animated, (ZOOM_PADDING_DP * resources.displayMetrics.density).toInt())
        invalidate()
    }

    /** Zurück zur Landesansicht, ohne die eingestellte Zoomgrenze zu ändern. */
    private fun zoomToCountry() {
        if (width <= 0 || height <= 0) return
        forestFocused = false
        zoomToBoundingBox(countryBox(), true, (ZOOM_PADDING_DP * resources.displayMetrics.density).toInt())
        invalidate()
    }

    /** Ausschnitt um den Flughafen, dessen Größe sich am Land orientiert. */
    private fun countryBox(): BoundingBox {
        val halfSpan = ((country?.latSpan ?: 1.2) * 0.9)
            .coerceIn(MIN_HALF_SPAN_DEG, MAX_HALF_SPAN_DEG)
        return BoundingBox(
            (airportLat + halfSpan).coerceAtMost(85.0),
            (airportLon + halfSpan).coerceAtMost(180.0),
            (airportLat - halfSpan).coerceAtLeast(-85.0),
            (airportLon - halfSpan).coerceAtLeast(-180.0)
        )
    }

    /** Radius der Waldfläche in Metern: aus der Fläche (ha) als Kreis. */
    private fun radiusMeters(): Double = sqrt(hectares * SQUARE_METERS_PER_HECTARE / PI)

    /** Radius der Waldfläche in Breitengraden (für Bounding-Box und Pixel). */
    private fun radiusDegreesLat(): Double = radiusMeters() / METERS_PER_DEGREE_LAT

    private fun rebuildOverlays() {
        overlayManager.overlays().clear()
        if (landOverlay.isLoaded) overlayManager.overlays().add(landOverlay)
        if (borderOverlay.isLoaded) overlayManager.overlays().add(borderOverlay)
        // Über Land und Grenzen, aber unter Wald, Orten und Kennzeichen.
        if (orthophotoOverlay.orthophoto != null) overlayManager.overlays().add(orthophotoOverlay)
        overlayManager.overlays().add(ForestOverlay(density = resources.displayMetrics.density))
        overlayManager.overlays().add(ScaleBarOverlay(density = resources.displayMetrics.density))
        invalidate()
    }

    /**
     * Lädt das Luftbild des Flughafens im Hintergrund und legt es auf die
     * Karte. Nur FRA und MUC haben eines; sonst bleibt die Karte wie bisher.
     */
    private fun loadOrthophoto(code: String) {
        if (!Orthophotos.isSupported(code)) {
            orthophotoOverlay.orthophoto = null
            return
        }
        if (orthophotoOverlay.orthophoto?.code == code.uppercase()) return
        val request = ++orthophotoRequest
        val appContext = context.applicationContext
        Thread {
            val photo = Orthophotos.load(appContext, code)
            post {
                if (request != orthophotoRequest) return@post
                orthophotoOverlay.orthophoto = photo
                rebuildOverlays()
                invalidate()
            }
        }.start()
    }

    /**
     * Zeichnet das Land, die grüne Ausgleichsfläche und die wachsenden Bäume.
     * Die Symbole verteilen sich nach der Sonnenblumen-Spirale, sodass sie die
     * Fläche gleichmäßig füllen und wie ein gepflanzter Wald wirken.
     */
    private inner class ForestOverlay(
        private val density: Float
    ) : Overlay() {

        private val countryFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            alpha = 40
            style = Paint.Style.FILL
        }
        private val countryStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val areaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(46, 125, 50)
            alpha = 50
            style = Paint.Style.FILL
        }
        private val areaFillLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(102, 187, 106)
            alpha = 40
            style = Paint.Style.FILL
        }
        private val areaRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(27, 94, 32)
            alpha = 160
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            alpha = 240
            style = Paint.Style.FILL
        }
        private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            alpha = 220
        }
        private val badgeLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFakeBoldText = true
        }
        private val trunkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(93, 64, 55)
            style = Paint.Style.FILL
        }
        private val canopies = listOf(
            Color.rgb(46, 125, 50),
            Color.rgb(67, 160, 71),
            Color.rgb(56, 142, 60)
        )
        private val coniferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val deciduousPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val cityDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark) Color.rgb(150, 162, 174) else Color.rgb(90, 104, 96)
            style = Paint.Style.FILL
        }
        private val cityLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = CITY_LABEL_DP * density
            color = if (isDark) Color.rgb(226, 232, 240) else Color.rgb(38, 44, 40)
        }
        private val cityHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = CITY_LABEL_DP * density
            color = if (isDark) Color.argb(170, 12, 18, 24) else Color.argb(180, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2.4f * density
            strokeJoin = Paint.Join.ROUND
        }
        private val edgeLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = EDGE_HINT_TEXT_DP * density
            color = if (isDark) Color.rgb(226, 232, 240) else Color.rgb(38, 44, 40)
        }
        private val edgeHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = EDGE_HINT_TEXT_DP * density
            color = if (isDark) Color.argb(170, 12, 18, 24) else Color.argb(180, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2.4f * density
            strokeJoin = Paint.Join.ROUND
        }
        private val hintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            alpha = 235
            style = Paint.Style.FILL
        }

        private val reusePoint = Point()
        private val cityPoint = Point()
        private val treePath = Path()
        private val hintPath = Path()

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val projection = mapView.projection
            projection.toPixels(GeoPoint(airportLat, airportLon), reusePoint)
            val centerX = reusePoint.x.toFloat()
            val centerY = reusePoint.y.toFloat()

            drawCountry(canvas, projection)
            val finalRadius = forestRadiusPx(projection)
            if (hectares <= 0.0 || finalRadius <= 0f) {
                drawCities(canvas, projection, centerX, centerY)
                drawAirportBadge(canvas, centerX, centerY)
                return
            }
            drawArea(canvas, centerX, centerY, finalRadius)
            drawTrees(canvas, centerX, centerY, finalRadius)
            drawCities(canvas, projection, centerX, centerY)
            drawAirportBadge(canvas, centerX, centerY)
        }

        /** Das Land mit der Akzentfarbe hervorheben, wenn Umrisse vorliegen. */
        private fun drawCountry(canvas: Canvas, projection: org.osmdroid.views.Projection) {
            // Liegt das Luftbild über dem ganzen Ausschnitt, zeigt es das Land
            // bereits; ein Farbton darüber wäre nur ein Schleier.
            if (orthophotoOverlay.coversViewport(this@Co2OffsetMapView)) return
            val shape = country ?: return
            val path = Path()
            for (ring in shape.rings) {
                var i = 0
                var started = false
                while (i + 1 < ring.size) {
                    projection.toPixels(GeoPoint(ring[i + 1].toDouble(), ring[i].toDouble()), reusePoint)
                    val x = reusePoint.x.toFloat()
                    val y = reusePoint.y.toFloat()
                    if (started) path.lineTo(x, y) else path.moveTo(x, y)
                    started = true
                    i += 2
                }
                if (started) path.close()
            }
            if (path.isEmpty) return
            canvas.drawPath(path, countryFill)
            canvas.drawPath(path, countryStroke)
        }

        /**
         * Die Ausgleichsfläche: ein maßstabsgetreuer Kreis um den Flughafen
         * (Radius aus der Fläche, [forestRadiusPx]). Er erscheint zu Beginn der
         * Animation aus dem Nichts wachsend.
         */
        private fun drawArea(canvas: Canvas, centerX: Float, centerY: Float, finalRadius: Float) {
            val grow = easeOutCubic((progress / AREA_GROW_SHARE).coerceIn(0f, 1f))
            val radius = finalRadius * grow
            if (radius < 0.5f) return
            canvas.drawCircle(centerX, centerY, radius, areaFillLight)
            canvas.drawCircle(centerX, centerY, radius * 0.94f, areaFill)
            canvas.drawCircle(centerX, centerY, radius, areaRim)
        }

        /**
         * Rein dekorative Bäume, die von innen nach außen aufsprießen. Ihre Zahl
         * ([DECORATIVE_TREES]) hat keine Zählfunktion; sie zeigen nur, dass die
         * Fläche bewaldet wäre. Ist die Fläche auf dem Bildschirm zu klein,
         * entfallen sie und es bleibt der grüne Kreis.
         */
        private fun drawTrees(canvas: Canvas, centerX: Float, centerY: Float, finalRadius: Float) {
            if (finalRadius < MIN_TREE_AREA_DP * density) return
            val displayCount = DECORATIVE_TREES

            val treeProgress = ((progress - TREE_START_SHARE) / (1f - TREE_START_SHARE)).coerceIn(0f, 1f)
            val appearing = treeProgress * displayCount
            val appeared = appearing.toInt()
            if (appeared <= 0) return

            val cell = finalRadius * sqrt((PI / displayCount)).toFloat() * 0.8f
            val treeSize = cell.coerceIn(MIN_TREE_DP * density, MAX_TREE_DP * density)

            var i = 0
            while (i < appeared) {
                val local = appearing - i
                var scale = if (local >= 1f) 1f else easeOutBack(local)
                if (scale <= 0.02f) {
                    i++
                    continue
                }
                if (scale > 1f) scale = 1f
                val angle = (i * GOLDEN_ANGLE).toFloat()
                val frac = sqrt((i + 0.5f) / displayCount)
                val r = finalRadius * frac * 0.92f
                val x = centerX + cos(angle) * r
                val y = centerY + sin(angle) * r
                val size = treeSize * scale
                drawTree(canvas, x, y, size, i % canopies.size)
                i++
            }
        }

        /** Ein einzelnes Baum-Symbol: Nadel- oder Laubbaum, abwechselnd. */
        private fun drawTree(canvas: Canvas, x: Float, y: Float, size: Float, variant: Int) {
            if (variant % 2 == 0) {
                drawConifer(canvas, x, y, size, canopies[variant % canopies.size])
            } else {
                drawDeciduous(canvas, x, y, size, canopies[variant % canopies.size])
            }
        }

        private fun drawConifer(canvas: Canvas, x: Float, y: Float, size: Float, color: Int) {
            coniferPaint.color = color
            treePath.reset()
            treePath.moveTo(x - size * 0.45f, y - size * 0.22f)
            treePath.lineTo(x, y - size * 0.95f)
            treePath.lineTo(x + size * 0.45f, y - size * 0.22f)
            treePath.close()
            canvas.drawPath(treePath, coniferPaint)
            treePath.reset()
            treePath.moveTo(x - size * 0.28f, y - size * 0.62f)
            treePath.lineTo(x, y - size * 1.05f)
            treePath.lineTo(x + size * 0.28f, y - size * 0.62f)
            treePath.close()
            canvas.drawPath(treePath, coniferPaint)
            canvas.drawRect(x - size * 0.05f, y - size * 0.22f, x + size * 0.05f, y, trunkPaint)
        }

        private fun drawDeciduous(canvas: Canvas, x: Float, y: Float, size: Float, color: Int) {
            deciduousPaint.color = color
            canvas.drawCircle(x - size * 0.1f, y - size * 0.68f, size * 0.42f, deciduousPaint)
            canvas.drawCircle(x + size * 0.18f, y - size * 0.45f, size * 0.3f, deciduousPaint)
            canvas.drawRect(x - size * 0.045f, y - size * 0.28f, x + size * 0.045f, y, trunkPaint)
        }

        /**
         * Ankerorte aus `cities_near.json`. Orte im Bild bekommen ihren Namen
         * wie bisher ([drawCityLabels]); Orte außerhalb werden als Hinweis an
         * den Bildrand gesetzt ([drawEdgeHints]), damit der enge Ausschnitt um
         * die Waldfläche trotzdem Anhaltspunkte bietet ("wo ist was").
         */
        private fun drawCities(
            canvas: Canvas,
            projection: org.osmdroid.views.Projection,
            centerX: Float,
            centerY: Float
        ) {
            if (nearbyCities.isEmpty()) return
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val visible = ArrayList<Candidate>(nearbyCities.size)
            val hidden = ArrayList<Candidate>(nearbyCities.size)
            for (city in nearbyCities) {
                projection.toPixels(GeoPoint(city.lat, city.lon), cityPoint)
                val x = cityPoint.x.toFloat()
                val y = cityPoint.y.toFloat()
                if (!x.isFinite() || !y.isFinite()) continue
                if (x in 0f..w && y in 0f..h) {
                    visible.add(Candidate(city, x, y))
                } else {
                    hidden.add(Candidate(city, x, y))
                }
            }
            visible.sortByDescending { it.city.population }

            val occupied = drawCityLabels(canvas, visible, w, centerX, centerY)
            drawEdgeHints(canvas, hidden, w, h, occupied)
        }

        /**
         * Beschriftet die Orte im Bild. Gezeichnet werden nur die größten; was
         * sich überlagert oder den Flughafen-Badge verdecken würde, bleibt weg.
         * Liefert die belegten Rechtecke, damit die Randhinweise nicht mit
         * ihnen zusammenstoßen.
         */
        private fun drawCityLabels(
            canvas: Canvas,
            candidates: List<Candidate>,
            w: Float,
            centerX: Float,
            centerY: Float
        ): List<RectF> {
            if (candidates.isEmpty()) return emptyList()

            val fm = cityLabel.fontMetrics
            val gap = CITY_LABEL_GAP_DP * density
            val dotRadius = CITY_DOT_DP * density
            val pad = 2f * density
            val minDistance = CITY_MIN_DISTANCE_DP * density
            val minDistance2 = minDistance * minDistance
            val badge = RectF(
                centerX - BADGE_RESERVE_HALF_W_DP * density,
                centerY - BADGE_RESERVE_HALF_H_DP * density,
                centerX + BADGE_RESERVE_HALF_W_DP * density,
                centerY + BADGE_RESERVE_HALF_H_DP * density
            )
            val chosen = ArrayList<Candidate>(MAX_CITY_LABELS)
            val placed = ArrayList<RectF>(MAX_CITY_LABELS)
            for (candidate in candidates) {
                if (chosen.size >= MAX_CITY_LABELS) break
                val name = candidate.city.displayName(cityLanguage)
                if (name.isEmpty()) continue
                if (chosen.any {
                        val dx = it.x - candidate.x
                        val dy = it.y - candidate.y
                        dx * dx + dy * dy < minDistance2
                    }
                ) {
                    continue
                }
                val textWidth = cityLabel.measureText(name)
                val textX = if (candidate.x + gap + dotRadius + textWidth <= w) {
                    candidate.x + gap + dotRadius
                } else {
                    candidate.x - gap - dotRadius - textWidth
                }
                val baseline = candidate.y - (fm.ascent + fm.descent) / 2f
                val box = RectF(
                    minOf(candidate.x - dotRadius, textX) - pad,
                    minOf(candidate.y - dotRadius, baseline + fm.ascent) - pad,
                    maxOf(candidate.x + dotRadius, textX + textWidth) + pad,
                    maxOf(candidate.y + dotRadius, baseline + fm.descent) + pad
                )
                if (RectF.intersects(badge, box)) continue
                if (placed.any { RectF.intersects(it, box) }) continue
                chosen.add(candidate)
                placed.add(box)
                canvas.drawCircle(candidate.x, candidate.y, dotRadius, cityDot)
                canvas.drawText(name, textX, baseline, cityHalo)
                canvas.drawText(name, textX, baseline, cityLabel)
            }
            return placed
        }

        /**
         * Hinweise am Kartenrand: Orte, die außerhalb des Bildes liegen, werden
         * mit einem kleinen Pfeil in Blickrichtung und ihrer Entfernung an den
         * Bildrand gesetzt. Gezeigt werden nur die nächsten Orte
         * ([MAX_EDGE_HINTS]); was mit einer Beschriftung im Bild oder einem
         * anderen Hinweis zusammenstößt, bleibt weg.
         */
        private fun drawEdgeHints(
            canvas: Canvas,
            hidden: List<Candidate>,
            w: Float,
            h: Float,
            occupied: List<RectF>
        ) {
            if (hidden.isEmpty()) return
            val margin = EDGE_HINT_MARGIN_DP * density
            val left = margin
            val top = margin
            val right = w - margin
            val bottom = h - margin
            if (right <= left || bottom <= top) return

            val fm = edgeLabel.fontMetrics
            val textHeight = fm.descent - fm.ascent
            val marker = EDGE_HINT_MARKER_DP * density
            val gap = EDGE_HINT_GAP_DP * density
            val pad = 2f * density
            val placed = ArrayList<RectF>(MAX_EDGE_HINTS)

            val ordered = hidden.sortedBy { distanceMeters(it.city) }
            for (candidate in ordered) {
                if (placed.size >= MAX_EDGE_HINTS) break
                val name = candidate.city.displayName(cityLanguage)
                if (name.isEmpty()) continue
                val edge = CityEdgeHints.clamp(candidate.x, candidate.y, left, top, right, bottom)
                if (edge.onScreen) continue

                val text = name + " " + ScaleBar.distanceText(
                    distanceMeters(candidate.city), scaleLocale
                )
                val textWidth = edgeLabel.measureText(text)
                // Die Beschriftung nach innen versetzen; der Pfeil steht am Rand.
                val textLeft = (edge.x - edge.dirX * (marker + gap) - textWidth / 2f)
                    .coerceIn(pad, maxOf(pad, w - pad - textWidth))
                val centerY = edge.y - edge.dirY * (marker + gap + textHeight / 2f)
                val boxTop = (centerY - textHeight / 2f)
                    .coerceIn(pad, maxOf(pad, h - pad - textHeight))
                val box = RectF(
                    textLeft - pad, boxTop - pad,
                    textLeft + textWidth + pad, boxTop + textHeight + pad
                )
                if (placed.any { RectF.intersects(it, box) }) continue
                if (occupied.any { RectF.intersects(it, box) }) continue

                drawHintMarker(canvas, edge, marker)
                val baseline = boxTop - fm.ascent
                canvas.drawText(text, textLeft, baseline, edgeHalo)
                canvas.drawText(text, textLeft, baseline, edgeLabel)
                placed.add(box)
            }
        }

        /** Pfeil am Bildrand, der in Richtung des Ortes zeigt. */
        private fun drawHintMarker(canvas: Canvas, edge: CityEdgeHints.Edge, size: Float) {
            val half = size / 2f
            val tipX = edge.x + edge.dirX * half
            val tipY = edge.y + edge.dirY * half
            val baseX = edge.x - edge.dirX * half
            val baseY = edge.y - edge.dirY * half
            // Senkrechte zur Blickrichtung spannt die Grundseite auf.
            val perpX = -edge.dirY
            val perpY = edge.dirX
            hintPath.reset()
            hintPath.moveTo(tipX, tipY)
            hintPath.lineTo(baseX + perpX * half, baseY + perpY * half)
            hintPath.lineTo(baseX - perpX * half, baseY - perpY * half)
            hintPath.close()
            canvas.drawPath(hintPath, hintFill)
        }

        /** Badge mit dem IATA-Code des Flughafens, über der Ausgleichsfläche. */
        private fun drawAirportBadge(canvas: Canvas, centerX: Float, centerY: Float) {
            badgeLabel.textSize = 8.5f * density
            val fm = badgeLabel.fontMetrics
            val textHeight = fm.descent - fm.ascent
            val textWidth = badgeLabel.measureText(airportCode)
            val padX = 3.5f * density
            val padY = 1.6f * density
            val badgeWidth = textWidth + 2 * padX
            val badgeHeight = textHeight + 2 * padY
            val left = centerX - badgeWidth / 2
            val top = centerY - badgeHeight / 2
            val radius = badgeHeight / 2
            badgeLabel.color = readableOn(accentColor)
            canvas.drawRoundRect(left, top, left + badgeWidth, top + badgeHeight, radius, radius, badgeFill)
            canvas.drawRoundRect(left, top, left + badgeWidth, top + badgeHeight, radius, radius, badgeRing)
            canvas.drawText(
                airportCode,
                centerX - textWidth / 2,
                centerY - (fm.descent + fm.ascent) / 2,
                badgeLabel
            )
        }

        private fun readableOn(bg: Int): Int {
            val r = Color.red(bg) / 255f
            val g = Color.green(bg) / 255f
            val b = Color.blue(bg) / 255f
            val luminance = 0.299f * r + 0.587f * g + 0.114f * b
            return if (luminance > 0.5f) Color.rgb(43, 43, 43) else Color.WHITE
        }
    }

    /**
     * Kleines Maßband unten links: eine Linie mit Endstrichen und der
     * Entfernung, die sie auf der Karte abdeckt (z. B. "10 km"). Die Länge wird
     * so gewählt, dass sie in rund [MAX_BAR_DP] dp passt und eine runde Zahl
     * ergibt – so bleibt der Maßstab der Karte ablesbar.
     */
    private inner class ScaleBarOverlay(
        private val density: Float
    ) : Overlay() {

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = SCALE_TEXT_DP * density
            color = MaterialColors.getColor(
                this@Co2OffsetMapView,
                com.google.android.material.R.attr.colorOnSurface
            )
        }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
            strokeCap = Paint.Cap.ROUND
            color = textPaint.color
        }
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark) Color.argb(170, 18, 22, 26) else Color.argb(190, 255, 255, 255)
            style = Paint.Style.FILL
        }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val w = mapView.width
            val h = mapView.height
            if (w <= 0 || h <= 0) return
            val metersPerPixel = metersPerPixel(mapView)
            if (metersPerPixel <= 0.0 || !metersPerPixel.isFinite()) return

            val distance = ScaleBar.niceDistance(MAX_BAR_DP * density * metersPerPixel)
            if (distance <= 0.0) return
            val barPx = (distance / metersPerPixel).toFloat()
            val label = ScaleBar.distanceText(distance, scaleLocale)

            val margin = SCALE_MARGIN_DP * density
            val pad = 4f * density
            val tick = 4f * density
            val labelWidth = textPaint.measureText(label)
            val fm = textPaint.fontMetrics

            val boxWidth = maxOf(barPx, labelWidth) + 2f * pad
            val barY = h - margin - pad
            val baseline = barY - tick - 2f * density - fm.descent
            val boxTop = baseline + fm.ascent - pad
            val boxLeft = margin

            canvas.drawRoundRect(
                boxLeft, boxTop, boxLeft + boxWidth, h - margin,
                3f * density, 3f * density, backgroundPaint
            )
            canvas.drawText(label, boxLeft + (boxWidth - labelWidth) / 2f, baseline, textPaint)

            val barLeft = boxLeft + (boxWidth - barPx) / 2f
            canvas.drawLine(barLeft, barY, barLeft + barPx, barY, barPaint)
            canvas.drawLine(barLeft, barY, barLeft, barY - tick, barPaint)
            canvas.drawLine(barLeft + barPx, barY, barLeft + barPx, barY - tick, barPaint)
        }
    }

    /** Bodenmeter je Bildschirmpixel am Kartenmittelpunkt (Web-Mercator). */
    private fun metersPerPixel(mapView: MapView): Double {
        val center = mapView.mapCenter ?: return 0.0
        val projection = mapView.projection
        val start = Point()
        val end = Point()
        projection.toPixels(GeoPoint(center.latitude, center.longitude), start)
        projection.toPixels(GeoPoint(center.latitude + SCALE_SAMPLE_DEG, center.longitude), end)
        val dy = abs(end.y - start.y)
        if (dy <= 0) return 0.0
        return SCALE_SAMPLE_DEG * METERS_PER_DEGREE_LAT / dy
    }

    /**
     * Radius der Ausgleichsfläche in Bildschirmpixeln – **maßstabsgetreu**. Der
     * Kreisradius in Metern ([radiusMeters]) wird über die Projektion in Pixel
     * umgerechnet und ist damit an Zoom und Kartenmaßstab gebunden.
     */
    private fun forestRadiusPx(projection: org.osmdroid.views.Projection): Float {
        if (hectares <= 0.0) return 0f
        val dLat = radiusDegreesLat()
        val center = Point()
        val edge = Point()
        projection.toPixels(GeoPoint(airportLat, airportLon), center)
        projection.toPixels(GeoPoint(airportLat + dLat, airportLon), edge)
        return abs(edge.y - center.y).toFloat()
    }

    private fun easeOutCubic(v: Float): Float {
        val u = 1f - v
        return 1f - u * u * u
    }

    private fun easeOutBack(v: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = v - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    /** Luftlinie vom Flughafen zum Ankerort in Metern (für die Randhinweise). */
    private fun distanceMeters(city: NearbyCity): Double =
        GeoMath.distanceKm(
            AirportData.GeoLocation(airportLat, airportLon),
            AirportData.GeoLocation(city.lat, city.lon)
        ) * 1000.0

    /** Ein Ankerort mit seiner Position auf dem Bildschirm. */
    private class Candidate(val city: NearbyCity, val x: Float, val y: Float)

    companion object {
        /** Feste Zahl der rein dekorativen Baum-Symbole (keine Zählfunktion). */
        const val DECORATIVE_TREES = 90

        /** Dauer des Wachstums der Ausgleichsfläche. */
        const val ANIMATION_DURATION_MS = 2600L

        /** Anteil der Animation, in dem nur die Fläche wächst. */
        const val AREA_GROW_SHARE = 0.3f

        /** Anteil, ab dem die Bäume zu sprießen beginnen. */
        const val TREE_START_SHARE = 0.25f

        /** Untergrenze, damit die Bäume nicht kleiner als ein Punkt werden. */
        const val MIN_TREE_DP = 3f
        const val MAX_TREE_DP = 14f

        /** Ab diesem Bildschirmradius (dp) lohnt sich das Zeichnen der Bäume. */
        const val MIN_TREE_AREA_DP = 12f

        /** Goldener Winkel der Sonnenblumen-Spirale (rad). */
        const val GOLDEN_ANGLE = 2.399963229728653

        /** Kleinster/groesster Ausschnitt um den Flughafen (Grad). */
        const val MIN_HALF_SPAN_DEG = 0.25
        const val MAX_HALF_SPAN_DEG = 6.0

        /** Rand um Waldfläche bzw. Land beim Zoomen (dp). */
        const val ZOOM_PADDING_DP = 24

        /** Der Wald füllt etwa 1/MARGIN der kürzeren Bildschirmkante. */
        const val FOREST_ZOOM_MARGIN = 4.0

        /** Ein Hektar in Quadratmetern. */
        const val SQUARE_METERS_PER_HECTARE = 10_000.0

        /** Mittlerer Abstand zweier Breitengrade in Metern. */
        const val METERS_PER_DEGREE_LAT = 111_320.0

        /** Schriftgröße, Rand und größte Länge des Maßbands (dp). */
        const val SCALE_TEXT_DP = 10f
        const val SCALE_MARGIN_DP = 10f
        const val MAX_BAR_DP = 96f

        /** Breitengrad-Schritt, mit dem der Maßstab des Maßbands gemessen wird. */
        const val SCALE_SAMPLE_DEG = 0.01

        /** Ankerorte: Schriftgröße, Punkt, Abstand und Höchstzahl je Ansicht. */
        const val CITY_LABEL_DP = 9f
        const val CITY_DOT_DP = 1.8f
        const val CITY_LABEL_GAP_DP = 3f
        const val CITY_MIN_DISTANCE_DP = 26f
        const val MAX_CITY_LABELS = 10

        /** Randhinweise: Schrift, Pfeil, Abstand zum Rand und Höchstzahl. */
        const val EDGE_HINT_TEXT_DP = 9f
        const val EDGE_HINT_MARKER_DP = 8f
        const val EDGE_HINT_GAP_DP = 4f
        const val EDGE_HINT_MARGIN_DP = 6f
        const val MAX_EDGE_HINTS = 6

        /** Um den Flughafen-Badge bleibt dieser Bereich frei von Städtenamen (dp). */
        const val BADGE_RESERVE_HALF_W_DP = 18f
        const val BADGE_RESERVE_HALF_H_DP = 10f

        /**
         * Höchste Zoomstufe dieser Seite. Sie liegt über [WorldMapFragment.MAX_ZOOM],
         * weil eine Waldfläche von wenigen Hektar sonst nur ein Punkt wäre; die
         * offline gezeichneten Umrisse fallen bei hohem Zoom auf die feinste
         * Stufe zurück.
         */
        const val MAX_DETAIL_ZOOM = 18.0
    }
}