package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

/**
 * Dezenter Flaggen-Hintergrund einer Kachel der Rubbelkarte (Länder- und
 * EU-Kacheln). Die Flagge ist ein Emoji aus zwei Regional-Indicator-Zeichen;
 * die sichtbare Glyphe sitzt in der Zeilenbox der Emoji-Schrift nicht mittig,
 * deshalb zentriert eine TextView mit gravity="center" nur die Zeilenbox und
 * die Flagge erscheint sichtbar versetzt. Diese View zeichnet das Emoji selbst,
 * misst die Tinten-Bounds (Paint.getTextBounds) und zentriert die Flagge daran
 * exakt in der Mitte der Kachel. Die Größe skaliert sie so, dass die Flagge die
 * ganze Kachel ausfüllt und um [FILL_FACTOR] über den Rand hinausragt.
 */
class TileFlagView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Emoji der Flagge, z. B. "🇩🇪"; leer zeichnet die View nichts. */
    var flag: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }
    private val bounds = Rect()

    override fun onDraw(canvas: Canvas) {
        val f = flag
        if (f.isEmpty() || width == 0 || height == 0) return
        // Tinten-Bounds bei Referenzgröße messen; sie skalieren linear mit der Textgröße.
        paint.textSize = REFERENCE_TEXT_SIZE
        paint.getTextBounds(f, 0, f.length, bounds)
        val inkW = bounds.width().toFloat()
        val inkH = bounds.height().toFloat()
        if (inkW <= 0f || inkH <= 0f) return
        // So groß, dass die kleinere Tinten-Kante die Kachel mal FILL_FACTOR füllt;
        // bei Hochformat-Flaggen ragt sie dann seitlich über den Rand hinaus.
        val textSize = REFERENCE_TEXT_SIZE * minOf(width, height) * FILL_FACTOR / minOf(inkW, inkH)
        paint.textSize = textSize
        val scale = textSize / REFERENCE_TEXT_SIZE
        // Zeichenursprung so wählen, dass die Tinten-Mitte in der View-Mitte liegt.
        val x = width / 2f - bounds.exactCenterX() * scale
        val y = height / 2f - bounds.exactCenterY() * scale
        canvas.drawText(f, 0, f.length, x, y, paint)
    }

    companion object {
        /** Referenz-Textgröße für die Tinten-Messung. */
        private const val REFERENCE_TEXT_SIZE = 100f

        /** Faktor, um den die Flagge größer als die Kachel gezeichnet wird. */
        const val FILL_FACTOR = 1.35f
    }
}
