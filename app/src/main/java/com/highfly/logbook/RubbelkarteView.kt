package com.highfly.logbook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
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
import kotlin.math.roundToInt
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
 *
 * Damit das Drehen flüssig bleibt, zahlt der Aufbau nicht mit Trigonometrie
 * pro Punkt: Die Ringe liegen als Einheitsvektoren bereit
 * ([CountryShapes.Country.unitRings]) und werden pro Neuaufbau je Punkt einmal
 * per Matrixmultiplikation projiziert ([GeoMath.Globe.unitPoint]). Während
 * einer Geste werden Ländername und Städte nicht neu aufgebaut - ihre
 * Regionsauflösung pro Land wäre pro Fingerzug zu teuer; beim Loslassen
 * zeichnet [gesteEnde] einmalig mit Beschriftung neu.
 */
class RubbelkarteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * Besuchte Bundesstaaten/-länder samt der Länder, in die es einen Flug
     * gab ("US"/"DE"). Über [setContent] gesetzt.
     */
    data class StateContent(
        val visitedStates: Set<String> = emptySet(),
        val activeCountries: Set<String> = emptySet()
    ) {
        companion object {
            val EMPTY = StateContent()
        }
    }

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
        private const val NAME_HALO_ALPHA = 210

        /**
         * Deckkraft der bereits erledigten Länder. Sie werden mit der
         * Akzentfarbe gezeichnet, damit sie sich von den offenen Ländern
         * abheben; mit dieser Deckkraft treten sie zurück und die offenen
         * Länder fallen mehr auf. Die Konturlinien bleiben ungedimmt.
         */
        private const val VISITED_ALPHA = 140

        /**
         * Deckkraft der nicht angeflogenen Bundesstaaten/-länder in einem Land,
         * in das es schon einen Flug gab. Deutlich schwächer als [VISITED_ALPHA]
         * (angeflogen) - das Land selbst wird in dieser Farbe gezeichnet, die
         * angeflogenen Staaten liegen als volle Besuchsfarbe darüber. Damit ist
         * ein unbesuchter Staat klar schwächer als ein besuchter, aber nicht in
         * der Farbe eines unbesuchten Landes - man war ja schon in dem Land.
         */
        private const val STATE_MUTED_ALPHA = 50

        /** Deckkraft der dünnen Staatengrenzen. */
        private const val STATE_BORDER_ALPHA = 200

        /**
         * Höchstzahl der beschrifteten Hauptstädte von Bundesstaaten/-ländern,
         * zusätzlich zu den Ländernamen und Städten.
         */
        private const val STATE_CAP_MAX = 16

        /** Dauer der programmatischen Zoom-Animation, siehe [zoomTo]. */
        private const val ZOOM_ANIM_MS = 500L

        /** Füllarten der Staaten, siehe [StateShape.fill]. */
        const val FILL_NONE = 0
        const val FILL_VISITED = 1
        const val FILL_MUTED = 2

        /** Wie lange der Name eines angetippten Landes stehen bleibt. */
        private const val NAME_ANZEIGE_MS = 2600L

        /**
         * Radius der Punkte auf der Kugel, einmal als Anteil der kleineren
         * Bildschirmseite und einmal als Untergrenze in dp. Der Anteil gilt bei
         * einem Telefon im Hochformat, die dp-Grenze fängt sehr kleine Karten
         * ab, auf denen der Punkt sonst ganz verschwände.
         *
         * Bewusst klein gewählt: Der Punkt muss auf der ganzen Kugel sichtbar
         * sein, ohne die Länder zu überdecken, und dicht beieinander liegende
         * Punkte sollen als einzelne Marken lesbar bleiben.
         */
        private const val PUNKT_ANTEIL = 0.0032f
        private const val PUNKT_MIN_DP = 1.0f

        /** Weisser Ring um den Punkt, als Vielfaches von [PUNKT_ANTEIL]. */
        private const val PUNKT_RING = 2f

        /** Schwarzer Punkt selbst, als Vielfaches von [PUNKT_ANTEIL]. */
        private const val PUNKT_INNEN = 1.3f

        /** Abstand des Namens vom Rand der Karte. */
        private const val NAME_RAND_DP = 8f

        /**
         * Zoombereich der Kugel.
         *
         * Der obere Anschlag liegt bewusst weit draussen. Die Kugel ist eine
         * Weltkugel: Bei [ZOOM_MIN] sieht man Kontinente, und erst wenn ein Land
         * gross genug auf dem Bildschirm steht, lohnt sich sein Name, erst wenn
         * man weit genug hineinzoomt, sind einzelne Städte mehr als ein Punkt.
         * Ohne diesen Zoombereich wären beide Beschriftungen unerreichbar.
         * ZOOM_MAX ist bewusst großzügig gewählt, damit man deutlich näher an
         * einzelne Länder heranzoomen kann als früher.
         */
        const val ZOOM_MIN = 1f
        const val ZOOM_MAX = 12f

        /**
         * Ab dieser Zoomstufe bekommen die Länder ihren Namen, aber nur wo er
         * ohne Probleme in das Land passt, siehe [labelRechteck].
         */
        const val ZOOM_LABELS = 1.8f

        /**
         * Ab dieser Zoomstufe kommen die Städte dazu. Bewusst deutlich über
         * [ZOOM_LABELS]: Hauptstadt und zwei, drei weitere Städte eines Landes
         * stehen erst dann weit genug auseinander, um lesbar zu bleiben.
         */
        const val ZOOM_STAEDTE = 2.8f

        /** Ziel des Doppeltipps, erneutes Tippen zoomt wieder heraus. */
        const val ZOOM_DOPPELTIPP = 1.8f

        /**
         * Anteil der View-Höhe, um den die Kugel bei geöffneter halbhoher Seite
         * nach oben rutscht (siehe [centerShiftFor]). Deckel auf GLOBE_MARGIN/2:
         * Bei FIT-Zoom (Region = (1-GLOBE_MARGIN)/2 der kleineren Seite um die
         * Kugelmitte) schneidet mehr oben ab, als unten frei wird.
         */
        const val PAGE_OPEN_UP_FRACTION = GLOBE_MARGIN / 2f

        /** Zweiter Deckel des Aufwärtsversatzes als Anteil der Seitenhöhe. */
        const val PAGE_OPEN_COVER_FRACTION = 0.25f

        /**
         * Versatz der Kugelmitte ([centerShiftY]) für eine View an [viewTop] mit
         * der Höhe [viewHeight] auf einem [screenHeight] hohen Bildschirm, unten
         * verdeckt durch [bottomCoverPx] Pixel (halbhohe Seite, 0 = zu).
         *
         * Ohne offene Seite steht die Kugel in der Bildschirmmitte - solange die
         * innerhalb der View liegt, sonst in der View-Mitte. Mit offener Seite
         * läge die Bildschirmmitte im verdeckten Bereich: Die Kugel nimmt dann
         * die Mitte des sichtbaren Streifens und rutscht davon noch bis zu
         * [PAGE_OPEN_UP_FRACTION] der View-Höhe nach oben, damit sie unten nicht
         * in die Seite hineinragt.
         */
        fun centerShiftFor(
            viewTop: Float,
            viewHeight: Float,
            screenHeight: Float,
            bottomCoverPx: Int
        ): Float {
            if (viewHeight <= 0f) return 0f
            val screenCenter = screenHeight / 2f
            val cover = bottomCoverPx.coerceAtLeast(0).toFloat()
            val want = if (cover <= 0f && screenCenter in viewTop..(viewTop + viewHeight)) {
                screenCenter
            } else {
                val up = minOf(viewHeight * PAGE_OPEN_UP_FRACTION, cover * PAGE_OPEN_COVER_FRACTION)
                viewTop + viewHeight / 2f - up
            }
            return want - viewTop - viewHeight / 2f
        }

        /**
         * Freier Rand zwischen Ländername und Küste in dp. Ohne ihn klebt der
         * Name an der Grenze und wirkt eher angeschnitten als gesetzt.
         */
        const val LABEL_RAND_DP = 3f

        /** Schriftgrössen der Beschriftungen auf der Kugel. */
        private const val LABEL_TEXT_SP = 11f
        private const val STADT_TEXT_SP = 8.5f

        /**
         * Höchstzahl der beschrifteten Städte auf der ganzen Kugel. Zusammen
         * mit [STADT_PRO_LAND] ist das die Bremse gegen eine zu volle Karte:
         * Wer einen ganzen Kontinent vor sich hat, soll nicht zwanzig
         * Stadtnamen gleichzeitig lesen müssen.
         */
        private const val STADT_MAX = 12

        /**
         * Städte je Land: eine Hauptstadt und so viele weitere grosse Städte,
         * wie hier Platz vorgesehen sind.
         */
        private const val STADT_PRO_LAND = 3

        /**
         * Ab diesem Rang gilt eine Stadt als gross genug für eine Beschriftung,
         * wenn sie keine Hauptstadt ist. Rang 0 ist die grösste Stadt des Landes,
         * 1 die zweitgrösste.
         */
        private const val STADT_RANG = 1

        /**
         * Kleinste Kantenlänge eines Landes in dp, ab der es überhaupt Städte
         * bekommt. Darunter ist ein Land nur ein Fleck, und der Name seiner
         * Hauptstadt stünde eher daneben als darin.
         */
        private const val STADT_MIN_LAND_DP = 28f

        /** Abstand zwischen Punkt und Städtename in dp. */
        private const val STADT_ABSTAND_DP = 2.5f

        /** Radius des Punktes vor einem Städtenamen in dp. */
        private const val STADT_PUNKT_DP = 1.5f

        /**
         * Zeilen, auf denen ein Städtename sitzen darf: erst neben seinem Punkt,
         * dann eine halbe Texthöhe darüber, dann eine halbe darunter. Zwei
         * Ausweichversuche reichen, weil die Ländernamen wenig Platz lassen;
         * ein dritter würde einen Namen nur so weit von seinem Punkt wegschieben,
         * dass er niemandem mehr zugehört.
         */
        private val STADT_AUSWEICH = floatArrayOf(0f, -1.6f, 1.6f)

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
         * Rechteck, in dem der Name eines Landes stehen müsste, oder `null`,
         * wenn er so gross ist, dass er in das Land auch an günstigster Stelle
         * nicht passt.
         *
         * Der Anker ist der vom Land selbst benannte Punkt
         * (`CountryShapes.Country.labelLat`/`labelLon`), nicht der Mittelpunkt der
         * Hülle: Bei einem langen Land wie Chile liegt die Mitte weit draussen
         * im Pazifik, der Anker dagegen im Land.
         *
         * Bewusst ohne `RectF` und ohne `Region`, damit die Regel im
         * Unit-Test nachvollziehbar bleibt. Die Prüfung, ob das Rechteck
         * tatsächlich im Landumriss liegt, braucht dagegen den aufgelösten Pfad
         * und passiert deshalb erst beim Aufbau, siehe [passtInLand].
         */
        @JvmStatic
        fun labelRechteck(
            textBreite: Float,
            textHoehe: Float,
            ankerX: Float,
            ankerY: Float,
            landBreite: Float,
            landHoehe: Float,
            rand: Float
        ): FloatArray? {
            if (textBreite <= 0f || textHoehe <= 0f) return null
            if (textBreite + 2f * rand > landBreite) return null
            if (textHoehe + 2f * rand > landHoehe) return null
            val links = ankerX - textBreite / 2f
            val oben = ankerY - textHoehe / 2f
            return floatArrayOf(links, oben, links + textBreite, oben + textHoehe)
        }

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


        private const val ANTARCTICA = "AQ"
        private const val DEFAULT_CENTER_LON = 10.0
        private const val DEFAULT_CENTER_LAT = 25.0
        private const val TWO_PI = 2.0 * PI

        // Obergrenze Dezimierungs-Cache (Punkte): ca. 400k Punkte ~ 10 MB.
        private const val DECIMATE_CACHE_MAX_POINTS = 400_000
        private const val HALF_PI = PI / 2.0

        /** Geladene Umrisse: Länder und Staaten gemeinsam, app-weit geteilt. */
        data class Shared(
            val countries: List<CountryShapes.Country>,
            val states: List<StateShapes.State>
        )

        /**
         * Die Umrisse sind app-weit dieselben, deshalb werden sie einmal geladen
         * und von allen Instanzen geteilt. Views, die noch warten, werden schwach
         * gehalten und nach dem Laden zum Neuzeichnen angestoßen.
         */
        @Volatile
        private var shared: Shared? = null

        @Volatile
        private var sharedLoading = false

        private val sharedLock = Any()

        private val waiting = mutableListOf<WeakReference<RubbelkarteView>>()

        private fun requestData(context: Context, view: RubbelkarteView) {
            val ready = shared
            if (ready != null) {
                view.onDataLoaded(ready)
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
                    Shared(
                        CountryShapes.load(appContext) ?: emptyList(),
                        StateShapes.load(appContext) ?: emptyList()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Umrisse konnten nicht geladen werden", e)
                    null
                }
                val views = synchronized(sharedLock) {
                    sharedLoading = false
                    if (parsed != null) shared = parsed
                    waiting.toList().also { waiting.clear() }
                }
                views.forEach { reference ->
                    val target = reference.get() ?: return@forEach
                    target.post { target.onDataLoaded(parsed ?: Shared(emptyList(), emptyList())) }
                }
            }.start()
        }
    }

    /**
     * Ein Bundesstaat/Bundesland mit dem bereits gezeichneten Pfad.
     * [fill] entscheidet, ob die Fläche überhaupt gefüllt wird:
     * [FILL_VISITED] in der Besuchsfarbe, [FILL_MUTED] abgeschwächt (Land
     * selbst besucht, Staat nicht), [FILL_NONE] nur die dünne Grenzlinie.
     */
    private class StateShape(
        val code: String,
        val iso2: String,
        val path: Path,
        val bounds: RectF,
        val fill: Int,
        /** Beschriftete Hauptstadt, siehe [fuelleStateCapitals]. */
        var staedte: List<Stadt> = emptyList()
    )

    /** Ein Land mit dem bereits gezeichneten Pfad. */
    private class Shape(
        val iso2: String,
        val name: String,
        val path: Path,
        val bounds: RectF,
        val visited: Boolean,
        /**
         * Rechteck für den Ländernamen als `[links, oben, rechts, unten]`,
         * gesetzt nur wenn er ohne Überstand in das Land passt. `null` heisst
         * "kein Name": Ob einer gesetzt wird, entscheidet der Aufbau, nicht das
         * Zeichnen, weil dafür der Landumriss aufgelöst werden muss.
         */
        val label: FloatArray?,

        /**
         * Beschriftete Städte dieses Landes, siehe [fuelleStaedte].
         *
         * Steht als `var` und nicht als Konstruktorargument: Die Städte zu einem
         * Land ergeben sich erst, wenn alle Länder aufgebaut sind, weil man
         * dafür in die Umrisse der Nachbarn sehen muss. Sonst müsste jeder der
         * rund 200 Länderpfade zweimal gebaut werden.
         */
        var staedte: List<Stadt> = emptyList()
    ) {
        val hatLabel: Boolean get() = label != null
    }

    /** Stadt auf der Kugel mit vorbereitetem Namen und Position. */
    private class Stadt(
        val name: String,
        val x: Float,
        val y: Float,
        val hauptstadt: Boolean
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
        val radius: Float,
        /**
         * Grösse der Zeichenfläche. Steht hier, weil [baueLabel] einen Namen
         * auch davon abhängt, ob er ganz auf dem Bildschirm liegt: Bei
         * [ZOOM_MAX] reicht die Kugel weit über den Rand hinaus, und ein Name
         * dicht an der Kante wäre abgeschnitten.
         */
        val viewBreite: Float,
        val viewHoehe: Float
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

    /**
     * Füllung der nicht angeflogenen Staaten in einem besuchten Land:
     * Akzentfarbe mit [STATE_MUTED_ALPHA], siehe dort.
     */
    private val stateMutedPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Dünne Linien der Staatengrenzen, deutlich feiner als die Ländergrenzen. */
    private val stateBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 0.5f * density
    }

    /** Kräftige Linie um das hervorgehobene Land/den Staat, siehe [setHighlight]. */
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.2f * density
    }

    /** Weicher Schein unter der Hervorhebung, damit sie auch auf Farbe auffällt. */
    private val highlightGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5f * density
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

    /**
     * Farbträger der Beschriftungen. Eigene Paints, weil [namePaint] für den
     * viel grösseren Namen an der Tippmarke zuständig ist und beim Aufbau
     * gemessen wird - und weil der Aufbau-Thread nicht an einen Paint darf,
     * den der Zeichen-Thread gerade benutzt.
     */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textSize = sp(LABEL_TEXT_SP)
    }
    private val labelHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
        textSize = labelPaint.textSize
    }
    private val stadtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(STADT_TEXT_SP)
    }

    /** Wie [stadtPaint], aber fett - damit die Hauptstadt hervortritt. */
    private val stadtFettPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textSize = sp(STADT_TEXT_SP)
    }
    private val stadtHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
        textSize = stadtPaint.textSize
    }

    /** Punkt vor einem Städtenamen, mit Halo in der Farbe des Textes. */
    private val stadtPunktPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stadtPunktHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    /**
     * Unterstrichene Beschriftung der Hauptstadt, die beim Zoomen per Auge
     * ([setCapitalMarker]) auf der Kugel steht - unabhängig von der Zoomstufe.
     */
    private val capitalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        isUnderlineText = true
        textSize = sp(STADT_TEXT_SP)
    }
    private val capitalHaloPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
        isUnderlineText = true
        textSize = capitalPaint.textSize
    }

    /** Farbe des Halos hinter den Beschriftungen, siehe [nameHaloPaint]. */
    private var labelHaloColor = Color.TRANSPARENT

    private fun sp(wert: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, wert, resources.displayMetrics
    )

    private val clipPath = Path()

    /** Nur zum Aufspüren des Landes unter dem Finger, siehe [landAt]. */
    private val hitClip = Region()
    private val hitRegion = Region()

    /**
     * Zum Prüfen von Namen und Städten gegen den Landumriss, gehören dem
     * Aufbau-Thread. Eigene Regionen, weil [hitClip]/[hitRegion] dem
     * Zeichen-Thread gehören und unter dem Finger stehen: Ein Aufbau, der
     * während eines Tippens die Region überschreibt, liest dort plötzlich das
     * Land unter dem Finger - oder gar keins.
     */
    private val labelClip = Region()
    private val labelRegion = Region()

    /** Zielpuffer für [project], gehört dem Zeichen-Thread. */
    private val projected = FloatArray(2)

    /** Zielpuffer für den Aufbau-Thread, siehe [projectGlobe]. */
    private val buildProjection = FloatArray(2)

    /**
     * Lage eines Punktes, je ein Puffer für den Aufbau-Thread und für den
     * Zeichen-Thread. [projectGlobe] und [GeoMath.Globe.unitPoint] schreiben in
     * [buildPoint] beziehungsweise [drawPoint]; beide Threads dürfen sich
     * keinen Puffer teilen, sonst liest einer die halbe Rechnung des anderen.
     */
    private val buildPoint = DoubleArray(3)
    private val drawPoint = DoubleArray(3)

    @Volatile
    private var shapes: List<Shape> = emptyList()

    /** Gezeichnete Bundesstaaten/-länder, siehe [StateShape]. */
    @Volatile
    private var stateShapes: List<StateShape> = emptyList()

    /** Besuchte Staaten und Länder mit Flug, siehe [StateContent]. */
    @Volatile
    private var stateContent = StateContent.EMPTY

    /**
     * Code des hervorgehobenen Landes/Staates (Auge in der Liste), oder null.
     * Wird als kräftige Linie über die Grenze gezeichnet.
     */
    @Volatile
    private var highlightCodes: Set<String> = emptySet()

    /**
     * Hauptstadt des per Auge angezoomten Landes/Staats als (lat, lon, Name),
     * wird unterstrichen auf der Kugel gezeichnet - unabhängig von der
     * Zoomstufe. null = keine Anzeige, siehe [setCapitalMarker].
     */
    private var capitalMarker: Triple<Double, Double, String>? = null

    /**
     * Programmatisch gesetztes Zentrum der Kugel als (lat, lon), oder null -
     * dann zählt die Mitte der besuchten Länder. Siehe [zoomTo].
     */
    @Volatile
    private var focusOverride: Pair<Double, Double>? = null

    /** Laufende Zoom-Animation, siehe [zoomTo]. */
    private var focusAnim: ValueAnimator? = null

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
     * Höhe des unten eingeblendeten Bereichs (halbhohe Seite im page_host) in
     * Pixeln; 0, wenn keine Seite offen ist. Setzt das Fragment über
     * [setBottomCover]. Bei offenem Bereich zentriert [updateCenterShift] die
     * Kugel auf den sichtbaren Streifen statt auf die Bildschirmmitte.
     */
    private var bottomCoverPx = 0

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
     * Finger auf dem Bildschirm. Während einer Geste werden Ländername und
     * Städte nicht neu aufgebaut: Beide brauchen die teure Regionsauflösung
     * ([Region.setPath]) pro Land, und die frisst bei jedem Fingerzug so viel
     * Zeit, dass das Drehen ruckelt. Beim Loslassen wird einmalig mit
     * Beschriftung neu aufgebaut, siehe [gesteEnde].
     */
    @Volatile
    private var gestureActive = false

    /**
     * Ob das gerade gezeichnete Bild Ländernamen trägt. Während einer Geste
     * gebaute Bilder tragen keine; [gesteEnde] stößt dann den Neuaufbau mit
     * Beschriftung an.
     */
    @Volatile
    private var drawnHatNamen = false

    /**
     * Gefilterte und sortierte Stadtkandidaten für [fuelleStaedte]. Rein aus
     * den Assetdaten, deshalb einmalig berechnet statt bei jedem Aufbau.
     */
    @Volatile
    private var stadtKandidatenCache: List<MapCity>? = null

    /**
     * Pro Ring des Aufbaus die projizierten Werte: X, Y und FRONT je Punkt,
     * einmalig pro Ring berechnet und für Sichtbarkeit wie Horizontschnitte
     * wieder gelesen. Gehören dem Aufbau-Thread; es läuft nie mehr als ein
     * Aufbau zugleich ([building]).
     */
    private var ringX = DoubleArray(0)
    private var ringY = DoubleArray(0)
    private var ringF = DoubleArray(0)

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
        val primary = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary
        )
        visitedPaint.color = primary
        visitedPaint.alpha = VISITED_ALPHA
        stateMutedPaint.color = ColorUtils.setAlphaComponent(primary, STATE_MUTED_ALPHA)
        val outline = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOutlineVariant
        )
        borderPaint.color = outline
        rimPaint.color = outline
        stateBorderPaint.color = ColorUtils.setAlphaComponent(outline, STATE_BORDER_ALPHA)
        highlightPaint.color = ColorUtils.setAlphaComponent(primary, 230)
        highlightGlowPaint.color = ColorUtils.setAlphaComponent(primary, 70)
        val nameColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnPrimary
        )
        namePaint.color = nameColor
        // Der Halo muss die Gegenfarbe zur Schrift sein, sonst tut er nichts und
        // verbreitert die Buchstaben nur. Er trennt den Namen auch dort vom
        // Hintergrund, wo er über Wasser oder über den Kartenrand ragt. Über
        // die Schrift entscheidet die Helligkeit, nicht die Tag-/Nachteinstellung:
        // Braun, Türkis und Magenta haben helle Zielfarben mit dunkler Schrift,
        // das Standardthema ist es umgekehrt.
        labelHaloColor = ColorUtils.setAlphaComponent(
            if (ColorUtils.calculateLuminance(nameColor) > 0.5f) {
                Color.rgb(12, 16, 24)
            } else {
                Color.rgb(244, 247, 252)
            },
            NAME_HALO_ALPHA
        )
        nameHaloPaint.color = labelHaloColor
        labelPaint.color = nameColor
        labelHaloPaint.color = labelHaloColor
        stadtPaint.color = nameColor
        stadtFettPaint.color = nameColor
        stadtHaloPaint.color = labelHaloColor
        stadtPunktPaint.color = nameColor
        stadtPunktHaloPaint.color = labelHaloColor
        capitalPaint.color = nameColor
        capitalHaloPaint.color = labelHaloColor
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }

    /**
     * Setzt den Besuch. [visited] enthält die besuchten Länder als ISO-2,
     * [airports] die besuchten Flughäfen als IATA. [states] trägt die
     * Bundesstaaten/-länder: welche angeflogen sind und in welchen Ländern
     * es überhaupt einen Flug gab - erst dann werden die Staaten gefüllt.
     */
    fun setContent(
        visited: Set<String>,
        airports: List<String>,
        states: StateContent = StateContent.EMPTY
    ) {
        visitedIso2 = visited
        visitedAirports = airports
        // Codes normalisieren, damit Groß-/Kleinschreibung keine Rolle spielt.
        stateContent = states.copy(
            visitedStates = states.visitedStates.map { it.trim().uppercase() }.toSet(),
            activeCountries = states.activeCountries.map { it.trim().uppercase() }.toSet()
        )
        requestData(context, this)
        invalidate()
    }

    /**
     * Hebt Länder (ISO-2) oder Staaten (ISO-3166-2) auf der Kugel hervor,
     * z. B. alle Länder eines Kontinents nach einem Kachel-Tipp oder ein Land
     * nach einem Tipp auf das Auge in der Länderliste. Eine leere Menge
     * schaltet die Hervorhebung aus.
     */
    fun setHighlight(codes: Set<String>) {
        highlightCodes = codes.map { it.trim().uppercase() }.toSet()
        invalidate()
    }

    /**
     * Zeigt die Hauptstadt [name] an [lat]/[lon] unterstrichen auf der Kugel an
     * (unabhängig von der Zoomstufe) - gesetzt, wenn per Auge auf ein Land oder
     * einen Staat gezoomt wird. [name] null schaltet die Anzeige aus (z. B. beim
     * Kachel-Tipp auf eine ganze Region).
     */
    fun setCapitalMarker(lat: Double, lon: Double, name: String?) {
        capitalMarker = if (name.isNullOrBlank()) null else Triple(lat, lon, name)
        invalidate()
    }

    /**
     * Setzt die Höhe des unten eingeblendeten Bereichs (halbhohe Seite) in
     * Pixeln; 0, wenn die Seite geschlossen ist. Das Fragment ruft das in
     * openPage bzw. hidePage.
     */
    fun setBottomCover(px: Int) {
        val value = px.coerceAtLeast(0)
        if (value != bottomCoverPx) {
            bottomCoverPx = value
            built = false
            invalidate()
        }
    }

    /**
     * Setzt die Kugel auf die Anfangsansicht zurück: Weltkugel bei [ZOOM_MIN],
     * zentriert auf die besuchten Länder (ohne solche auf die Vorgabe) - als
     * hätte der Nutzer weder gezoomt noch verschoben. Läuft als kurze Animation.
     */
    fun resetToWorld() {
        capitalMarker = null
        val center = shared?.countries?.let { RubbelkarteStats.centerOf(it, visitedIso2) }
        if (center != null) {
            // centerOf liefert (lon, lat).
            zoomTo(center.second, center.first, ZOOM_MIN)
        } else {
            focusAnim?.cancel()
            focusOverride = null
            // Auch die Nutzerdrehung zurücksetzen - sonst addiert
            // [liveState] sie auf die Mitte der besuchten Länder drauf.
            userLonRad = 0.0
            userLatRad = 0.0
            zoom = ZOOM_MIN
            built = false
            rebuildIfNeeded()
            invalidate()
        }
    }

    /**
     * Fährt die Kugel programmgesteuert auf den Punkt [lat]/[lon] und zoomt auf
     * [targetZoom]. Die Bewegung läuft als kurze Animation; ein Wischen des
     * Nutzers übernimmt wieder die Steuerung (das Zielzentrum wird verworfen).
     * Eine vorherige Drehung per Finger wird in das Zielzentrum übernommen
     * ([userLonRad]/[userLatRad] werden 0), denn [liveState] addiert sie sonst
     * auf das fertige Ziel drauf - das Land läge dann daneben.
     */
    fun zoomTo(lat: Double, lon: Double, targetZoom: Float) {
        val toZoom = clampZoom(targetZoom)
        val state = liveState()
        val fromLat = if (state != null) Math.toDegrees(state.globe.centerLat) else DEFAULT_CENTER_LAT
        val fromLon =
            if (state != null) normalizeLon(Math.toDegrees(state.globe.centerLon)) else DEFAULT_CENTER_LON
        // Die Drehung aus Fingerwischen wird in das programmatische Zentrum
        // übernommen: [liveState] addiert [userLonRad]/[userLatRad] auf jedes
        // Zentrum - blieben sie stehen, läge das angefahrene Ziel um den
        // Wischweg daneben (z. B. Auge in der Länderliste nach manuellem
        // Drehen der Kugel; jede weitere programmatische Fahrt käme ebenso
        // daneben an).
        userLonRad = 0.0
        userLatRad = 0.0
        focusOverride = fromLat to fromLon
        if (width <= 0 || height <= 0) {
            focusOverride = lat to lon
            zoom = toZoom
            built = false
            rebuildIfNeeded()
            invalidate()
            return
        }
        val fromZoom = zoom
        // Kürzester Weg über die Datumslinie: ein Sprung von 350° wäre derselbe
        // Blickpunkt wie -10°, darf aber nicht die ganze Kugel herumdrehen.
        val deltaLon = ((lon - fromLon + 540.0) % 360.0) - 180.0
        focusAnim?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                focusOverride = (fromLat + (lat - fromLat) * f) to (fromLon + deltaLon * f)
                zoom = clampZoom(fromZoom + (toZoom - fromZoom) * f)
                built = false
                rebuildIfNeeded()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    focusOverride = lat to lon
                    zoom = toZoom
                    built = false
                    rebuildIfNeeded()
                    invalidate()
                }
            })
            start()
        }
        focusAnim = anim
    }

    private fun onDataLoaded(data: Shared) {
        if (data.countries.isEmpty()) {
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

    override fun onDetachedFromWindow() {
        focusAnim?.cancel()
        focusAnim = null
        removeCallbacks(nameAusblenden)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        // Nur das Wasser ist blau, der Kasten um die Kugel verschwindet.
        updateCenterShift()
        if (!built) {
            if (shared == null) {
                requestData(context, this)
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
        // Länder mit eigenen Staaten (USA/Deutschland mit Flug) werden nur
        // schwach gefüllt - die Staaten zeichnen sich darüber selbst: angeflogene
        // in voller Besuchsfarbe, nicht angeflogene bleiben in der schwachen
        // Grundfarbe des Landes sichtbar.
        val activeCountries = stateContent.activeCountries
        for (shape in list) {
            val paint = when {
                shape.iso2 in activeCountries -> stateMutedPaint
                shape.visited -> visitedPaint
                else -> unvisitedPaint
            }
            canvas.drawPath(shape.path, paint)
        }
        // AngeFlogene Bundesstaaten/-länder in derselben Farbe wie ein besuchtes
        // Land. Nicht angeflogene bekommen keine eigene Füllung mehr - das Land
        // darunter ist schon schwach gefüllt, sie treten dadurch deutlich zurück.
        for (shape in stateShapes) {
            if (shape.fill == FILL_VISITED) {
                canvas.drawPath(shape.path, visitedPaint)
            }
        }
        for (shape in list) {
            canvas.drawPath(shape.path, borderPaint)
        }
        // Die Staatengrenzen als dünne Linien obendrauf.
        for (shape in stateShapes) {
            canvas.drawPath(shape.path, stateBorderPaint)
        }
        drawHighlight(canvas, list)
        drawBeschriftung(canvas)
        drawCapitalMarker(canvas, state)
        drawAirports(canvas)
        drawName(canvas)
        canvas.restore()
        // Der Rand gehört zum Bild, nicht zum Wasser, deshalb ohne Beschneiden.
        canvas.drawCircle(state.cx, state.cy, state.radius, rimPaint)
    }

    /**
     * Zeichnet die Hauptstadt des per Auge angezoomten Landes/Staats
     * ([capitalMarker]): Punkt mit unterstrichenem Namen, unabhängig von der
     * Zoomstufe - beim Hereinzoomen über [ZOOM_STAEDTE] hinaus stehen die
     * Städtenamen ohnehin, darunter auch diese Hauptstadt.
     */
    private fun drawCapitalMarker(canvas: Canvas, state: GlobeState) {
        val marker = capitalMarker ?: return
        val pos = project(marker.first, marker.second) ?: return
        val dx = pos[0] - state.cx
        val dy = pos[1] - state.cy
        if (dx * dx + dy * dy > state.radius * state.radius) return
        val punkt = max(PUNKT_MIN_DP * density, min(width, height) * PUNKT_ANTEIL) * 1.2f
        canvas.drawCircle(pos[0], pos[1], punkt * PUNKT_RING, stadtPunktHaloPaint)
        canvas.drawCircle(pos[0], pos[1], punkt * PUNKT_INNEN, stadtPunktPaint)
        val x = pos[0] + punkt * PUNKT_RING + STADT_ABSTAND_DP * density
        val baseline = pos[1] - (capitalPaint.ascent() + capitalPaint.descent()) / 2f
        canvas.drawText(marker.third, x, baseline, capitalHaloPaint)
        canvas.drawText(marker.third, x, baseline, capitalPaint)
    }

    /**
     * Zeichnet die Hervorhebung aus [setHighlight]: erst den weichen Schein,
     * dann die kräftige Linie - beim Land wie beim Staat.
     */
    private fun drawHighlight(canvas: Canvas, list: List<Shape>) {
        val codes = highlightCodes
        if (codes.isEmpty()) return
        for (shape in list) {
            if (shape.iso2.uppercase() in codes) {
                canvas.drawPath(shape.path, highlightGlowPaint)
                canvas.drawPath(shape.path, highlightPaint)
            }
        }
        for (shape in stateShapes) {
            if (shape.code.uppercase() in codes) {
                canvas.drawPath(shape.path, highlightGlowPaint)
                canvas.drawPath(shape.path, highlightPaint)
            }
        }
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
        val list = shared?.countries
        // Ein programmatisch gesetztes Zentrum ([zoomTo]) schlägt die Mitte der
        // besuchten Länder. Ohne Länder gibt es keine solche Mitte, dann zählt
        // die Vorgabe - so fehlt der Aufbau nicht an einer leeren Kugel.
        val center = focusOverride?.let { Pair(it.second, it.first) }
            ?: list?.let { RubbelkarteStats.centerOf(it, visitedIso2) }
        val centerLon = normalizeLon(center?.first ?: DEFAULT_CENTER_LON)
        val centerLat = (center?.second ?: DEFAULT_CENTER_LAT).coerceIn(-60.0, 70.0)
        val lonRad = Math.toRadians(centerLon) + userLonRad
        val latRad = (Math.toRadians(centerLat) + userLatRad).coerceIn(-HALF_PI, HALF_PI)
        return GlobeState(
            globe = GeoMath.Globe(lonRad, latRad),
            cx = cx,
            cy = cy,
            radius = radius,
            viewBreite = width.toFloat(),
            viewHoehe = height.toFloat()
        )
    }

    /**
     * Rechnet den Versatz aus, um den die Kugel tiefer sitzen muss, damit sie in
     * der Bildschirmmitte steht. Die halbe Höhe des Wurzelverlaufs ist die Mitte
     * des Bildschirms, von der Position der View aus gesehen liegt sie um
     * [centerShiftY] tiefer als die Viewmitte.
     *
     * Ist unten die halbhohe Seite offen ([bottomCoverPx] > 0), liegt die
     * Bildschirmmitte im verdeckten Bereich - die Kugel würde dort zeichnen, wo
     * die Seite sie verdeckt. Sie nimmt dann die Mitte des sichtbaren Streifens
     * und rutscht davon noch etwas nach oben. Die reine Rechnung steht in
     * [centerShiftFor].
     */
    private fun updateCenterShift() {
        val root = rootView
        if (root.height <= 0) return
        getLocationInWindow(windowOffset)
        val shift = centerShiftFor(
            viewTop = windowOffset[1].toFloat(),
            viewHeight = height.toFloat(),
            screenHeight = root.height.toFloat(),
            bottomCoverPx = bottomCoverPx
        )
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
                // Eine laufende programmatische Zoom-Animation ([zoomTo]) hört
                // auf, sobald der Nutzer selbst greift; das Zielzentrum bleibt
                // aber stehen, damit die Kugel nicht zurückspringt.
                focusAnim?.cancel()
                focusAnim = null
                // Ab jetzt bis zum Loslassen ohne Beschriftung neu aufbauen.
                gestureActive = true
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
                gesteEnde()
                if (!scaled && draggedPx <= touchSlop) performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                gesteEnde()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Ende einer Geste: Beschriftungen, die während des Drehens oder Zoomens
     * ausgesetzt waren, werden einmalig neu aufgebaut. Ein Tipp ohne Zug
     * stößt nichts an - dann lief während der Geste kein Aufbau ohne Namen.
     */
    private fun gesteEnde() {
        gestureActive = false
        if (!beschriftungFehlt()) return
        built = false
        rebuildIfNeeded()
    }

    /**
     * Ob das gezeichnete Bild Beschriftung tragen müsste, sie aber nicht hat:
     * Während einer Geste wird ohne Namen aufgebaut; endet die Geste, während
     * der Aufbau noch läuft, muss der fertige Zustand einmalig nachgereicht
     * werden, sonst bliebe die Kugel bis zur nächsten Drehung namenlos.
     */
    private fun beschriftungFehlt(): Boolean =
        !drawnHatNamen && zoom >= ZOOM_LABELS && !gestureActive

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
        // Ab dem ersten Wischen gehört die Kugel wieder dem Nutzer: ein
        // programmatisch gesetztes Zentrum ([zoomTo]) wird verworfen.
        focusOverride = null
        focusAnim?.cancel()
        focusAnim = null
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
        val dot = max(PUNKT_MIN_DP * density, min(width, height) * PUNKT_ANTEIL)
        for (point in points) {
            val pos = project(point.lat, point.lon) ?: continue
            // Schwarzer Punkt mit weißem Ring: der Ring trennt ihn vom Wasser,
            // das im dunklen Design selbst fast schwarz ist.
            canvas.drawCircle(pos[0], pos[1], dot * PUNKT_RING, airportHaloPaint)
            canvas.drawCircle(pos[0], pos[1], dot * PUNKT_INNEN, airportPaint)
        }
    }

    /**
     * Zeichnet die Ländernamen und darunter die Städte.
     *
     * Die Länder kommen zuerst und unbedingt: Ihr Name ist der grösste Text auf
     * der Karte und der Grund, warum man hereingezoomt hat. Ein Städtename, der
     * damit kollidiert, weicht aus oder fällt weg - andersherum wäre es der
     * Ländername, der verschwindet, und die Karte verlöre ihre Orientierung.
     *
     * Das Rechteck des Ländernamens steht schon im [Shape]: Dort wurde es
     * gegen den Landumriss geprüft und damit zugleich gegen den Kartenrand. Hier
     * wird nur noch der Mittelpunkt zur Grundlinie gemacht, weil `drawText` an
     * der Grundlinie zeichnet, das Rechteck aber mittig um den Text liegt.
     */
    private fun drawBeschriftung(canvas: Canvas) {
        if (zoom < ZOOM_LABELS) return
        val belegt = ArrayList<FloatArray>(16)
        for (shape in shapes) {
            val label = shape.label ?: continue
            val grundlinie = (label[1] + label[3]) / 2f - grundlinienVersatz(labelPaint)
            canvas.drawText(shape.name, label[0], grundlinie, labelHaloPaint)
            canvas.drawText(shape.name, label[0], grundlinie, labelPaint)
            belegt.add(label)
        }
        if (zoom < ZOOM_STAEDTE) return
        for (shape in shapes) {
            for (stadt in shape.staedte) {
                // Die Hauptstadt wird fett gesetzt: Sie ist der Anker, an dem
                // man ein Land auf der Kugel wiedererkennt.
                val textPaint = if (stadt.hauptstadt) stadtFettPaint else stadtPaint
                if (platziereStadt(canvas, belegt, stadt, textPaint)) continue
            }
        }
        // Hauptstädte der aktiven Bundesstaaten/-länder (USA/Deutschland mit
        // Flug): eigenes Budget, damit sie die Städtenamen nicht verdrängen.
        var capBudget = STATE_CAP_MAX
        for (shape in stateShapes) {
            if (capBudget <= 0) break
            if (shape.fill == FILL_NONE) continue
            for (stadt in shape.staedte) {
                if (capBudget <= 0) break
                if (platziereStadt(canvas, belegt, stadt, stadtFettPaint)) capBudget--
            }
        }
    }

    /**
     * Setzt einen Städtenamen neben seinen Punkt, an einer der Ausweichpositionen
     * aus [STADT_AUSWEICH]. Gibt true zurück, wenn er gezeichnet wurde - dann
     * gehört das belegte Rechteck in [belegt], damit der nächste Name ausweicht.
     *
     * Der Punkt gehört mit zur Beschriftung: Ein Name, der auf einen Punkt
     * zeigt, ist ohne den Zusammenhang einem anderen Land zuzuordnen.
     */
    // measureText pro Beschriftung pro Bild ist teuer - Breiten werden gecacht
    // (Schlüssel: fett/normal + Text; Schlüsselraum begrenzt: Länder-/Städtenamen).
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

    private fun platziereStadt(
        canvas: Canvas,
        belegt: MutableList<FloatArray>,
        stadt: Stadt,
        textPaint: Paint
    ): Boolean {
        val metrics = stadtPaint.fontMetrics
        val textHoehe = metrics.descent - metrics.ascent
        val abstand = STADT_ABSTAND_DP * density
        val punktRadius = STADT_PUNKT_DP * density
        val textBreite = cachedTextWidth(textPaint, textPaint.isFakeBoldText, stadt.name)
        for (versatz in STADT_AUSWEICH) {
            val y = stadt.y + versatz * textHoehe
            val rect = floatArrayOf(
                stadt.x - punktRadius - 1f,
                y - textHoehe / 2f - abstand / 2f,
                stadt.x + abstand + textBreite,
                y + textHoehe / 2f + abstand / 2f
            )
            if (belegt.any { ueberlappt(it, rect) }) continue
            belegt.add(rect)
            val grundlinie = y - grundlinienVersatz(stadtPaint)
            canvas.drawText(stadt.name, stadt.x + abstand, grundlinie, stadtHaloPaint)
            canvas.drawText(stadt.name, stadt.x + abstand, grundlinie, textPaint)
            // Der Punkt bekommt denselben Halo wie der Text, sonst stünde
            // ein winziger Punkt ohne Anschluss an seinem Namen.
            canvas.drawCircle(stadt.x, stadt.y, punktRadius, stadtPunktHaloPaint)
            canvas.drawCircle(stadt.x, stadt.y, punktRadius, stadtPunktPaint)
            return true
        }
        return false
    }

    /** Versatz der Grundlinie zur Mitte des Textes. */
    private fun grundlinienVersatz(paint: Paint): Float {
        val metrics = paint.fontMetrics
        return (metrics.ascent + metrics.descent) / 2f
    }

    /** Überschneiden sich zwei Beschriftungsrechtecke? */
    private fun ueberlappt(a: FloatArray, b: FloatArray): Boolean =
        a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    /**
     * Blendet den Namen des angetippten Landes über der Tippmarke ein. Ohne
     * Tippen bleibt die Karte an dieser Stelle schriftfrei, deshalb wird hier
     * nichts geprüft und nichts reserviert.
     */
    private fun drawName(canvas: Canvas) {
        val shape = tapped ?: return
        val rand = NAME_RAND_DP * density
        val metrics = namePaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val textWidth = cachedTextWidth(namePaint, namePaint.isFakeBoldText, shape.name)
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
        val data = shared ?: return
        val state = liveState() ?: return
        built = true
        building = true
        val generation = ++buildGeneration
        val visitedSnapshot = visitedIso2
        val stateSnapshot = stateContent
        // Während einer Geste ohne Beschriftung aufbauen, siehe [gestureActive].
        // Der Wert wird hier auf dem Hauptthread gelesen und dem Aufbau mitgegeben,
        // der Thread liest selbst nichts aus der View.
        val mitNamen = zoom >= ZOOM_LABELS && !gestureActive
        // Der Aufbau-Thread liest nichts aus der View. Für die Städte braucht er
        // aber einen Context zum Lesen des Assets, also wandert der
        // Anwendungskontext mit - nicht die View, an die die View hängt.
        val appContext = context.applicationContext
        Thread {
            // Ein Fehler in buildGlobe darf die App nicht mitnehmen: Auf dem
            // Thread laeuft nichts vom UncaughtExceptionHandler des
            // Hauptthreads, aber eine Exception beendet trotzdem den Prozess
            // - und ohne finally bliebe building dauerhaft true, sodass die
            // Kugel gar nichts mehr neu aufbaut.
            val builtShapes = try {
                buildGlobe(
                    data.countries, visitedSnapshot, data.states, stateSnapshot,
                    state, generation, zoom, mitNamen, appContext
                )
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
                // Land, Staaten und Punkte kommen im selben Zug um: [drawnState]
                // ist genau der Zustand, aus dem [builtShapes] gebaut wurde. Erst
                // danach darf gezeichnet werden, sonst stehen die Punkte
                // waehrend des Drehens neben dem Land.
                shapes = builtShapes.first
                stateShapes = builtShapes.second
                drawnState = state
                drawnHatNamen = mitNamen
                invalidate()
                // Waehrend des Aufbaus ist der Finger weitergewandert. Ohne
                // diesen Auftrag bliebe die Kugel auf dem Stand des Aufbaus
                // stehen, bis der naechste Fingerimpuls kommt - das war der
                // Verspaetungseffekt beim Drehen. Dasselbe gilt, wenn die Geste
                // endete, während ein Aufbau ohne Beschriftung noch lief.
                built = false
                if (needsRebuild(state) || beschriftungFehlt()) rebuildIfNeeded()
            }
        }.start()
    }

    /**
     * Ob seit dem Zustand [state] die Wunschlage der Kugel weitergelaufen ist.
     * Ohne Land ist nichts zu tun, das Wasser zeichnet [onDraw] ohnehin aus
     * [liveState].
     */
    private fun needsRebuild(state: GlobeState): Boolean {
        if (shared == null || shapes.isEmpty()) return false
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
     * Kugel gehoert, die der Nutzer je gesehen hat. Dasselbe gilt für [zoom]:
     * Ob ein Name gesetzt wird, hängt an der Zoomstufe, und die muss zu dem
     * Zustand gehören, aus dem die Pfade gerechnet sind. [mitNamen] schaltet
     * die Beschriftung ab - während einer Geste, siehe [gestureActive].
     */
    private fun buildGlobe(
        list: List<CountryShapes.Country>,
        visited: Set<String>,
        states: List<StateShapes.State>,
        stateContent: StateContent,
        state: GlobeState,
        generation: Int,
        zoom: Float,
        mitNamen: Boolean,
        appContext: Context
    ): Pair<List<Shape>, List<StateShape>> {
        val globe = state.globe
        val cx = state.cx
        val cy = state.cy
        val radius = state.radius
        val german = useGermanNames()
        // Gemessen wird mit einem eigenen Paint, nicht mit dem zum Zeichnen:
        // Der Aufbau läuft neben dem Zeichen-Thread, und zwei Threads an einem
        // Paint sind nur unkritisch, solange keiner davon etwas einträgt.
        val messPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFakeBoldText = true
            textSize = sp(LABEL_TEXT_SP)
        }
        val rand = LABEL_RAND_DP * density
        val result = ArrayList<Shape>(list.size)
        for (country in list) {
            if (generation != buildGeneration) return result to emptyList()
            if (country.iso2 == ANTARCTICA) continue
            // Ganz auf der Rückseite liegende Länder werden gar nicht erst
            // projiziert - bei den feinen Umrissen spart das pro Neuaufbau
            // zigtausende Punkte.
            if (ganzHinten(globe, country.minLon, country.minLat, country.maxLon, country.maxLat)) continue
            val path = globeRings(
                dezimiereRinge(country.unitRings, country.sharedMasks, radius),
                globe, cx, cy, radius
            ) ?: continue
            val bounds = RectF().apply { path.computeBounds(this, true) }
            val name = if (german) country.nameDe else country.nameEn
            val label = if (mitNamen) {
                baueLabel(country, name, path, bounds, messPaint, rand, state)
            } else {
                null
            }
            result.add(
                Shape(
                    iso2 = country.iso2,
                    name = name,
                    path = path,
                    bounds = bounds,
                    visited = visited.contains(country.iso2),
                    label = label
                )
            )
        }
        val geordnet = drawOrder(result)
        if (mitNamen && zoom >= ZOOM_STAEDTE) {
            fuelleStaedte(geordnet, globe, cx, cy, radius, german, appContext)
        }
        // Bundesstaaten/-länder (USA, Deutschland): Umrisse und Füllung. Die
        // dünnen Grenzen zeichnen sich immer, die Füllung nur in Ländern, in
        // die es einen Flug gab.
        val stateResult = ArrayList<StateShape>(states.size)
        for (stateEntry in states) {
            if (generation != buildGeneration) break
            if (ganzHinten(globe, stateEntry.minLon, stateEntry.minLat, stateEntry.maxLon, stateEntry.maxLat)) continue
            val path = globeRings(
                dezimiereRinge(stateEntry.unitRings, stateEntry.sharedMasks, radius),
                globe, cx, cy, radius
            ) ?: continue
            val bounds = RectF().apply { path.computeBounds(this, true) }
            val active = stateEntry.iso2 in stateContent.activeCountries
            val fill = when {
                !active -> FILL_NONE
                stateEntry.code.uppercase() in stateContent.visitedStates -> FILL_VISITED
                else -> FILL_MUTED
            }
            stateResult.add(
                StateShape(
                    code = stateEntry.code,
                    iso2 = stateEntry.iso2,
                    path = path,
                    bounds = bounds,
                    fill = fill
                )
            )
        }
        if (mitNamen && zoom >= ZOOM_STAEDTE) {
            fuelleStateCapitals(stateResult, states, globe, cx, cy, radius, german)
        }
        return geordnet to stateResult
    }

    /**
     * Besetzt die Hauptstädte der aktiven Bundesstaaten/-länder: nur in Ländern,
     * in die es einen Flug gab ([StateShape.fill] ungleich [FILL_NONE]), und nur
     * wo der Staat groß genug auf dem Bildschirm ist. Die Kollisionsprüfung mit
     * den übrigen Beschriftungen passiert erst beim Zeichnen, siehe
     * [platziereStadt].
     */
    private fun fuelleStateCapitals(
        shapes: List<StateShape>,
        states: List<StateShapes.State>,
        globe: GeoMath.Globe,
        cx: Float,
        cy: Float,
        radius: Float,
        german: Boolean
    ) {
        val byCode = HashMap<String, StateShapes.State>(states.size)
        for (state in states) byCode[state.code.uppercase()] = state
        val kleinsteLaenge = STADT_MIN_LAND_DP * density
        var budget = STATE_CAP_MAX
        for (shape in shapes) {
            if (budget <= 0) break
            if (shape.fill == FILL_NONE) continue
            if (shape.bounds.width() < kleinsteLaenge ||
                shape.bounds.height() < kleinsteLaenge
            ) {
                continue
            }
            val state = byCode[shape.code.uppercase()] ?: continue
            if (!projectGlobe(
                    state.capLat, state.capLon, globe, cx, cy, radius,
                    buildPoint, buildProjection
                )
            ) {
                continue
            }
            val capital = state.capDe.ifBlank { state.capEn }
            if (capital.isBlank()) continue
            shape.staedte = listOf(
                Stadt(
                    name = if (german) capital else state.capEn.ifBlank { capital },
                    x = buildProjection[0],
                    y = buildProjection[1],
                    hauptstadt = true
                )
            )
            budget--
        }
    }

    /**
     * Lage eines Ländernamens auf der Scheibe, oder `null`, wenn er nicht
     * sauber in das Land passt und deshalb gar nicht erst gesetzt wird.
     *
     * Geprüft wird von der billigsten zur genausten Stufe: Der Name muss erst
     * überhaupt in die Hülle des Landes passen, dann ganz auf der Kugel und auf
     * dem Bildschirm liegen, und erst dann wird der Landumriss aufgelöst. So
     * greift die teure Regionsrechnung nur für Länder, die überhaupt in Frage
     * kommen.
     */
    private fun baueLabel(
        country: CountryShapes.Country,
        name: String,
        path: Path,
        bounds: RectF,
        messPaint: Paint,
        rand: Float,
        state: GlobeState
    ): FloatArray? {
        val cx = state.cx
        val cy = state.cy
        val radius = state.radius
        val metrics = messPaint.fontMetrics
        val breite = cachedTextWidth(messPaint, messPaint.isFakeBoldText, name)
        val hoehe = metrics.descent - metrics.ascent
        if (!projectGlobe(
                country.labelLat, country.labelLon, state.globe, cx, cy, radius,
                buildPoint, buildProjection
            )
        ) {
            return null
        }
        val rect = labelRechteck(
            textBreite = breite,
            textHoehe = hoehe,
            ankerX = buildProjection[0],
            ankerY = buildProjection[1],
            landBreite = bounds.width(),
            landHoehe = bounds.height(),
            rand = rand
        ) ?: return null
        // Ein Name, der über den Kugelrand oder den Bildschirmrand ragt, sähe
        // aus wie abgeschnitten. Beides wird hier geprüft, denn die beiden
        // Ränder fallen bei hohem Zoom auseinander: Die Kugel ist dann viel
        // grösser als der Bildschirm.
        if (rect[0] < 0f || rect[1] < 0f ||
            rect[2] > state.viewBreite || rect[3] > state.viewHoehe
        ) {
            return null
        }
        for (ecke in 0 until 4) {
            val x = if (ecke and 1 == 0) rect[0] else rect[2]
            val y = if (ecke < 2) rect[1] else rect[3]
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy > radius * radius) return null
        }
        if (!passtInLand(path, bounds, rect)) return null
        return rect
    }

    /**
     * Baut die Region eines Landes auf. Gibt false zurück, wenn aus dem Pfad
     * keine Region wird, etwa weil er leer ist.
     */
    private fun regionFor(path: Path, bounds: RectF): Boolean {
        labelClip.set(
            bounds.left.toInt() - 1,
            bounds.top.toInt() - 1,
            bounds.right.toInt() + 1,
            bounds.bottom.toInt() + 1
        )
        return labelRegion.setPath(path, labelClip)
    }

    /**
     * Liegt das Rechteck vollständig im Land?
     *
     * Geprüft werden die vier Ecken, die Mitten der vier Seiten und der
     * Mittelpunkt, nicht nur die Ecken: Bei einem stark eingebuchteten Land wie
     * der Schweiz oder Vietnam würde ein Rechteck durch die Bucht der Küste
     * ragen und trotzdem alle vier Ecken im Land haben.
     */
    private fun passtInLand(path: Path, bounds: RectF, rect: FloatArray): Boolean {
        if (!regionFor(path, bounds)) return false
        val links = rect[0].toInt()
        val rechts = rect[2].toInt()
        val oben = rect[1].toInt()
        val unten = rect[3].toInt()
        val mitteX = (links + rechts) / 2
        val mitteY = (oben + unten) / 2
        return labelRegion.contains(links, oben) &&
            labelRegion.contains(rechts, oben) &&
            labelRegion.contains(links, unten) &&
            labelRegion.contains(rechts, unten) &&
            labelRegion.contains(links, mitteY) &&
            labelRegion.contains(rechts, mitteY) &&
            labelRegion.contains(mitteX, oben) &&
            labelRegion.contains(mitteX, unten) &&
            labelRegion.contains(mitteX, mitteY)
    }

    /**
     * Die beschriftbaren Städte: Hauptstädte und die grössten Städte, nach
     * Bedeutung sortiert. Das Ergebnis hängt nur von den Assetdaten ab, nicht
     * von der Kugellage, und wird deshalb einmalig zwischengespeichert statt
     * bei jedem Neuaufbau erneut gefiltert und sortiert.
     */
    private fun stadtKandidaten(appContext: Context): List<MapCity> {
        stadtKandidatenCache?.let { return it }
        val list = MapCities.load(appContext)
            .filter { it.isCapital || it.rank <= STADT_RANG }
            .sortedBy { it.importance() }
        stadtKandidatenCache = list
        return list
    }

    /**
     * Sucht zu jedem sichtbaren Land seine Hauptstadt und bis zu
     * [STADT_PRO_LAND] weitere grosse Städte.
     *
     * Die Zuordnung entsteht aus der Lage: Ein Ort gehört zu dem Land, dessen
     * aufgelöster Umriss ihn enthält. `cities.json` führt kein Land mit sich, und
     * eine Tabelle aus dieser Quelle zu pflegen wäre eine zweite Wahrheit, die
     * irgendwann auseinanderläuft.
     *
     * Städte sind [STADT_MAX] Stück auf der ganzen Kugel, nicht je Land: Bei
     * Zoomstufe [ZOOM_STAEDTE] ist zwar nur ein Fleck der Welt sichtbar, aber ein
     * Blick von oben aufs Baltikum zeigt sonst sieben Städte auf einmal.
     *
     * Die Länder kommen in der Zeichenreihenfolge [drawOrder] durch, also die
     * besuchten zuerst. Das ist Absicht: Wer die Karneerfüllung von Ländern
     * ansieht, will deren Hauptstädte lesen, nicht die von Nachbarn, die
     * zufällig im selben Ausschnitt liegen.
     */
    private fun fuelleStaedte(
        shapes: List<Shape>,
        globe: GeoMath.Globe,
        cx: Float,
        cy: Float,
        radius: Float,
        german: Boolean,
        appContext: Context
    ) {
        val kandidaten = stadtKandidaten(appContext)
        if (kandidaten.isEmpty()) return
        val kleinsteLaenge = STADT_MIN_LAND_DP * density
        var budget = STADT_MAX
        // Ein Ort an einer Küste liegt in den Regionen zweier Nachbarländer.
        // Wer ihn zuerst bekommen hat, behält ihn.
        val vergeben = HashSet<Int>(kandidaten.size)
        for (shape in shapes) {
            if (budget <= 0) break
            if (shape.bounds.width() < kleinsteLaenge ||
                shape.bounds.height() < kleinsteLaenge
            ) {
                continue
            }
            if (!regionFor(shape.path, shape.bounds)) continue
            var hauptstadt: Stadt? = null
            var hauptstadtIndex = -1
            val weitere = ArrayList<Stadt>(STADT_PRO_LAND)
            val weitereIndizes = ArrayList<Int>(STADT_PRO_LAND)
            for (i in kandidaten.indices) {
                if (vergeben.contains(i)) continue
                val city = kandidaten[i]
                if (!projectGlobe(
                        city.lat, city.lon, globe, cx, cy, radius, buildPoint, buildProjection
                    )
                ) {
                    continue
                }
                if (!labelRegion.contains(
                        buildProjection[0].toInt(), buildProjection[1].toInt()
                    )
                ) {
                    continue
                }
                val stadt = Stadt(
                    name = if (german) city.deName else city.enName,
                    x = buildProjection[0],
                    y = buildProjection[1],
                    hauptstadt = city.isCapital
                )
                if (city.isCapital && hauptstadt == null) {
                    hauptstadt = stadt
                    hauptstadtIndex = i
                } else if (weitere.size < STADT_PRO_LAND) {
                    weitere.add(stadt)
                    weitereIndizes.add(i)
                } else {
                    continue
                }
                if (hauptstadt != null && weitere.size >= STADT_PRO_LAND) break
            }
            // Die Hauptstadt zuerst, und insgesamt so viele, wie das Land noch
            // übrig hat: Bei knappem Budget fallen die weiteren Städte weg, die
            // Hauptstadt bleibt. Erst danach gilt eine Stadt als vergeben -
            // sonst würde eine nicht gezeichnete Stadt den Platz ihres Landes
            // blockieren und am Ende gar nicht mehr beschriftet.
            val gewaehlt = ArrayList<Stadt>(STADT_PRO_LAND + 1)
            val genommen = ArrayList<Int>(STADT_PRO_LAND + 1)
            if (hauptstadt != null) {
                gewaehlt.add(hauptstadt)
                genommen.add(hauptstadtIndex)
            }
            for (k in weitere.indices) {
                if (gewaehlt.size >= budget) break
                gewaehlt.add(weitere[k])
                genommen.add(weitereIndizes[k])
            }
            if (gewaehlt.isEmpty()) continue
            budget -= gewaehlt.size
            vergeben.addAll(genommen)
            shape.staedte = gewaehlt
        }
    }

    /**
     * Winkelausdehnung (Diagonale der Bounding-Box) eines Gebiets in Radiant,
     * begrenzt auf 180° - Länder wie Russland reichen über die Datumslinie und
     * hätten sonst eine viel zu große "Diagonale".
     */
    private fun diagRad(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): Double {
        val midLatRad = Math.toRadians((minLat + maxLat) / 2.0)
        var d = max((maxLon - minLon) * cos(midLatRad), maxLat - minLat)
        if (d > 180.0) d = 180.0
        return Math.toRadians(d)
    }

    /**
     * Liegt das Gebiet ganz auf der Rückseite der Kugel? Der Winkel zwischen
     * Blickrichtung und jedem Punkt des Gebiets ist dann größer als 90°.
     */
    private fun ganzHinten(
        globe: GeoMath.Globe,
        minLon: Double, minLat: Double, maxLon: Double, maxLat: Double
    ): Boolean {
        var midLon = (minLon + maxLon) / 2.0
        if (maxLon - minLon > 180.0) midLon += 180.0
        midLon = ((midLon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val midLat = (minLat + maxLat) / 2.0
        val mlat = Math.toRadians(midLat)
        val mlon = Math.toRadians(midLon)
        val dot = cos(globe.centerLat) * cos(globe.centerLon) * cos(mlat) * cos(mlon) +
            cos(globe.centerLat) * sin(globe.centerLon) * cos(mlat) * sin(mlon) +
            sin(globe.centerLat) * sin(mlat)
        return dot < -sin(diagRad(minLon, minLat, maxLon, maxLat)) - 0.02
    }

    /**
     * Dezimiert die Ringe eines Gebiets passend zum Zoom per Douglas-Peucker mit
     * bildschirmbezogener Toleranz (epsRad = spacingPx / radius -> Fehler höchstens
     * ~2 px, konkave Küsten behalten ihre Form). Selbstschnittfrei, damit die
     * Füllung keine Löcher bekommt.
     */
    private fun dezimiereRinge(
        unitRings: List<DoubleArray>,
        sharedMasks: List<BooleanArray>,
        radius: Float
    ): List<DoubleArray> {
        // Während einer Geste doppelte Toleranz (schnellerer Neuaufbau), sonst 2 dp.
        val epsRad = ((if (gestureActive) 4.0 else 2.0) * density) / radius
        // Dezimierung cachen (siehe GlobeView.decimateCached): Beim Drehen ändert
        // sich weder Ring noch Toleranz - ohne Cache würde jeder Frame alle
        // sichtbaren Länder komplett neu dezimieren und die Karte ruckelte.
        // Gemeinsame Grenzpunkte (sharedMasks) hält die Dezimierung fest, damit
        // Grenzen in beiden Nachbarländern identisch bleiben (keine Lücken).
        val bucket = epsBucket(epsRad)
        return unitRings.mapIndexed { i, ring ->
            decimateCached(ring, epsRad, bucket, sharedMasks.getOrNull(i))
        }
    }

    // Nur der einzelne Aufbau-Thread liest/schreibt den Cache.
    private val decimateCache = java.util.IdentityHashMap<DoubleArray, HashMap<Int, DoubleArray>>()
    private var decimateCachePoints = 0

    private fun epsBucket(eps: Double): Int =
        (Math.log(eps.coerceAtLeast(1e-9)) / Math.log(1.19)).roundToInt()

    private fun decimateCached(
        ring: DoubleArray,
        eps: Double,
        bucket: Int,
        forceKeep: BooleanArray? = null
    ): DoubleArray {
        decimateCache[ring]?.get(bucket)?.let { return it }
        val d = GeoMath.decimateUnitsSafe(ring, eps, forceKeep)
        if (decimateCachePoints > DECIMATE_CACHE_MAX_POINTS) {
            decimateCache.clear()
            decimateCachePoints = 0
        }
        decimateCache.getOrPut(ring) { HashMap() }[bucket] = d
        decimateCachePoints += d.size / 3
        return d
    }

    /**
     * Wandelt die Ringe eines Landes in einen Pfad auf der Kugel um. Ringe, die
     * vollständig auf der Rückseite liegen, fallen weg; die übrigen werden an
     * den Horizontlinien abgeschnitten, damit die Kugel geschlossen wirkt.
     *
     * [unitRings] trägt die Ringpunkte als Einheitsvektoren (siehe
     * [CountryShapes.Country.unitRings]). Jeder Punkt wird genau einmal
     * projiziert und landet in den Ringpuffern [ringX]/[ringY]/[ringF] -
     * Sichtbarkeit und Horizontschnitte lesen nur noch diese Werte. Rechnet
     * man dagegen jeden Punkt mehrfach mit [GeoMath.Globe.point] nach, kostet
     * der Neuaufbau der ~80.000 Punkte bei jedem Fingerzug zu viel Zeit.
     */
    private fun globeRings(
        unitRings: List<DoubleArray>,
        center: GeoMath.Globe,
        cx: Float,
        cy: Float,
        radius: Float
    ): Path? {
        val path = Path()
        var any = false
        for (unitRing in unitRings) {
            val count = unitRing.size / 3
            if (count < 3) continue
            ensureRingBuffers(count)
            // Einmalig pro Ring projizieren: X, Y und FRONT je Punkt.
            var anyVisible = false
            for (i in 0 until count) {
                center.unitPoint(
                    unitRing[i * 3], unitRing[i * 3 + 1], unitRing[i * 3 + 2],
                    buildPoint
                )
                ringX[i] = buildPoint[GeoMath.Globe.X]
                ringY[i] = buildPoint[GeoMath.Globe.Y]
                ringF[i] = buildPoint[GeoMath.Globe.FRONT]
                if (ringF[i] > ZERO_EPS) anyVisible = true
            }
            if (!anyVisible) continue
            // Direkt in den Zielpfad schreiben (runPath als Zwischenpuffer entfällt -
            // eine native Kopie pro Punkt pro Bild sparen): moveTo beginnt jeweils
            // einen neuen Subpfad, close() schließt den Ring.
            val crossings = ArrayList<Crossing>(4)
            for (i in 0 until count) {
                val j = (i + 1) % count
                val visA = ringF[i] > ZERO_EPS
                val visB = ringF[j] > ZERO_EPS
                if (visA == visB) continue
                val alpha = horizonWinkel(
                    ringX[i], ringY[i], ringF[i],
                    ringX[j], ringY[j], ringF[j]
                )
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
                var firstPt = true
                for (i in 0 until count) {
                    // Nicht `> ZERO_EPS`: ein NaN ist im Vergleich mit jedem
                    // Wert false und wuerde durchrutschen.
                    if (!(ringF[i] > ZERO_EPS)) continue
                    val x = (cx + ringX[i] * radius).toFloat()
                    val y = (cy - ringY[i] * radius).toFloat()
                    if (firstPt) {
                        path.moveTo(x, y)
                        firstPt = false
                    } else {
                        path.lineTo(x, y)
                    }
                }
                if (!firstPt) path.close()
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
                    path.moveTo(enter.x, enter.y)
                    var steps = exit.segment - enter.segment
                    if (steps <= 0) steps += count
                    if (steps > count) steps = count
                    val start = enter.segment + 1
                    for (s in 0 until steps) {
                        val index = (start + s) % count
                        if (!(ringF[index] > ZERO_EPS)) continue
                        path.lineTo(
                            (cx + ringX[index] * radius).toFloat(),
                            (cy - ringY[index] * radius).toFloat()
                        )
                    }
                    path.lineTo(exit.x, exit.y)
                    appendHorizonArc(exit.alpha, enter.alpha, cx, cy, radius, path)
                    path.close()
                }
            }
            any = true
        }
        if (!any) return null
        // WINDING (Default) lassen: Die Enklaven-Lochringe der Quelle sind bis auf
        // drei Mini-Inseln gegenläufig orientiert (Vatikan, San Marino, Lesotho
        // bleiben so Löcher), und die Rand-Slivers verschiedener Ringe am Horizont
        // haben dieselbe Orientierung - bei WINDING addieren sie sich (gefüllt),
        // bei EVEN_ODD würden sie sich gegenseitig auslöschen (Loch am Kugelrand).
        return path
    }

    /** Übergang eines Ringsegments über den Horizont der Kugel. */
    private class Crossing(
        val segment: Int,
        val entering: Boolean,
        val alpha: Double,
        val x: Float,
        val y: Float
    )

    /** Sorgt dafür, dass die Ringpuffer mindestens [count] Punkte fassen. */
    private fun ensureRingBuffers(count: Int) {
        if (ringX.size >= count) return
        ringX = DoubleArray(count)
        ringY = DoubleArray(count)
        ringF = DoubleArray(count)
    }

    /**
     * Schnittpunkt eines Ringsegments mit dem Horizont der Kugel, als Winkel auf
     * dem Randkreis. Arbeitet auf den bereits projizierten Werten [ringX],
     * [ringY] und [ringF] - dieselbe Rechnung wie [GeoMath.Globe.horizon], ohne
     * die Endpunkte ein zweites Mal zu projizieren.
     */
    private fun horizonWinkel(
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
        // Schrittweite in Pixeln (nicht in Radiant): sonst wird der Horizontbogen
        // bei großer Kugel eckig.
        val steps = min(512, max(2, ceil(abs(delta) * radius / (2.0 * density)).toInt()))
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
