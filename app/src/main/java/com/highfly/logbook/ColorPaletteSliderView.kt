package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Farbpalette als waagerechter Balken mit einem Knopf, den man nach links und
 * rechts verschiebt, um die Farbe nahtlos anzupassen. Zwei Modi:
 *
 *  - [setPalette]: der Balken zeigt einen **nahtlosen Verlauf zwischen den
 *    wenigen Palettenfarben** (z. B. Haar- und Hautfarben) – der Knopf kann
 *    jede Zwischenfarbe wählen, die Palettenfarben sind die Farbstützstellen.
 *  - [setSpectrum]: der Balken zeigt das komplette Farbspektrum (Hue 0–360°);
 *    der Knopf wählt nahtlos jede Farbe daraus.
 *
 * [onColorChanged] feuert live beim Verschieben. [setColor] setzt den Knopf
 * programmatisch (ohne Callback). Die Rechenfunktionen ([interpolatePalette],
 * [spectrumColor], [hueOf]) liegen ohne Android-Abhängigkeiten im Companion,
 * damit [ColorPaletteSliderTest] sie prüfen kann.
 */
class ColorPaletteSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Palettenfarben; null = komplettes Spektrum. */
    private var palette: List<Int>? = null

    /** Position des Knopfs 0..1. */
    private var progress = 0f

    /** Aktuell gewählte Farbe (auch zum Abgleich, ob setColor nötig ist). */
    var currentColor = 0xFF000000.toInt()
        private set

    /** Gefeuert beim Verschieben des Knopfs (live). */
    var onColorChanged: ((Int) -> Unit)? = null

    /** Radius des Knopfs: passt exakt in den Balken (Durchmesser = Balkenhöhe). */
    private val knobRadius: Float
        get() = height / 2f - knobRingPaint.strokeWidth / 2f

    private val barRect = RectF()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private var barShader: LinearGradient? = null
    private var barShaderWidth = -1

    init {
        barPaint.style = Paint.Style.FILL
    }

    /** Paletten-Modus: nahtloser Verlauf zwischen den [colors]. */
    fun setPalette(colors: List<Int>) {
        palette = colors
        barShader = null
        invalidate()
    }

    /** Spektrum-Modus: komplettes Farbspektrum. */
    fun setSpectrum() {
        palette = null
        barShader = null
        invalidate()
    }

    /** Setzt den Knopf auf [color] (programmtisch, ohne Callback). */
    fun setColor(color: Int) {
        currentColor = color
        progress = when (val p = palette) {
            null -> hueOf(color) / 360f
            else -> nearestPaletteIndex(p, color).toFloat() / (p.size - 1).coerceAtLeast(1)
        }.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barShader = null
        barShaderWidth = -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f
        val knobR = knobRadius
        // Der Balken läuft zwischen den Knopf-Mitten an den Rändern, damit der
        // Knopf an den Enden komplett sichtbar bleibt und exakt hineinpasst.
        val trackLeft = knobR
        val trackRight = (width - knobR).coerceAtLeast(trackLeft)
        barRect.set(trackLeft, 0f, trackRight, height.toFloat())
        // Balken: nahtloser Verlauf – Spektrum (Hue 0–360°) oder die
        // Palettenfarben als gleich verteilte Farbstützstellen.
        if (barShader == null || barShaderWidth != width) {
            val colors: IntArray
            val positions: FloatArray
            when (val p = palette) {
                null -> {
                    colors = IntArray(13) { i -> spectrumColor(i / 12f) }
                    positions = FloatArray(13) { i -> i / 12f }
                }
                else -> {
                    colors = p.toIntArray()
                    positions = if (p.size == 1) {
                        floatArrayOf(0f)
                    } else {
                        FloatArray(p.size) { i -> i.toFloat() / (p.size - 1) }
                    }
                }
            }
            barShader = LinearGradient(
                trackLeft, 0f, trackRight, 0f,
                colors, positions, Shader.TileMode.CLAMP
            )
            barShaderWidth = width
        }
        barPaint.shader = barShader
        canvas.drawRoundRect(barRect, radius, radius, barPaint)
        barPaint.shader = null

        // Knopf: farbige Scheibe mit weissem Ring, genau so gross wie der Balken.
        val knobX = trackLeft + progress * (trackRight - trackLeft)
        val knobY = height / 2f
        knobPaint.color = currentColor
        canvas.drawCircle(knobX, knobY, knobR, knobPaint)
        canvas.drawCircle(knobX, knobY, knobR, knobRingPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val knobR = knobRadius
                val trackLeft = knobR
                val trackRight = (width - knobR).coerceAtLeast(trackLeft)
                progress = ((event.x - trackLeft) / (trackRight - trackLeft).coerceAtLeast(1f))
                    .coerceIn(0f, 1f)
                currentColor = currentColorAt(progress)
                onColorChanged?.invoke(currentColor)
                invalidate()
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun currentColorAt(p: Float): Int = when (val colors = palette) {
        null -> spectrumColor(p)
        else -> interpolatePalette(p, colors)
    }

    companion object {
        /** Index des næchsten Paletten-Eintrags zu [color] (nach RGB-Abstand). */
        fun nearestPaletteIndex(colors: List<Int>, color: Int): Int {
            var best = 0
            var bestDist = Int.MAX_VALUE
            colors.forEachIndexed { index, c ->
                val dr = ((c shr 16) and 0xFF) - ((color shr 16) and 0xFF)
                val dg = ((c shr 8) and 0xFF) - ((color shr 8) and 0xFF)
                val db = (c and 0xFF) - (color and 0xFF)
                val dist = dr * dr + dg * dg + db * db
                if (dist < bestDist) {
                    bestDist = dist
                    best = index
                }
            }
            return best
        }

        /**
         * Farbe an der Knopf-Position im Paletten-Modus: lineare Interpolation
         * zwischen den benachbarten Palettenfarben (RGB je Kanal). Die
         * Palettenfarben selbst liegen an den Stützstellen 0, 1/(n-1), …, 1.
         */
        fun interpolatePalette(progress: Float, colors: List<Int>): Int {
            if (colors.isEmpty()) return 0xFF000000.toInt()
            if (colors.size == 1) return colors[0]
            val pos = progress.coerceIn(0f, 1f) * (colors.size - 1)
            val index = pos.toInt().coerceIn(0, colors.size - 2)
            val frac = pos - index
            val a = colors[index]
            val b = colors[index + 1]
            fun channel(shift: Int): Int = (
                ((a shr shift) and 0xFF) * (1f - frac) +
                    ((b shr shift) and 0xFF) * frac
                ).roundToInt()
            return 0xFF000000.toInt() or
                (channel(16) shl 16) or
                (channel(8) shl 8) or
                channel(0)
        }

        /**
         * Spektrum-Farbe zur Knopf-Position: Hue 0–360°, volle Sættigung und
         * Helligkeit (reines HSV, ohne android.graphics.Color gerechnet).
         */
        fun spectrumColor(progress: Float): Int {
            val hue = progress.coerceIn(0f, 1f) * 360f
            val c = hue / 60f
            val x = 1f - abs(c % 2f - 1f)
            val (r, g, b) = when {
                c < 1f -> Triple(1f, x, 0f)
                c < 2f -> Triple(x, 1f, 0f)
                c < 3f -> Triple(0f, 1f, x)
                c < 4f -> Triple(0f, x, 1f)
                c < 5f -> Triple(x, 0f, 1f)
                else -> Triple(1f, 0f, x)
            }
            return 0xFF000000.toInt() or
                ((r * 255f).roundToInt() shl 16) or
                ((g * 255f).roundToInt() shl 8) or
                (b * 255f).roundToInt()
        }

        /** Hue (0–360°) einer RGB-Farbe. */
        fun hueOf(color: Int): Float {
            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            if (d == 0f) return 0f
            val h = when (max) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            return ((h * 60f) % 360f + 360f) % 360f
        }
    }
}
