package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Item(val label: String, val count: Int)

    private val items = mutableListOf<Item>()

    private val density get() = resources.displayMetrics.density
    private val scaledDensity get() = resources.displayMetrics.scaledDensity

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var barColor = 0
    private var barLabelColor = 0
    private var countColor = 0

    private var topPadding = 0f
    private var countSpace = 0f
    private var rowHeight = 0f
    private var gap = 0f
    private var bottomPadding = 0f
    private var sidePadding = 0f
    private var innerPadding = 0f

    init {
        barColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        barLabelColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnPrimary
        )
        countColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant
        )

        topPadding = dp(8)
        countSpace = dp(18)
        rowHeight = dp(44)
        gap = dp(10)
        bottomPadding = dp(8)
        sidePadding = dp(16)
        innerPadding = dp(10)

        labelPaint.textSize = sp(14)
        labelPaint.isFakeBoldText = true
        countPaint.textSize = sp(12)
        countPaint.isFakeBoldText = true
    }

    fun setItems(data: List<Item>) {
        items.clear()
        items.addAll(data)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val height = if (items.isEmpty()) {
            topPadding.toInt()
        } else {
            (topPadding +
                items.size * (countSpace + rowHeight) +
                (items.size - 1) * gap +
                bottomPadding).toInt()
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return

        val width = width.toFloat()
        val usable = width - 2 * sidePadding
        val maxCount = items.maxOf { it.count }
        val pill = rowHeight / 2f

        val barRect = RectF()

        items.forEachIndexed { index, item ->
            val blockTop = topPadding + index * (countSpace + rowHeight + gap)
            val barTop = blockTop + countSpace
            val barBottom = barTop + rowHeight
            val barWidth = usable * item.count / maxCount.toFloat()
            val barRight = sidePadding + barWidth

            countPaint.color = countColor
            val countBaseline = blockTop + countSpace - dp(3)
            canvas.drawText(item.count.toString(), sidePadding, countBaseline, countPaint)

            barRect.set(sidePadding, barTop, barRight, barBottom)
            barPaint.color = barColor
            canvas.drawRoundRect(barRect, pill, pill, barPaint)

            labelPaint.color = barLabelColor
            val availableLabel = barWidth - 2 * innerPadding
            if (availableLabel > 0) {
                val label = if (labelPaint.measureText(item.label) > availableLabel) {
                    TextUtils.ellipsize(
                        item.label, labelPaint, availableLabel, TextUtils.TruncateAt.END
                    ).toString()
                } else {
                    item.label
                }
                val baseline = barTop + (rowHeight -
                    (labelPaint.descent() - labelPaint.ascent())) / 2f - labelPaint.ascent()
                canvas.drawText(
                    label,
                    sidePadding + innerPadding,
                    baseline,
                    labelPaint
                )
            }
        }
    }

    private fun dp(value: Int): Float = value * density

    private fun sp(value: Int): Float = value * scaledDensity
}