package com.highfly.logbook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Weltkugel für die Rubbelkarte: besuchte Länder werden farblich hervorgehoben,
 * alle übrigen bleiben gedeckt.
 *
 * Die Kugel ist auf die besuchten Länder zentriert und lässt sich mit dem Finger
 * in jede Richtung drehen, per Zwei-Finger-Zoom und Doppeltipp zoomen. Sie steht
 * mittig im Bild zwischen dem Fortschrittsbalken oben und den Kontinent-Kacheln
 * darunter und füllt ein fast quadratisches Feld aus. Im Ausgangszustand bleibt
 * links und rechts ein kleiner Abstand zum Seitenrand, der beim Zoomen verschwindet
 * und die Kugel so die ganze Bildschirmbreite nutzen lässt. Das Wasser endet am
 * Rand der Kugel, darüber und darunter bleibt der Hintergrund der Seite frei.
 *
 * Die Umrisse werden einmalig im Hintergrund geladen, die gezeichneten Pfade
 * bei jeder Größenänderung und Drehung neu gebaut. [onDraw] zeichnet danach nur
 * noch fertige Pfade und bleibt auch mit über 230 Ländern flüssig.
 */
class RubbelkarteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TAG = "RubbelkarteView"

        /** Längengradspanne, in der normalizeLon rechnet. */
        private const val LON_MIN = -180.0
        private const val LON_MAX = 180.0

        /**
         * Freier Rand um die Kugel, als Anteil ihres Radius, und zwar nur im
         * Ausgangszustand: Die Kugel reicht dann nicht bis an den Seitenrand, wo
         * sie sonst mit einem hellen Saum von einem Pixel Breite kleben würde.
         *
         * Der Rand steckt nur im Radius, deshalb schrumpft er beim Zoomen von
         * selbst und ist bei [ZOOM_RANDLOS] verschwunden - dann nutzt die Kugel
         * die volle Bildschirmbreite. Vorher gab es hierfür das auf [ZOOM_MIN]
         * abgestimmte [GLOBE_FILL] mit einem Hauch über 1, das genau den
         * Saum vermeiden sollte und deshalb dauerhaft am Rand kleben blieb.
         */
        const val GLOBE_MARGIN = 0.04f

        /**
         * Zoomstufe, ab der die Kugel ohne seitlichen Abstand bis an den
         * Bildschirmrand reicht. Muss innerhalb des Zoombereichs liegen, sonst
         * wäre der Abstand gar nicht wegzubekommen.
         */
        val ZOOM_RANDLOS: Float get() = 1f / (1f - GLOBE_MARGIN)

        private const val ZERO_EPS = 1e-9

        /** Deckkraft des Halos hinter dem eingeblendeten Ländernamen. */
        private const val NAME_HALO_ALPHA = 160

        /**
         * Deckkraft des Ländernamens nach einem Tipp. Er ist eine Angabe zum
         * Bild, kein Beschriftungsschild: Er soll den Blick nicht vom Land
         * weglenken, deshalb bleibt er unter der vollen Deckkraft.
         */
        private const val NAME_ALPHA = 185

        /**
         * Wie stark die bereits erledigten Länder zurücktreten, als Anteil
         * Schwarz in ihrer Fläche.
         *
         * Ihre Farbe ist ein neutrales Grau, kein Farbton: Die Akzentfarbe zog
         * den Blick auf sich, gleichgültig wie dunkel sie gemischt war, und ein
         * kräftiger Farbton auf einer Karte wirkt immer wie eine Markierung.
         *
         * Das Grau ist nicht fest gewählt, sondern aus der Farbe des offenen
         * Landes abgeleitet und dann um [VISITED_DIM] abgedunkelt. So ist es in
         * beiden Designs dunkler als das offene Land - das ist im hellen Design
         * fast weiss, im dunklen ein dunkles Schiefergrau - und der Abstand
         * bleibt in beiden gleich.
         *
         * Über die Deckkraft der Fläche ging es nicht: Je geringer sie war, desto
         * mehr schimmert das helle Wasser durch, und im hellen Design ist das
         * Wasser fast so hell wie ein offenes Land.
         *
         * [VISITED_RAND_ALPHA] gehört dazu: Auf der dunklen Fläche stünde der
         * ungedimmte Umriss als heller Saum darum und liefe dem Dämpfen zuwider.
         */
        private const val VISITED_DIM = 0.8f
        private const val VISITED_RAND_ALPHA = 40

        /** Wie lange der Name eines angetippten Landes stehen bleibt. */
        private const val NAME_ANZEIGE_MS = 2600L

        /** Abstand des Namens vom Rand der Karte. */
        private const val NAME_RAND_DP = 8f

        /**
         * Zoombereich der Kugel. [ZOOM_MAX] ist bewusst klein gewählt: bei
         * 1,35 ragt der Kugelrand bereits über die Seitenränder, mehr Verdeckung
         * bringt der Karte nichts, zeigt aber nur noch Wasser.
         */
        const val ZOOM_MIN = 1f
        const val ZOOM_MAX = 1.35f

        /** Ziel des Doppeltipps, erneutes Tippen zoomt wieder heraus. */
        const val ZOOM_DOPPELTIPP = 1.25f

        /**
         * Hält den Zoombereich ein, auch wenn das Wischen danebenliegt.
         *
         * Nicht-endliche Werte landen auf [ZOOM_MIN]: `coerceIn` liefert bei
         * NaN oder ±Inf den Eingangswert zurück, und ein NaN-Zoom macht aus
         * jeder weiteren Skalierung der Kugel undefinierte Koordinaten.
         */
        fun clampZoom(wert: Float): Float =
            if (wert.isFinite()) wert.coerceIn(ZOOM_MIN, ZOOM_MAX) else ZOOM_MIN

        /**
         * Index eines Zeigers im Ereignis _nach_ einem `ACTION_POINTER_UP`.
         *
         * Android nummeriert die Zeiger pro Ereignis neu: was hinter dem
         * abgehobenen Zeiger lag, rückt eine Position vor. Wer den Index aus
         * dem `POINTER_UP` unverändert weiterverwendet, liest im nächsten
         * Ereignis den falschen - bei zwei Fingern gar einen ungültigen -
         * Zeiger.
         */
        fun folgeIndex(zeiger: Int, abgehoben: Int): Int =
            zeiger - if (zeiger > abgehoben) 1 else 0

        /**
         * Weicht die Kugellage so weit von dem gezeichneten Bild ab, dass es
         * neu gebaut werden muss? [lonAbstand] und [latAbstand] sind Winkel in
         * Bogen, [pixelAbstand] die grösste Verschiebung von Mitte oder Radius
         * in Pixel.
         *
         * Getrennt vom Messen, damit die Regel pruefbar ist: vor allem der
         * Sprung ueber 360 Grad darf keinen Aufbau ausloesen, obwohl die Zahlen
         * weit auseinanderliegen - es ist derselbe Blickpunkt, nur von der
         * anderen Seite her gerechnet.
         */
        @JvmStatic
        fun weichtAb(lonAbstand: Double, latAbstand: Double, pixelAbstand: Float): Boolean {
            // NaN vergleicht mit jedem Wert false und wuerde durchrutschen.
            if (!lonAbstand.isFinite() || !latAbstand.isFinite()) return true
            if (!pixelAbstand.isFinite()) return true
            val lon = abs(lonAbstand).coerceAtMost(TWO_PI)
            val lat = abs(latAbstand)
            // 1e-4 Bogen sind auf einer Kugel von 600px Radius gut ein
            // Hundertstel Pixel: darunter faellt der Unterschied nicht auf.
            val gedreht = min(lon, TWO_PI - lon) + lat > 1e-4
            return gedreht || pixelAbstand > 0.5f
        }

        private const val ARC_STEP = 0.06

        private const val ANTARCTICA = "AQ"
        private const val DEFAULT_CENTER_LON = 10.0
        private const val DEFAULT_CENTER_LAT = 25.0
        private const val TWO_PI = 2.0 * PI
        private const val HALF_PI = PI / 2.0

        /**
         * Die Umrisse sind app-weit dieselben, deshalb werden sie einmal geladen
         * und von allen Instanzen geteilt. Views, die noch warten, werden schwach
         * gehalten und nach dem Laden zum Neuzeichnen angestoßen.
         */
        @Volatile
        private var sharedCountries: List<CountryShapes.Country>? = null

        @Volatile
        private var sharedLoading = false

        private val sharedLock = Any()

        private val waiting = mutableListOf<WeakReference<RubbelkarteView>>()

        private fun requestCountries(context: Context, view: RubbelkarteView) {
            val ready = sharedCountries
            if (ready != null) {
                view.onCountriesLoaded(ready)
                return
            }
            val appContext = context.applicationContext
            synchronized(sharedLock) {
                if (waiting.none { it.get() === view }) {
                    waiting.add(WeakReference(view))
                }
                if (sharedLoading) return
                sharedLoading = true
            }
            Thread {
                val parsed = try {
                    CountryShapes.load(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Länderumrisse konnten nicht geladen werden", e)
                    null
                }
                val views = synchronized(sharedLock) {
                    sharedLoading = false
                    sharedCountries = parsed
                    waiting.toList().also { waiting.clear() }
                }
                views.forEach { reference ->
                    val target = reference.get() ?: return@forEach
                    target.post { target.onCountriesLoaded(parsed ?: emptyList()) }
                }
            }.start()
        }
    }

    /** Ein Land mit dem bereits gezeichneten Pfad. */
    private class Shape(
        val iso2: String,
        val name: String,
        val path: Path,
        val bounds: RectF,
        val visited: Boolean
    )

    /** Besuchter Flughafen als Punkt auf der Karte. */
    private data class AirportPoint(val lat: Double, val lon: Double, val code: String)

    /**
     * Lage und Größe der Kugel, aus der **ein** Bild besteht: Blickpunkt,
     * Mittelpunkt und Radius.
     *
     * Wichtig ist, dass Land und Flughafenpunkte aus demselben Zustand
     * gezeichnet werden. Rechnet jedes für sich mit dem, was gerade neu ist,
     * laufen die Punkte während einer Drehung sichtbar neben dem Land her -
     * der Aufbau braucht einen eigenen Thread, bis die neuen Pfade fertig sind.
     */
    private class GlobeState(
        val globe: GeoMath.Globe,
        val cx: Float,
        val cy: Float,
        val radius: Float
    )

    private val density get() = resources.displayMetrics.density

    private val isDarkTheme = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDarkTheme) Color.rgb(18, 28, 40) else Color.rgb(172, 202, 228)
    }
    private val unvisitedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDarkTheme) Color.rgb(48, 60, 72) else Color.rgb(228, 231, 224)
    }
    private val visitedPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Umriss der erledigten Länder, gegenüber dem der offenen abgeschwächt. */
    private val visitedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 0.7f * density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 0.7f * density
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val airportHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val airportPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics
        )
    }
    private val nameHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.4f * density
        textSize = namePaint.textSize
    }

    private val clipPath = Path()

    /** Nur zum Aufspüren des Landes unter dem Finger, siehe [landAt]. */
    private val hitClip = Region()
    private val hitRegion = Region()
    private val runPath = Path()

    /** Zielpuffer für [project], gehört dem Zeichen-Thread. */
    private val projected = FloatArray(2)

    /** Zielpuffer für den Aufbau-Thread, siehe [projectGlobe]. */
    private val buildProjection = FloatArray(2)

    /**
     * Lage zweier Ringpunkte, je ein Puffer für den Aufbau-Thread und für den
     * Zeichen-Thread. [projectGlobe] schreibt in [buildPoint] beziehungsweise
     * [drawPoint]; beide Threads dürfen sich keinen Puffer teilen, sonst liest
     * einer die halbe Rechnung des anderen.
     */
    private val buildPoint = DoubleArray(3)
    private val drawPoint = DoubleArray(3)
    private val pointA = DoubleArray(3)
    private val pointB = DoubleArray(3)

    @Volatile
    private var shapes: List<Shape> = emptyList()

    /**
     * Land, dessen Name gerade eingeblendet wird, und wo der erscheint. Ohne
     * Tippen steht auf der Karte keine Schrift.
     */
    @Volatile
    private var tapped: Shape? = null
    private var tapX = 0f
    private var tapY = 0f

    private val nameAusblenden = Runnable {
        tapped = null
        invalidate()
    }

    @Volatile
    private var points: List<AirportPoint> = emptyList()

    private var visitedIso2: Set<String> = emptySet()
    private var visitedAirports: List<String> = emptyList()
    private var built = false
    private var buildGeneration = 0
    private var loadGeneration = 0

    /**
     * Drehung der Kugel durch Wischen in Radiant, addiert sich auf die
     * automatisch gesetzte Mitte. Die Projektion dreht um die Achse durch den
     * Blickpunkt, deshalb sind beide Werte unabhängig voneinander.
     */
    private var userLonRad = 0.0
    private var userLatRad = 0.0

    /**
     * Versatz der Kugelmitte nach unten, damit sie in der Bildschirmmitte steht
     * statt in der Mitte der View: die View beginnt ja unter der Überschrift.
     */
    @Volatile
    private var centerShiftY = 0f

    /**
     * Zustand, aus dem das gerade gezeichnete Bild stammt, siehe [GlobeState].
     * Null, solange die erste Kugel noch im Aufbau ist; dann zeichnet [onDraw]
     * ersatzweise [liveState], weil es ohnehin noch kein Land zu zeigen gibt.
     */
    @Volatile
    private var drawnState: GlobeState? = null

    /** Laufender Aufbau, damit ein Wischen nicht je Bewegung einen Thread erzeugt. */
    @Volatile
    private var building = false

    private val windowOffset = IntArray(2)
    private var touchIndex = 0
    private var touchX = 0f
    private var touchY = 0f
    private var touchSlop = 0f
    private var draggedPx = 0f

    /** Nach einem Zoomen ist der Rest der Geste kein Klick mehr. */
    private var scaled = false

    /**
     * Zoomstufe der Kugel. Sie steckt nur im Radius, deshalb ist ein eigener
     * Blickwinkel nötig: [GeoMath.Globe] kennt keine Vergrößerung, die
     * Projektion bleibt orthografisch, nur der Ausschnitt wird größer.
     */
    @Volatile
    private var zoom = ZOOM_MIN

    private val zoomDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaled = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor)
                return true
            }
        }
    )

    private val tipDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom()
                return true
            }
        }
    )

    init {
        // Die erledigten Länder bekommen kein Grau aus dem Theme, sondern das
        // offene Land selbst, abgedunkelt: Das offene Land ist im hellen Design
        // fast weiss und im dunklen ein dunkles Schiefergrau, ein festes Grau
        // wäre in einem der beiden Designs entweder zu hell oder unsichtbar.
        visitedPaint.color = ColorUtils.blendARGB(
            unvisitedPaint.color, Color.BLACK, VISITED_DIM
        )
        val outline = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOutlineVariant
        )
        borderPaint.color = outline
        visitedBorderPaint.color = outline
        visitedBorderPaint.alpha = VISITED_RAND_ALPHA
        rimPaint.color = outline
        val nameColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnPrimary
        )
        namePaint.color = nameColor
        namePaint.alpha = NAME_ALPHA
        // Der Halo muss die Gegenfarbe zur Schrift sein, sonst tut er nichts und
        // verbreitert die Buchstaben nur. Er trennt den Namen auch dort vom
        // Hintergrund, wo er über Wasser oder über den Kartenrand ragt. Über
        // die Schrift entscheidet die Helligkeit, nicht die Tag-/Nachteinstellung:
        // Braun, Türkis und Magenta haben helle Zielfarben mit dunkler Schrift,
        // das Standardthema ist es umgekehrt.
        nameHaloPaint.color = ColorUtils.setAlphaComponent(
            if (ColorUtils.calculateLuminance(nameColor) > 0.5f) {
                Color.rgb(12, 16, 24)
            } else {
                Color.rgb(244, 247, 252)
            },
            NAME_HALO_ALPHA
        )
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }

    /**
     * Setzt den Besuch. [visited] enthält die besuchten Länder als ISO-2,
     * [airports] die besuchten Flughäfen als IATA.
     */
    fun setContent(visited: Set<String>, airports: List<String>) {
        visitedIso2 = visited
        visitedAirports = airports
        requestCountries(context, this)
        invalidate()
    }

    private fun onCountriesLoaded(list: List<CountryShapes.Country>) {
        if (list.isEmpty()) {
            invalidate()
            return
        }
        val generation = ++loadGeneration
        Thread {
            val collected = collectAirportPoints(generation)
            post {
                if (generation != loadGeneration) return@post
                points = collected
                // Die Pfade hängen an Größe, Drehung und Besuch, also müssen sie
                // nach jedem neuen Datensatz neu gebaut werden.
                built = false
                rebuildIfNeeded()
                invalidate()
            }
        }.start()
    }

    /**
     * Liest die Koordinaten der besuchten Flughäfen. Die Flughafendatei wird von
     * [AirportData] selbst im Hintergrund geladen, deshalb läuft auch das hier
     * außerhalb des UI-Threads.
     */
    private fun collectAirportPoints(generation: Int): List<AirportPoint> {
        val appContext = context.applicationContext
        val result = ArrayList<AirportPoint>(visitedAirports.size)
        for (iata in visitedAirports) {
            if (generation != loadGeneration) return result
            val location = AirportData.location(appContext, iata) ?: continue
            result.add(AirportPoint(location.lat, location.lon, iata))
        }
        return result
    }

    /**
     * Die Kugel bestimmt ihre Höhe selbst: sie braucht ein quadratisches Feld,
     * damit sie links und rechts bis an den Seitenrand reicht.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val width = if (widthMode == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val maxHeight = if (heightMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        val height = if (heightMode == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            width.coerceAtMost(maxHeight)
        }
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        built = false
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        // Nur das Wasser ist blau, der Kasten um die Kugel verschwindet.
        updateCenterShift()
        if (!built) {
            if (sharedCountries == null) {
                requestCountries(context, this)
                return
            }
            rebuildIfNeeded()
        }
        val list = shapes
        // Solange es kein Land gibt, gibt es auch keinen gezeichneten Zustand:
        // Dann zählt die aktuelle Lage, das Bild ist dann nur das blaue Wasser.
        val state = drawnState ?: liveState() ?: return
        clipPath.reset()
        clipPath.addCircle(state.cx, state.cy, state.radius, Path.Direction.CW)
        canvas.drawCircle(state.cx, state.cy, state.radius, oceanPaint)
        if (list.isEmpty()) return

        canvas.save()
        canvas.clipPath(clipPath)
        for (shape in list) {
            canvas.drawPath(shape.path, if (shape.visited) visitedPaint else unvisitedPaint)
        }
        for (shape in list) {
            // Der Umriss der erledigten Länder ist mitgedimmt - auf der
            // dunklen Fläche stünde er sonst als heller Saum darum.
            canvas.drawPath(
                shape.path, if (shape.visited) visitedBorderPaint else borderPaint
            )
        }
        drawAirports(canvas)
        drawName(canvas)
        canvas.restore()
        // Der Rand gehört zum Bild, nicht zum Wasser, deshalb ohne Beschneiden.
        canvas.drawCircle(state.cx, state.cy, state.radius, rimPaint)
    }

    /**
     * Lage der Kugel, wie sie der Nutzer gerade will. Nur auf dem Hauptthread
     * aufrufen: gelesen werden [width], [height], [centerShiftY], [zoom] und
     * die beiden Wischwinkel, und der Aufbau-Thread darf davon nichts
     * mitlesen - sonst baut er Pfade zu einem Zustand, den es nie gab.
     */
    private fun liveState(): GlobeState? {
        if (width <= 0 || height <= 0) return null
        val cx = width / 2f
        val cy = height / 2f + centerShiftY
        val radius = globeRadius(width.toFloat(), height.toFloat())
        val list = sharedCountries
        // Ohne Länder gibt es keine Mitte der besuchten Länder, dann zählt die
        // Vorgabe - so fehlt der Aufbau nicht an einer leeren Kugel.
        val center = list?.let { RubbelkarteStats.centerOf(it, visitedIso2) }
        val centerLon = normalizeLon(center?.first ?: DEFAULT_CENTER_LON)
        val centerLat = (center?.second ?: DEFAULT_CENTER_LAT).coerceIn(-60.0, 70.0)
        val lonRad = Math.toRadians(centerLon) + userLonRad
        val latRad = (Math.toRadians(centerLat) + userLatRad).coerceIn(-HALF_PI, HALF_PI)
        return GlobeState(GeoMath.Globe(lonRad, latRad), cx, cy, radius)
    }

    /**
     * Rechnet den Versatz aus, um den die Kugel tiefer sitzen muss, damit sie in
     * der Bildschirmmitte steht. Die halbe Höhe des Wurzelverlaufs ist die Mitte
     * des Bildschirms, von der Position der View aus gesehen liegt sie um
     * [centerShiftY] tiefer als die Viewmitte.
     *
     * Die Mitte der View ist die Mitte des Raums zwischen Fortschrittsbalken und
     * Kachelzeile, die Bildschirmmitte liegt ein Stück darunter, weil unten noch
     * die Kacheln und die Navigationsleiste Platz brauchen. Beides liegt dicht
     * beieinander - wichtig ist nur, dass der Versatz die Kugel nicht aus ihrem
     * Feld schiebt.
     */
    private fun updateCenterShift() {
        val root = rootView
        if (root.height <= 0) return
        getLocationInWindow(windowOffset)
        val shift = root.height / 2f - windowOffset[1] - height / 2f
        if (abs(shift - centerShiftY) > 0.5f) {
            centerShiftY = shift
            built = false
        }
    }

    /**
     * Dreht die Kugel mit dem Finger, in beide Richtungen: waagerecht um die
     * Hochachse, senkrecht um die Querachse.
     *
     * Der Weg wird 1:1 umgesetzt, bezogen auf die Kugelmitte: ein Fingerweg von
     * einem Kugelradius entspricht einem Radiant. Am Äquator ist das genau
     * richtig, in der Nähe der Pole wird die Bewegung durch die Kugelfläche
     * gestaucht, so wie bei einem echten Globus auch. Weil der Radius den Zoom
     * enthält, dreht eine herangezoomte Kugel unter dem Finger langsamer, das
     * passt zu dem, was man sieht.
     *
     * Zoomen und Doppeltipp übergeben die Erkanner, sonst würde die zweite
     * Hand das Drehen fortsetzen.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return super.onTouchEvent(event)
        zoomDetector.onTouchEvent(event)
        tipDetector.onTouchEvent(event)
        if (zoomDetector.isInProgress) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchIndex = 0
                touchX = event.getX(0)
                touchY = event.getY(0)
                tapX = touchX
                tapY = touchY
                draggedPx = 0f
                scaled = false
                return true
            }

            // Ab dem zweiten Finger zoomt es, gedreht wird erst wieder, wenn
            // wieder nur einer auf dem Bildschirm ist.
            MotionEvent.ACTION_POINTER_DOWN -> return true

            MotionEvent.ACTION_POINTER_UP -> {
                // Ohne Sprung weiterdrehen: den Finger neu ansetzen, der noch
                // auf dem Bildschirm ist, sonst schlägt der Weg bis zu seiner
                // Position durch und die Kugel springt.
                //
                // Achtung Index: Die Zeiger werden ab dem Folgeereignis neu
                // durchnummeriert, alle hinter dem abgehobenen rücken eine
                // Position vor. Der Index aus diesem Ereignis gilt also nicht
                // mehr im nächsten - der alte Zeiger wird dort zu einem
                // falschen, im schlimmsten Fall ungültigen Zeiger, und das
                // Drehen endet im Absturz.
                for (i in 0 until event.pointerCount) {
                    if (i == event.actionIndex) continue
                    touchIndex = folgeIndex(i, event.actionIndex)
                    touchX = event.getX(i)
                    touchY = event.getY(i)
                    break
                }
                draggedPx = 0f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Sicherheitsnetz: Ein Index ausserhalb des Ereignisses waere
                // ungueltig, getX/getY liefern dann je nach Geraet Mist oder
                // eine IllegalArgumentException. Also immer festklemmen.
                val index = touchIndex.coerceIn(0, event.pointerCount - 1)
                val x = event.getX(index)
                val y = event.getY(index)
                val dx = x - touchX
                val dy = y - touchY
                touchX = x
                touchY = y
                tapX = x
                tapY = y
                draggedPx += abs(dx) + abs(dy)
                if (dx != 0f || dy != 0f) rotate(dx, dy)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!scaled && draggedPx <= touchSlop) performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    /** Vergrößert oder verkleinert die Kugel um [faktor]. */
    private fun zoomBy(faktor: Float) {
        val naechste = clampZoom(zoom * faktor)
        if (abs(naechste - zoom) < 0.001f) return
        zoom = naechste
        // Die Pfade sind in Bildkoordinaten gebaut, ein neuer Radius braucht
        // also denselben Neuaufbau wie eine Drehung.
        built = false
        rebuildIfNeeded()
    }

    /** Doppeltipp: einmal hinein, beim nächsten wieder heraus. */
    private fun toggleZoom() {
        zoomBy(if (zoom > ZOOM_MIN + 0.05f) ZOOM_MIN / zoom else ZOOM_DOPPELTIPP)
    }

    /**
     * Fortgeschriebene Drehung aus dem Fingerweg. Bewegt wird der Blickpunkt
     * selbst, die Mitte der besuchten Länder steckt schon in [userLonRad] und
     * [userLatRad] und wandert mit.
     */
    private fun rotate(dx: Float, dy: Float) {
        val radius = globeRadius()
        if (radius <= 0f) return
        val naechste = GeoMath.Globe(userLonRad, userLatRad).drag(dx, dy, radius)
        userLonRad = naechste[0] % TWO_PI
        if (userLonRad < 0.0) userLonRad += TWO_PI
        userLatRad = naechste[1]
        built = false
        rebuildIfNeeded()
    }

    /**
     * Ein Tippen ohne Wischen fragt das Land an. Für das Drehen und Zoomen ist
     * [onTouchEvent] zuständig, hier landet nur der kurze Tipp.
     */
    override fun performClick(): Boolean {
        super.performClick()
        showNameAt(tapX, tapY)
        return true
    }

    private fun drawAirports(canvas: Canvas) {
        val dot = max(1.6f * density, min(width, height) * 0.006f)
        for (point in points) {
            val pos = project(point.lat, point.lon) ?: continue
            // Schwarzer Punkt mit weißem Ring: der Ring trennt ihn vom Wasser,
            // das im dunklen Design selbst fast schwarz ist.
            canvas.drawCircle(pos[0], pos[1], dot * 2f, airportHaloPaint)
            canvas.drawCircle(pos[0], pos[1], dot * 1.3f, airportPaint)
        }
    }

    /**
     * Blendet den Namen des angetippten Landes über der Tippmarke ein. Ohne
     * Tippen bleibt die Karte schriftfrei, deshalb wird hier nichts geprüft und
     * nichts reserviert.
     */
    private fun drawName(canvas: Canvas) {
        val shape = tapped ?: return
        val rand = NAME_RAND_DP * density
        val metrics = namePaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val textWidth = namePaint.measureText(shape.name)
        // Über der Tippmarke, aber ganz auf der Karte. Der Rand gewinnt, wenn
        // der Name breiter ist als die Karte selbst.
        val x = (tapX - textWidth / 2f).coerceIn(rand, max(rand, width - textWidth - rand))
        val y = (tapY - textHeight / 2f).coerceIn(
            metrics.ascent + rand,
            max(metrics.ascent + rand, height - metrics.descent - rand)
        )
        canvas.drawText(shape.name, x, y, nameHaloPaint)
        canvas.drawText(shape.name, x, y, namePaint)
    }

    /**
     * Das Land unter dem Punkt [x], [y]. Mehrere Kandidaten sind an der Grenze
     * normal, deshalb gewinnt das zuletzt gezeichnete Land: die Zeichenreihenfolge
     * [drawOrder] legt die besuchten Länder nach vorn, ein angetipptes Land
     * schlägt also seinen unbesuchten Nachbarn.
     */
    private fun landAt(x: Float, y: Float): Shape? {
        if (x < 0f || y < 0f || x > width || y > height) return null
        val px = x.toInt()
        val py = y.toInt()
        hitClip.set(0, 0, width, height)
        var treffer: Shape? = null
        for (i in shapes.indices.reversed()) {
            val shape = shapes[i]
            // Die Hülle ist der grobe Filter, damit nur wenige Länder wirklich
            // in eine Region umgerechnet werden müssen.
            if (!shape.bounds.contains(x, y)) continue
            hitRegion.setPath(shape.path, hitClip)
            if (hitRegion.contains(px, py)) treffer = shape
        }
        return treffer
    }

    /** Blendet den Namen des Landes an der Tippmarke ein, sonst gar nichts. */
    private fun showNameAt(x: Float, y: Float) {
        val land = landAt(x, y)
        if (land == null) {
            // Wasser oder Rand: Ein Tipp dort nimmt einen stehenden Namen weg.
            removeCallbacks(nameAusblenden)
            tapped = null
            invalidate()
            return
        }
        tapped = land
        removeCallbacks(nameAusblenden)
        postDelayed(nameAusblenden, NAME_ANZEIGE_MS)
        // Auch vorlesen: ohne das bliebe der Name nur für das Auge.
        announceForAccessibility(land.name)
        invalidate()
    }

    /**
     * Baut die gezeichneten Pfade neu auf. Das passiert nur bei
     * Größenänderung, Drehung oder neuen Daten, nicht pro Bild.
     */
    private fun rebuildIfNeeded() {
        if (width <= 0 || height <= 0 || built || building) return
        val list = sharedCountries ?: return
        val state = liveState() ?: return
        built = true
        building = true
        val generation = ++buildGeneration
        val visitedSnapshot = visitedIso2
        Thread {
            // Ein Fehler in buildGlobe darf die App nicht mitnehmen: Auf dem
            // Thread laeuft nichts vom UncaughtExceptionHandler des
            // Hauptthreads, aber eine Exception beendet trotzdem den Prozess
            // - und ohne finally bliebe building dauerhaft true, sodass die
            // Kugel gar nichts mehr neu aufbaut.
            val builtShapes = try {
                buildGlobe(list, visitedSnapshot, state, generation)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Kugel konnte nicht neu aufgebaut werden", e)
                null
            }
            post {
                building = false
                if (generation != buildGeneration) return@post
                if (builtShapes == null) {
                    // Alte Kugel stehen lassen und bewusst nicht sofort neu
                    // versuchen - sonst spannt der Fehler pro Bild einen
                    // neuen Thread auf. built=true markiert den Aufbau als
                    // erledigt; die naechste Drehung oder Zoom setzt ihn
                    // wieder auf false und versucht es erneut.
                    invalidate()
                    return@post
                }
                // Land und Punkte kommen im selben Zug um: [drawnState] ist
                // genau der Zustand, aus dem [builtShapes] gebaut wurde. Erst
                // danach darf gezeichnet werden, sonst stehen die Punkte
                // waehrend des Drehens neben dem Land.
                shapes = builtShapes
                drawnState = state
                invalidate()
                // Waehrend des Aufbaus ist der Finger weitergewandert. Ohne
                // diesen Auftrag bliebe die Kugel auf dem Stand des Aufbaus
                // stehen, bis der naechste Fingerimpuls kommt - das war der
                // Verspaetungseffekt beim Drehen.
                built = false
                if (needsRebuild(state)) rebuildIfNeeded()
            }
        }.start()
    }

    /**
     * Ob seit dem Zustand [state] die Wunschlage der Kugel weitergelaufen ist.
     * Ohne Land ist nichts zu tun, das Wasser zeichnet [onDraw] ohnehin aus
     * [liveState].
     */
    private fun needsRebuild(state: GlobeState): Boolean {
        if (sharedCountries == null || shapes.isEmpty()) return false
        val live = liveState() ?: return false
        val pixel = max(abs(live.cx - state.cx), abs(live.cy - state.cy)) +
            abs(live.radius - state.radius)
        return weichtAb(
            live.globe.centerLon - state.globe.centerLon,
            live.globe.centerLat - state.globe.centerLat,
            pixel
        )
    }

    /**
     * Ordnet die Länder so, dass die besuchten zuletzt gezeichnet werden. Die
     * Umrisse sind unabhängig voneinander vereinfacht und überlappen deshalb an
     * den Küsten ein Stück; so kann ein unbesuchter Nachbar keinen Hauch von
     * einem besuchten Land verdecken.
     */
    private fun drawOrder(shapes: List<Shape>): List<Shape> =
        shapes.sortedBy { if (it.visited) 1 else 0 }

    /**
     * Rechnet alle Länder in Bildkoordinaten um. Der Aufbau laeuft auf einem
     * eigenen Thread und liest deshalb **nichts** aus der View: Lage und Radius
     * kommen fertig als [state] mit, sonst entstuende ein Bild, das zu keiner
     * Kugel gehoert, die der Nutzer je gesehen hat.
     */
    private fun buildGlobe(
        list: List<CountryShapes.Country>,
        visited: Set<String>,
        state: GlobeState,
        generation: Int
    ): List<Shape> {
        val globe = state.globe
        val cx = state.cx
        val cy = state.cy
        val radius = state.radius
        val german = useGermanNames()
        val result = ArrayList<Shape>(list.size)
        for (country in list) {
            if (generation != buildGeneration) return result
            if (country.iso2 == ANTARCTICA) continue
            val path = globeRings(country.rings, globe, cx, cy, radius) ?: continue
            result.add(
                Shape(
                    iso2 = country.iso2,
                    name = if (german) country.nameDe else country.nameEn,
                    path = path,
                    bounds = RectF().apply { path.computeBounds(this, true) },
                    visited = visited.contains(country.iso2)
                )
            )
        }
        return drawOrder(result)
    }

    /**
     * Wandelt die Ringe eines Landes in einen Pfad auf der Kugel um. Ringe, die
     * vollständig auf der Rückseite liegen, fallen weg; die übrigen werden an
     * den Horizontlinien abgeschnitten, damit die Kugel geschlossen wirkt.
     */
    private fun globeRings(
        rings: List<FloatArray>,
        center: GeoMath.Globe,
        cx: Float,
        cy: Float,
        radius: Float
    ): Path? {
        val path = Path()
        var any = false
        for (ring in rings) {
            val count = ring.size / 2
            if (count < 3) continue
            if (!anyVisible(ring, count, center)) continue
            runPath.reset()
            val crossings = ArrayList<Crossing>(4)
            for (i in 0 until count) {
                val j = (i + 1) % count
                val lonA = ring[i * 2].toDouble()
                val latA = ring[i * 2 + 1].toDouble()
                val lonB = ring[j * 2].toDouble()
                val latB = ring[j * 2 + 1].toDouble()
                    val visA = isVisible(lonA, latA, center, pointA)
                    val visB = isVisible(lonB, latB, center, pointB)
                    if (visA == visB) continue
                    val alpha = horizonAlpha(lonA, latA, lonB, latB, center)
                    // cos/sin von NaN ergeben NaN, und ein NaN in moveTo/lineTo
                    // landet ungeprueft im nativen Path. Also lieber die
                    // Schnittstelle weglassen als den Pfad zu vergiften.
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
                    if (!projectGlobe(
                            ring[i * 2 + 1].toDouble(), ring[i * 2].toDouble(),
                            center, cx, cy, radius, buildPoint, buildProjection
                        )
                    ) {
                        continue
                    }
                    if (i == 0) {
                        runPath.moveTo(buildProjection[0], buildProjection[1])
                    } else {
                        runPath.lineTo(buildProjection[0], buildProjection[1])
                    }
                }
                runPath.close()
            } else {
                var first = 0
                while (first < crossings.size && !crossings[first].entering) first++
                if (first >= crossings.size) continue
                val total = crossings.size
                for (k in 0 until total / 2) {
                    val enter = crossings[(first + 2 * k) % total]
                    val exit = crossings[(first + 2 * k + 1) % total]
                    runPath.moveTo(enter.x, enter.y)
                    var steps = exit.segment - enter.segment
                    if (steps <= 0) steps += count
                    if (steps > count) steps = count
                    val start = enter.segment + 1
                    for (s in 0 until steps) {
                        val index = (start + s) % count
                        if (!projectGlobe(
                                ring[index * 2 + 1].toDouble(),
                                ring[index * 2].toDouble(),
                                center, cx, cy, radius, buildPoint, buildProjection
                            )
                        ) {
                            continue
                        }
                        runPath.lineTo(buildProjection[0], buildProjection[1])
                    }
                    runPath.lineTo(exit.x, exit.y)
                    appendHorizonArc(exit.alpha, enter.alpha, cx, cy, radius, runPath)
                    runPath.close()
                }
            }
            path.addPath(runPath)
            any = true
        }
        return if (any) path else null
    }

    /** Übergang eines Ringsegments über den Horizont der Kugel. */
    private class Crossing(
        val segment: Int,
        val entering: Boolean,
        val alpha: Double,
        val x: Float,
        val y: Float
    )

    private fun anyVisible(ring: FloatArray, count: Int, center: GeoMath.Globe): Boolean {
        for (i in 0 until count) {
            if (isVisible(ring[i * 2].toDouble(), ring[i * 2 + 1].toDouble(), center, pointA)) {
                return true
            }
        }
        return false
    }

    private fun isVisible(lon: Double, lat: Double, center: GeoMath.Globe, out: DoubleArray): Boolean {
        center.point(lat, lon, out)
        return out[GeoMath.Globe.FRONT] > ZERO_EPS
    }

    /**
     * Ort eines Punktes auf der Scheibe der Kugel. Liegt er auf der Rückseite,
     * ist das Ergebnis ungültig und die Rückgabe false. [scratch] gehört dem
     * aufrufenden Thread.
     */
    private fun projectGlobe(
        lat: Double,
        lon: Double,
        center: GeoMath.Globe,
        cx: Float,
        cy: Float,
        radius: Float,
        scratch: DoubleArray,
        out: FloatArray
    ): Boolean {
        center.point(lat, lon, scratch)
        // Nicht `> ZERO_EPS`: ein NaN ist im Vergleich mit jedem Wert
        // false und wuerde durchrutschen.
        if (!(scratch[GeoMath.Globe.FRONT] > ZERO_EPS)) return false
        out[0] = (cx + scratch[GeoMath.Globe.X] * radius).toFloat()
        out[1] = (cy - scratch[GeoMath.Globe.Y] * radius).toFloat()
        return true
    }

    /**
     * Schnittpunkt eines Ringsegments mit dem Horizont der Kugel, als Winkel auf
     * dem Randkreis.
     */
    private fun horizonAlpha(
        lonA: Double, latA: Double,
        lonB: Double, latB: Double,
        center: GeoMath.Globe
    ): Double = center.horizon(latA, lonA, latB, lonB, pointA, pointB)

    private fun appendHorizonArc(
        from: Double,
        to: Double,
        cx: Float,
        cy: Float,
        radius: Float,
        out: Path
    ) {
        var delta = to - from
        delta = ((delta + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI
        if (abs(delta) < ZERO_EPS) return
        val steps = min(64, max(2, ceil(abs(delta) / ARC_STEP).toInt()))
        for (s in 1..steps) {
            val a = from + delta * s / steps
            out.lineTo((cx + cos(a) * radius).toFloat(), (cy - sin(a) * radius).toFloat())
        }
    }

    /**
     * Bildschirmposition eines Punktes auf der Kugel. Liegt er auf der Rückseite,
     * ist das Ergebnis ungültig und die Rückgabe null. Das Ergebnis landet in
     * [projected] - der Zeichen-Thread liest es sofort aus, deshalb wird nichts
     * allokiert.
     *
     * Gerechnet wird mit [drawnState], also mit dem Zustand, aus dem auch das
     * Land daneben gezeichnet wurde. Das ist der ganze Unterschied zu einer
     * frischen Rechnung: Ein Aufbau braucht einen Thread, die Punkte warten
     * solange auf das Land und nicht umgekehrt.
     */
    private fun project(lat: Double, lon: Double): FloatArray? {
        val state = drawnState ?: return null
        return if (projectGlobe(
                lat, lon, state.globe, state.cx, state.cy, state.radius,
                drawPoint, projected
            )
        ) {
            projected
        } else {
            null
        }
    }

    private fun globeRadius(): Float = globeRadius(width.toFloat(), height.toFloat())

    /**
     * Radius der Kugel für eine View der Größe [w] mal [h]. Der Aufbau-Thread
     * rechnet mit den Maßen, die er bekommen hat, der Zeichen-Thread mit den
     * aktuellen, deshalb beide Wege über dieselbe Rechnung.
     *
     * Die kleinere Seite bestimmt den Radius: Im Hochformat ist die Karte
     * quadratisch, im Querformat bleibt links und rechts nur die halbe
     * Bildschirmhöhe - dort kann eine Kugel gar nicht bis zum Rand reichen.
     *
     * [GLOBE_MARGIN] ist herausgerechnet und steckt deshalb nur im Ausgangsmaß:
     * Je weiter hereingezoomt wird, desto mehr davon ist wieder da, bis die
     * Kugel die ganze Breite nutzt.
     */
    private fun globeRadius(w: Float, h: Float): Float =
        min(w, h) * (1f - GLOBE_MARGIN) / 2f * zoom

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(): Boolean {
        val language = resources.configuration.locales[0].language
        return language.isEmpty() || language == "de"
    }

    private fun normalizeLon(lon: Double): Double {
        var value = lon
        while (value < LON_MIN) value += 360.0
        while (value > LON_MAX) value -= 360.0
        return value
    }
}
