package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.google.android.material.color.MaterialColors
import java.text.NumberFormat
import kotlin.math.min

/**
 * Ring in der Kontinent-Kachel: der volle Kreis steht für alle Länder des
 * Kontinents, der farbige Bogen für die davon besuchten. In der Mitte steht
 * der Anteil in Prozent, darunter - in der Kachel selbst - die absolute Zahl
 * wie 10 von 40.
 *
 * Die Kachel gibt dem Ring festen Anteil an der Höhe, deshalb misst er sich
 * quadratisch. Ein nichtquadratisches Feld wäre aber kein Fehler: gezeichnet
 * wird immer ein Kreis in die kleinere der beiden Seiten.
 */
class ContinentProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density get() = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val percentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val arcRect = RectF()

    /** Anteil, der am Ende dastehen soll. */
    private var ziel = 0f

    /** Anteil, der gerade gezeichnet wird - während der Animation unterwegs. */
    private var gezeichnet = 0f

    private var animator: ValueAnimator? = null

    /**
     * Prozentzahl in der Sprache der App: deutsch mit Leerzeichen vor dem
     * Prozentzeichen, englisch ohne. Ohne Nachkommastellen, sonst stünde bei
     * einem Land von fünfundfünfzig "1,8 %" im Ring.
     */
    private val prozentFormat: NumberFormat =
        NumberFormat.getPercentInstance(resources.configuration.locales[0]).apply {
            maximumFractionDigits = 0
        }

    /**
     * Prozentzahl zu [gezeichnet]. Wird mitgeführt statt in [onDraw] gebildet,
     * weil das Umrechnen während der Animation 25mal je Sekunde passierte.
     * Startet bei 0, damit ein Kontinent ohne Besuch nicht leer bleibt.
     */
    private var prozentText = prozentFormat.format(0f)

    /**
     * Normale Schriftgrösse der Prozentzahl, aus den Ressourcen: Im Querformat
     * ist die Kachel kleiner und die Zahl wächst mit. Die Systemschrift wird
     * bewusst nicht begrenzt - passt die Zahl nicht mehr in den Ring, verkleinert
     * [passendeSchriftgroesse] sie ohnehin.
     */
    private val normaleSchriftgroesse: Float =
        resources.getDimension(R.dimen.continent_tile_ring_text)

    init {
        // Die Spur ist der Nenner, sie muss sich auch im hellen Theme vom
        // Kartenhintergrund abheben: colorOutlineVariant wäre dort zu blass.
        trackPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOutline
        )
        // Nicht colorPrimary: der ist im hellen Thema ein heller Tan und auf
        // der hellen Kachel nicht zu sehen. Der Ton aus dem Container-Paar
        // ist in beiden Themes kräftig genug.
        progressPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnPrimaryContainer
        )
        percentPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface
        )
        percentPaint.textSize = normaleSchriftgroesse
    }

    /**
     * Setzt den Anteil der besuchten Länder, 0 bis 1. [animate] lässt den Ring
     * von seinem bisherigen Wert auf den neuen laufen, damit ein neu
     * hinzugekommenes Land sichtbar wird.
     */
    fun setProgress(fraction: Float, animate: Boolean = true) {
        val neu = fraction.coerceIn(0f, 1f)
        // Der gleiche Wert noch einmal zu setzen würde den Ring bei jedem
        // Aufruf der Seite neu hochzählen.
        if (neu == ziel) return
        ziel = neu
        animator?.cancel()
        animator = null
        if (!animate || !isAttachedToWindow) {
            setGezeichnet(neu)
            return
        }
        animator = ValueAnimator.ofFloat(gezeichnet, neu).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { setGezeichnet(it.animatedValue as Float) }
            start()
        }
    }

    /** Setzt den gezeichneten Anteil samt seiner Beschriftung. */
    private fun setGezeichnet(wert: Float) {
        gezeichnet = wert
        prozentText = prozentFormat.format(wert)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        // Der Ring ist rund: Er passt sich der Breite an, ausser die Kachel
        // gibt ihm eine bestimmte Höhe vor, dann gewinnt die kleinere Seite.
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val height = when (mode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(MeasureSpec.getSize(heightMeasureSpec), width)
            else -> width
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        // Dicke im Verhältnis zur Kachel: Bei sehr kleinen Kacheln würde ein
        // fester Wert den Ring auffressen.
        val dicke = min(dp(8f), min(width, height) * 0.14f)
        trackPaint.strokeWidth = dicke
        progressPaint.strokeWidth = dicke
        val radius = min(width, height) / 2f - dicke / 2f
        if (radius <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawCircle(cx, cy, radius, trackPaint)
        val sweep = 360f * gezeichnet
        // Bei 0 Grad zeichnet ein runder Pinsel je nach Version einen Punkt -
        // das wäre ein Fortschritt, den es nicht gibt.
        if (sweep > 0f) canvas.drawArc(arcRect, START_WINKEL, sweep, false, progressPaint)
        percentPaint.textSize = passendeSchriftgroesse(radius, dicke)
        canvas.drawText(
            prozentText,
            cx,
            cy - (percentPaint.ascent() + percentPaint.descent()) / 2f,
            percentPaint
        )
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    /**
     * Verkleinert die Prozentzahl, falls sie nicht mehr in den Ring passt. Bei
     * "100 %" in einer kleinen Kachel würde sie sonst über den Rand hinausragen.
     */
    private fun passendeSchriftgroesse(radius: Float, dicke: Float): Float {
        val breite = percentPaint.measureText(prozentText)
        val platz = 2f * (radius - dicke)
        if (breite <= platz || breite <= 0f) return normaleSchriftgroesse
        return normaleSchriftgroesse * platz / breite
    }

    private fun dp(value: Float): Float = value * density

    private companion object {
        /** Der Bogen beginnt oben und läuft im Uhrzeigersinn. */
        const val START_WINKEL = -90f

        const val ANIMATION_MS = 450L
    }
}
