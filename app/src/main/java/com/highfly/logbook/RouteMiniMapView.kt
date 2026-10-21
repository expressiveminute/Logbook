package com.highfly.logbook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.atan2
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
 *
 * Im Ruhezustand ([PlanePhase.IDLE], so in der Listenansicht) ist der Inhalt
 * statisch: Er wird einmal in eine Bitmap gerendert und pro Bild nur noch als
 * fertiges Bild gezeichnet. Land, Grenzen und Städte für alle Ringe der Welt
 * pro Zeile und Frame neu zu projizieren, macht das Scrollen in einer Liste
 * mit vielen Karten sonst spürbar langsam. Nur die Animation ([PlanePhase.DRAWING]
 * und [PlanePhase.CRUISING]) zeichnet weiterhin jeden Frame vollständig neu.
 */
class RouteMiniMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * Öffentlich, damit [planeRotation], [mitte] und die Projektionsrechnung
     * ([Projection], [buildArc], [buildProjectionMulti], [badgeAnchor],
     * [badgeDrawOrder]) ohne Android testbar sind.
     */
    companion object {
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

        /** Kantenlänge des Flugzeugsymbols auf der Strecke. */
        const val PLANE_SIZE_DP = 16f

        /** Dauer, in der die Strecke einmal gezeichnet wird. */
        const val ROUTE_ANIM_MS = 1800L

        /**
         * Dauer eines vollen Animationslaufs. Alle vier Fluganimationen der
         * Distanz-Seite – der Dauerflug auf der Strecke, die Erdumrundung, der
         * Weg zum Mond und der Weg zur Sonne – laufen in diesem Tempo, damit
         * keine schneller wirkt als die andere.
         */
        const val PLANE_ANIM_MS = 10000L

        /**
         * Stützpunkt-Index, bis zu dem bei [fraction] der Strecke gezeichnet
         * wird. Die Stützstellen des Großkreises liegen gleichmäßig, deshalb
         * genügt der lineare Anteil. Der Wert zeigt immer auf einen echten
         * Punkt, nie hinter das Ende der Strecke.
         */
        @JvmStatic
        fun progressIndex(count: Int, fraction: Float): Int {
            if (count < 2) return 0
            return ((count - 1) * fraction.coerceIn(0f, 1f)).toInt().coerceIn(0, count - 1)
        }

        /**
         * Drehwinkel in Grad für ein nach Norden zeigendes Flugzeugsymbol, das
         * in Fahrtrichtung [(dx), (dy)] zeigen soll. [dx] und [dy] sind
         * Bildschirmkoordinaten, [dy] also nach unten positiv.
         *
         * Das Symbol zeigt nach oben, wird also um den Winkel gedreht, um den
         * seine Nase von "oben" auf die Fahrtrichtung zeigt: Der Winkel zwischen
         * (0, -1) und (dx, dy) ist genau `atan2(dx, -dy)`.
         */
        fun planeRotation(dx: Float, dy: Float): Float {
            if (dx == 0f && dy == 0f) return 0f
            return Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        }

        /**
         * Punktindex in der Mitte einer Strecke aus [count] Punkten. Der Wert
         * zeigt immer auf einen echten Punkt, ist also [ARC_POINTS] / 2 bei
         * gleichmäßig verteilten Stützstellen.
         */
        fun mitte(count: Int): Int = count / 2

        /** Kartenausschnitt in Grad: alle Werte beziehen sich auf die Kartenmitte. */
        class Projection(
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

        /**
         * Verschiebt einen Längengrad auf die Seite der Datumslinie, die
         * näher an [reference] liegt. Dadurch entstehen keine Sprünge über
         * den Kartenrand.
         */
        @JvmStatic
        fun unwrapNear(lon: Double, reference: Double): Double {
            var value = lon
            while (value - reference > 180.0) value -= 360.0
            while (reference - value > 180.0) value += 360.0
            return value
        }

        /**
         * Großkreisbogen zwischen zwei Flughäfen als (Längengrad,
         * Breitengrad)-Paare. Die Längengrade werden an der Datumslinie
         * entrollt, damit der Bogen zusammenhängend bleibt.
         */
        @JvmStatic
        fun buildArc(
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

        /**
         * Kartenausschnitt, der alle Strecken [list] umfasst. Dieselbe
         * Rechnung wie beim Einzelbogen, nur über alle Strecken zusammen.
         */
        @JvmStatic
        fun buildProjectionMulti(list: List<FloatArray>, w: Float, h: Float): Projection? {
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            for (points in list) {
                for (i in points.indices step 2) {
                    val lon = points[i].toDouble()
                    val lat = points[i + 1].toDouble()
                    if (lon < minLon) minLon = lon
                    if (lon > maxLon) maxLon = lon
                    if (lat < minLat) minLat = lat
                    if (lat > maxLat) maxLat = lat
                }
            }
            if (minLon == Double.MAX_VALUE) return null
            return projectionFor(minLon, maxLon, minLat, maxLat, w, h)
        }

        /** Rechnet aus einem Gradbereich den Kartenausschnitt aus. */
        @JvmStatic
        fun projectionFor(
            rawMinLon: Double, rawMaxLon: Double,
            rawMinLat: Double, rawMaxLat: Double,
            w: Float, h: Float
        ): Projection? {
            var minLon = rawMinLon
            var maxLon = rawMaxLon
            var minLat = rawMinLat
            var maxLat = rawMaxLat

            // Etwas Luft um die Strecke, damit sie nicht am Kartenrand klebt.
            val lonPad = max(MIN_SPAN_DEG, (maxLon - minLon) * BBOX_PADDING)
            val latPad = max(MIN_SPAN_DEG, (maxLat - minLat) * BBOX_PADDING)
            minLon -= lonPad
            maxLon += lonPad
            minLat = max(minLat - latPad, -85.0)
            maxLat = min(maxLat + latPad, 85.0)

            val centerLon = (minLon + maxLon) / 2.0
            val centerLat = (minLat + maxLat) / 2.0
            // Breitengrade werden am Breitenkreis gestaucht, sonst wirken
            // Strecken in hohen Breiten überdehnt.
            val lonFactor = cos(Math.toRadians(centerLat)).coerceAtLeast(0.05)
            val scale = min(
                w / ((maxLon - minLon) * lonFactor),
                h / (maxLat - minLat)
            )
            if (!scale.isFinite() || scale <= 0.0) return null
            return Projection(centerLon, centerLat, lonFactor, scale, w, h)
        }

        /**
         * Bildschirmposition des Flughafenschildes in der Mehrstrecken-Ansicht:
         * die echten geografischen Koordinaten, der Längengrad an der
         * Datumslinie auf die Kartenmitte entrollt. So liegt das Schild exakt
         * auf dem Punkt, auch wenn die Strecke über die Datumslinie führt und
         * der Bogen dort entrollte Längengrade trägt.
         */
        @JvmStatic
        fun badgeAnchor(
            projection: Projection,
            location: AirportData.GeoLocation
        ): FloatArray = floatArrayOf(
            projection.x(unwrapNear(location.lon, projection.centerLon)),
            projection.y(location.lat)
        )

        /**
         * Reihenfolge, in der die Flughafenschilder gezeichnet werden: selten
         * angeflogene zuerst, die mit den meisten Strecken zuletzt. Überlappen
         * sich Schilder, liegt der Code des wichtigsten Flughafens oben.
         */
        @JvmStatic
        fun badgeDrawOrder(counts: Map<String, Int>): List<String> =
            counts.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }

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

    private val planeBounds = Rect()

    /** Flugzeugsymbol auf der Strecke, in Akzentfarbe. */
    private val plane by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_flight)?.mutate()
    }

    /** Stadt mit entrolltem Längengrad, passend zur Kartenzentrale. */
    private class PlacedCity(val xLon: Double, val city: MapCity)

    /** Strecke als aufgeschnittene Längengrade (Datumslinie entrollt). */
    private var arc: FloatArray? = null

    /** Mehrere Strecken zugleich (Hangar- und Reisebuddy-Detailseite). */
    private var arcs: List<FloatArray>? = null

    /** Dreilettercodes je Strecke der Mehrstrecken-Ansicht (Abflug, Ziel). */
    private var arcCodes: List<Pair<String, String>>? = null
    
    /** Echte geografische Koordinaten je Strecke (Abflug, Ziel) für die Badge-Positionierung. */
    private var arcLocations: List<Pair<AirportData.GeoLocation, AirportData.GeoLocation>>? = null
    private var projection: Projection? = null
    private var fromLabel: String? = null
    private var toLabel: String? = null
    private var cityLanguage: String = Settings.CITY_LANG_NATIVE

    /**
     * In welcher Phase die Streckenanimation steckt. Ohne Animation (Vorgabe)
     * zeichnet [PlanePhase.IDLE] die volle Strecke mit dem Flugzeug in der
     * Mitte; das ist das Bild in der Eintragsliste. [PlanePhase.DRAWING] baut
     * die Strecke auf, [PlanePhase.CRUISING] lässt das Flugzeug danach die
     * fertige Strecke in der tatsächlichen Flugrichtung abfliegen.
     */
    private enum class PlanePhase { IDLE, DRAWING, CRUISING }

    private var planePhase = PlanePhase.IDLE
    private var routeProgress = 1f
    private var cruiseProgress = 0f
    private var drawAnimator: ValueAnimator? = null
    private var cruiseAnimator: ValueAnimator? = null

    /** Puffer zum Entrollen der Ringe, wird wiederverwendet. */
    private var ringBuffer = FloatArray(0)

    /**
     * Gerenderter Ruhezustand der Karte, siehe Klassenkopf. Wird bei jeder
     * Inhaltsänderung ([setRoute], [setRoutes]), Größenänderung und nach dem
     * Nachladen der Kartendaten neu gezeichnet; bis dahin zeichnet [onDraw] das
     * fertige Bild, statt Land und Grenzen pro Frame neu aufzubauen.
     */
    private var bildCache: Bitmap? = null
    private var bildCacheDirty = true

    init {
        routePaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary
        )
        airportPaint.color = routePaint.color
        airportHaloPaint.color = Color.WHITE
        labelPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnPrimary
        )
        labelBgPaint.color = routePaint.color
        plane?.setTint(routePaint.color)
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
        arcs = null
        arcCodes = null
        fromLabel = fromCode
        toLabel = toCode
        cityLanguage = Settings.getCityLabelLanguage(context)
        projection = null
        markBildCacheDirty()
        stopAnimators()
        planePhase = PlanePhase.IDLE
        routeProgress = 1f
        cruiseProgress = 0f
        requestDetails()
        invalidate()
    }

    /**
     * Setzt mehrere Strecken zugleich (z. B. alle Flüge eines Flugzeugs oder
     * einer Person). Sie teilen sich einen Kartenausschnitt; jede Strecke trägt
     * an ihren Endpunkten den Dreilettercode des Flughafens. Städtenamen bleiben
     * weg, damit die Karte bei vielen Strecken nicht unleserlich wird. Ein
     * Aufruf von [setRoute] schaltet wieder auf die Einzelstrecke um.
     */
    fun setRoutes(routes: List<Triple<AirportData.GeoLocation, AirportData.GeoLocation, Pair<String, String>>>) {
        if (routes.isEmpty()) {
            setRoute(AirportData.GeoLocation(0.0, 0.0), AirportData.GeoLocation(0.0, 0.0), "", "")
            return
        }
        arcs = routes.map { buildArc(it.first, it.second) }
        arcCodes = routes.map { it.third }
        arcLocations = routes.map { Pair(it.first, it.second) }
        arc = null
        fromLabel = null
        toLabel = null
        cityLanguage = Settings.getCityLabelLanguage(context)
        projection = null
        markBildCacheDirty()
        stopAnimators()
        planePhase = PlanePhase.IDLE
        routeProgress = 1f
        cruiseProgress = 0f
        requestDetails()
        invalidate()
    }

    /**
     * Zeichnet die Strecke einmal von Anfang bis Ende und lässt das
     * Flugzeugsymbol danach die fertige Strecke in der tatsächlichen
     * Flugrichtung abfliegen. [onEnd] läuft nach dem Aufbau, damit die Seite
     * darauf ihre nächste Grafik einblenden kann.
     */
    fun playRouteAnimation(onEnd: (() -> Unit)? = null) {
        stopAnimators()
        planePhase = PlanePhase.DRAWING
        routeProgress = 0f
        invalidate()
        var cancelled = false
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ROUTE_ANIM_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                routeProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (drawAnimator === animation) drawAnimator = null
                    if (cancelled) return
                    onEnd?.invoke()
                    // Danach fliegt das Flugzeug weiter, wie beim Mond.
                    startCruise()
                }
            })
            start()
        }
        drawAnimator = animator
    }

    /**
     * Lässt das Flugzeug die fertige Strecke wiederholt in der tatsächlichen
     * Flugrichtung abfliegen: von der Herkunft zum Ziel, nie in die
     * Gegenrichtung.
     */
    private fun startCruise() {
        planePhase = PlanePhase.CRUISING
        // In der Mehrstrecken-Ansicht bleibt die volle Karte stehen; ein
        // Dauerflug über alle Strecken gäbe es nicht.
        if (arcs != null) {
            invalidate()
            return
        }
        cruiseProgress = 0f
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PLANE_ANIM_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                cruiseProgress = it.animatedValue as Float
                invalidate()
            }
        }
        animator.start()
        cruiseAnimator = animator
    }

    /** Beendet die laufende Streckenanimation ohne [onEnd] auszulösen. */
    fun cancelRouteAnimation() {
        stopAnimators()
        planePhase = PlanePhase.IDLE
        routeProgress = 1f
        cruiseProgress = 0f
        invalidate()
    }

    private fun stopAnimators() {
        drawAnimator?.cancel()
        drawAnimator = null
        cruiseAnimator?.cancel()
        cruiseAnimator = null
    }

    /**
     * Im Hintergrund pausieren, im Vordergrund weiterfliegen: Der Dauerflug
     * läuft endlos und soll nicht unsichtbar weiter zeichnen.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (planePhase != PlanePhase.CRUISING) return
        if (visibility == VISIBLE) {
            if (cruiseAnimator == null) startCruise()
        } else {
            cruiseAnimator?.cancel()
            cruiseAnimator = null
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        projection = null
        markBildCacheDirty()
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Die Bitmap gibt der Garbage Collector frei; sie darf hier nicht
        // recycelt werden, solange ein aufgezeichneter Frame sie noch referenziert.
        bildCache = null
        bildCacheDirty = true
    }

    /**
     * Markiert den Bitmap-Cache als veraltet. Auf dem Hauptthread aufrufen;
     * der nächste [onDraw] zeichnet den Inhalt dann einmal neu in die Bitmap.
     */
    private fun markBildCacheDirty() {
        bildCacheDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (planePhase == PlanePhase.IDLE) {
            // Ruhezustand: fertiges Bild zeichnen, siehe Klassenkopf.
            canvas.drawBitmap(statischeBitmap(w.toInt(), h.toInt()), 0f, 0f, null)
            return
        }
        zeichneInhalt(canvas, w, h)
    }

    /**
     * Die Bitmap mit dem gerenderten Ruhezustand, bei Bedarf neu aufgebaut. Die
     * Bitmap wird wiederverwendet, solange sie die passende Größe hat - beim
     * Recycling der Zeilen einer Liste entsteht so kein neuer Speicher.
     */
    private fun statischeBitmap(w: Int, h: Int): Bitmap {
        var bitmap = bildCache
        if (bildCacheDirty || bitmap == null || bitmap.isRecycled ||
            bitmap.width != w || bitmap.height != h
        ) {
            if (bitmap == null || bitmap.isRecycled || bitmap.width != w || bitmap.height != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bildCache = bitmap
            }
            bitmap.eraseColor(Color.TRANSPARENT)
            zeichneInhalt(Canvas(bitmap), w.toFloat(), h.toFloat())
            bildCacheDirty = false
        }
        return bitmap
    }

    /** Zeichnet den vollständigen Inhalt: Ozean, Land, Grenzen, Städte, Strecke. */
    private fun zeichneInhalt(canvas: Canvas, w: Float, h: Float) {
        if (bounds.isEmpty) bounds.set(0f, 0f, w, h)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, oceanPaint)

        val multi = arcs
        if (multi != null && multi.isNotEmpty()) {
            drawMultiRoutes(canvas, multi, w, h)
            return
        }

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
        when (planePhase) {
            PlanePhase.IDLE -> {
                drawRoute(canvas, points, projection, 1f)
                drawPlane(canvas, points, projection, null)
            }
            PlanePhase.DRAWING -> {
                drawRoute(canvas, points, projection, routeProgress)
                drawPlane(canvas, points, projection, routeProgress)
            }
            PlanePhase.CRUISING -> {
                // Volle Strecke; das Flugzeug fliegt sie wiederholt in der
                // tatsächlichen Flugrichtung ab (SCL nach FRA, nie zurück).
                drawRoute(canvas, points, projection, 1f)
                drawPlane(canvas, points, projection, cruiseProgress)
            }
        }
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
                // Die Bitmap enthält noch keine Landdaten, sie muss neu gezeichnet werden.
                view.post {
                    view.markBildCacheDirty()
                    view.invalidate()
                }
            }
        }.start()
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
        return projectionFor(minLon, maxLon, minLat, maxLat, w, h)
    }

    /**
     * Zeichnet alle Strecken der Mehrstrecken-Ansicht. Sie werden gemeinsam
     * aufgebaut ([routeProgress]) und danach vollständig gezeigt; die Endpunkte
     * tragen kleine Punkte und, wo Platz ist, ein Schild mit dem
     * Dreilettercode, damit sich die vielen Strecken auseinanderhalten lassen.
     */
    private fun drawMultiRoutes(canvas: Canvas, list: List<FloatArray>, w: Float, h: Float) {
        val projection = this.projection
            ?: buildProjectionMulti(list, w, h)?.also { this.projection = it }
            ?: return

        val rings = landRings
        if (rings == null) requestDetails()

        canvas.save()
        canvas.clipPath(clipPath)
        if (rings != null) drawLand(canvas, rings, projection)
        drawBorders(canvas, projection)

        val fraction = if (planePhase == PlanePhase.DRAWING) routeProgress else 1f
        for (points in list) {
            drawRoute(canvas, points, projection, fraction)
        }
        // Endpunkte erst nach dem Aufbau setzen, sonst stünden sie vor der
        // noch unfertigen Strecke.
        if (fraction >= 1f) {
            for (points in list) {
                val last = points.size - 2
                drawAirportMarker(
                    canvas,
                    projection.x(points[0].toDouble()),
                    projection.y(points[1].toDouble())
                )
                drawAirportMarker(
                    canvas,
                    projection.x(points[last].toDouble()),
                    projection.y(points[last + 1].toDouble())
                )
            }
            drawMultiBadges(canvas, list, projection)
        }
        canvas.restore()
    }

    /**
     * Beschriftet die Endpunkte der Mehrstrecken-Ansicht mit ihren
     * Dreilettercodes: Jeder Flughafen bekommt seinen Code nur einmal, auch
     * wenn er mehrfach angeflogen wird. Jedes Schild sitzt exakt auf seinem
     * Flughafen (siehe [badgeAnchor]) und wird immer gezeichnet - damit die
     * geografische Position stimmt, wird es nie verschoben. Überlappen sich
     * Schilder, zeichnet [badgeDrawOrder] den Code des Flughafens mit den
     * meisten Strecken zuletzt, er liegt oben.
     */
    private fun drawMultiBadges(
        canvas: Canvas,
        list: List<FloatArray>,
        projection: Projection
    ) {
        val codes = arcCodes ?: return
        val locations = arcLocations ?: return
        val counts = HashMap<String, Int>()
        val positions = LinkedHashMap<String, FloatArray>()

        for (index in list.indices) {
            val codePair = codes.getOrNull(index) ?: continue
            val locationPair = locations.getOrNull(index) ?: continue

            // Abflugsflughafen
            if (codePair.first.isNotEmpty()) {
                val anchor = badgeAnchor(projection, locationPair.first)
                collectAirport(positions, counts, codePair.first, anchor[0], anchor[1])
            }

            // Ankunftsflughafen
            if (codePair.second.isNotEmpty()) {
                val anchor = badgeAnchor(projection, locationPair.second)
                collectAirport(positions, counts, codePair.second, anchor[0], anchor[1])
            }
        }

        if (positions.isEmpty()) return

        for (code in badgeDrawOrder(counts)) {
            val point = positions[code] ?: continue
            drawBadge(canvas, badgeRectAt(point[0], point[1], code), code)
        }
    }

    /**
     * Merkt sich einen Flughafen samt Position und zählt seine Strecken. Durch
     * das Entrollen an der Kartenmitte ist die Position für denselben
     * Flughafen immer gleich, egal in welcher Strecke er vorkommt.
     */
    private fun collectAirport(
        positions: MutableMap<String, FloatArray>,
        counts: MutableMap<String, Int>,
        code: String,
        x: Float,
        y: Float
    ) {
        if (code.isEmpty()) return
        if (positions[code] == null) {
            positions[code] = floatArrayOf(x, y)
        }
        counts[code] = (counts[code] ?: 0) + 1
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

    private fun drawRoute(
        canvas: Canvas,
        points: FloatArray,
        projection: Projection,
        fraction: Float
    ) {
        val count = points.size / 2
        routePath.reset()
        if (count < 2) return
        val pos = (count - 1) * fraction.coerceIn(0f, 1f)
        val last = progressIndex(count, fraction)
        routePath.moveTo(
            projection.x(points[0].toDouble()),
            projection.y(points[1].toDouble())
        )
        for (i in 1..last) {
            routePath.lineTo(
                projection.x(points[i * 2].toDouble()),
                projection.y(points[i * 2 + 1].toDouble())
            )
        }
        // Den letzten Abschnitt anteilig zeichnen, damit die Spitze mit dem
        // Flugzeugsymbol wandert statt in Stützstellen zu springen.
        if (last < count - 1) {
            val t = (pos - last).coerceIn(0f, 1f)
            val ax = projection.x(points[last * 2].toDouble())
            val ay = projection.y(points[last * 2 + 1].toDouble())
            val bx = projection.x(points[(last + 1) * 2].toDouble())
            val by = projection.y(points[(last + 1) * 2 + 1].toDouble())
            routePath.lineTo(ax + (bx - ax) * t, ay + (by - ay) * t)
        }
        // Nur der Akzentstrich. Ein heller Halo darunter legte sich als weisse
        // Abstufung neben die Strecke, die wie eine zweite Linie aussah.
        canvas.drawPath(routePath, routePaint)
    }

    /**
     * Setzt das Flugzeugsymbol auf die Strecke, in Flugrichtung gedreht: Von
     * MUC nach BOS zeigt es nach rechts, also vom Abflug zum Ziel.
     *
     * [fraction] ist die Stelle entlang der Strecke: 0 am Abflug, 1 am Ziel;
     * `null` setzt das Symbol in die Mitte, wie in der Eintragsliste. Die
     * Richtung kommt aus der Tangente zwischen dem Stützpunkt davor und danach,
     * damit das Symbol im Verlauf sitzt.
     */
    private fun drawPlane(
        canvas: Canvas,
        points: FloatArray,
        projection: Projection,
        fraction: Float?
    ) {
        val drawable = plane ?: return
        val count = points.size / 2
        if (count < 3) return
        val pos = if (fraction == null) {
            count / 2f
        } else {
            (count - 1) * fraction.coerceIn(0f, 1f)
        }
        val index = pos.toInt().coerceIn(0, count - 1)
        val t = pos - index
        val next = (index + 1).coerceAtMost(count - 1)
        val prev = (index - 1).coerceAtLeast(0)
        val x = lerp(
            projection.x(points[index * 2].toDouble()),
            projection.x(points[next * 2].toDouble()),
            t
        )
        val y = lerp(
            projection.y(points[index * 2 + 1].toDouble()),
            projection.y(points[next * 2 + 1].toDouble()),
            t
        )
        val dx = projection.x(points[next * 2].toDouble()) -
            projection.x(points[prev * 2].toDouble())
        val dy = projection.y(points[next * 2 + 1].toDouble()) -
            projection.y(points[prev * 2 + 1].toDouble())
        if (dx == 0f && dy == 0f) return

        val size = PLANE_SIZE_DP * density
        val rotation = planeRotation(dx, dy)
        drawSymbol(canvas, drawable, x, y, size, rotation)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Zeichnet [drawable] quadratisch um ([x], [y]) herum, um [rotation] gedreht. */
    private fun drawSymbol(
        canvas: Canvas,
        drawable: Drawable,
        x: Float,
        y: Float,
        size: Float,
        rotation: Float
    ) {
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

    /** Badge exakt um den Punkt zentriert, ohne es in die Ansicht zu klemmen. */
    private fun badgeRectAt(x: Float, y: Float, code: String): RectF {
        val paddingH = 4f * density
        val paddingV = 2f * density
        val boxWidth = labelPaint.measureText(code) + 2 * paddingH
        val boxHeight = -labelPaint.ascent() + labelPaint.descent() + 2 * paddingV
        return RectF(
            x - boxWidth / 2, y - boxHeight / 2,
            x + boxWidth / 2, y + boxHeight / 2
        )
    }

    /** Badge zentriert über dem Flughafenpunkt, innerhalb des Ausschnitts. */
    private fun labelBounds(x: Float, y: Float, code: String): RectF {
        val rect = badgeRectAt(x, y, code)
        val margin = LABEL_MARGIN * density
        if (rect.left < margin) rect.offsetTo(margin, rect.top)
        if (rect.right > width - margin) rect.offsetTo(width - margin - rect.width(), rect.top)
        if (rect.top < margin) rect.offsetTo(rect.left, margin)
        if (rect.bottom > height - margin) rect.offsetTo(rect.left, height - margin - rect.height())
        return rect
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
