package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/**
 * Zeigt das mitgelieferte Satellitenbild eines Flughafens als Kartenausschnitt
 * in echtem Maßstab. Das Bild deckt seine Randkoordinaten (WGS84) ab und wird
 * so in die Ansicht gelegt, dass der Flughafen – die Mitte des Ausschnitts –
 * in der Bildmitte sitzt. Die Lage folgt der Web-Mercator-Projektion, damit
 * das Bild nicht verzerrt wird; in der Mitte sitzt eine Nadel als Kennzeichen.
 *
 * Die Ansicht dient nur der Anzeige; es gibt hier nichts zu bedienen.
 */
class AirportSatelliteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var image: AirportSatelliteImage? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val strokeWidthPx = 2f * resources.displayMetrics.density
    private val markerRadiusPx = 5f * resources.displayMetrics.density

    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
    }

    // Einmal angelegt und im Zeichnen nur neu gesetzt, damit `onDraw` nichts
    // anlegt (DrawAllocation).
    private val sourceRect = Rect()
    private val targetRect = RectF()

    /** Setzt das anzuzeigende Bild; ohne Bild bleibt die Ansicht leer. */
    fun setImage(value: AirportSatelliteImage?) {
        image = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = image ?: return
        if (width == 0 || height == 0 || current.bitmap.isRecycled) return

        // Randkoordinaten in Web-Mercator (Bogenmaß): x = Länge, y = Mercator-
        // Breite. So stimmen Bild- und Ansichtsmaßstab überein.
        val minX = current.minLon * PI / 180.0
        val maxX = current.maxLon * PI / 180.0
        val minY = mercatorY(current.minLat)
        val maxY = mercatorY(current.maxLat)
        val rectW = maxX - minX
        val rectH = maxY - minY
        if (rectW <= 0.0 || rectH <= 0.0) return

        // "cover": das Bild füllt die Ansicht, der Flughafen bleibt mittig.
        val scale = max(width / rectW, height / rectH)
        val drawnW = (rectW * scale).toFloat()
        val drawnH = (rectH * scale).toFloat()
        val left = (width - drawnW) / 2f
        val top = (height - drawnH) / 2f
        val src = sourceRect.apply { set(0, 0, current.bitmap.width, current.bitmap.height) }
        val dst = targetRect.apply { set(left, top, left + drawnW, top + drawnH) }
        canvas.drawBitmap(current.bitmap, src, dst, bitmapPaint)

        // Nadel in der Mitte: der Ausschnitt ist um den Flughafen gebaut.
        canvas.drawCircle(width / 2f, height / 2f, markerRadiusPx, markerFill)
        canvas.drawCircle(width / 2f, height / 2f, markerRadiusPx, markerRing)
    }

    /** Mercator-Breite in Bogenmaß, wie bei der Web-Mercator-Projektion. */
    private fun mercatorY(lat: Double): Double =
        ln(tan(PI / 4.0 + lat * PI / 360.0))
}
