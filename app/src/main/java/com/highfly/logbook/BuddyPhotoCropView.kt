package com.highfly.logbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vorschau zum Zuschneiden eines Buddy-Fotos: das Bild liegt unter einem
 * festen Kreis in der Mitte ([circleRadius]), der Ausschnitt ist, der später
 * als runder Avatar gespeichert wird. Draußen herum ist das Bild abgedunkelt.
 *
 * Der Nutzer kann das Bild mit zwei Fingern zoomen (rein/raus) und mit einem
 * Finger verschieben; die Matrix wird dabei so begrenzt ([clampMatrix]), dass
 * der Kreis immer komplett vom Bild bedeckt bleibt und der Zoom nicht über
 * [MAX_ZOOM_FACTOR] hinausgeht. [cropBitmap] rechnet den sichtbaren Kreis
 * über die inverse Matrix zurück in Bildkoordinaten und liefert den
 * quadratischen Ausschnitt (auf [BuddyPhotos.OUTPUT_SIZE] skaliert).
 */
class BuddyPhotoCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null

    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val tmpRect = RectF()
    private val tmpPoints = FloatArray(2)

    /** Zoom-Grenze relativ zum Ausgangs-Zoom (Bild füllt die Ansicht). */
    private var minScale = 1f

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val overlayPath = Path().apply {
        fillType = Path.FillType.EVEN_ODD
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                imageMatrix.postScale(
                    detector.scaleFactor, detector.scaleFactor,
                    detector.focusX, detector.focusY
                )
                clampMatrix()
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                imageMatrix.postTranslate(-distanceX, -distanceY)
                clampMatrix()
                invalidate()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        }
    )

    /** Setzt das Ausgangsbild und setzt Zoom und Position zurück. */
    fun setImage(bmp: Bitmap) {
        bitmap = bmp
        resetMatrix()
        invalidate()
    }

    /** Mittelpunkt und Radius des Zuschnitt-Kreises in View-Koordinaten. */
    private fun circleCenterX() = width / 2f
    private fun circleCenterY() = height / 2f
    private fun circleRadius() = min(width, height) / 2f * CIRCLE_FRACTION

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrix()
    }

    /** Ausgangs-Zustand: Bild füllt die Ansicht komplett (Cover). */
    private fun resetMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = max(
            width / bmp.width.toFloat(),
            height / bmp.height.toFloat()
        )
        minScale = scale
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(
            (width - bmp.width * scale) / 2f,
            (height - bmp.height * scale) / 2f
        )
        clampMatrix()
    }

    /**
     * Begrenzt die Matrix: maximaler Zoom [MAX_ZOOM_FACTOR], und der Kreis
     * muss stets vollständig im Bild liegen - sonst wird das Bild passend
     * zurückgeschoben.
     */
    private fun clampMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        val maxScale = minScale * MAX_ZOOM_FACTOR
        if (scale > maxScale) {
            val factor = maxScale / scale
            imageMatrix.postScale(factor, factor, circleCenterX(), circleCenterY())
        }
        // Rechteck des Bildes in View-Koordinaten.
        tmpRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapRect(tmpRect)
        val r = circleRadius()
        val left = circleCenterX() - r
        val right = circleCenterX() + r
        val top = circleCenterY() - r
        val bottom = circleCenterY() + r
        if (tmpRect.left > left) imageMatrix.postTranslate(left - tmpRect.left, 0f)
        if (tmpRect.right < right) imageMatrix.postTranslate(right - tmpRect.right, 0f)
        if (tmpRect.top > top) imageMatrix.postTranslate(0f, top - tmpRect.top)
        if (tmpRect.bottom < bottom) imageMatrix.postTranslate(0f, bottom - tmpRect.bottom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) performClick()
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, imageMatrix, null)

        // Abgedunkelte Fläche außerhalb des Kreises.
        val r = circleRadius()
        overlayPath.reset()
        overlayPath.addRect(
            0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW
        )
        overlayPath.addCircle(circleCenterX(), circleCenterY(), r, Path.Direction.CW)
        canvas.drawPath(overlayPath, dimPaint)

        // Ring um den Zuschnitt.
        canvas.drawCircle(circleCenterX(), circleCenterY(), r, ringPaint)
    }

    /**
     * Liefert den sichtbaren Kreisausschnitt als quadratisches Bitmap auf
     * [BuddyPhotos.OUTPUT_SIZE] skaliert; null, wenn etwas schiefgeht.
     */
    fun cropBitmap(): Bitmap? {
        val bmp = bitmap ?: return null
        if (!imageMatrix.invert(inverseMatrix)) return null
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        if (scale <= 0f) return null

        // Kreis-Mittelpunkt zurück in Bildkoordinaten rechnen.
        tmpPoints[0] = circleCenterX()
        tmpPoints[1] = circleCenterY()
        inverseMatrix.mapPoints(tmpPoints)
        val radiusImg = circleRadius() / scale
        if (radiusImg < 1f) return null

        val side = (radiusImg * 2).roundToInt().coerceAtLeast(1)
        val left = (tmpPoints[0] - radiusImg).roundToInt().coerceIn(0, bmp.width - side)
        val top = (tmpPoints[1] - radiusImg).roundToInt().coerceIn(0, bmp.height - side)
        if (left + side > bmp.width || top + side > bmp.height) return null

        val cropped = Bitmap.createBitmap(bmp, left, top, side, side)
        val result = Bitmap.createScaledBitmap(
            cropped, BuddyPhotos.OUTPUT_SIZE, BuddyPhotos.OUTPUT_SIZE, true
        )
        if (cropped != result) cropped.recycle()
        return result
    }

    private companion object {
        /** Anteil des Zuschnitt-Kreises an der kleineren View-Kante. */
        const val CIRCLE_FRACTION = 0.8f

        /** Maximaler Zoom relativ zum Ausgangs-Zoom. */
        const val MAX_ZOOM_FACTOR = 4f
    }
}
