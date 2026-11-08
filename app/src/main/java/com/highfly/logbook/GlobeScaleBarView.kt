package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.max

/**
 * Maßband der Weltkugel ([GlobeView]), unten rechts auf der Seite: eine
 * Linie mit Endstrichen und der Entfernung, die sie abdeckt. Die Länge wird
 * bei jedem Zeichnen aus dem aktuellen Maßstab der Kugel neu gewählt (runde
 * Entfernung, siehe [ScaleBar.niceDistance]) und ändert sich so mit dem
 * Zoomen mit. Grundlage ist [GlobeView.metersPerPixelAtCenter].
 */
class GlobeScaleBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * Linker und unterer Rand in px. Der linke Rand der Box liegt immer bei
     * [leftInsetPx] (plus View-Rand): Der Maßstab wächst nach rechts, der
     * linke Rand bleibt fixiert - bündig mit dem Zurück-Knopf, dessen linke
     * Kante ebenfalls 8 dp vom Bildschirmrand liegt.
     */
    var leftInsetPx: Int = 0
    var bottomInsetPx: Int = 0

    private var metersPerPixel = 0.0

    private val density get() = resources.displayMetrics.density
    private val isDark: Boolean
        get() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, ScaleBarOverlay.SCALE_TEXT_DP, resources.displayMetrics
        )
        color = if (isDark) Color.rgb(230, 236, 242) else Color.rgb(40, 50, 60)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textPaint.color
        strokeWidth = 1.5f * density
    }

    /** Neuer Maßstab (Meter pro Pixel) - zeichnet nur bei Änderung neu. */
    fun update(value: Double) {
        if (!value.isFinite() || value <= 0.0) return
        if (value == metersPerPixel) return
        metersPerPixel = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mpp = metersPerPixel
        if (mpp <= 0.0 || width <= 0 || height <= 0) return

        val distance = ScaleBar.niceDistance(ScaleBarOverlay.MAX_BAR_DP * density * mpp)
        if (distance <= 0.0) return
        val barPx = (distance / mpp).toFloat()
        val label = ScaleBar.distanceText(distance, Settings.language(context))

        val pad = 4f * density
        val tick = 4f * density
        val labelWidth = textPaint.measureText(label)
        val fm = textPaint.fontMetrics

        // Linke Kante fixiert, die Box wächst nach rechts.
        val boxWidth = max(barPx, labelWidth) + 2f * pad
        val boxLeft = leftInsetPx.toFloat()
        val barY = height - ScaleBarOverlay.SCALE_MARGIN_DP * density - bottomInsetPx - pad
        val baseline = barY - tick - 2f * density - fm.descent

        // Ohne Hintergrund-Box: nur Text und Linie direkt auf der Karte.
        canvas.drawText(label, boxLeft + (boxWidth - labelWidth) / 2f, baseline, textPaint)

        val barLeft = boxLeft + (boxWidth - barPx) / 2f
        canvas.drawLine(barLeft, barY, barLeft + barPx, barY, barPaint)
        canvas.drawLine(barLeft, barY, barLeft, barY - tick, barPaint)
        canvas.drawLine(barLeft + barPx, barY, barLeft + barPx, barY - tick, barPaint)
    }
}
