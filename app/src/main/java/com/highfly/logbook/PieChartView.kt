package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.google.android.material.color.MaterialColors
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Slice(val label: String, val value: Int, val color: Int)

    private val slices = mutableListOf<Slice>()

    var showLegend: Boolean = true
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var sliceTouchEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                selectedIndex = -1
                animFraction = 0f
                explodeAnimator?.cancel()
                invalidate()
            }
        }

    private val density get() = resources.displayMetrics.density

    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val legendLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val legendValuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var labelColor = 0
    private var valueColor = 0

    private var sidePadding = 0f
    private var tilePadding = 0f
    private var topPadding = 0f
    private var legendTopGap = 0f
    private var legendRowHeight = 0f
    private var legendDotSize = 0f
    private var legendDotGap = 0f
    private var bottomPadding = 0f

    private val gapDegrees = 5f
    private val explodeDistance get() = dp(10)

    private val pieRect = RectF()
    private var pieCenterX = 0f
    private var pieCenterY = 0f
    private var pieRadius = 0f

    private var selectedIndex = -1
    private var animFraction = 0f
    private var explodeAnimator: ValueAnimator? = null

    init {
        labelColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface
        )
        valueColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant
        )

        topPadding = dp(8)
        sidePadding = dp(16)
        tilePadding = dp(8)
        legendTopGap = dp(16)
        legendRowHeight = dp(30)
        legendDotSize = dp(14)
        legendDotGap = dp(10)
        bottomPadding = dp(8)

        legendLabelPaint.textSize = sp(14)
        legendLabelPaint.isFakeBoldText = true
        legendValuePaint.textSize = sp(13)
        legendValuePaint.textAlign = Paint.Align.RIGHT
    }

    fun setSlices(data: List<Slice>) {
        slices.clear()
        slices.addAll(data)
        selectedIndex = -1
        animFraction = 0f
        explodeAnimator?.cancel()
        requestLayout()
        invalidate()
    }

    private fun reservedPadding(): Float =
        if (showLegend) sidePadding else tilePadding

    private fun pieSize(): Float {
        val padding = reservedPadding()
        val pie = width.toFloat() - 2 * (padding + explodeDistance)
        return pie.coerceAtLeast(0f)
    }

    private fun layoutPie() {
        val padding = reservedPadding()
        val pie = pieSize()
        val x = padding + explodeDistance
        val y = if (showLegend) topPadding + explodeDistance
        else padding + explodeDistance
        pieRect.set(x, y, x + pie, y + pie)
        pieCenterX = pieRect.centerX()
        pieCenterY = pieRect.centerY()
        pieRadius = pie / 2f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val padding = reservedPadding()
        val pie = (width.toFloat() - 2 * (padding + explodeDistance)).coerceAtLeast(0f)
        val height = if (!showLegend) {
            width
        } else if (slices.isEmpty()) {
            topPadding.toInt()
        } else {
            (topPadding + explodeDistance + pie + legendTopGap +
                slices.size * legendRowHeight + bottomPadding).toInt()
        }
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutPie()
    }

    override fun onDraw(canvas: Canvas) {
        if (slices.isEmpty()) return

        layoutPie()

        val width = width.toFloat()
        val total = slices.sumOf { it.value }.takeIf { it > 0 } ?: return
        val explodeAmount = explodeDistance * animFraction

        var start = -90f
        slices.forEachIndexed { index, slice ->
            val sweep = 360f * slice.value / total
            if (sweep <= 0f) return@forEachIndexed

            val arcStart = start + gapDegrees / 2f
            val arcSweep = sweep - gapDegrees

            slicePaint.color = slice.color
            if (explodeAmount > 0f && index == selectedIndex) {
                val radians = Math.toRadians((start + sweep / 2f).toDouble())
                val dx = (cos(radians) * explodeAmount).toFloat()
                val dy = (sin(radians) * explodeAmount).toFloat()
                canvas.drawArc(
                    RectF(
                        pieRect.left + dx,
                        pieRect.top + dy,
                        pieRect.right + dx,
                        pieRect.bottom + dy
                    ),
                    arcStart,
                    arcSweep,
                    true,
                    slicePaint
                )
            } else {
                canvas.drawArc(pieRect, arcStart, arcSweep, true, slicePaint)
            }
            start += sweep
        }

        if (showLegend) {
            val legendTop = pieRect.bottom + legendTopGap
            slices.forEachIndexed { index, slice ->
                val centerY = legendTop + index * legendRowHeight + legendRowHeight / 2f

                slicePaint.color = slice.color
                val dotLeft = sidePadding
                val dotTop = centerY - legendDotSize / 2f
                canvas.drawRoundRect(
                    dotLeft,
                    dotTop,
                    dotLeft + legendDotSize,
                    dotTop + legendDotSize,
                    legendDotSize / 2f,
                    legendDotSize / 2f,
                    slicePaint
                )

                legendLabelPaint.color = labelColor
                val baseline =
                    centerY - (legendLabelPaint.ascent() + legendLabelPaint.descent()) / 2f
                canvas.drawText(
                    slice.label,
                    dotLeft + legendDotSize + legendDotGap,
                    baseline,
                    legendLabelPaint
                )

                legendValuePaint.color = valueColor
                val percent = Math.round(100f * slice.value / total)
                val valueText = String.format(
                    Locale.GERMANY, "%d (%d %%)", slice.value, percent
                )
                canvas.drawText(valueText, width - sidePadding, baseline, legendValuePaint)
            }
        }
    }

    private fun findSliceAt(x: Float, y: Float): Int {
        if (slices.size <= 1) return 0

        val dx = x - pieCenterX
        val dy = y - pieCenterY
        if (hypot(dx, dy) > pieRadius) return -1

        val rawAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val angle = ((rawAngle + 90f) % 360f + 360f) % 360f

        val total = slices.sumOf { it.value }.takeIf { it > 0 } ?: return -1
        var start = 0f
        slices.forEachIndexed { index, slice ->
            val sweep = 360f * slice.value / total
            val from = start + gapDegrees / 2f
            val to = start + sweep - gapDegrees / 2f
            if (angle >= from && angle < to) return index
            start += sweep
        }
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!sliceTouchEnabled) return super.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val index = findSliceAt(event.x, event.y)
            if (index != -1) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                selectSlice(if (selectedIndex == index) -1 else index)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun selectSlice(index: Int) {
        explodeAnimator?.cancel()
        explodeAnimator = ValueAnimator.ofFloat(
            animFraction, if (index >= 0) 1f else 0f
        ).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dp(value: Int): Float = value * density

    private fun sp(value: Int): Float =
        value * density * minOf(resources.configuration.fontScale, 1.0f)
}