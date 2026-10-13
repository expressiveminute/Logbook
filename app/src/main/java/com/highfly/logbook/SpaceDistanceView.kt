package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Kleine, bewusst schematische 2D-Grafiken der Distanz-Detailseite. Die Ansicht
 * kennt drei Bilder, die sich denselben Erdball teilen:
 *
 *  - [Mode.ORBITS]: Die Erde steht in der Mitte, ein Flugzeug umrundet sie auf
 *    einer Bahn. Dazu zählt die Seite die Erdumrundungen hoch.
 *  - [Mode.MOON]: Erde und Mond stehen nebeneinander, das Flugzeug pendelt
 *    zwischen beiden hin und her.
 *  - [Mode.SUN]: Erde und Sonne stehen sich gegenüber, das Flugzeug wandert von
 *    der Erde bis zu dem Bruchteil der Strecke, den die geflogenen Kilometer
 *    abdecken.
 *
 * Die Kontinente sind ein grob vereinfachter Umriss, nicht die feine Küste der
 * Rubbelkarte: Die Grafiken sind klein, eine Kugel mit erkennbaren Landmassen
 * genügt. Die Umrisse werden geteilt geladen und je Größe einmal in einen Pfad
 * gerechnet, damit [onDraw] während der Animation nur fertige Pfade zeichnet.
 */
class SpaceDistanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Welches der drei Bilder gezeichnet wird. */
    enum class Mode { ORBITS, MOON, SUN }

    companion object {
        private const val TAG = "SpaceDistanceView"

        /** Grobe Küstenumrisse, dieselbe Datei wie die Streckenkarte. */
        private const val LAND_ASSET = "world_land.json"

        /** Blickpunkt der Erdkugel: Europa und Afrika liegen im Bild. */
        private const val CENTER_LON = 14.0
        private const val CENTER_LAT = 20.0

        /**
         * Laufzeit eines vollen Animationslaufs. Alle vier Fluganimationen der
         * Distanz-Seite teilen sich dasselbe Tempo, siehe
         * [RouteMiniMapView.PLANE_ANIM_MS]: Der Wechsel hier wäre eine
         * Doppelpflege.
         */
        const val ANIM_MS = RouteMiniMapView.PLANE_ANIM_MS

        /**
         * Lage und Größe der Himmelskörper. Erde und Partner (Mond bzw. Sonne)
         * sitzen in beiden Bildern an derselben Stelle, damit die Seiten
         * untereinander gleich aussehen. Nur der Partner unterscheidet sich in
         * der Größe.
         */
        private const val EARTH_CX = 0.20f
        private const val EARTH_CY = 0.62f
        private const val EARTH_RW = 0.28f
        private const val EARTH_RH = 0.20f
        private const val PARTNER_CX = 0.80f
        private const val PARTNER_CY = 0.36f

        private const val TWO_PI = 2.0 * PI

        /**
         * Die Umrisse sind in allen Instanzen dieselben, deshalb werden sie
         * einmal geladen und geteilt; wartende Ansichten werden schwach
         * gehalten und danach zum Neuzeichnen angestupst.
         */
        @Volatile
        private var landRings: List<FloatArray>? = null

        @Volatile
        private var loading = false

        private val loadLock = Any()
        private val waiting = mutableListOf<WeakReference<SpaceDistanceView>>()

        private fun requestLand(context: Context, view: SpaceDistanceView) {
            val ready = landRings
            if (ready != null) {
                view.onLandLoaded()
                return
            }
            val appContext = context.applicationContext
            synchronized(loadLock) {
                if (waiting.none { it.get() === view }) {
                    waiting.add(WeakReference(view))
                }
                if (loading) return
                loading = true
            }
            Thread {
                val parsed = try {
                    loadRings(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Konnte Küstendaten nicht laden", e)
                    emptyList()
                }
                val views = synchronized(loadLock) {
                    loading = false
                    landRings = parsed
                    waiting.toList().also { waiting.clear() }
                }
                views.forEach { reference ->
                    val target = reference.get() ?: return@forEach
                    target.post { target.onLandLoaded() }
                }
            }.start()
        }

        private fun loadRings(context: Context): List<FloatArray> {
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
            return rings
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

    private val density get() = resources.displayMetrics.density

    private val isDarkTheme = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDarkTheme) Color.rgb(22, 34, 48) else Color.rgb(168, 200, 228)
    }
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDarkTheme) Color.rgb(58, 74, 88) else Color.rgb(226, 231, 222)
    }
    private val coastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        color = if (isDarkTheme) Color.rgb(80, 96, 110) else Color.rgb(150, 162, 150)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(6f * density, 5f * density), 0f
        )
    }
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDarkTheme) Color.rgb(196, 198, 204) else Color.rgb(206, 208, 214)
    }
    private val moonCraterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDarkTheme) Color.rgb(150, 152, 160) else Color.rgb(176, 178, 186)
    }
    private val sunRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
        color = Color.rgb(244, 176, 46)
    }
    private val sunCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val clipPath = Path()
    private val planeBounds = Rect()

    private val plane by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_flight)?.mutate()
    }

    private var mode = Mode.ORBITS

    /** Anteil der Sonnenstrecke, den die Flüge abdecken (0..1). */
    private var reachedFraction = 0f

    /** Animationsfortschritt. Bei ORBITS/MOON läuft er 0..1 und wiederholt
     * sich, bei SUN einmalig bis [reachedFraction]. */
    private var progress = 0f

    private var animator: ValueAnimator? = null

    /** Ob [start] gerufen wurde und die Animation im Vordergrund laufen soll. */
    private var autoplay = false

    /** Erdball als fertiger Pfad; hängt an Größe und Bild. */
    private var earthPath: Path? = null
    private var earthX = 0f
    private var earthY = 0f
    private var earthRadius = 0f

    init {
        val outline = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOutlineVariant
        )
        rimPaint.color = outline
        val variant = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        trackPaint.color = variant
        trackPaint.alpha = 150
        plane?.setTint(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        )
    }

    /**
     * Startet das Bild [mode]. [reachedFraction] ist nur bei [Mode.SUN] wichtig
     * und gibt an, bis wohin (0..1) das Flugzeug wandern soll.
     */
    fun start(mode: Mode, reachedFraction: Float = 0f) {
        this.mode = mode
        this.reachedFraction = reachedFraction.coerceIn(0f, 1f)
        progress = 0f
        earthPath = null
        autoplay = true
        requestLand(context, this)
        buildEarthPath()
        if (windowVisibility == VISIBLE) startAnimator()
        invalidate()
    }

    /** Beendet die laufende Animation, z. B. beim Verlassen der Seite. */
    fun stop() {
        autoplay = false
        animator?.cancel()
        animator = null
    }

    /**
     * Im Hintergrund pausieren, im Vordergrund weiterlaufen: Die Bahn- und
     * Pendel-Animationen laufen endlos und sollen nicht unsichtbar weiter
     * zeichnen.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!autoplay) return
        if (visibility == VISIBLE) {
            if (animator == null) startAnimator()
        } else {
            animator?.cancel()
            animator = null
        }
    }

    private fun onLandLoaded() {
        earthPath = null
        buildEarthPath()
        invalidate()
    }

    private fun startAnimator() {
        animator?.cancel()
        val target = if (mode == Mode.SUN) reachedFraction else 1f
        val value = ValueAnimator.ofFloat(0f, target).apply {
            duration = ANIM_MS
            interpolator = LinearInterpolator()
            if (mode != Mode.SUN) repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
        }
        value.start()
        animator = value
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        earthPath = null
        buildEarthPath()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    /**
     * Maße des Erdballs im jeweiligen Bild: Mittelpunkt und Radius. Für die
     * Kontinente muss das zur Größe passen, in der [onDraw] sie zeichnet.
     */
    private fun earthGeometry(w: Float, h: Float): FloatArray = when (mode) {
        Mode.ORBITS -> floatArrayOf(w / 2f, h / 2f, min(w, h) * 0.24f)
        Mode.MOON, Mode.SUN -> floatArrayOf(
            w * EARTH_CX,
            h * EARTH_CY,
            bodyEarthRadius(w, h)
        )
    }

    /** Radius des Erdballs in den Bildern Mond und Sonne (gleich groß). */
    private fun bodyEarthRadius(w: Float, h: Float): Float = min(w * EARTH_RW, h * EARTH_RH)

    private fun buildEarthPath() {
        if (width <= 0 || height <= 0) return
        val rings = landRings ?: return
        val geo = earthGeometry(width.toFloat(), height.toFloat())
        val cx = geo[0]
        val cy = geo[1]
        val r = geo[2]
        if (earthPath != null && cx == earthX && cy == earthY && r == earthRadius) return
        earthX = cx
        earthY = cy
        earthRadius = r
        earthPath = projectLand(rings, cx, cy, r)
    }

    /** Rechnet die Umrisse auf die sichtbare Vorderseite der Kugel. */
    private fun projectLand(rings: List<FloatArray>, cx: Float, cy: Float, r: Float): Path {
        val globe = GeoMath.Globe(Math.toRadians(CENTER_LON), Math.toRadians(CENTER_LAT))
        val out = DoubleArray(3)
        val path = Path()
        for (ring in rings) {
            val count = ring.size / 2
            if (count < 3) continue
            var started = false
            for (i in 0 until count) {
                val lon = ring[i * 2].toDouble()
                val lat = ring[i * 2 + 1].toDouble()
                if (!globe.inFront(lat, lon, out)) {
                    // Rückseite: der Ring wird dort aufgetrennt, damit keine
                    // Sehne quer über die sichtbare Kugel entsteht.
                    started = false
                    continue
                }
                val x = cx + (out[GeoMath.Globe.X] * r).toFloat()
                val y = cy - (out[GeoMath.Globe.Y] * r).toFloat()
                if (started) path.lineTo(x, y) else path.moveTo(x, y)
                started = true
            }
        }
        return path
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        when (mode) {
            Mode.ORBITS -> drawOrbits(canvas, w, h)
            Mode.MOON -> drawMoon(canvas, w, h)
            Mode.SUN -> drawSun(canvas, w, h)
        }
    }

    private fun drawEarth(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, oceanPaint)
        val path = earthPath
        if (path != null) {
            clipPath.reset()
            clipPath.addCircle(cx, cy, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawPath(path, landPaint)
            canvas.drawPath(path, coastPaint)
            canvas.restore()
        }
        canvas.drawCircle(cx, cy, r, rimPaint)
    }

    private fun drawOrbits(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) * 0.24f
        val orbit = r * 1.85f
        val angle = progress * TWO_PI
        canvas.drawCircle(cx, cy, orbit, trackPaint)
        drawEarth(canvas, cx, cy, r)
        val px = cx + (orbit * cos(angle)).toFloat()
        val py = cy + (orbit * sin(angle)).toFloat()
        // Tangente über einen kleinen Schritt voraus, damit die Ausrichtung
        // nicht von Hand gerechnet werden muss.
        val ahead = angle + 0.06
        val ax = cx + (orbit * cos(ahead)).toFloat()
        val ay = cy + (orbit * sin(ahead)).toFloat()
        drawPlane(canvas, px, py, r * 0.42f, RouteMiniMapView.planeRotation(ax - px, ay - py))
    }

    private fun drawMoon(canvas: Canvas, w: Float, h: Float) {
        val earthR = bodyEarthRadius(w, h)
        val earthCx = w * EARTH_CX
        val earthCy = h * EARTH_CY
        val moonR = min(w * 0.20f, h * 0.15f)
        val moonCx = w * PARTNER_CX
        val moonCy = h * PARTNER_CY

        drawEarth(canvas, earthCx, earthCy, earthR)
        // Mond: helle Scheibe mit ein paar Kratern.
        canvas.drawCircle(moonCx, moonCy, moonR, moonPaint)
        canvas.drawCircle(moonCx - moonR * 0.30f, moonCy - moonR * 0.22f, moonR * 0.22f, moonCraterPaint)
        canvas.drawCircle(moonCx + moonR * 0.32f, moonCy + moonR * 0.24f, moonR * 0.16f, moonCraterPaint)
        canvas.drawCircle(moonCx + moonR * 0.02f, moonCy - moonR * 0.42f, moonR * 0.10f, moonCraterPaint)
        canvas.drawCircle(moonCx, moonCy, moonR, rimPaint)

        // Der Flug geht von der Erd- zur Mondoberfläche; der Weg ist ein
        // leichter Bogen, das Flugzeug pendelt hin und her.
        val startX = earthCx + (moonCx - earthCx) / hypot(moonCx - earthCx, moonCy - earthCy) * earthR
        val startY = earthCy + (moonCy - earthCy) / hypot(moonCx - earthCx, moonCy - earthCy) * earthR
        val endX = moonCx - (moonCx - earthCx) / hypot(moonCx - earthCx, moonCy - earthCy) * moonR
        val endY = moonCy - (moonCy - earthCy) / hypot(moonCx - earthCx, moonCy - earthCy) * moonR
        val ctrlX = (startX + endX) / 2f
        val ctrlY = (startY + endY) / 2f - h * 0.22f
        canvas.drawPath(
            Path().apply {
                moveTo(startX, startY)
                quadTo(ctrlX, ctrlY, endX, endY)
            },
            trackPaint
        )
        // Hinflug: Kurve von 0 nach 1, Rückflug: von 1 nach 0. Die Tangente
        // zeigt nur in Richtung wachsender Kurve, auf dem Rückflug muss das
        // Symbol also um 180 Grad gedreht werden.
        val outbound = progress <= 0.5f
        val u = if (outbound) progress * 2f else (1f - progress) * 2f
        val p = bezier(startX, startY, ctrlX, ctrlY, endX, endY, u)
        val d = bezierDerivative(startX, startY, ctrlX, ctrlY, endX, endY, u)
        val dx = if (outbound) d[0] else -d[0]
        val dy = if (outbound) d[1] else -d[1]
        drawPlane(
            canvas, p[0], p[1], moonR * 0.55f,
            RouteMiniMapView.planeRotation(dx, dy)
        )
    }

    private fun drawSun(canvas: Canvas, w: Float, h: Float) {
        val earthR = bodyEarthRadius(w, h)
        val earthCx = w * EARTH_CX
        val earthCy = h * EARTH_CY
        val sunR = min(w * 0.26f, h * 0.22f)
        val sunCx = w * PARTNER_CX
        val sunCy = h * PARTNER_CY

        // Sonne mit Strahlenkranz.
        for (i in 0 until 12) {
            val a = i * TWO_PI / 12.0
            val inner = sunR * 1.12f
            val outer = sunR * 1.34f
            canvas.drawLine(
                sunCx + (inner * cos(a)).toFloat(),
                sunCy + (inner * sin(a)).toFloat(),
                sunCx + (outer * cos(a)).toFloat(),
                sunCy + (outer * sin(a)).toFloat(),
                sunRayPaint
            )
        }
        val shader = RadialGradient(
            sunCx - sunR * 0.25f, sunCy - sunR * 0.25f, sunR * 1.35f,
            intArrayOf(Color.rgb(255, 238, 178), Color.rgb(250, 194, 64), Color.rgb(238, 138, 28)),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        sunCorePaint.shader = shader
        canvas.drawCircle(sunCx, sunCy, sunR, sunCorePaint)

        drawEarth(canvas, earthCx, earthCy, earthR)

        // Der Weg zur Sonne: von der Erdoberfläche bis zum Sonnenrand, das
        // Flugzeug sitzt auf dem Anteil, den die Flüge abdecken.
        val dx = sunCx - earthCx
        val dy = sunCy - earthCy
        val len = hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        val startX = earthCx + ux * earthR
        val startY = earthCy + uy * earthR
        val endX = sunCx - ux * sunR
        val endY = sunCy - uy * sunR
        canvas.drawLine(startX, startY, endX, endY, trackPaint)
        val t = progress.coerceIn(0f, 1f)
        val px = startX + (endX - startX) * t
        val py = startY + (endY - startY) * t
        drawPlane(
            canvas, px, py, earthR * 0.55f,
            RouteMiniMapView.planeRotation(endX - startX, endY - startY)
        )
    }

    private fun bezier(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        t: Float
    ): FloatArray {
        val u = 1f - t
        return floatArrayOf(
            u * u * x0 + 2f * u * t * x1 + t * t * x2,
            u * u * y0 + 2f * u * t * y1 + t * t * y2
        )
    }

    private fun bezierDerivative(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        t: Float
    ): FloatArray {
        val u = 1f - t
        return floatArrayOf(
            2f * u * (x1 - x0) + 2f * t * (x2 - x1),
            2f * u * (y1 - y0) + 2f * t * (y2 - y1)
        )
    }

    private fun drawPlane(canvas: Canvas, x: Float, y: Float, size: Float, rotation: Float) {
        val drawable = plane ?: return
        val half = size / 2f
        canvas.save()
        canvas.rotate(rotation, x, y)
        planeBounds.set(
            (x - half).toInt(), (y - half).toInt(),
            (x + half).toInt(), (y + half).toInt()
        )
        drawable.bounds = planeBounds
        drawable.draw(canvas)
        canvas.restore()
    }
}
