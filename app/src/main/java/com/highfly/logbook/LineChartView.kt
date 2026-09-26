package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Liniendiagramm: Die Kategorien (z. B. Jahre) liegen auf der x-Achse, die
 * Werte werden als Linie mit Punktmarkern gegen die y-Achse aufgetragen.
 * Die Werte werden in der Reihenfolge der uebergebenen [Item]s verbunden,
 * der Aufrufer bestimmt also die Sortierung (z. B. chronologisch nach Jahr).
 *
 * Bei vielen Kategorien ist das Diagramm breiter als der Bildschirm und muss
 * in eine [android.widget.HorizontalScrollView] gelegt werden: Im
 * UNSPECIFIED-Modus meldet die View ihre volle Inhaltsbreite, damit der
 * Container scrollen kann. Engt der Container die View auf die Bildschirmbreite
 * ein, wird der Platz gleichmäßig verteilt und zu dichte Punktmarker
 * ausgedünnt, damit die Linie lesbar bleibt. Die Beschriftung der y-Achse
 * wandert beim Scrollen mit nach links und wird deshalb - wie beim
 * [MonthBarChartView] - von einer zweiten, ueberlagerten Instanz
 * ([setContentVisible]) am festen linken Rand gehalten.
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Item(val label: String, val count: Int)

    private val items = mutableListOf<Item>()

    private val density get() = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val categoryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val axisLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
    }

    private val linePath = Path()
    private val fillPath = Path()

    /** x- und y-Koordinate je Datenpunkt, damit [onDraw] nichts alloziert. */
    private var points = FloatArray(0)

    private var lineColor = 0
    private var labelColor = 0
    private var axisColor = 0
    private var gridColor = 0
    private var backgroundColor = 0

    private var chartHeight = 0f
    private var topPadding = 0f
    private var bottomPadding = 0f
    private var leftPadding = 0f
    private var rightPadding = 0f
    private var leadingSpace = 0f

    private var axisMax = 1
    private var axisStep = 1
    private var contentVisible = true

    /** Abstand zweier Datenpunkte, aus [onMeasure] uebernommen. */
    private var slotWidth = 0f

    /** true, wenn der Container die View auf ihre Breite gestreckt hat. */
    private var stretched = false

    init {
        lineColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        labelColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        axisColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOutline
        )
        backgroundColor = MaterialColors.getColor(
            this, android.R.attr.colorBackground
        )
        gridColor = (axisColor and 0x00FFFFFF) or (0x33 shl 24)

        chartHeight = dp(180)
        topPadding = dp(24)
        bottomPadding = dp(28)
        rightPadding = dp(8)

        categoryPaint.textSize = sp(11)
        categoryPaint.color = labelColor
        valuePaint.textSize = sp(11)
        valuePaint.isFakeBoldText = true
        valuePaint.color = labelColor
        axisLabelPaint.textSize = sp(10)
        axisLabelPaint.color = labelColor

        linePaint.color = lineColor
        linePaint.strokeWidth = dp(2.5f)
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.strokeJoin = Paint.Join.ROUND
        gridPaint.strokeWidth = dp(1)
        gridPaint.color = gridColor
        axisPaint.strokeWidth = dp(1)
        axisPaint.color = axisColor
        markerPaint.color = backgroundColor
        markerRingPaint.color = lineColor
        markerRingPaint.strokeWidth = dp(2)
    }

    fun setItems(data: List<Item>) {
        items.clear()
        items.addAll(data)
        points = FloatArray(data.size * 2)
        requestLayout()
        invalidate()
    }

    /** Platz, den eine ueberlagerte y-Achse im Scrollcontainer belegt. */
    fun setLeadingSpace(widthPx: Int) {
        val newSpace = widthPx.toFloat().coerceAtLeast(0f)
        if (leadingSpace == newSpace) return
        leadingSpace = newSpace
        requestLayout()
        invalidate()
    }

    /**
     * false zeichnet nur y-Achse und Beschriftung: So bleibt die Achse beim
     * horizontalen Scrollen am festen linken Rand stehen.
     */
    fun setContentVisible(visible: Boolean) {
        if (contentVisible == visible) return
        contentVisible = visible
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val tint = lineColor and 0x00FFFFFF
        fillPaint.shader = LinearGradient(
            0f, topPadding, 0f, topPadding + chartHeight,
            tint or (0x4D shl 24), tint or (0x00 shl 24),
            Shader.TileMode.CLAMP
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        if (items.isEmpty()) {
            val emptyWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
                suggestedMinimumWidth
            } else {
                MeasureSpec.getSize(widthMeasureSpec)
            }
            slotWidth = 0f
            stretched = widthMode != MeasureSpec.UNSPECIFIED
            setMeasuredDimension(emptyWidth, 0)
            return
        }

        val rawMax = items.maxOf { it.count }
        axisStep = niceStep(rawMax)
        axisMax = if (rawMax <= 0) axisStep else
            ceil(rawMax / axisStep.toDouble()).toInt() * axisStep

        val widestTick = (0..axisMax step axisStep)
            .maxOf { axisLabelPaint.measureText(it.toString()) }
        leftPadding = maxOf(widestTick + dp(8), leadingSpace)

        val widestLabel = items.maxOf { categoryPaint.measureText(it.label) }
        slotWidth = maxOf(dp(MIN_COLUMN_WIDTH_DP), widestLabel + dp(8))
        val contentWidth = (items.size * slotWidth + leftPadding + rightPadding).toInt()

        stretched = widthMode != MeasureSpec.UNSPECIFIED
        val width = if (widthMode == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth.coerceAtLeast(contentWidth)
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val height = (topPadding + chartHeight + bottomPadding).toInt()
        setMeasuredDimension(width, height)
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
        val stepX = if (stretched) plotWidth / items.size else slotWidth

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

        // Die y-Achse wird in beiden Modi gezeichnet: die scrollende Instanz
        // am Diagramm, die ueberlagerte Achsenmaske am festen linken Rand.
        canvas.drawLine(axisX, chartTop, axisX, baseline, axisPaint)

        if (!contentVisible) return

        canvas.drawLine(axisX, baseline, plotRight, baseline, axisPaint)

        items.forEachIndexed { index, item ->
            val x = axisX + stepX * (index + 0.5f)
            val y = baseline - chartHeight * item.count / axisMax.toFloat()
            points[index * 2] = x
            points[index * 2 + 1] = y
        }

        linePath.reset()
        fillPath.reset()
        fillPath.moveTo(points[0], baseline)
        items.indices.forEach { index ->
            val x = points[index * 2]
            val y = points[index * 2 + 1]
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(points[(items.size - 1) * 2], baseline)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // Bei sehr vielen Kategorien werden nur Marker im Mindestabstand
        // gezeichnet; der erste und der letzte Punkt bleiben immer sichtbar.
        val radius = dp(4)
        val markerStep = markerStep(stepX)
        val labelStep = labelStep(stepX)
        val withValues = valueLabelsFit(stepX)
        val lastIndex = items.lastIndex

        items.forEachIndexed { index, item ->
            val x = points[index * 2]
            val y = points[index * 2 + 1]
            val isEndpoint = index == 0 || index == lastIndex
            if (isEndpoint || index % markerStep == 0) {
                canvas.drawCircle(x, y, radius, markerPaint)
                canvas.drawCircle(x, y, radius, markerRingPaint)
            }
            if (index % labelStep == 0) {
                canvas.drawText(item.label, x, baseline + dp(16), categoryPaint)
            }
            if (withValues && (isEndpoint || index % markerStep == 0)) {
                canvas.drawText(item.count.toString(), x, y - dp(8), valuePaint)
            }
        }
    }

    /** So viele Kategorien werden beschriftet, dass sich die Labels nicht
     * ueberlappen. */
    private fun labelStep(stepX: Float): Int {
        if (items.size < 2 || stepX <= 0f) return 1
        val widest = items.maxOf { categoryPaint.measureText(it.label) }
        return ceil((widest + dp(8)) / stepX).toInt().coerceAtLeast(1)
    }

    /** Marker im Abstand ihres own Durchmessers, sonst verschmelzen sie. */
    private fun markerStep(stepX: Float): Int {
        if (stepX <= 0f) return 1
        return ceil(MARKER_MIN_GAP_DP * density / stepX).toInt().coerceAtLeast(1)
    }

    private fun valueLabelsFit(stepX: Float): Boolean {
        if (items.size == 1) return true
        val widest = items.maxOf { valuePaint.measureText(it.count.toString()) }
        return stepX >= widest + dp(6)
    }

    private fun centeredBaseline(centerY: Float, paint: TextPaint): Float =
        centerY - (paint.descent() + paint.ascent()) / 2f

    private fun dp(value: Int): Float = value * density

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Int): Float =
        value * density * resources.configuration.fontScale.coerceAtMost(MAX_CHART_FONT_SCALE)

    private companion object {
        /** Obergrenze fuer die Systemschrift in Diagrammen. */
        const val MAX_CHART_FONT_SCALE = 1.3f

        const val MIN_COLUMN_WIDTH_DP = 32

        /** Mindestabstand zweier Punktmarker in dp. */
        const val MARKER_MIN_GAP_DP = 12f
    }
}
