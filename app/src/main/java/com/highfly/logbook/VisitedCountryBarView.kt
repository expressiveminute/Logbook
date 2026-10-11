package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Diskreter Balken im Hintergrund einer Länderzeile: Er füllt die Zeile von
 * links und zeigt so, wie oft das Land angeflogen wurde, gemessen am
 * meistbesuchten Land der Liste. Die Füllung bleibt leise - niedrige Deckkraft
 * in der Primärfarbe -, damit die Flagge, der Name und die Flugzahl darüber
 * ungestört lesbar bleiben.
 *
 * Die Fraktion wird über [setFraction] gesetzt, 0 bis 1 (Anteil am Maximum).
 */
class VisitedCountryBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MaterialColors.getColor(
            this@VisitedCountryBarView, com.google.android.material.R.attr.colorPrimary
        )
        alpha = FILL_ALPHA
    }
    private val bounds = RectF()

    /** Anteil der Zeilenbreite, den die Füllung einnimmt, 0 bis 1. */
    private var fraction = 0f

    /** Setzt den Anteil an der Zeilenbreite; Werte außerhalb werden begrenzt. */
    fun setFraction(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (fraction == next) return
        fraction = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fraction <= 0f || width <= 0 || height <= 0) return
        val right = width * fraction
        bounds.set(0f, 0f, right, height.toFloat())
        canvas.drawRoundRect(bounds, height / 2f, height / 2f, fillPaint)
    }

    private companion object {
        /** Deckkraft der Füllung im Hintergrund. */
        const val FILL_ALPHA = 45
    }
}