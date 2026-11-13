package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Kleine Analoguhr fuer die Bordkarte: Beim Start drehen sich beide Zeiger
 * einmal komplett (360°) und bleiben dann stehen. Die Farbe der Zeiger und des
 * Zifferblatts wird von der Seite gesetzt ([setColors]), damit sie auf dem
 * Hintergrund der Bordkarte lesbar ist.
 *
 * Wie die übrigen Animationen der App pausiert die Uhr, wenn die Seite in den
 * Hintergrund geht, und zeichnet sofort statisch, wenn der Nutzer
 * Systemanimationen abgeschaltet hat.
 */
class LayoverClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        val d = resources.displayMetrics.density
        facePaint.strokeWidth = 1.5f * d
        handPaint.strokeWidth = 2.5f * d
    }

    /** Drehwinkel der Zeiger in Grad; 0 = beide zeigen nach oben. */
    private var handAngle = 0f

    private var animator: ValueAnimator? = null
    private var playing = false

    fun setColors(faceColor: Int, handColor: Int) {
        facePaint.color = faceColor
        handPaint.color = handColor
        dotPaint.color = handColor
        invalidate()
    }

    /** Startet die einmalige Drehung der Zeiger. */
    fun playOnce() {
        stop()
        if (animationsDisabled()) {
            handAngle = 360f
            invalidate()
            return
        }
        playing = true
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = HAND_SPIN_MS
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                handAngle = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    playing = false
                    handAngle = 360f
                    invalidate()
                }
            })
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        playing = false
    }

    private fun animationsDisabled(): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE && playing) {
            // Pausiert: Winkel bleibt stehen, die Uhr zeichnet beim
            // Sichtbarwerden weiter.
            animator?.pause()
        } else if (visibility == VISIBLE && playing) {
            animator?.resume()
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - facePaint.strokeWidth / 2f - 1f
        if (radius <= 0f) return
        canvas.drawCircle(cx, cy, radius, facePaint)
        // Beide Zeiger drehen sich gemeinsam einmal herum; der "Stundenzeiger"
        // ist kürzer.
        val minuteAngle = Math.toRadians(handAngle.toDouble())
        val hourAngle = Math.toRadians(handAngle.toDouble() / 12.0)
        canvas.drawLine(
            cx, cy,
            cx + (radius * 0.5f * Math.sin(hourAngle)).toFloat(),
            cy - (radius * 0.5f * Math.cos(hourAngle)).toFloat(),
            handPaint
        )
        canvas.drawLine(
            cx, cy,
            cx + (radius * 0.8f * Math.sin(minuteAngle)).toFloat(),
            cy - (radius * 0.8f * Math.cos(minuteAngle)).toFloat(),
            handPaint
        )
        canvas.drawCircle(cx, cy, radius * 0.07f, dotPaint)
    }

    companion object {
        /** Dauer einer vollen Zeigerumdrehung. */
        const val HAND_SPIN_MS = 2400L
    }
}
