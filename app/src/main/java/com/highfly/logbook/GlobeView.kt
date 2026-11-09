package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wiederverwendbare Weltkugel (orthografische Projektion auf Canvas, siehe
 * [GeoMath.Globe]): Land mit Grenzen, Flugverbindungen als Großkreisbögen,
 * Flughafen-Punkte mit Dreilettercodes und ab einer Zoomstufe Städte.
 *
 * Wird an zwei Stellen genutzt: als kleine, langsam rotierende Kugel in der
 * Dashboard-Kachel „Weltkugel" (nicht interaktiv, ohne Beschriftung) und als große bedienbare Kugel auf der Seite „Weltkugel"
 * ([GlobeFragment]). Dieselbe Komponente soll später auch die Rubbelkarte
 * ablösen.
 *
 * Flüssigkeit: Die Umrisse liegen als Einheitsvektoren vor
 * ([CountryShapes.Country.unitRings]) und werden pro Neuaufbau je Punkt einmal
 * per Matrixmultiplikation projiziert; der teure Pfadaufbau läuft auf einem
 * Hintergrund-Thread, der Zeichen-Thread malt nur den zuletzt fertigen
 * Zustand ([drawn]). Während einer Geste wird pro Bild nur neu aufgebaut,
 * wenn sich Blickpunkt oder Zoom tatsächlich geändert haben. Drehen per
 * Finger mit Fling-Nachlauf, Zoomen per Pinch und Doppeltipp, programmatisch
 * über [zoomTo].
 */
class GlobeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Inhalt der Kugel: Länder, Routen, Flughäfen, Städte. */
    data class Content(
        val countries: List<CountryShapes.Country> = emptyList(),
        val routes: List<GlobeRoutes.Arc> = emptyList(),
        val airports: Map<String, GlobeRoutes.Airport> = emptyMap(),
        /** Zahl der Verbindungen je Flughafen; stark vernetzte zeichnen zuletzt (oben). */
        val connections: Map<String, Int> = emptyMap(),
        val cities: List<MapCity> = emptyList(),
        /**
         * Angeklickter Flughafen (wie auf der Weltkarte): null = alle gleich.
         * Die mit ihm verbundenen ([reached]) werden betont, die übrigen gräulich.
         */
        val selectedIata: String? = null,
        val reached: Set<String> = emptySet()
    ) {
        /** Städte einmalig nach Wichtigkeit sortiert - pro Aufbau sortieren wäre teuer. */
        val citiesSorted: List<MapCity> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            cities.sortedBy { it.importance() }
        }
    }

    /** Ein projizierter Punkt mit Beschriftung. */
    private data class Marker(
        val label: String,
        val x: Float,
        val y: Float,
        val importance: Int,
        /** Gräulich zurücktreten, weil ein anderer Flughafen ausgewählt ist. */
        val muted: Boolean = false,
        /** Der ausgewählte Flughafen selbst, wird zuletzt (oben) gezeichnet. */
        val selected: Boolean = false,
        /** Hauptstadt: wird unterstrichen wie auf der Weltkarte. */
        val capital: Boolean = false
    )

    /**
     * Fertig projizierter Zustand, vom Aufbau-Thread gebaut, nur gelesen beim
     * Zeichnen. Land und Routen liegen je in EINEM gemergten Pfad: ein
     * drawPath-Aufruf statt hunderter einzelner - das hält das Drehen in der
     * kleinen Kachel flüssig (drawPath ist der teure Teil, nicht die Projektion).
     */
    private class Built(
        val globe: GeoMath.Globe,
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val zoom: Float,
        val landPath: Path,
        val routePath: Path,
        val dotHaloPath: Path,
        val dotPath: Path,
        val airportMarkers: List<Marker>,
        val countryMarkers: List<Marker>,
        val cityMarkers: List<Marker>
    )

    @Volatile
    private var content = Content()

    // Blickpunkt in Radianten und Zoomstufe.
    private var centerLonRad = DEFAULT_CENTER_LON_RAD
    private var centerLatRad = DEFAULT_CENTER_LAT_RAD
    private var zoom = ZOOM_MIN

    private var accentColor = Color.BLUE
    @Volatile
    private var drawn: Built? = null
    private var buildGeneration = 0
    private var buildExecutor = Executors.newSingleThreadExecutor()
    private var rebuildPosted = false

    /** Interaktives Drehen/Zoomen; in der Kachel-Vorschau aus. */
    var interactive = true
        set(value) {
            field = value
            if (!value) stopFling()
        }

    /** Langsame Eigendrehung (Dashboard-Kachel). */
    var autoRotate = false
        set(value) {
            field = value
            updateAutoRotate()
        }

    /** Tippen auf einen Flughafen-Punkt. */
    var onAirportTap: ((String) -> Unit)? = null

    /** Nach jedem neu gezeichneten Zustand (z. B. für den Maßstab). */
    var onStateChanged: (() -> Unit)? = null

    /** Plan der Layover-Animation, null wenn sie nicht läuft. */
    private var layoverPlan: LayoverAnimation.Plan? = null
    private var layoverStartMs = 0L
    private var layoverPausedAtMs: Long? = null
    private var layoverStatic = false
    private var layoverTickerActive = false

    /** Blendet der Knopf die Spuren dauerhaft ein, zeichnet sie stattdessen statisch. */
    var onLayoverFinished: (() -> Unit)? = null

    private val isDark: Boolean
        get() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private val density get() = resources.displayMetrics.density

    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val landFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val airportDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cityDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(8.5f)
        style = Paint.Style.FILL
    }
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    /** Äußerer Akzent-Rahmen um den ausgewählten Flughafen (wie auf der Weltkarte). */
    private val badgeHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    /** Füllfarbe der gräulich zurücktretenden Badges bei Auswahl. */
    private var mutedBadgeColor = Color.GRAY
    private val cityLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f)
        style = Paint.Style.FILL
    }
    private val cityLabelHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val countryLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        style = Paint.Style.FILL
        isFakeBoldText = true
    }
    private val countryLabelHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val collision = LabelCollision()
    private val runPath = Path()
    private val clipPath = Path()
    private val planePath = Path()

    // Gesten
    private var touchX = 0f
    private var touchY = 0f
    private var touchIndex = 0
    private var tapX = 0f
    private var tapY = 0f
    private var draggedPx = 0f
    private var scaled = false
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastMoveMs = 0L
    private var flingActive = false
    private var focusAnim: ValueAnimator? = null

    private val zoomDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.OnScaleGestureListener {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaled = true
            zoomBy(detector.scaleFactor)
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {}
    })

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            toggleZoom()
            return true
        }
    })

    init {
        applyColors()
        planePath.moveTo(6f * density, 0f)
        planePath.lineTo(-4.5f * density, 3.5f * density)
        planePath.lineTo(-2f * density, 0f)
        planePath.lineTo(-4.5f * density, -3.5f * density)
        planePath.close()
    }

    /** Farben: Akzent für Routen/Punkte, Land/Ozean je nach Dark Mode. */
    fun setColors(accent: Int) {
        accentColor = accent
        applyColors()
        invalidate()
    }

    private fun applyColors() {
        oceanPaint.color = if (isDark) Color.rgb(20, 30, 40) else Color.rgb(174, 202, 226)
        landFillPaint.color = if (isDark) Color.rgb(52, 65, 76) else Color.rgb(222, 226, 220)
        borderPaint.color = if (isDark) Color.rgb(120, 132, 144) else Color.rgb(150, 160, 152)
        borderPaint.strokeWidth = 0.7f * density
        routePaint.color = accentColor
        airportDotPaint.color = accentColor
        cityDotPaint.color = if (isDark) Color.rgb(200, 210, 220) else Color.rgb(90, 100, 90)
        badgeFillPaint.color = accentColor
        badgeFillPaint.alpha = 255
        badgeRingPaint.color = Color.WHITE
        badgeRingPaint.alpha = 255
        badgeTextPaint.color = readableOn(accentColor)
        badgeTextPaint.isFakeBoldText = false
        mutedBadgeColor = if (isDark) Color.rgb(96, 104, 116) else Color.rgb(150, 158, 152)
        cityLabelPaint.color = if (isDark) Color.rgb(210, 218, 226) else Color.rgb(70, 80, 70)
        cityLabelHaloPaint.color = if (isDark) Color.rgb(20, 30, 40) else Color.rgb(240, 246, 250)
        countryLabelPaint.color = if (isDark) Color.rgb(235, 240, 246) else Color.rgb(35, 42, 48)
        countryLabelHaloPaint.color = cityLabelHaloPaint.color
        dotHaloPaint.color = cityLabelHaloPaint.color
        rimPaint.color = accentColor
        rimPaint.alpha = 140
        rimPaint.strokeWidth = 1.5f * density
        planePaint.color = accentColor
    }

    /** Setzt den Inhalt und stößt einen Neuaufbau an. */
    fun setContent(value: Content) {
        content = value
        scheduleRebuild()
    }

    /** Aktueller Inhalt (z. B. für den Maßstab). */
    fun currentContent(): Content = content

    // ---------------------------------------------------------------- Zustand

    /** Kleiner seitlicher Rand der Kugel im Ausgangszustand, Anteil am Radius. */
    private fun radiusFor(w: Float, h: Float, z: Float): Float =
        min(w, h) * (1f - GLOBE_MARGIN) / 2f * z

    private fun clampZoom(value: Float): Float = value.coerceIn(ZOOM_MIN, ZOOM_MAX)

    /** Vergrößert/verkleinert um [faktor] (Zoom-Knöpfe, Pinch). */
    fun zoomBy(faktor: Float) {
        val next = clampZoom(zoom * faktor)
        if (abs(next - zoom) < 0.001f) return
        zoom = next
        scheduleRebuild()
    }

    private fun toggleZoom() {
        zoomBy(if (zoom > ZOOM_MIN + 0.05f) ZOOM_MIN / zoom else ZOOM_DOUBLE_TAP / zoom)
    }

    /** Animiertes Zentrieren auf (lat, lon) mit Ziel-Zoom. */
    fun zoomTo(lat: Double, lon: Double, targetZoom: Float) {
        val target = clampZoom(targetZoom)
        val fromLon = centerLonRad
        var toLon = Math.toRadians(lon)
        while (toLon - fromLon > Math.PI) toLon -= 2 * Math.PI
        while (fromLon - toLon > Math.PI) toLon += 2 * Math.PI
        val fromLat = centerLatRad
        val toLat = Math.toRadians(lat).coerceIn(-Math.PI / 2, Math.PI / 2)
        val fromZoom = zoom
        focusAnim?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_ANIM_MS
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                centerLonRad = fromLon + (toLon - fromLon) * f
                centerLatRad = fromLat + (toLat - fromLat) * f
                zoom = fromZoom + (target - fromZoom) * f
                scheduleRebuild()
            }
        }
        focusAnim = anim
        anim.start()
    }

    /** Zurück auf die Weltansicht. */
    fun resetToWorld() {
        zoomTo(DEFAULT_CENTER_LAT, DEFAULT_CENTER_LON, ZOOM_MIN)
    }

    /**
     * Bodenmeter je Bildschirmpixel in der Kugelmitte: Ein Radiant am Äquator
     * ist [EARTH_RADIUS_M] lang und belegt [radiusPx] Pixel; in Breite φ
     * schrumpft der Parallelkreis auf cos φ.
     */
    fun metersPerPixelAtCenter(): Double {
        val b = drawn ?: return 0.0
        return metersPerPixel(b.globe.centerLat, b.radius.toDouble())
    }

    // ---------------------------------------------------------------- Layover

    /** Startet die Layover-Animation; die statischen Routen werden dabei ausgeblendet. */
    internal fun startLayover(plan: LayoverAnimation.Plan) {
        layoverPlan = plan
        layoverStatic = false
        layoverStartMs = SystemClock.uptimeMillis()
        layoverPausedAtMs = null
        startLayoverTicker()
        invalidate()
    }

    /** Blendet die Spuren dauerhaft ein (Knopf), ohne die Animation erneut zu spielen. */
    fun showLayoverTrails() {
        layoverStatic = true
        stopLayoverTicker()
        invalidate()
    }

    fun stopLayover() {
        layoverPlan = null
        layoverStatic = false
        stopLayoverTicker()
        invalidate()
    }

    private val layoverTicker = object : Runnable {
        override fun run() {
            val plan = layoverPlan
            if (plan == null || layoverStatic || !isAttachedToWindow) {
                layoverTickerActive = false
                return
            }
            val elapsed = SystemClock.uptimeMillis() - layoverStartMs
            invalidate()
            if (LayoverAnimation.isFinished(elapsed, plan)) {
                layoverTickerActive = false
                onLayoverFinished?.invoke()
                return
            }
            postOnAnimationDelayed(this, LAYOVER_FRAME_MS)
        }
    }

    private fun startLayoverTicker() {
        if (layoverTickerActive) return
        layoverTickerActive = true
        postOnAnimationDelayed(layoverTicker, LAYOVER_FRAME_MS)
    }

    private fun stopLayoverTicker() {
        layoverTickerActive = false
        removeCallbacks(layoverTicker)
    }

    // ---------------------------------------------------------------- Aufbau

    private fun scheduleRebuild() {
        if (width <= 0 || height <= 0 || !isAttachedToWindow) return
        if (rebuildPosted) return
        rebuildPosted = true
        val generation = ++buildGeneration
        val executor = synchronized(this) {
            // Nach dem Detach wurde der Executor heruntergefahren; bei einer
            // erneuten Anhängung neu anlegen, sonst fliegt die RejectedExecutionException.
            if (buildExecutor.isShutdown) buildExecutor = Executors.newSingleThreadExecutor()
            buildExecutor
        }
        try {
            executor.execute {
                // Zustand erst beim Ausführen lesen: so gewinnt immer der neueste
                // Stand. Ein Fehler im Aufbau darf die App nicht abstürzen lassen -
                // das alte Bild bleibt einfach stehen.
                val built = try {
                    build(width, height, centerLonRad, centerLatRad, zoom, content)
                } catch (e: Exception) {
                    android.util.Log.e("GlobeView", "Aufbau der Kugel fehlgeschlagen", e)
                    null
                }
                post {
                    rebuildPosted = false
                    // Nach dem Detach (z. B. Orientierungs-Recreate) nicht mehr
                    // anrufen - die Empfänger (Fragment) sind dann ungültig.
                    if (!isAttachedToWindow) return@post
                    if (built != null && generation == buildGeneration) {
                        drawn = built
                        onStateChanged?.invoke()
                    }
                    invalidate()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Die Ansicht wurde zwischen Abfrage und execute() abgehängt und der
            // Executor heruntergefahren (z. B. Orientierungswechsel beim
            // 2D/3D-Wechsel) - der Neuaufbau ist dann ohnehin hinfällig.
            rebuildPosted = false
        }
    }

    /** Baut alle Pfade und Punkte für einen Zustand - läuft auf dem Hintergrund-Thread. */
    private fun build(
        w: Int, h: Int,
        lonRad: Double, latRad: Double,
        z: Float,
        c: Content
    ): Built? {
        if (w <= 0 || h <= 0) return null
        val globe = GeoMath.Globe(lonRad, latRad)
        val radius = radiusFor(w.toFloat(), h.toFloat(), z)
        if (radius <= 0f) return null
        val cx = w / 2f
        val cy = h / 2f

        // In der Kachel-Vorschau (nicht interaktiv) werden die Ringe dezimiert:
        // Bei kleiner Kugel reichen wenige Punkte pro Ring, und der Neuaufbau
        // pro Frame bleibt dadurch leicht genug für flüssiges Drehen.
        val landPath = Path()
        for (country in c.countries) {
            for (ring in country.unitRings) {
                val units = if (interactive) ring else decimate(ring, PREVIEW_RING_POINTS)
                val path = ringPath(units, globe, cx, cy, radius, closed = true) ?: continue
                landPath.addPath(path)
            }
        }
        val routePath = Path()
        for (arc in c.routes) {
            val units = if (interactive) arc.units else decimate(arc.units, PREVIEW_ROUTE_POINTS)
            val path = ringPath(units, globe, cx, cy, radius, closed = false) ?: continue
            routePath.addPath(path)
        }
        val scratch = DoubleArray(3)
        val dotHaloPath = Path()
        val dotPath = Path()
        val airportMarkers = ArrayList<Marker>(c.airports.size)
        for ((code, airport) in c.airports) {
            globe.point(airport.lat, airport.lon, scratch)
            if (!(scratch[GeoMath.Globe.FRONT] > ZERO_EPS)) continue
            val mx = (cx + scratch[GeoMath.Globe.X] * radius).toFloat()
            val my = (cy - scratch[GeoMath.Globe.Y] * radius).toFloat()
            dotHaloPath.addCircle(mx, my, 4.5f * density, Path.Direction.CW)
            dotPath.addCircle(mx, my, 3f * density, Path.Direction.CW)
            val selected = c.selectedIata
            airportMarkers.add(
                Marker(
                    code, mx, my, c.connections[code] ?: 0,
                    muted = selected != null && code != selected && code !in c.reached,
                    selected = code == selected
                )
            )
        }
        // Zeichenreihenfolge wie auf der Weltkarte: gräuliche zuerst, dann die
        // betonten, der ausgewählte ganz zuletzt - und innerhalb jeder Gruppe
        // schwach vernetzte zuerst, stark vernetzte (Hubs) zuletzt, damit deren
        // Badges bei Überlappung oben liegen.
        airportMarkers.sortWith(
            compareBy({ if (it.muted) 0 else 1 }, { if (it.selected) 1 else 0 }, { it.importance })
        )
        // Beschriftungsstufen je Zoom (siehe [labelTier]): Ländernamen und
        // Hauptstädte ab Stufe 1, größte Städte ab Stufe 2, mehr Städte ab
        // Stufe 3.
        val tier = labelTier(z)
        val countryMarkers = ArrayList<Marker>()
        if (interactive && tier >= 1) {
            val de = useGermanNames()
            for (country in c.countries) {
                globe.point(country.labelLat, country.labelLon, scratch)
                if (!(scratch[GeoMath.Globe.FRONT] > ZERO_EPS)) continue
                countryMarkers.add(
                    Marker(
                        if (de) country.nameDe else country.nameEn,
                        (cx + scratch[GeoMath.Globe.X] * radius).toFloat(),
                        (cy - scratch[GeoMath.Globe.Y] * radius).toFloat(),
                        -1
                    )
                )
            }
        }
        val cityMarkers = ArrayList<Marker>()
        if (interactive && tier >= 1) {
            val limit = if (tier >= 3) CITY_LABEL_MAX_MORE else CITY_LABEL_MAX
            // Nur Städte, die tatsächlich auf dem Bildschirm liegen, zählen
            // gegen das Limit - sonst belegen bei hohem Zoom die vielen
            // außersichtlichen Großstädte der sichtbaren Halbkugel alle Plätze.
            val margin = 48f * density
            for (city in c.citiesSorted) {
                if (!tierIncludesCity(tier, city)) continue
                globe.point(city.lat, city.lon, scratch)
                if (!(scratch[GeoMath.Globe.FRONT] > ZERO_EPS)) continue
                val mx = (cx + scratch[GeoMath.Globe.X] * radius).toFloat()
                val my = (cy - scratch[GeoMath.Globe.Y] * radius).toFloat()
                if (mx < -margin || mx > w + margin || my < -margin || my > h + margin) continue
                if (cityMarkers.size >= limit) break
                cityMarkers.add(
                    Marker(
                        city.displayName(Settings.getCityLabelLanguage(context)),
                        mx, my,
                        city.importance(),
                        capital = city.isCapital
                    )
                )
            }
        }
        return Built(globe, cx, cy, radius, z, landPath, routePath, dotHaloPath, dotPath, airportMarkers, countryMarkers, cityMarkers)
    }

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(): Boolean {
        val language = resources.configuration.locales[0].language
        return language.isEmpty() || language == Settings.LANG_DE
    }

    /**
     * Projiziert einen Ring aus Einheitsvektoren in einen Pfad, am Horizont
     * abgeschnitten. Geschlossene Ringe (Länder) schließen sich über den
     * Horizontbogen, offene (Routen) enden am Horizont.
     */
    // Puffer für den Aufbau - nur der einzelne Aufbau-Thread benutzt sie.
    private var bufX = DoubleArray(0)
    private var bufY = DoubleArray(0)
    private var bufF = DoubleArray(0)

    private fun ensureBuffers(count: Int) {
        if (bufX.size >= count) return
        bufX = DoubleArray(count)
        bufY = DoubleArray(count)
        bufF = DoubleArray(count)
    }

    private fun ringPath(
        units: DoubleArray,
        globe: GeoMath.Globe,
        cx: Float, cy: Float, radius: Float,
        closed: Boolean
    ): Path? {
        val count = units.size / 3
        if (count < 2) return null
        ensureBuffers(count)
        val xs = bufX
        val ys = bufY
        val fs = bufF
        val scratch = DoubleArray(3)
        var anyVisible = false
        for (i in 0 until count) {
            globe.unitPoint(units[i * 3], units[i * 3 + 1], units[i * 3 + 2], scratch)
            xs[i] = scratch[GeoMath.Globe.X]
            ys[i] = scratch[GeoMath.Globe.Y]
            fs[i] = scratch[GeoMath.Globe.FRONT]
            if (fs[i] > ZERO_EPS) anyVisible = true
        }
        if (!anyVisible) return null

        val out = Path()
        val run = Path()
        if (!closed) {
            // Offene Linie (Route): sichtbare Abschnitte, an Horizontübergängen
            // an den Rand geführt.
            var pen = false
            for (i in 0 until count - 1) {
                val j = i + 1
                val visA = fs[i] > ZERO_EPS
                val visB = fs[j] > ZERO_EPS
                if (visA && visB) {
                    if (!pen) {
                        run.moveTo(px(xs[i], cx, radius), py(ys[i], cy, radius))
                        pen = true
                    }
                    run.lineTo(px(xs[j], cx, radius), py(ys[j], cy, radius))
                } else if (visA != visB) {
                    val alpha = horizonAngle(xs[i], ys[i], fs[i], xs[j], ys[j], fs[j])
                    if (alpha.isFinite()) {
                        val hx = (cx + cos(alpha) * radius).toFloat()
                        val hy = (cy - sin(alpha) * radius).toFloat()
                        if (visA) {
                            if (pen) run.lineTo(hx, hy)
                            pen = false
                        } else if (!pen) {
                            run.moveTo(hx, hy)
                            pen = true
                        }
                    } else {
                        pen = false
                    }
                }
            }
            out.addPath(run)
            return if (out.isEmpty) null else out
        }

        // Geschlossener Ring: wie RubbelkarteView - sichtbare Bögen zwischen
        // Horizontkreuzungen, dazwischen der Horizontbogen.
        val crossings = ArrayList<Crossing>(4)
        for (i in 0 until count) {
            val j = (i + 1) % count
            val visA = fs[i] > ZERO_EPS
            val visB = fs[j] > ZERO_EPS
            if (visA == visB) continue
            val alpha = horizonAngle(xs[i], ys[i], fs[i], xs[j], ys[j], fs[j])
            if (!alpha.isFinite()) continue
            crossings.add(
                Crossing(
                    segment = i,
                    entering = visB,
                    alpha = alpha,
                    x = (cx + cos(alpha) * radius).toFloat(),
                    y = (cy - sin(alpha) * radius).toFloat()
                )
            )
        }
        if (crossings.isEmpty()) {
            for (i in 0 until count) {
                if (!(fs[i] > ZERO_EPS)) continue
                val x = px(xs[i], cx, radius)
                val y = py(ys[i], cy, radius)
                if (i == 0) run.moveTo(x, y) else run.lineTo(x, y)
            }
            run.close()
            out.addPath(run)
        } else {
            var first = 0
            while (first < crossings.size && !crossings[first].entering) first++
            if (first < crossings.size) {
                val total = crossings.size
                for (k in 0 until total / 2) {
                    val enter = crossings[(first + 2 * k) % total]
                    val exit = crossings[(first + 2 * k + 1) % total]
                    run.reset()
                    run.moveTo(enter.x, enter.y)
                    var steps = exit.segment - enter.segment
                    if (steps <= 0) steps += count
                    val start = enter.segment + 1
                    for (s in 0 until steps) {
                        val index = (start + s) % count
                        if (!(fs[index] > ZERO_EPS)) continue
                        run.lineTo(px(xs[index], cx, radius), py(ys[index], cy, radius))
                    }
                    run.lineTo(exit.x, exit.y)
                    appendHorizonArc(exit.alpha, enter.alpha, cx, cy, radius, run)
                    run.close()
                    out.addPath(run)
                }
            }
        }
        return if (out.isEmpty) null else out
    }

    private fun px(x: Double, cx: Float, radius: Float): Float = (cx + x * radius).toFloat()
    private fun py(y: Double, cy: Float, radius: Float): Float = (cy - y * radius).toFloat()

    /**
     * Dezimiert einen Ring aus Einheitsvektoren auf höchstens [maxPoints]
     * Punkte (gleichmäßig verteilt, erster Punkt bleibt). Für die kleine
     * Kugel in der Dashboard-Kachel: volle Auflösung wäre pro Frame zu teuer.
     */
    private fun decimate(units: DoubleArray, maxPoints: Int): DoubleArray {
        val count = units.size / 3
        if (count <= maxPoints) return units
        val step = ceil(count.toDouble() / maxPoints).toInt()
        val kept = (count + step - 1) / step
        val out = DoubleArray(kept * 3)
        var j = 0
        var i = 0
        while (i < count && j < out.size) {
            out[j++] = units[i * 3]
            out[j++] = units[i * 3 + 1]
            out[j++] = units[i * 3 + 2]
            i += step
        }
        return if (j == out.size) out else out.copyOf(j)
    }

    private class Crossing(
        val segment: Int,
        val entering: Boolean,
        val alpha: Double,
        val x: Float,
        val y: Float
    )

    /** Schnittwinkel eines Segments mit dem Horizont, NaN wenn keiner. */
    private fun horizonAngle(
        ax: Double, ay: Double, af: Double,
        bx: Double, by: Double, bf: Double
    ): Double {
        if (af == bf || (af > 0.0) == (bf > 0.0)) return Double.NaN
        val t = af / (af - bf)
        val x = ax + t * (bx - ax)
        val y = ay + t * (by - ay)
        val len = sqrt(x * x + y * y)
        if (len < 1e-12) return Double.NaN
        return atan2(y / len, x / len)
    }

    /** Horizontbogen von [from] nach [to] (Winkel auf dem Randkreis). */
    private fun appendHorizonArc(
        from: Double, to: Double,
        cx: Float, cy: Float, radius: Float,
        out: Path
    ) {
        var delta = to - from
        delta = ((delta + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI
        if (abs(delta) < ZERO_EPS) return
        val steps = min(64, max(2, ceil(abs(delta) / ARC_STEP).toInt()))
        for (s in 1..steps) {
            val a = from + delta * s / steps
            out.lineTo((cx + cos(a) * radius).toFloat(), (cy - sin(a) * radius).toFloat())
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = drawn ?: return
        val r = b.radius
        if (r <= 0f) return

        canvas.drawCircle(b.cx, b.cy, r, oceanPaint)

        clipPath.reset()
        clipPath.addCircle(b.cx, b.cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        // Land als ein gemergter Pfad: Füllung und Grenze sind zwei
        // drawPath-Aufrufe statt hunderter einzelner.
        canvas.drawPath(b.landPath, landFillPaint)
        canvas.drawPath(b.landPath, borderPaint)

        // Routen - während der Layover-Animation ausgeblendet, die animierten
        // Spuren zeichnen stattdessen.
        val suppressRoutes = layoverPlan != null && !layoverStatic
        if (!suppressRoutes && !b.routePath.isEmpty) {
            routePaint.strokeWidth = 1.1f * density
            canvas.drawPath(b.routePath, routePaint)
        }
        drawLayover(canvas, b)

        canvas.drawPath(b.dotHaloPath, dotHaloPaint)
        canvas.drawPath(b.dotPath, airportDotPaint)
        if (interactive) {
            drawMarkers(canvas, b)
        }

        canvas.restore()

        // Der Schatten-Shader hängt nur vom Radius ab und wird gecacht, damit
        // pro Bild kein neues Gradient-Objekt allokiert wird.
        if (shadowShaderRadius != r) {
            shadowShaderRadius = r
            shadowShader = RadialGradient(
                b.cx, b.cy, r,
                intArrayOf(Color.TRANSPARENT, Color.argb(110, 0, 0, 0)),
                floatArrayOf(0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        shadowPaint.shader = shadowShader
        canvas.drawCircle(b.cx, b.cy, r, shadowPaint)
        shadowPaint.shader = null
        canvas.drawCircle(b.cx, b.cy, r, rimPaint)
    }

    private var shadowShader: RadialGradient? = null
    private var shadowShaderRadius = -1f

    /**
     * Dreilettercodes als Badges wie auf der Weltkarte: gefüllte Pill in der
     * Akzentfarbe mit weißem Ring und Text in Kontrastfarbe, zentriert auf dem
     * Punkt. Danach die Städtebeschriftungen mit Kollisionsauflösung.
     */
    private fun drawMarkers(canvas: Canvas, b: Built) {
        val padX = 3f * density
        val padY = 1.5f * density
        val fm = badgeTextPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        // Gruppen in Zeichenreihenfolge (siehe build): gräulich, normal/betont,
        // ausgewählt. Pro Gruppe werden die Paints einmalig gesetzt.
        var group = -1
        for (m in b.airportMarkers) {
            val g = if (m.muted) 0 else if (m.selected) 2 else 1
            if (g != group) {
                group = g
                val fill: Int
                val bold: Boolean
                if (g == 0) {
                    fill = mutedBadgeColor
                    bold = false
                } else {
                    fill = accentColor
                    bold = true
                }
                badgeFillPaint.color = fill
                badgeFillPaint.alpha = 255
                badgeTextPaint.color = readableOn(fill)
                badgeTextPaint.isFakeBoldText = bold
                badgeRingPaint.alpha = if (g == 0) 200 else 255
            }
            val textWidth = badgeTextPaint.measureText(m.label)
            val badgeW = textWidth + 2 * padX
            val badgeH = textH + 2 * padY
            val left = m.x - badgeW / 2
            val top = m.y - badgeH / 2
            if (m.selected) {
                val extra = 3f * density
                badgeHighlightPaint.color = accentColor
                canvas.drawRoundRect(
                    left - extra, top - extra,
                    left + badgeW + extra, top + badgeH + extra,
                    badgeH / 2 + extra, badgeH / 2 + extra,
                    badgeHighlightPaint
                )
            }
            canvas.drawRoundRect(
                left, top, left + badgeW, top + badgeH,
                badgeH / 2, badgeH / 2, badgeFillPaint
            )
            canvas.drawRoundRect(
                left, top, left + badgeW, top + badgeH,
                badgeH / 2, badgeH / 2, badgeRingPaint
            )
            canvas.drawText(
                m.label,
                m.x - textWidth / 2,
                m.y - (fm.descent + fm.ascent) / 2,
                badgeTextPaint
            )
        }
        if (b.zoom >= ZOOM_COUNTRY_LABELS) {
            collision.beginFrame()
            // Zuerst die Ländernamen (grösser), dann die Städte - die
            // Kollisionsauflösung verteilt beide.
            for (m in b.countryMarkers) {
                val w = countryLabelPaint.measureText(m.label)
                val cfm = countryLabelPaint.fontMetrics
                val textH = cfm.descent - cfm.ascent
                val placed = collision.place(
                    m.x - w / 2f,
                    m.y - cfm.ascent + textH / 2f,
                    w, textH,
                    2f * density, textH * 0.9f, 2,
                    null,
                    width.toFloat(), height.toFloat()
                )
                if (placed != null) {
                    canvas.drawText(m.label, placed[0], placed[1], countryLabelHaloPaint)
                    canvas.drawText(m.label, placed[0], placed[1], countryLabelPaint)
                }
            }
            val dotR = 1.6f * density
            for (m in b.cityMarkers) {
                val w = cityLabelPaint.measureText(m.label)
                val cfm = cityLabelPaint.fontMetrics
                val textH = cfm.descent - cfm.ascent
                val placed = collision.place(
                    m.x + dotR + 2.5f * density,
                    m.y - cfm.ascent + textH / 2f,
                    w, textH,
                    2f * density, textH * 0.9f, 2,
                    m.x - dotR - 2.5f * density - w,
                    width.toFloat(), height.toFloat()
                )
                if (placed != null) {
                    canvas.drawCircle(m.x, m.y, dotR, cityDotPaint)
                    // Hauptstädte unterstreichen wie auf der Weltkarte.
                    cityLabelPaint.isUnderlineText = m.capital
                    cityLabelHaloPaint.isUnderlineText = m.capital
                    canvas.drawText(m.label, placed[0], placed[1], cityLabelHaloPaint)
                    canvas.drawText(m.label, placed[0], placed[1], cityLabelPaint)
                    cityLabelPaint.isUnderlineText = false
                    cityLabelHaloPaint.isUnderlineText = false
                }
            }
        }
    }

    /** Textfarbe auf einem Badge-Hintergrund je nach Helligkeit des Akzents. */
    private fun readableOn(bg: Int): Int {
        val r = Color.red(bg) / 255f
        val g = Color.green(bg) / 255f
        val b = Color.blue(bg) / 255f
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return if (luminance > 0.5f) Color.rgb(43, 43, 43) else Color.WHITE
    }

    /** Layover-Animation: Spuren vom Hub zu den Zielen plus Flugzeuge. */
    private fun drawLayover(canvas: Canvas, b: Built) {
        val plan = layoverPlan ?: return
        val elapsed = if (layoverPausedAtMs != null) {
            layoverPausedAtMs!! - layoverStartMs
        } else {
            SystemClock.uptimeMillis() - layoverStartMs
        }
        val alpha = if (layoverStatic) 200 else LayoverAnimation.trailAlpha(elapsed, plan)
        if (alpha <= 0) return
        trailPaint.color = accentColor
        trailPaint.alpha = alpha
        trailPaint.strokeWidth = 1.4f * density
        val scratch = DoubleArray(3)
        for (route in plan.routes) {
            val pts = route.points
            if (pts.size < 2) continue
            // Spur: sichtbare Abschnitte des Bogens.
            runPath.reset()
            var pen = false
            var prevX = 0f
            var prevY = 0f
            for (i in 0 until pts.size) {
                val p = pts[i]
                b.globe.point(p.lat, p.lon, scratch)
                val front = scratch[GeoMath.Globe.FRONT] > ZERO_EPS
                if (front) {
                    val x = (b.cx + scratch[GeoMath.Globe.X] * b.radius).toFloat()
                    val y = (b.cy - scratch[GeoMath.Globe.Y] * b.radius).toFloat()
                    if (!pen) {
                        runPath.moveTo(x, y)
                        pen = true
                    } else {
                        runPath.lineTo(x, y)
                    }
                    prevX = x
                    prevY = y
                } else {
                    pen = false
                }
            }
            if (!runPath.isEmpty) canvas.drawPath(runPath, trailPaint)

            // Flugzeug an der Spitze der Spur.
            val f = if (layoverStatic) 1f else LayoverAnimation.planeFraction(elapsed, route)
            if (f <= 0f) continue
            val fi = (f * (pts.size - 1)).coerceIn(0f, (pts.size - 1).toFloat())
            val i0 = fi.toInt().coerceAtMost(pts.size - 2)
            val frac = fi - i0
            val a = pts[i0]
            val c = pts[i0 + 1]
            val lat = a.lat + (c.lat - a.lat) * frac
            val lon = a.lon + (c.lon - a.lon) * frac
            b.globe.point(lat, lon, scratch)
            if (!(scratch[GeoMath.Globe.FRONT] > ZERO_EPS)) continue
            val x = (b.cx + scratch[GeoMath.Globe.X] * b.radius).toFloat()
            val y = (b.cy - scratch[GeoMath.Globe.Y] * b.radius).toFloat()
            // Fahrtrichtung aus dem projizierten Nachbarpunkt.
            val nlat = a.lat + (c.lat - a.lat) * (frac + 0.05f).coerceAtMost(1f)
            val nlon = a.lon + (c.lon - a.lon) * (frac + 0.05f).coerceAtMost(1f)
            b.globe.point(nlat, nlon, scratch)
            val nx = (b.cx + scratch[GeoMath.Globe.X] * b.radius).toFloat()
            val ny = (b.cy - scratch[GeoMath.Globe.Y] * b.radius).toFloat()
            val dxn = nx - x
            val dyn = ny - y
            val angle = if (abs(dxn) < 0.01f && abs(dyn) < 0.01f) {
                atan2(-(y - prevY).toDouble(), (x - prevX).toDouble())
            } else {
                atan2(dyn.toDouble(), dxn.toDouble())
            }
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(Math.toDegrees(angle).toFloat())
            canvas.drawPath(planePath, planePaint)
            canvas.restore()
        }
    }

    // ---------------------------------------------------------------- Gesten

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive || width <= 0 || height <= 0) return super.onTouchEvent(event)
        zoomDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        if (zoomDetector.isInProgress) {
            stopFling()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                focusAnim?.cancel()
                focusAnim = null
                touchIndex = 0
                touchX = event.getX(0)
                touchY = event.getY(0)
                tapX = touchX
                tapY = touchY
                draggedPx = 0f
                scaled = false
                velocityX = 0f
                velocityY = 0f
                lastMoveMs = SystemClock.uptimeMillis()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return true
            MotionEvent.ACTION_POINTER_UP -> {
                for (i in 0 until event.pointerCount) {
                    if (i == event.actionIndex) continue
                    touchIndex = if (i > event.actionIndex) i - 1 else i
                    touchX = event.getX(i)
                    touchY = event.getY(i)
                    break
                }
                draggedPx = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = touchIndex.coerceIn(0, event.pointerCount - 1)
                val x = event.getX(index)
                val y = event.getY(index)
                val dx = x - touchX
                val dy = y - touchY
                val now = SystemClock.uptimeMillis()
                val dt = (now - lastMoveMs).coerceAtLeast(1L)
                if (dx != 0f || dy != 0f) {
                    // Gleitende Geschwindigkeit fürs Fling.
                    velocityX = 0.8f * velocityX + 0.2f * dx / dt * 16f
                    velocityY = 0.8f * velocityY + 0.2f * dy / dt * 16f
                    lastMoveMs = now
                    rotate(dx, dy)
                }
                touchX = x
                touchY = y
                tapX = x
                tapY = y
                draggedPx += abs(dx) + abs(dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!scaled && draggedPx > touchSlopPx()) startFling()
                if (!scaled && draggedPx <= touchSlopPx()) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopFling()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun touchSlopPx(): Float = 8f * density

    private fun rotate(dx: Float, dy: Float) {
        val radius = radiusFor(width.toFloat(), height.toFloat(), zoom)
        if (radius <= 0f) return
        val next = GeoMath.Globe(centerLonRad, centerLatRad).drag(dx, dy, radius)
        centerLonRad = next[0] % (2 * Math.PI)
        if (centerLonRad < 0.0) centerLonRad += 2 * Math.PI
        centerLatRad = next[1]
        scheduleRebuild()
    }

    /** Fling-Nachlauf: Drehung según Finger-Schwung, auslaufend. */
    private val flingTick = object : Runnable {
        override fun run() {
            if (!flingActive) return
            val dt = 16f
            val dx = velocityX * dt / 16f
            val dy = velocityY * dt / 16f
            if (abs(dx) < 0.02f && abs(dy) < 0.02f) {
                flingActive = false
                return
            }
            rotate(dx, dy)
            velocityX *= FLING_DECAY
            velocityY *= FLING_DECAY
            if (flingActive) postOnAnimationDelayed(this, 16)
        }
    }

    private fun startFling() {
        if (abs(velocityX) < 40f && abs(velocityY) < 40f) return
        if (flingActive) return
        flingActive = true
        postOnAnimationDelayed(flingTick, 16)
    }

    private fun stopFling() {
        flingActive = false
        removeCallbacks(flingTick)
    }

    override fun performClick(): Boolean {
        super.performClick()
        val b = drawn ?: return true
        val limit = 24f * density
        var best: Marker? = null
        var bestDist = limit
        for (m in b.airportMarkers) {
            val d = sqrt((m.x - tapX) * (m.x - tapX) + (m.y - tapY) * (m.y - tapY))
            if (d < bestDist) {
                bestDist = d
                best = m
            }
        }
        best?.let { onAirportTap?.invoke(it.label) }
        return true
    }

    // ---------------------------------------------------------------- Lebenszyklus

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scheduleRebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAutoRotate()
    }

    override fun onDetachedFromWindow() {
        stopAutoRotate()
        stopLayoverTicker()
        stopFling()
        synchronized(this) { buildExecutor.shutdown() }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            updateAutoRotate()
            if (layoverPlan != null && !layoverStatic && !layoverTickerActive) {
                layoverPausedAtMs?.let { layoverStartMs += SystemClock.uptimeMillis() - it }
                layoverPausedAtMs = null
                startLayoverTicker()
            }
        } else {
            stopAutoRotate()
            if (layoverTickerActive) {
                layoverPausedAtMs = SystemClock.uptimeMillis()
                stopLayoverTicker()
            }
        }
    }

    fun onPause() {
        stopAutoRotate()
        if (layoverTickerActive) {
            layoverPausedAtMs = SystemClock.uptimeMillis()
            stopLayoverTicker()
        }
    }

    fun onResume() {
        updateAutoRotate()
        if (layoverPlan != null && !layoverStatic && !layoverTickerActive) {
            layoverPausedAtMs?.let { layoverStartMs += SystemClock.uptimeMillis() - it }
            layoverPausedAtMs = null
            startLayoverTicker()
        }
        scheduleRebuild()
    }

    // ---------------------------------------------------------------- Eigendrehung

    private var autoRotateActive = false

    private val autoRotateTick = object : Runnable {
        override fun run() {
            if (!autoRotateActive) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastAutoRotateMs == 0L) 16L else (now - lastAutoRotateMs).coerceIn(1L, 100L)
            lastAutoRotateMs = now
            centerLonRad = (centerLonRad + AUTO_ROTATE_RAD_PER_MS * dt) % (2 * Math.PI)
            scheduleRebuild()
            if (autoRotateActive) postOnAnimationDelayed(this, 33)
        }
    }

    private var lastAutoRotateMs = 0L

    private fun animationsEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f

    private fun updateAutoRotate() {
        val should = autoRotate && isAttachedToWindow && visibility == VISIBLE &&
            isShown && animationsEnabled()
        if (should == autoRotateActive) return
        autoRotateActive = should
        if (should) {
            lastAutoRotateMs = 0L
            postOnAnimationDelayed(autoRotateTick, 33)
        } else {
            removeCallbacks(autoRotateTick)
        }
    }

    private fun stopAutoRotate() {
        autoRotateActive = false
        removeCallbacks(autoRotateTick)
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val ZERO_EPS = 1e-9
        private const val ARC_STEP = 0.06

        /** Rand der Kugel im Ausgangszustand (Anteil am Halbmesser). */
        const val GLOBE_MARGIN = 0.04f

        /**
         * Zoombereich: 1 = ganze Weltkugel. [ZOOM_MAX] ist so gewählt, dass
         * die kurze Bildschirmseite am Äquator etwa 50 km zeigt (bei 1080 px
         * Breite: Radius ≈ 0,48 · 1080 · 280 ≈ 145.000 px, also ≈ 44 m/Pixel).
         */
        const val ZOOM_MIN = 1f
        const val ZOOM_MAX = 280f
        private const val ZOOM_DOUBLE_TAP = 2.6f

        /**
         * Beschriftungsstufen je Zoom (Zoom-Knopf = Faktor 1,6, also Stufe 1
         * ab ca. 3× Plus - der Maßstab zeigt dann erstmals 500 km): Stufe 1
         * Ländernamen und Hauptstädte (unterstrichen), Stufe 2 ab ca. 5× Plus
         * (Maßstab ~1.250 km) zusätzlich die größten Städte (Rang 0), Stufe 3
         * ab ca. 7× Plus (Maßstab ~160 km) alle übrigen Großstädte des Assets
         * (Rang bis 3, also z. B. Hamburg, Flensburg, Samarkand, Khiva, Almaty)
         * - beschränkt auf die tatsächlich sichtbaren (Bildschirm) und
         * höchstens [CITY_LABEL_MAX_MORE].
         */
        const val ZOOM_COUNTRY_LABELS = 4.0f
        const val ZOOM_CITIES = 10.4f
        const val ZOOM_MORE_CITIES = 26.8f

        /** Punkte pro Landring in der Kachel-Vorschau (dezimiert, siehe [decimate]). */
        const val PREVIEW_RING_POINTS = 40

        /** Punkte pro Route in der Kachel-Vorschau. */
        const val PREVIEW_ROUTE_POINTS = 24

        /** Höchstzahl der Städtebeschriftungen (Stufe 1+2 bzw. Stufe 3). */
        const val CITY_LABEL_MAX = 60
        const val CITY_LABEL_MAX_MORE = 150

        /** Beschriftungsstufe zur Zoomstufe [zoom] (0 = keine, reine Kugel). */
        fun labelTier(zoom: Float): Int = when {
            zoom >= ZOOM_MORE_CITIES -> 3
            zoom >= ZOOM_CITIES -> 2
            zoom >= ZOOM_COUNTRY_LABELS -> 1
            else -> 0
        }

        /** Gehört [city] zur Beschriftungsstufe [tier]? */
        fun tierIncludesCity(tier: Int, city: MapCity): Boolean = when (tier) {
            3 -> true // alle Großstädte des Assets (Rang 0-3 + Hauptstädte)
            2 -> city.isCapital || city.rank == 0
            else -> city.isCapital
        }

        private const val ZOOM_ANIM_MS = 500L
        private const val LAYOVER_FRAME_MS = 33L

        /** Eigendrehung der Kachel-Kugel: eine Umdrehung in 60 s (bewusst langsam). */
        private const val AUTO_ROTATE_RAD_PER_MS = (2.0 * Math.PI / 60000.0)

        /** Auslaufkonstante des Flings pro Frame. */
        private const val FLING_DECAY = 0.94f

        private const val DEFAULT_CENTER_LAT = 25.0
        private const val DEFAULT_CENTER_LON = 0.0
        private val DEFAULT_CENTER_LAT_RAD = Math.toRadians(DEFAULT_CENTER_LAT)
        private val DEFAULT_CENTER_LON_RAD = Math.toRadians(DEFAULT_CENTER_LON)

        /** Mittlerer Erdradius in Metern. */
        const val EARTH_RADIUS_M = 6_371_000.0

        /**
         * Bodenmeter je Bildschirmpixel in der Kugelmitte bei Blickpunkt
         * [centerLatRad] und Kugelradius [radiusPx] (reine Rechnung, siehe
         * [GlobeViewTest]).
         */
        fun metersPerPixel(centerLatRad: Double, radiusPx: Double): Double =
            if (radiusPx <= 0.0) 0.0
            else EARTH_RADIUS_M * cos(centerLatRad) / radiusPx
    }
}
