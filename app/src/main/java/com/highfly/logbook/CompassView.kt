package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Kompass der Kachel "Flughäfen". In der Mitte steht der Flughafen, von dem
 * aus am häufigsten geflogen wurde; an den vier Himmelsrichtungen die
 * Extrem-Flughäfen des Datensatzes: Norden = nördlichster, Süden =
 * südlichster, Westen = westlichster, Osten = östlichster. Derselbe Flughafen
 * darf mehrere Positionen belegen, dann steht sein Code mehrfach.
 *
 * Gezeichnet wird eine Kompassrose aus vier schlanken, zweifarbigen Nadeln.
 * In der Mitte steht der Code des häufigsten Abflughafens; an jeder Nadel
 * folgt zuerst mit etwas Abstand am Nadelende der Buchstabe der
 * Himmelsrichtung, daneben der Dreilettercode - etwas grösser und fett. Ein
 * Tipp auf einen Code öffnet die Detailseite des Flughafens (siehe
 * [setOnAirportClickListener]).
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Airport(val code: String, val label: String)

    private val density get() = resources.displayMetrics.density

    /** Zweifarbige Nadel: die eine Hälfte hell, die andere dunkel. */
    private val needleLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val needleDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    /** Feine Kontur der Nadel, damit die Form auch hell klar bleibt. */
    private val needleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val letterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val centerCodePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var center: Airport? = null
    private var north: Airport? = null
    private var south: Airport? = null
    private var west: Airport? = null
    private var east: Airport? = null

    private var onAirportClickListener: ((String) -> Unit)? = null

    /** Klickziele der zuletzt gezeichneten Codes, in Zeichenreihenfolge. */
    private val clickTargets = mutableListOf<Pair<RectF, String>>()

    init {
        val primary = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary
        )
        needleLightPaint.color = primary
        needleLightPaint.alpha = 0x30
        needleDarkPaint.color = primary
        needleDarkPaint.alpha = 0x78
        needleStrokePaint.color = primary
        needleStrokePaint.alpha = 0x99
        needleStrokePaint.strokeWidth = dp(1.2f)
        letterPaint.color = primary
        letterPaint.textSize = sp(14f)
        codePaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        codePaint.textSize = sp(17f)
        centerCodePaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface
        )
        centerCodePaint.textSize = sp(20f)
    }

    /**
     * Setzt die fünf Flughäfen des Kompasses; alle außer [center] dürfen
     * `null` sein.
     */
    fun setAirports(
        center: Airport?,
        north: Airport? = null,
        south: Airport? = null,
        west: Airport? = null,
        east: Airport? = null
    ) {
        this.center = center
        this.north = north
        this.south = south
        this.west = west
        this.east = east
        invalidate()
    }

    /**
     * Meldet einen Tipp auf einen der Codes (Mitte oder Himmelsrichtung) mit
     * dessen Dreilettercode, z. B. um die Detailseite zu öffnen.
     */
    fun setOnAirportClickListener(listener: ((String) -> Unit)?) {
        onAirportClickListener = listener
        isClickable = listener != null
    }

    override fun onDraw(canvas: Canvas) {
        clickTargets.clear()
        if (width <= 0 || height <= 0 || center == null) return
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.30f

        // Kompassrose aus vier schlanken Nadeln; in der Mitte steht der Code
        // des häufigsten Abflughafens.
        drawNeedles(canvas, cx, cy, r)

        val northText = resources.getString(R.string.compass_north)
        val southText = resources.getString(R.string.compass_south)
        val westText = resources.getString(R.string.compass_west)
        val eastText = resources.getString(R.string.compass_east)

        // Reihenfolge von innen nach aussen: Nadelende, dann mit etwas Abstand
        // der Buchstabe der Himmelsrichtung, daneben mit deutlich mehr Abstand
        // der Dreilettercode. Der Code sitzt so weit draussen wie der Platz
        // zulässt, damit er auf schmalen Geräten nicht seitlich abgeschnitten
        // wird (siehe [clampCodeRadius]).
        val letterRadius = r + dp(LETTER_OFFSET_DP)
        val codeHalfWidth = listOfNotNull(north, south, west, east)
            .maxOfOrNull { codePaint.measureText(it.code) / 2f }
            ?: (codePaint.measureText("XXX") / 2f)
        // West und Ost stehen links und rechts der Rose: ihr Code darf nicht
        // seitlich aus der Ansicht laufen, der begrenzende Wert ist die Breite.
        val codeRadiusX = clampCodeRadius(
            letterRadius,
            dp(CODE_GAP_DP),
            width / 2f,
            codeHalfWidth,
            dp(CODE_MARGIN_DP)
        )
        // Nord und Süd stehen über und unter der Rose: dort begrenzt die Höhe.
        // Ohne diese zweite Schranke stünde der Code oben bzw. unten am Rand
        // und würde abgeschnitten.
        val codeMetrics = codePaint.fontMetrics
        val codeHalfHeight = (codeMetrics.descent - codeMetrics.ascent) / 2f
        val codeRadiusY = clampCodeRadius(
            letterRadius,
            dp(CODE_GAP_DP),
            height / 2f,
            codeHalfHeight,
            dp(CODE_MARGIN_DP)
        )
        drawDirection(canvas, northText, north?.code, cx, cy - letterRadius, cx, cy - codeRadiusY)
        drawDirection(canvas, southText, south?.code, cx, cy + letterRadius, cx, cy + codeRadiusY)
        drawDirection(canvas, westText, west?.code, cx - letterRadius, cy, cx - codeRadiusX, cy)
        drawDirection(canvas, eastText, east?.code, cx + letterRadius, cy, cx + codeRadiusX, cy)

        // Zentrum: Code des häufigsten Abflughafens.
        val centerCode = center!!.code
        val baseline = cy - (centerCodePaint.ascent() + centerCodePaint.descent()) / 2f
        canvas.drawText(centerCode, cx, baseline, centerCodePaint)
        addClickTarget(centerCode, cx, cy, centerCodePaint)
    }

    /** Vier schlanke Nadeln nach Norden, Osten, Süden und Westen. */
    private fun drawNeedles(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawNeedle(canvas, cx, cy, r, 0f)
        drawNeedle(canvas, cx, cy, r, 90f)
        drawNeedle(canvas, cx, cy, r, 180f)
        drawNeedle(canvas, cx, cy, r, 270f)
    }

    /**
     * Eine moderne Kompassnadel: ein schlanker Drachen, dessen Wurzel mit
     * Abstand von der Mitte beginnt und der zur Spitze hin ausläuft. Der
     * Abstand hält die Mitte frei, damit der dortige Dreilettercode klar
     * lesbar bleibt. Die beiden Hälften sind unterschiedlich hell, was der
     * Nadel eine leichte Faltung gibt; eine feine Kontur hält die Form
     * zusammen. Die Nadel wird um [angleDeg] um die Mitte gedreht.
     */
    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, r: Float, angleDeg: Float) {
        val halfWidth = dp(NEEDLE_HALF_WIDTH_DP)
        val rootRadius = r * NEEDLE_ROOT_FRACTION
        val shoulderRadius = rootRadius + (r - rootRadius) * NEEDLE_SHOULDER_FRACTION
        val save = canvas.save()
        canvas.rotate(angleDeg, cx, cy)

        val left = Path().apply {
            moveTo(cx, cy - rootRadius)
            lineTo(cx - halfWidth, cy - shoulderRadius)
            lineTo(cx, cy - r)
            close()
        }
        val right = Path().apply {
            moveTo(cx, cy - rootRadius)
            lineTo(cx + halfWidth, cy - shoulderRadius)
            lineTo(cx, cy - r)
            close()
        }
        canvas.drawPath(left, needleLightPaint)
        canvas.drawPath(right, needleDarkPaint)
        canvas.drawPath(left, needleStrokePaint)
        canvas.drawPath(right, needleStrokePaint)
        canvas.restoreToCount(save)
    }

    /**
     * Zeichnet den Buchstaben einer Himmelsrichtung am Nadelende mit etwas
     * Abstand ([letterX], [letterY]) und daneben den Dreilettercode
     * ([codeX], [codeY]). Für Nord und Süd liegt der Code über bzw. unter dem
     * Buchstaben (gleiches x, versetztes y), für West und Ost daneben.
     */
    private fun drawDirection(
        canvas: Canvas,
        letter: String,
        code: String?,
        letterX: Float, letterY: Float,
        codeX: Float, codeY: Float
    ) {
        val letterBaseline = letterY - (letterPaint.ascent() + letterPaint.descent()) / 2f
        canvas.drawText(letter, letterX, letterBaseline, letterPaint)
        if (code.isNullOrBlank()) return
        val codeBaseline = codeY - (codePaint.ascent() + codePaint.descent()) / 2f
        canvas.drawText(code, codeX, codeBaseline, codePaint)
        addClickTarget(code, codeX, codeY, codePaint)
    }

    /** Merkt sich den Klickbereich eines gezeichneten Codes, wenn nötig. */
    private fun addClickTarget(code: String, x: Float, y: Float, paint: TextPaint) {
        if (onAirportClickListener == null) return
        val halfWidth = paint.measureText(code) / 2f
        val metrics = paint.fontMetrics
        val halfHeight = (metrics.descent - metrics.ascent) / 2f
        clickTargets += RectF(
            x - halfWidth, y - halfHeight,
            x + halfWidth, y + halfHeight
        ) to code
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onAirportClickListener == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val code = clickTargets.asReversed()
                    .firstOrNull { (rect, _) -> rect.contains(event.x, event.y) }
                    ?.second
                if (code != null) {
                    performClick()
                    onAirportClickListener?.invoke(code)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    companion object {
        /** Halbe Breite der Nadel an ihrem breitesten Punkt. */
        private const val NEEDLE_HALF_WIDTH_DP = 7.5f

        /** Wo die Nadel beginnt, als Anteil der Nadellänge ab der Mitte. */
        private const val NEEDLE_ROOT_FRACTION = 0.36f

        /** Lage des breitesten Punkts als Anteil der sichtbaren Nadellänge. */
        private const val NEEDLE_SHOULDER_FRACTION = 0.34f

        /** Abstand des Richtungsbuchstabens vom Nadelende. */
        private const val LETTER_OFFSET_DP = 22f

        /** Gewünschter Abstand zwischen Richtungsbuchstabe und Dreilettercode. */
        private const val CODE_GAP_DP = 52f

        /** Mindestabstand des Dreilettercodes zum seitlichen Bildrand. */
        private const val CODE_MARGIN_DP = 4f

        /**
         * Radius, auf dem der Dreilettercode sitzt: der Radius des
         * Richtungsbuchstabens [letterRadius] plus der gewünschte Abstand [gap].
         * Weil die Codes links und rechts der Kompassrose stehen, wird der
         * Radius bei Bedarf so weit hereingeholt, dass der breiteste Code
         * ([codeHalfWidth] ist seine halbe Textbreite) samt [margin] noch in
         * die halbe Ansichtsbreite [halfWidth] passt. So bleibt der Abstand auf
         * breiten Geräten gross und der Code auf schmalen trotzdem vollständig
         * sichtbar.
         */
        @JvmStatic
        fun clampCodeRadius(
            letterRadius: Float,
            gap: Float,
            halfWidth: Float,
            codeHalfWidth: Float,
            margin: Float
        ): Float {
            val maxRadius = halfWidth - codeHalfWidth - margin
            return (letterRadius + gap).coerceAtMost(maxRadius).coerceAtLeast(letterRadius)
        }
    }
}