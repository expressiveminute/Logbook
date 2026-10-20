package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

/**
 * Balken der Höhenstrahlung-Kachel: Anteil der Jahresdosis am Grenzwert von
 * 20 mSv ([RadiationCalculator.ANNUAL_LIMIT_USV]). Die Prozentzahl steht ab
 * [LABEL_INSIDE_MIN_PERCENT] im ausgefüllten Balken, darunter rechts daneben
 * auf der Spur - so ist sie bei kleinem Anteil nicht im schmalen Stück
 * versteckt. Über dem Grenzwert ([overLimit]) ist die Füllung rot.
 *
 * Die Prozentzahl wird als contentDescription gesetzt, damit sie auch ohne
 * Blick auf den Balken angesagt wird.
 */
class RadiationLimitBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Anteil am Jahresgrenzwert in Prozent; über 100 wird der Balken voll. */
    var percent: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /** true bei Überschreitung des Grenzwerts: Füllung rot. */
    var overLimit: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val primaryColor = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorPrimary
    )
    private val limitColor = ContextCompat.getColor(context, R.color.radiation_limit)
    private val onSurfaceVariant = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurfaceVariant
    )

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MaterialColors.getColor(
            this@RadiationLimitBar, com.google.android.material.R.attr.colorSurfaceVariant
        )
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics
        )
    }
    private val trackRect = RectF()
    private val fillRect = RectF()

    /** Abstand der Zahl rechts neben der Füllung, wenn sie nicht hineinpasst. */
    private val labelGapPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = h / 2f
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        // Füllung, gedeckelt bei 100 % - ein höherer Prozentwert steht nur
        // in der Zahl.
        val fillWidth = w * percent.coerceIn(0, 100) / 100f
        fillPaint.color = if (overLimit) limitColor else primaryColor
        if (fillWidth > 0f) {
            fillRect.set(0f, 0f, fillWidth, h)
            val fillRadius = minOf(radius, fillWidth / 2f)
            canvas.drawRoundRect(fillRect, fillRadius, fillRadius, fillPaint)
        }
        val label = context.getString(R.string.radiation_limit_percent, percent)
        val metrics = textPaint.fontMetrics
        val baseline = h / 2f - (metrics.ascent + metrics.descent) / 2f
        if (labelInsideBar(percent)) {
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(label, fillWidth / 2f, baseline, textPaint)
        } else {
            textPaint.color = onSurfaceVariant
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, fillWidth + labelGapPx, baseline, textPaint)
        }
    }

    companion object {
        /** Ab diesem Prozent steht die Zahl im Balken statt daneben. */
        const val LABEL_INSIDE_MIN_PERCENT = 50

        /** true, wenn die Prozentzahl in den ausgefüllten Balken gehört. */
        fun labelInsideBar(percent: Int): Boolean = percent >= LABEL_INSIDE_MIN_PERCENT
    }
}
