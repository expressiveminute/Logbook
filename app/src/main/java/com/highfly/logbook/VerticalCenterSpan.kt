package com.highfly.logbook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.style.ReplacementSpan
import kotlin.math.ceil

/**
 * Zeichnet den Routenpfeil samt seiner umgebenden Leerzeichen so, dass seine
 * sichtbare Mitte auf der senkrechten Mitte der Textzeile liegt. Der Pfeil-Glyph
 * sitzt in manchen Schriften tief in seiner Box; ohne den Span rutscht er
 * dadurch zwischen den Flughafen-Codes ein Stück nach unten. Die waagrechten
 * Abstände bleiben dabei genau die der normalen Schrift, der Span verschiebt
 * den Text nur nach oben.
 */
class VerticalCenterSpan(
    private val text: String = ARROW
) : ReplacementSpan() {

    private val bounds = Rect()

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        // Die Breite ist der echte Vorlauf des Textes (inklusive der
        // umgebenden Leerzeichen), nicht nur die Tintenbreite des Pfeils:
        // Sonst wäre der Abstand links und rechts ungleich, weil der Pfeil
        // innerhalb seines Vorlaufs nicht mittig sitzt.
        return ceil(paint.measureText(this.text).toDouble()).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        paint.getTextBounds(this.text, 0, this.text.length, bounds)
        // Senkrechte Mitte der Zeile: halber Abstand zwischen oberster und
        // unterster Kante der umliegenden Schrift.
        val lineCenter = (top + bottom) / 2f
        // Dort läge die Mitte des Pfeils, wenn er an der normalen Grundlinie
        // (y) stünde; daraus ergibt sich die Verschiebung nach oben.
        val glyphCenter = y + (bounds.top + bounds.bottom) / 2f
        canvas.drawText(this.text, x, y + (lineCenter - glyphCenter), paint)
    }

    private companion object {
        const val ARROW = "→"
    }
}