package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import java.util.Locale

class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Slice(val label: String, val value: Int, val color: Int)

    private val slices = mutableListOf<Slice>()

    private val density get() = resources.displayMetrics.density
    private val scaledDensity get() = resources.displayMetrics.scaledDensity

    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val legendLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val legendValuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var labelColor = 0
    private var valueColor = 0

    private var sidePadding = 0f
    private var topPadding = 0f
    private var legendTopGap = 0f
    private var legendRowHeight = 0f
    private var legendDotSize = 0f
    private var legendDotGap = 0f
    private var bottomPadding = 0f

    private val pieRect = RectF()

    init {
        labelColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        valueColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant
        )

        topPadding = dp(8)
        sidePadding = dp(16)
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
        requestLayout()
        invalidate()
    }

    private fun pieSize(): Float {
        val width = width.toFloat()
        return width - 2 * sidePadding
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val pie = width.toFloat() - 2 * sidePadding
        val height = if (slices.isEmpty()) {
            topPadding.toInt()
        } else {
            (topPadding + pie + legendTopGap +
                slices.size * legendRowHeight + bottomPadding).toInt()
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (slices.isEmpty()) return

        val width = width.toFloat()
        val pie = pieSize()
        val total = slices.sumOf { it.value }.takeIf { it > 0 } ?: return

        pieRect.set(sidePadding, topPadding, sidePadding + pie, topPadding + pie)

        if (slices.size == 1) {
            slicePaint.color = slices[0].color
            canvas.drawArc(pieRect, 0f, 360f, true, slicePaint)
        } else {
            val gapAngle = 3f
            var start = -90f
            slices.forEach { slice ->
                val sweep = 360f * slice.value / total
                slicePaint.color = slice.color
                canvas.drawArc(
                    pieRect,
                    start + gapAngle / 2f,
                    (sweep - gapAngle).coerceAtLeast(0f),
                    true,
                    slicePaint
                )
                start += sweep
            }
        }

        val legendTop = topPadding + pie + legendTopGap
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
                dp(4),
                dp(4),
                slicePaint
            )

            legendLabelPaint.color = labelColor
            val baseline = centerY - (legendLabelPaint.ascent() + legendLabelPaint.descent()) / 2f
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

    private fun dp(value: Int): Float = value * density

    private fun sp(value: Int): Float = value * scaledDensity
}