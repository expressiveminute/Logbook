package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.max

/**
 * Höhen-Grafik der Kachel "Flughäfen": links am Boden steht der
 * niedrigstgelegene Flughafen des Datensatzes, rechts daneben ragt ein Berg
 * auf, auf dessen Spitze der höchstgelegene Flughafen steht. Beide
 * Beschriftungen hängen an genau einem Punkt, der den Standort markiert
 * (Boden beziehungsweise Gipfel); Code und Höhe stehen mittig über dem
 * Punkt. Auf dem Gipfel liegt eine Schneekappe. Ohne Animation und ohne
 * Flugzeug; die Angaben kommen aus den erfassten Flügen
 * ([AirportData.elevationMeters]). Ein Tipp auf einen Code öffnet die
 * Detailseite des Flughafens.
 */
class AirportAltitudeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Airport(val code: String, val label: String, val elevationMeters: Int?)

    private val density get() = resources.displayMetrics.density

    private val mountainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var lowest: Airport? = null
    private var highest: Airport? = null

    private var onAirportClickListener: ((String) -> Unit)? = null

    /** Klickziele der zuletzt gezeichneten Beschriftungen. */
    private val clickTargets = mutableListOf<Pair<RectF, String>>()

    init {
        val primary = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary
        )
        mountainPaint.color = primary
        mountainPaint.alpha = 0x4D
        // Winterschnee bleibt hell, auch im dunklen Modus.
        snowPaint.color = Color.WHITE
        groundPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOutline
        )
        groundPaint.strokeWidth = dp(2f)
        dotPaint.color = primary
        codePaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface
        )
        codePaint.textSize = sp(13f)
        labelPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        labelPaint.textSize = sp(11f)
    }

    /** Setzt den niedrigsten und den höchsten Flughafen des Datensatzes. */
    fun setAltitudeAirports(lowest: Airport?, highest: Airport?) {
        this.lowest = lowest
        this.highest = highest
        invalidate()
    }

    /**
     * Meldet einen Tipp auf einen der beiden Codes (niedrigster oder höchster
     * Flughafen), z. B. um die Detailseite zu öffnen.
     */
    fun setOnAirportClickListener(listener: ((String) -> Unit)?) {
        onAirportClickListener = listener
        isClickable = listener != null
    }

    override fun onDraw(canvas: Canvas) {
        clickTargets.clear()
        if (width <= 0 || height <= 0) return

        // Liegt der niedrigste Flughafen unter dem Meeresspiegel, braucht der
        // Graben unter der Linie samt Beschriftung Platz; die Bodenlinie
        // rückt dann nach oben.
        val belowSea = (lowest?.elevationMeters ?: 0) < 0
        val groundY = height - dp(if (belowSea) 104f else 46f)

        // Ein einzelner, symmetrischer Berg rechts; seine Flanke beginnt links
        // von der Mitte, damit für den niedrigsten Flughafen Platz bleibt.
        val peakX = width * 0.66f
        val peakY = dp(60f)
        drawMountain(canvas, groundY, peakX, peakY)

        // Bodenlinie: die Nulllinie des Meeresspiegels. Was darunter liegt,
        // wird als Graben darunter gezeichnet.
        canvas.drawLine(dp(12f), groundY, width - dp(12f), groundY, groundPaint)

        // Niedrigster Flughafen: über N.N. links am Boden, unter N.N. in
        // einem Graben unter der Linie. Höchster auf der Bergspitze.
        lowest?.let { airport ->
            val meters = airport.elevationMeters
            if (meters != null && meters < 0) {
                val pitX = dp(40f)
                val depth = belowSeaLift(meters)
                drawValley(canvas, pitX, groundY, depth)
                drawAirportMarker(canvas, airport, pitX, groundY + depth, labelsBelow = true)
            } else {
                drawAirportMarker(canvas, airport, dp(40f), groundY, labelsBelow = false)
            }
        }
        highest?.let { drawAirportMarker(canvas, it, peakX, peakY, labelsBelow = false) }
    }

    /**
     * Berg samt winterlicher Schneekappe auf dem Gipfel. Der Berg ist ein
     * gleichschenkliges Dreieck; die Schneekappe ist derselbe Umriss, oben
     * waagerecht abgeschnitten, und liegt damit exakt auf den Flanken - so
     * wirkt der Gipfel nicht verzerrt.
     */
    private fun drawMountain(canvas: Canvas, groundY: Float, peakX: Float, peakY: Float) {
        val halfBase = width * 0.30f
        val left = peakX - halfBase
        val right = peakX + halfBase
        val mountain = Path().apply {
            moveTo(left, groundY)
            lineTo(peakX, peakY)
            lineTo(right, groundY)
            close()
        }
        canvas.drawPath(mountain, mountainPaint)

        val snowHeight = dp(26f)
        val drop = (groundY - peakY).coerceAtLeast(1f)
        val snowHalf = halfBase * (snowHeight / drop)
        val snowCap = Path().apply {
            moveTo(peakX, peakY)
            lineTo(peakX - snowHalf, peakY + snowHeight)
            lineTo(peakX + snowHalf, peakY + snowHeight)
            close()
        }
        canvas.drawPath(snowCap, snowPaint)
    }

    /** Ein Graben unter der Nulllinie, der zum Flughafen hin abfällt. */
    private fun drawValley(canvas: Canvas, centerX: Float, topY: Float, depth: Float) {
        val half = dp(22f)
        val valley = Path().apply {
            moveTo(centerX - half, topY)
            lineTo(centerX, topY + depth)
            lineTo(centerX + half, topY)
            close()
        }
        canvas.drawPath(valley, mountainPaint)
    }

    /**
     * Tiefe des Grabens für einen Flughafen unter dem Meeresspiegel. Ein
     * Mindestmaß hält wenige Meter unter N.N. sichtbar; danach wächst der
     * Graben, bis die volle Tiefe erreicht ist.
     */
    private fun belowSeaLift(meters: Int): Float {
        val fraction = (abs(meters).toFloat() / BELOW_SEA_FULL_METERS).coerceIn(0f, 1f)
        return dp(BELOW_SEA_MIN_DP) + fraction * dp(BELOW_SEA_EXTRA_DP)
    }

    /**
     * Beschriftung zu einem Flughafen: unten ein Punkt am Standort ([dotY]),
     * darüber bzw. darunter mittig die Höhe und der Code. [labelsBelow]
     * stellt den Text unter den Punkt, damit er im Graben nicht in die
     * Bodenlinie und den Berg läuft. Geklickt werden kann auf den Code.
     */
    private fun drawAirportMarker(
        canvas: Canvas,
        airport: Airport,
        dotX: Float,
        dotY: Float,
        labelsBelow: Boolean
    ) {
        val dotRadius = dp(3f)
        val gap = dp(4f)
        val lineGap = dp(2f)
        val altitudeText = altitudeText(airport)
        val halfWidth = max(
            codePaint.measureText(airport.code) / 2f,
            if (altitudeText.isNotEmpty()) labelPaint.measureText(altitudeText) / 2f else 0f
        )

        if (labelsBelow) {
            // Unter dem Punkt aufbauen: zuerst die Höhe, darunter den Code.
            var inkTop = dotY + dotRadius + gap
            if (altitudeText.isNotEmpty()) {
                val baseline = inkTop - labelPaint.ascent()
                canvas.drawText(altitudeText, dotX, baseline, labelPaint)
                inkTop = baseline + labelPaint.descent()
            }
            inkTop += lineGap
            val codeBaseline = inkTop - codePaint.ascent()
            canvas.drawText(airport.code, dotX, codeBaseline, codePaint)
            val codeBottom = codeBaseline + codePaint.descent()
            canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)
            addClickTarget(airport.code, dotX, dotY - dotRadius, codeBottom, halfWidth)
        } else {
            // Über dem Punkt aufbauen: zuerst die Höhe, darüber den Code.
            var inkBottom = dotY - dotRadius - gap
            if (altitudeText.isNotEmpty()) {
                val baseline = inkBottom - labelPaint.descent()
                canvas.drawText(altitudeText, dotX, baseline, labelPaint)
                inkBottom = baseline + labelPaint.ascent()
            }
            inkBottom -= lineGap
            val codeBaseline = inkBottom - codePaint.descent()
            canvas.drawText(airport.code, dotX, codeBaseline, codePaint)
            val codeTop = codeBaseline + codePaint.ascent()
            canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)
            addClickTarget(airport.code, dotX, codeTop, dotY + dotRadius, halfWidth)
        }
    }

    private fun altitudeText(airport: Airport): String {
        val altitude = airport.elevationMeters ?: return ""
        return altitudeCaption(context, altitude)
    }

    /** Merkt sich den Klickbereich einer Beschriftung, wenn nötig. */
    private fun addClickTarget(
        code: String,
        x: Float,
        top: Float, bottom: Float,
        halfWidth: Float
    ) {
        if (onAirportClickListener == null) return
        clickTargets += RectF(
            x - halfWidth, top,
            x + halfWidth, bottom
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
        /** Unter dieser Höhe gilt der Graben als voll ausgeprägt (Meter). */
        private const val BELOW_SEA_FULL_METERS = 300f

        /** Mindest- und Zusatztiefe des Grabens in dp. */
        private const val BELOW_SEA_MIN_DP = 12f
        private const val BELOW_SEA_EXTRA_DP = 34f

        /**
         * Höhenangabe wie überall in der App: über N.N. "1.234 m ü. N.N.",
         * unter N.N. "3 m unter N.N." mit dem Betrag.
         */
        fun altitudeCaption(context: Context, meters: Int): String {
            val res = if (meters < 0) {
                R.string.airport_altitude_caption_below
            } else {
                R.string.airport_altitude_caption
            }
            return context.getString(res, String.format(Settings.language(context), "%,d", abs(meters)))
        }
    }
}