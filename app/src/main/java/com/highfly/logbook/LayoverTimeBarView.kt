package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.google.android.material.color.MaterialColors

/**
 * Horizontale Leiste der Layover-Detailseite: Spur und Füllung sehen aus wie
 * die Weltleiste der Rubbelkarte, dazu kommen Skalenstriche bei 25, 50 und
 * 75 Prozent und die Einheit der Skala rechts unter der Spur. Die Füllung
 * stammt aus [LayoverTimeScale]: Sie zeigt, wie weit die verbrachte Zeit in
 * der aktuellen Einheit läuft.
 */
class LayoverTimeBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density get() = resources.displayMetrics.density

    /** Dicke der Spur, wie bei der Rubbelkarte (trackThickness 6dp). */
    private val trackHeight = dp(6f)

    /** Eckenradius der Spur, wie bei der Rubbelkarte (trackCornerRadius 3dp). */
    private val cornerRadius = dp(3f)

    /** Breite der Skalenstriche bei 25, 50 und 75 Prozent. */
    private val tickWidth = dp(1.5f)

    /** Wie weit die Skalenstriche über die Spur hinausragen. */
    private val tickOverhang = dp(3f)

    /** Abstand zwischen Spur und Einheit rechts darunter. */
    private val labelGap = dp(6f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
        )
    }

    private val trackRect = RectF()

    /** Zielstand der Leiste, 0 bis 1. */
    private var ziel = 0f

    /** Gezeichneter Stand - während der Animation der laufende Wert. */
    private var gezeichnet = 0f

    /** Einheit der Skala, rechts unter der Spur. */
    private var stepLabel = ""

    private var animator: ValueAnimator? = null

    init {
        // Gleiche Töne wie bei der Rubbelkarte: Spur in der grauen Variante,
        // Füllung in der Primärfarbe des Themas.
        trackPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceVariant
        )
        progressPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary
        )
        tickPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOutline
        )
        labelPaint.color = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
    }

    /**
     * Füllt die Leiste für [hours] Stunden und schreibt [label] - die Einheit
     * der Skala, etwa "1 Woche" - rechts darunter. Steht die Leiste schon auf
     * demselben Wert, wird nur die Beschriftung neu gezeichnet.
     */
    fun setLayoverHours(hours: Int, label: String) {
        stepLabel = label
        val neu = LayoverTimeScale.fraction(hours)
        if (neu == ziel) {
            invalidate()
            return
        }
        ziel = neu
        animator?.cancel()
        animator = null
        if (!isAttachedToWindow) {
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

    /** Setzt den gezeichneten Stand und fordert die Neuzeichnung an. */
    private fun setGezeichnet(wert: Float) {
        gezeichnet = wert
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val labelHeight = labelPaint.fontMetrics.let { it.bottom - it.top }
        val height = (
            paddingTop + paddingBottom +
                tickOverhang * 2 + trackHeight + labelGap + labelHeight
            ).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val trackWidth = right - left
        if (trackWidth <= 0f) return
        val top = paddingTop + tickOverhang
        val bottom = top + trackHeight

        trackRect.set(left, top, right, bottom)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)

        val fillRight = left + trackWidth * gezeichnet
        if (fillRight > left) {
            canvas.drawRoundRect(
                left, top, fillRight, bottom,
                cornerRadius, cornerRadius, progressPaint
            )
        }

        // Skalenstriche bei 25, 50 und 75 Prozent. Sie liegen erst nach der
        // Füllung, damit sie auch über dem gefüllten Teil sichtbar bleiben.
        val half = tickWidth / 2f
        for (fraction in TICK_FRACTIONS) {
            val x = left + trackWidth * fraction
            canvas.drawRect(
                x - half, top - tickOverhang,
                x + half, bottom + tickOverhang,
                tickPaint
            )
        }

        if (stepLabel.isNotEmpty()) {
            val baseline = bottom + tickOverhang + labelGap - labelPaint.ascent()
            canvas.drawText(stepLabel, right, baseline, labelPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float = value * density

    private companion object {
        /** Skalenstriche auf der Leiste. */
        val TICK_FRACTIONS = listOf(0.25f, 0.5f, 0.75f)

        const val ANIMATION_MS = 450L
    }
}
