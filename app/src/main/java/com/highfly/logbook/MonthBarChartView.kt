package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Senkrechtes Balkendiagramm: die Kategorien (z. B. Monate) liegen auf der
 * x-Achse, die Werte werden als Balken gegen die y-Achse aufgetragen.
 */
class MonthBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Item(val label: String, val count: Int)

    private val items = mutableListOf<Item>()

    private val density get() = resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val monthPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val axisLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
    }

    private var barColor = 0
    private var labelColor = 0
    private var axisColor = 0
    private var gridColor = 0

    private var chartHeight = 0f
    private var topPadding = 0f
    private var bottomPadding = 0f
    private var leftPadding = 0f
    private var rightPadding = 0f
    private var leadingSpace = 0f
    private var columnWidthDp = HORIZONTAL_ITEM_WIDTH_DP

    private var axisMax = 1
    private var axisStep = 1
    private var contentVisible = true

    init {
        barColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        labelColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        axisColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOutline
        )
        gridColor = (axisColor and 0x00FFFFFF) or (0x33 shl 24)

        chartHeight = dp(200)
        topPadding = dp(24)
        bottomPadding = dp(28)
        leftPadding = dp(32)
        rightPadding = dp(8)

        monthPaint.textSize = sp(11)
        monthPaint.color = labelColor
        valuePaint.textSize = sp(11)
        valuePaint.isFakeBoldText = true
        valuePaint.color = labelColor
        axisLabelPaint.textSize = sp(10)
        axisLabelPaint.color = labelColor

        gridPaint.strokeWidth = dp(1)
        gridPaint.color = gridColor
        axisPaint.strokeWidth = dp(1)
        axisPaint.color = axisColor
    }

    fun setItems(data: List<Item>) {
        items.clear()
        items.addAll(data)
        requestLayout()
        invalidate()
    }

    fun setLeadingSpace(widthPx: Int) {
        val newSpace = widthPx.toFloat().coerceAtLeast(0f)
        if (leadingSpace == newSpace) return
        leadingSpace = newSpace
        requestLayout()
        invalidate()
    }

    fun setContentVisible(visible: Boolean) {
        if (contentVisible == visible) return
        contentVisible = visible
        invalidate()
    }

    /** Mindestbreite einer Spalte; breitere Labels (z. B. Flugzeugtypen)
     * verbreitern die Spalte automatisch. */
    fun setColumnWidthDp(value: Int) {
        val newWidth = value.coerceAtLeast(1)
        if (columnWidthDp == newWidth) return
        columnWidthDp = newWidth
        requestLayout()
        invalidate()
    }

    fun setPlotHeight(heightPx: Int) {
        chartHeight = heightPx.toFloat().coerceAtLeast(dp(200))
        requestLayout()
        invalidate()
    }

    /**
     * Kleinere Beschriftung der x-Achse. Noetig bei Diagrammen mit vielen
     * schmalen Spalten (z. B. 20 Stunden-Balken), in denen die Spaltenbreite
     * nicht mehr von der Textbreite bestimmt wird.
     */
    fun setCategoryTextSizeSp(value: Int) {
        val size = sp(value)
        if (monthPaint.textSize == size) return
        monthPaint.textSize = size
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        if (items.isEmpty()) {
            val width = if (widthMode == MeasureSpec.UNSPECIFIED) {
                suggestedMinimumWidth
            } else {
                MeasureSpec.getSize(widthMeasureSpec)
            }
            setMeasuredDimension(width, 0)
            return
        }

        updateAxis()
        val widest = (0..axisMax step axisStep)
            .maxOf { axisLabelPaint.measureText(it.toString()) }
        leftPadding = maxOf(widest + dp(8), leadingSpace)

        val widestLabel = items.maxOf { monthPaint.measureText(it.label) }
        val columnWidth = maxOf(dp(columnWidthDp), widestLabel + dp(12))

        val contentWidth =
            (items.size * columnWidth + leftPadding + rightPadding).toInt()
        val width = if (widthMode == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth.coerceAtLeast(contentWidth)
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }

        val height = (topPadding + chartHeight + bottomPadding).toInt()
        setMeasuredDimension(width, height)
    }

    private fun updateAxis() {
        val rawMax = items.maxOf { it.count }
        axisStep = niceStep(rawMax)
        axisMax = if (rawMax <= 0) axisStep else
            ceil(rawMax / axisStep.toDouble()).toInt() * axisStep
    }

    private fun niceStep(rawMax: Int): Int {
        if (rawMax <= 4) return 1
        val rough = rawMax / 4.0
        val exponent = floor(log10(rough)).toInt()
        val base = 10.0.pow(exponent)
        val normalized = rough / base
        val nice = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return (nice * base).toInt().coerceAtLeast(1)
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return

        val baseline = topPadding + chartHeight
        val chartTop = topPadding
        val axisX = if (contentVisible) leftPadding else width - dp(1)
        val tickLabelX = if (contentVisible) axisX - dp(6) else axisX - dp(2)
        val plotRight = width - rightPadding
        val plotWidth = (plotRight - axisX).coerceAtLeast(0f)
        val slotWidth = plotWidth / items.size

        for (tick in 0..axisMax step axisStep) {
            val y = baseline - chartHeight * tick / axisMax.toFloat()
            if (contentVisible) {
                canvas.drawLine(axisX, y, plotRight, y, gridPaint)
            }
            canvas.drawText(
                tick.toString(),
                tickLabelX,
                centeredBaseline(y, axisLabelPaint),
                axisLabelPaint
            )
        }

        // Die y-Achse wird in beiden Modi gezeichnet: Die scrollende Instanz
        // zeichnet sie am Diagramm, die ueberlagerte Achsenmaske am festen
        // linken Rand, damit der Strich beim Scrollen nicht mitwandert. Die
        // x-Achse gehoert dagegen zum Inhalt und wird nur im Diagramm gezeichnet.
        canvas.drawLine(axisX, chartTop, axisX, baseline, axisPaint)

        if (!contentVisible) return

        canvas.drawLine(axisX, baseline, plotRight, baseline, axisPaint)

        val barWidth = slotWidth * 0.56f
        val radius = dp(4)
        val rect = RectF()

        items.forEachIndexed { index, item ->
            val centerX = axisX + slotWidth * (index + 0.5f)
            val barTop = baseline - chartHeight * item.count / axisMax.toFloat()
            val left = centerX - barWidth / 2f
            val right = centerX + barWidth / 2f

            if (item.count > 0) {
                barPaint.color = barColor
                rect.set(left, barTop, right, baseline)
                canvas.drawRoundRect(rect, radius, radius, barPaint)

                canvas.drawText(
                    item.count.toString(),
                    centerX,
                    barTop - dp(4),
                    valuePaint
                )
            }

            canvas.drawText(
                item.label,
                centerX,
                baseline + dp(16),
                monthPaint
            )
        }
    }

    private fun centeredBaseline(centerY: Float, paint: TextPaint): Float =
        centerY - (paint.descent() + paint.ascent()) / 2f

    private fun dp(value: Int): Float = value * density

    private fun sp(value: Int): Float =
        value * density * resources.configuration.fontScale.coerceAtMost(MAX_CHART_FONT_SCALE)

    private companion object {
        /** Obergrenze fuer die Systemschrift in Diagrammen. */
        const val MAX_CHART_FONT_SCALE = 1.3f

        const val HORIZONTAL_ITEM_WIDTH_DP = 48
    }
}
