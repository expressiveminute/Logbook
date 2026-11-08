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
import kotlin.math.roundToInt
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
        /** Länder einmalig nach Ausdehnung sortiert (große zuerst für die Beschriftung). */
        val countriesBySize: List<CountryShapes.Country> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            countries.sortedByDescending { it.latSpan }
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
        /**
         * Landfüllung interaktiv: ein Path **pro Land** (WINDING). Wichtig: NICHT
         * alle Länder in einen gemergten Pfad - am Horizont überlappen sich die
         * Rand-Slivers zweier Nachbarländer, und bei WINDING in einem Pfad
         * können sich gegenläufig orientierte Slivers gegenseitig auslöschen
         * (Loch am Kugelrand, z. B. Kanada/Alaska). Pro Land heben sich nur die
         * eigenen Enklaven-Lochringe auf.
         */
        val landPaths: List<Path>,
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

    /**
     * Läuft gerade eine Zug-/Fling-/Zoom-Animation: Der Neuaufbau arbeitet dann
     * mit doppelter Dezimierungs-Toleranz (schneller), nach dem Ende wird mit
     * voller Toleranz nachgebaut. Sonst ruckelt das Drehen, weil jeder Fingerzug
     * einen kompletten Neuaufbau aller Länderpfade auslöst.
     */
    @Volatile
    private var gestureActive = false

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
        gestureActive = true
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (focusAnim === animation) {
                    gestureActive = false
                    scheduleRebuild()
                }
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {
                if (focusAnim === animation) gestureActive = false
            }
        })
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

    /**
     * Steht, während ein Aufbau läuft, aber schon ein neuer Zustand anliegt:
     * Nach Fertigstellung des laufenden Aufbaus wird sofort der neueste Stand
     * erneut gebaut. Ohne diese Markierung gingen Drehwinkel, die während eines
     * Aufbaus entstehen, verloren - die Kugel bliebe stehen und ruckelte dann
     * nach (sichtbar in der rotierenden Kachel).
     */
    private var rebuildPending = false

    private fun scheduleRebuild() {
        if (width <= 0 || height <= 0 || !isAttachedToWindow) return
        if (rebuildPosted) {
            rebuildPending = true
            return
        }
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
                    if (!isAttachedToWindow) {
                        rebuildPending = false
                        return@post
                    }
                    if (built != null && generation == buildGeneration) {
                        drawn = built
                        onStateChanged?.invoke()
                    }
                    invalidate()
                    // Während des Aufbaus ist die Kugel weitergedreht: sofort den
                    // neuesten Stand nachbauen, statt bis zum nächsten Impuls zu warten.
                    if (rebuildPending) {
                        rebuildPending = false
                        scheduleRebuild()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Die Ansicht wurde zwischen Abfrage und execute() abgehängt und der
            // Executor heruntergefahren (z. B. Orientierungswechsel beim
            // 2D/3D-Wechsel) - der Neuaufbau ist dann ohnehin hinfällig.
            rebuildPosted = false
            rebuildPending = false
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

        // Rückseiten- und Bildschirm-Ausschluss: Ein Land, dessen Bounding-Box
        // ganz auf der Rückseite der Kugel oder (beim Zoom) ganz außerhalb des
        // Bildschirms liegt, wird gar nicht erst projiziert - bei den feinen
        // 10m-Umrissen sind das sonst zigtausend Punkte pro Bild, die niemand
        // sieht.
        val clat = globe.centerLat
        val clon = globe.centerLon
        val viewX = cos(clat) * cos(clon)
        val viewY = cos(clat) * sin(clon)
        val viewZ = sin(clat)
        val scratch = DoubleArray(3)
        // In der Kachel-Vorschau (nicht interaktiv) werden die Ringe fest dezimiert:
        // Bei kleiner Kugel reichen wenige Punkte pro Ring, und der Neuaufbau
        // pro Frame bleibt dadurch leicht genug für flüssiges Drehen.
        val landPaths = ArrayList<Path>()
        // Dezimierung per Douglas-Peucker mit bildschirmbezogener Toleranz:
        // epsRad = spacingPx / radius -> sichtbarer Fehler höchstens ~2 px,
        // konkave Küsten behalten ihre Form (keine langen Sehnen quer durchs Land).
        // Während einer Zug-/Zoom-Animation (gestureActive) doppelte Toleranz -
        // der Neuaufbau pro Bild bleibt leicht, nach dem Loslassen wird fein
        // nachgebaut.
        // Niedriger LOD während jeder Bewegung (Finger, Fling, Eigendrehung der
        // Kachel): 4 dp Toleranz statt 2 dp - nach Ende der Bewegung wird fein nachgebaut.
        val lowLod = gestureActive || autoRotateActive
        val spacingPx = (if (lowLod) 4.0 else 2.0) * density
        var epsRad = (spacingPx / radius).toDouble()
        // Pro Land die Ringe plus die Maske der gemeinsamen Grenzpunkte (parallel
        // zu den Ringen) - die Dezimierung hält diese Punkte fest, damit Grenzen
        // in beiden Nachbarländern identisch bleiben (keine Lücken dazwischen).
        val countryRingJobs = ArrayList<Pair<List<DoubleArray>, List<BooleanArray>>>()
        for (country in c.countries) {
            var midLon = (country.minLon + country.maxLon) / 2.0
            if (country.maxLon - country.minLon > 180.0) midLon += 180.0
            midLon = ((midLon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            val midLat = (country.minLat + country.maxLat) / 2.0
            val midLatRad = Math.toRadians(midLat)
            val midLonRad = Math.toRadians(midLon)
            val ccx = cos(midLatRad) * cos(midLonRad)
            val ccy = cos(midLatRad) * sin(midLonRad)
            val ccz = sin(midLatRad)
            val dot = viewX * ccx + viewY * ccy + viewZ * ccz
            var diagDeg = max(
                (country.maxLon - country.minLon) * cos(midLatRad),
                country.maxLat - country.minLat
            )
            if (diagDeg > 180.0) diagDeg = 180.0
            val diagRad = Math.toRadians(diagDeg)
            // Ganz auf der Rückseite: Winkel zwischen Blickrichtung und jedem
            // Punkt des Landes > 90°.
            if (dot < -sin(diagRad) - 0.02) continue
            // Ganz außerhalb des Bildschirms (nur beim Hineinzoomen wirksam):
            // Nahe am Horizont dehnt die Projektion eine Box stärker als
            // diagRad*radius aus - deshalb werden die Ecken mit projiziert und
            // die wahre Bildschirm-Ausdehnung bestimmt, sonst würde ein Land am
            // Rand fälschlich abgeschnitten.
            val diagPx = diagRad * radius
            if (diagPx > 0f) {
                globe.point(midLat, midLon, scratch)
                if (scratch[GeoMath.Globe.FRONT] > ZERO_EPS) {
                    val sx = (cx + scratch[GeoMath.Globe.X] * radius).toDouble()
                    val sy = (cy - scratch[GeoMath.Globe.Y] * radius).toDouble()
                    var extent = diagPx
                    for ((elat, elon) in listOf(
                        country.minLat to country.minLon,
                        country.minLat to country.maxLon,
                        country.maxLat to country.minLon,
                        country.maxLat to country.maxLon
                    )) {
                        globe.point(elat, elon, scratch)
                        if (scratch[GeoMath.Globe.FRONT] > ZERO_EPS) {
                            val ex = (cx + scratch[GeoMath.Globe.X] * radius).toDouble()
                            val ey = (cy - scratch[GeoMath.Globe.Y] * radius).toDouble()
                            val d = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy))
                            if (d > extent) extent = d
                        }
                    }
                    val dxr = if (sx < 0.0) -sx else if (sx > w) sx - w else 0.0
                    val dyr = if (sy < 0.0) -sy else if (sy > h) sy - h else 0.0
                    if (sqrt(dxr * dxr + dyr * dyr) > extent + 2.0 * density) continue
                }
            }
            if (country.unitRings.isNotEmpty()) {
                countryRingJobs.add(country.unitRings.toList() to country.sharedMasks)
            }
        }
        // Globales Kontingent: bei Überschreitung die Toleranz für ALLE Ringe
        // gleichmäßig erhöhen (nie einzelne Länder auf wenige Punkte drücken).
        // Dezimierung ist teuer (Douglas-Peucker über alle Originalpunkte) und
        // hängt nur vom Ring und der Toleranz ab - beim Drehen ändert sich keine
        // von beiden, deshalb wird das Ergebnis je Ring und Toleranzstufe gecacht.
        // Ohne Cache würde jeder Frame der Drehung alle sichtbaren Länder neu
        // dezimieren: das ist der Hauptgrund für das Ruckeln beim Drehen.
        fun decimateAll(eps: Double): Pair<List<List<DoubleArray>>, Int> {
            val bucket = epsBucket(eps)
            var total = 0
            val out = ArrayList<List<DoubleArray>>(countryRingJobs.size)
            for ((rings, masks) in countryRingJobs) {
                val dl = ArrayList<DoubleArray>(rings.size)
                for (r in rings.indices) {
                    val d = decimateCached(rings[r], eps, bucket, masks.getOrNull(r))
                    total += d.size / 3
                    dl.add(d)
                }
                out.add(dl)
            }
            return out to total
        }
        // Während einer Geste (Ziehen/Zommen/Fling) ein niedrigeres Kontingent:
        // weniger Punkte zu projizieren hält den Neuaufbau pro Bild leicht, nach
        // dem Loslassen wird mit voller Qualität nachgebaut (gestureActive=false).
        val maxPoints = if (lowLod) MAX_BUILD_POINTS_GESTURE else MAX_BUILD_POINTS
        var decResult = decimateAll(epsRad)
        var decLists = decResult.first
        var totalPoints = decResult.second
        if (totalPoints > maxPoints) {
            epsRad *= sqrt(totalPoints.toDouble() / maxPoints)
            decResult = decimateAll(epsRad)
            decLists = decResult.first
            totalPoints = decResult.second
            if (totalPoints > maxPoints) {
                epsRad *= sqrt(totalPoints.toDouble() / maxPoints)
                decLists = decimateAll(epsRad).first
            }
        }
        for (decRings in decLists) {
            // Pro Land ein Pfad (WINDING), direkt angehängt (keine Zwischen-Paths,
            // eine native Schreibung pro Punkt). Niemals alle Länder in einen
            // gemergten Pfad: am Horizont überlappen sich die Rand-Slivers zweier
            // Nachbarländer, und gegenläufig orientierte Slivers löschen sich bei
            // WINDING in einem globalen Pfad gegenseitig aus (Löcher am Rand).
            val cp = Path()
            for (units in decRings) {
                appendRingPath(units, globe, cx, cy, radius, closed = true, cp)
            }
            if (!cp.isEmpty) landPaths.add(cp)
        }
        val routePath = Path()
        for (arc in c.routes) {
            // Routen per Winkel-Subsampling dezimieren (nicht per Douglas-Peucker):
            // DP kann Sehnen von bis zu sqrt(8*eps) erzeugen - am Kugelrand schneidet
            // so eine Sehne sichtbar durchs Land statt dem Rand zu folgen. Der
            // maximale Segmentwinkel bleibt klein, das Ergebnis ist pro Bogen
            // konstant (drehungsunabhängig) und wird gecacht.
            val units = routeUnits(arc.units, epsRad)
            appendRingPath(units, globe, cx, cy, radius, closed = false, routePath)
        }
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
        // Hauptstädte ab Stufe 1, größte Städte ab Stufe 2, noch mehr Städte
        // (alle Ränge) ab Stufe 3.
        val tier = labelTier(z)
        val countryMarkers = ArrayList<Marker>()
        if (interactive && tier >= 1) {
            val de = useGermanNames()
            // Große Länder zuerst: Ihre Namen belegen in der
            // Kollisionsauflösung zuerst Platz, kleine weichen aus.
            for (country in c.countriesBySize) {
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
        return Built(globe, cx, cy, radius, z, landPaths, routePath, dotHaloPath, dotPath, airportMarkers, countryMarkers, cityMarkers)
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

    // ---------------------------------------------------------------- Dezimierungs-Cache

    // Nur der einzelne Aufbau-Thread (buildExecutor) liest/schreibt den Cache.
    // Schlüssel ist die Ring-Referenz (die Ringe liegen ab dem Laden fest in
    // Content), zweiter Schlüssel die quantisierte Toleranzstufe.
    private val decimateCache = java.util.IdentityHashMap<DoubleArray, HashMap<Int, DoubleArray>>()
    private var decimateCachePoints = 0

    /**
     * Toleranzstufe: eps wird geometrisch quantisiert (Faktor ~1,19 pro Stufe),
     * damit schon kleinste Zoom-Änderungen nicht jede Stufe neu rechnen lassen -
     * beim reinen Drehen ist eps ohnehin konstant und landet immer im selben Fach.
     */
    private fun epsBucket(eps: Double): Int {
        // Während einer Bewegung gröbere Stufen (Faktor 1.5 statt 1.19): Beim
        // Pinch-Zoom wechselt die Toleranz sonst bei jedem Bild die Stufe und der
        // Cache läuft leer - jeder Frame müsste alle Ringe neu dezimieren.
        val factor = if (gestureActive || autoRotateActive) 1.5 else 1.19
        return (Math.log(eps.coerceAtLeast(1e-9)) / Math.log(factor)).roundToInt()
    }

    // Cache für winkel-subsamplete Routenbogen: Schlüssel Bogen-Referenz +
    // quantisierter maximaler Segmentwinkel. Nur der Aufbau-Thread benutzt ihn.
    private val arcCache = java.util.IdentityHashMap<DoubleArray, HashMap<Int, DoubleArray>>()

    /**
     * Dezimiert einen Routenbogen per [GeoMath.subsampleUnitsMaxSegment]. Der
     * maximale Segmentwinkel folgt der Bildschirm-Toleranz (epsRad), ist aber
     * nach unten begrenzt (fein genug, dass der Bogen am Kugelrand sauber dem
     * Rand folgt) und nach oben gedeckelt.
     */
    private fun routeUnits(arc: DoubleArray, epsRad: Double): DoubleArray {
        val maxSeg = max(0.02, min(0.25, epsRad))
        val bucket = (Math.log(maxSeg.coerceAtLeast(1e-9)) / Math.log(1.25)).roundToInt()
        arcCache[arc]?.get(bucket)?.let { return it }
        val d = GeoMath.subsampleUnitsMaxSegment(arc, maxSeg)
        arcCache.getOrPut(arc) { HashMap() }[bucket] = d
        if (arcCache.size > 4096) arcCache.clear()
        return d
    }

    private fun decimateCached(
        ring: DoubleArray,
        eps: Double,
        bucket: Int,
        forceKeep: BooleanArray? = null
    ): DoubleArray {
        decimateCache[ring]?.get(bucket)?.let { return it }
        val d = GeoMath.decimateUnitsSafe(ring, eps, forceKeep)
        if (decimateCachePoints > DECIMATE_CACHE_MAX_POINTS) {
            // Cache überläuft (z. B. nach Zoom-Wechsel über viele Stufen): einmal
            // komplett verwerfen - die Ringe der aktuellen Stufe werden ohnehin
            // sofort neu eingetragen.
            decimateCache.clear()
            decimateCachePoints = 0
        }
        decimateCache.getOrPut(ring) { HashMap() }[bucket] = d
        decimateCachePoints += d.size / 3
        return d
    }

    /**
     * Projiziert einen Ring aus Einheitsvektoren und hängt ihn direkt an [out] an
     * (am Horizont abgeschnitten, siehe Doku oben). Geschlossene Ringe (Länder)
     * schließen sich über den Horizontbogen, offene (Routen) enden am Horizont.
     *
     * Es wird bewusst direkt in den Ziel-Pfad geschrieben: Ein Zwischen-Path pro
     * Ring plus addPath-Kopie pro Land kostete pro Drehwinkel jeden Punkt mehrfach
     * nativ - das war der Hauptgrund für das Ruckeln.
     */
    private fun appendRingPath(
        units: DoubleArray,
        globe: GeoMath.Globe,
        cx: Float, cy: Float, radius: Float,
        closed: Boolean,
        out: Path
    ): Boolean {
        val count = units.size / 3
        if (count < 2) return false
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
        if (!anyVisible) return false

        var appended = false
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
                        out.moveTo(px(xs[i], cx, radius), py(ys[i], cy, radius))
                        pen = true
                        appended = true
                    }
                    out.lineTo(px(xs[j], cx, radius), py(ys[j], cy, radius))
                } else if (visA != visB) {
                    val alpha = horizonAngle(xs[i], ys[i], fs[i], xs[j], ys[j], fs[j])
                    if (alpha.isFinite()) {
                        val hx = (cx + cos(alpha) * radius).toFloat()
                        val hy = (cy - sin(alpha) * radius).toFloat()
                        if (visA) {
                            if (pen) out.lineTo(hx, hy)
                            pen = false
                        } else if (!pen) {
                            out.moveTo(hx, hy)
                            pen = true
                        }
                    } else {
                        pen = false
                    }
                }
            }
            return appended
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
            var firstPt = true
            for (i in 0 until count) {
                if (!(fs[i] > ZERO_EPS)) continue
                val x = px(xs[i], cx, radius)
                val y = py(ys[i], cy, radius)
                if (firstPt) {
                    out.moveTo(x, y)
                    firstPt = false
                } else {
                    out.lineTo(x, y)
                }
            }
            if (!firstPt) {
                out.close()
                appended = true
            }
        } else {
            var first = 0
            while (first < crossings.size && !crossings[first].entering) first++
            // Kein "entering" gefunden (nur bei NaN-abgebrochenen Übergängen möglich):
            // trotzdem ab dem ersten Übergang paaren, sonst fiele der Ring komplett weg.
            if (first >= crossings.size) first = 0
            val total = crossings.size
            for (k in 0 until total / 2) {
                val enter = crossings[(first + 2 * k) % total]
                val exit = crossings[(first + 2 * k + 1) % total]
                out.moveTo(enter.x, enter.y)
                var steps = exit.segment - enter.segment
                if (steps <= 0) steps += count
                val start = enter.segment + 1
                for (s in 0 until steps) {
                    val index = (start + s) % count
                    if (!(fs[index] > ZERO_EPS)) continue
                    out.lineTo(px(xs[index], cx, radius), py(ys[index], cy, radius))
                }
                out.lineTo(exit.x, exit.y)
                appendHorizonArc(exit.alpha, enter.alpha, cx, cy, radius, out)
                out.close()
                appended = true
            }
        }
        return appended
    }

    private fun px(x: Double, cx: Float, radius: Float): Float = (cx + x * radius).toFloat()
    private fun py(y: Double, cy: Float, radius: Float): Float = (cy - y * radius).toFloat()

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
        // Schrittweite in Pixeln (nicht in Radiant): sonst wird der Horizontbogen
        // bei großer Kugel eckig (0.06 rad sind bei 500 px Radius ~31 px pro Stück).
        val steps = min(512, max(2, ceil(abs(delta) * radius / (2.0 * density)).toInt()))
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

        // Land pro Land ein Pfad (WINDING): Füllung und Grenzlinie daraus. In
        // einem globalen Pfad könnten sich Rand-Slivers benachbarter Länder am
        // Horizont gegenseitig auslöschen (Löcher am Kugelrand, siehe Built).
        for (p in b.landPaths) {
            canvas.drawPath(p, landFillPaint)
            canvas.drawPath(p, borderPaint)
        }

        // Routen - während der Layover-Animation ausgeblendet, die animierten
        // Spuren zeichnen stattdessen.
        val suppressRoutes = layoverPlan != null && !layoverStatic
        if (!suppressRoutes && !b.routePath.isEmpty) {
            routePaint.strokeWidth = 1.1f * density
            canvas.drawPath(b.routePath, routePaint)
        }
        drawLayover(canvas, b)

        // Punkte unter den Dreilettercodes nur in der Kachel-Vorschau; auf der
        // Seite sitzen die Badges wie auf der Weltkarte ohne Punkt darunter.
        if (!interactive) {
            canvas.drawPath(b.dotHaloPath, dotHaloPaint)
            canvas.drawPath(b.dotPath, airportDotPaint)
        }
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
     * Dreilettercodes als Badges wie auf der Weltkarte: undurchsichtige Pill mit
     * Ring und Text in Kontrastfarbe, zentriert auf dem Punkt (kein Punkt
     * darunter). Ist ein Flughafen ausgewählt, treten die nicht verbundenen
     * gräulich zurück, die verbundenen werden fett betont, der ausgewählte
     * bekommt zusätzlich einen Akzent-Rahmen - und zeichnet immer zuletzt (oben).
     * Danach Länder- und Städtebeschriftungen mit Kollisionsauflösung; die
     * Badge-Flächen sind dabei reserviert, damit keine Beschriftung auf einem
     * Code steht.
     */
    // measureText pro Marker pro Bild ist teuer - Breiten werden gecacht
    // (Schlüssel: fett/normal + Text; Schlüsselraum ist begrenzt: IATA-Codes,
    // Länder- und Städtenamen).
    // Concurrent: bei der Rubbelkarte misst der Aufbau-Thread (Label-Bau) und der
    // Zeichen-Thread (Stadtplatzierung) - beides darf nicht auf einer HashMap landen.
    private val textWidthCache = java.util.concurrent.ConcurrentHashMap<String, Float>()

    private fun cachedTextWidth(paint: Paint, bold: Boolean, text: String): Float {
        val key = (if (bold) 'b' else 'n') + text
        textWidthCache[key]?.let { return it }
        val w = paint.measureText(text)
        if (textWidthCache.size > 8192) textWidthCache.clear()
        textWidthCache.putIfAbsent(key, w)
        return w
    }

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
            val textWidth = cachedTextWidth(badgeTextPaint, badgeTextPaint.isFakeBoldText, m.label)
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
            // Die Badge-Flächen reservieren, damit keine Beschriftung über
            // einem Dreilettercode steht.
            for (m in b.airportMarkers) {
                val textWidth = cachedTextWidth(badgeTextPaint, badgeTextPaint.isFakeBoldText, m.label)
                val badgeW = textWidth + 2 * padX
                val badgeH = textH + 2 * padY
                collision.reserve(
                    m.x - badgeW / 2 - 2f * density,
                    m.y - badgeH / 2 - 2f * density,
                    m.x + badgeW / 2 + 2f * density,
                    m.y + badgeH / 2 + 2f * density
                )
            }
            // Zuerst die Ländernamen (grösser), dann die Städte - die
            // Kollisionsauflösung verteilt beide. Grössere Verschiebungen als
            // auf der Weltkarte, damit möglichst wenige Namen übereinanderfallen.
            for (m in b.countryMarkers) {
                val w = cachedTextWidth(countryLabelPaint, false, m.label)
                val cfm = countryLabelPaint.fontMetrics
                val cTextH = cfm.descent - cfm.ascent
                val placed = collision.place(
                    m.x - w / 2f,
                    m.y - cfm.ascent + cTextH / 2f,
                    w, cTextH,
                    2f * density, cTextH * 0.9f, 3,
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
                val w = cachedTextWidth(cityLabelPaint, m.capital, m.label)
                val cfm = cityLabelPaint.fontMetrics
                val cTextH = cfm.descent - cfm.ascent
                val placed = collision.place(
                    m.x + dotR + 2.5f * density,
                    m.y - cfm.ascent + cTextH / 2f,
                    w, cTextH,
                    2f * density, cTextH * 0.9f, 3,
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
                gestureActive = true
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
                if (!scaled && draggedPx > touchSlopPx()) {
                    startFling() // setzt gestureActive selbst, bis der Fling ausläuft
                } else {
                    gestureActive = false
                    scheduleRebuild()
                    if (!scaled && draggedPx <= touchSlopPx()) performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopFling()
                gestureActive = false
                scheduleRebuild()
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
                gestureActive = false
                scheduleRebuild()
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
        gestureActive = true
        postOnAnimationDelayed(flingTick, 16)
    }

    private fun stopFling() {
        if (flingActive) {
            flingActive = false
            gestureActive = false
            scheduleRebuild()
        }
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

    @Volatile
    private var autoRotateActive = false

    private val autoRotateTick = object : Runnable {
        override fun run() {
            if (!autoRotateActive) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastAutoRotateMs == 0L) 16L else (now - lastAutoRotateMs).coerceIn(1L, 100L)
            lastAutoRotateMs = now
            centerLonRad = (centerLonRad + AUTO_ROTATE_RAD_PER_MS * dt) % (2 * Math.PI)
            if (centerLonRad < 0.0) centerLonRad += 2 * Math.PI
            scheduleRebuild()
            // postOnAnimation (Choreographer) statt festem 33-ms-Timer: Der Tick
            // läuft damit im VSync-Takt des Displays (60/120 Hz) und die Drehung
            // der Kachel bleibt gleichmäßig statt in 30-Hz-Schritten zu ruckeln.
            if (autoRotateActive) postOnAnimation(this)
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
            postOnAnimation(autoRotateTick)
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
         * Ländernamen und Hauptstädte (unterstrichen), Stufe 2 eine Zoomstufe
         * früher als zuvor ab ca. 5× Plus (Maßstab ~1.250 km) zusätzlich die
         * größten Städte (Rang 0), Stufe 3 ab ca. 7× Plus (Maßstab ~160 km)
         * alle übrigen Großstädte des Assets (Rang bis 3, also z. B. Hamburg,
         * Dresden, München, Stuttgart oder Flensburg in Deutschland und
         * Bukhara, Samarkand oder Khiva in Usbekistan) - beschränkt auf die
         * tatsächlich sichtbaren (Bildschirm) und höchstens [CITY_LABEL_MAX_MORE].
         */
        const val ZOOM_COUNTRY_LABELS = 4.0f
        const val ZOOM_CITIES = 10.4f
        const val ZOOM_MORE_CITIES = 26.8f

        /** Höchstzahl der Städtebeschriftungen (Stufe 1+2 bzw. Stufe 3). */
        const val CITY_LABEL_MAX = 60
        const val CITY_LABEL_MAX_MORE = 150

        /**
         * Globales Punkt-Kontingent pro Kugel-Aufbau: Begrenzt den Aufwand beim
         * Drehen/Zoomen. Wird es überschritten, werden ALLE Ring-Budgets
         * proportional skaliert (nie einzelne Länder auf wenige Punkte drücken).
         */
        const val MAX_BUILD_POINTS = 200_000

        // Punktkontingent während einer Geste: 60k Punkte projizieren reicht für
        // flüssiges Drehen (danach wird auf MAX_BUILD_POINTS nachgebaut).
        const val MAX_BUILD_POINTS_GESTURE = 60_000

        // Obergrenze für den Dezimierungs-Cache (Punkte, nicht Bytes): ca. 400k
        // Punkte sind ~10 MB - genug für alle Ringe der aktuellen Toleranzstufen.
        private const val DECIMATE_CACHE_MAX_POINTS = 400_000

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
