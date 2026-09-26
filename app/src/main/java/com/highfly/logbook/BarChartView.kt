package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors

class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * @param countLabel Zahl, die statt [count] neben dem Balken steht, wenn der
     *   Balken nicht eine Anzahl, sondern einen anderen Messwert darstellt
     *   (z. B. eine Flugdauer in Minuten).
     */
    data class Item(
        val label: String,
        val count: Int,
        val subLabel: String? = null,
        val countLabel: String? = null
    )

    private val items = mutableListOf<Item>()

    private var onItemClickListener: ((Int) -> Unit)? = null

    private val density get() = resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val flagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var barColor = 0
    private var labelColor = 0
    private var countColor = 0
    private var backgroundColor = 0

    private var topPadding = 0f
    private var rowHeight = 0f
    private var gap = 0f
    private var bottomPadding = 0f
    private var sidePadding = 0f
    private var rankGap = 0f
    private var barGap = 0f
    private var countGap = 0f
    private var flagGap = 0f
    private var medalRadius = 0f
    private var barInset = 0f

    init {
        barColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        labelColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface
        )
        countColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        backgroundColor = MaterialColors.getColor(
            this, android.R.attr.colorBackground
        )

        topPadding = dp(8)
        rowHeight = dp(44)
        gap = dp(10)
        bottomPadding = dp(8)
        sidePadding = dp(16)
        rankGap = dp(10)
        barGap = dp(10)
        countGap = dp(8)
        flagGap = dp(8)
        medalRadius = dp(11)
        barInset = dp(10)

        labelPaint.textSize = sp(14)
        labelPaint.isFakeBoldText = true
        countPaint.textSize = sp(12)
        countPaint.isFakeBoldText = true
        flagPaint.textSize = sp(14)
        flagPaint.isFakeBoldText = true
        flagPaint.color = labelColor
        flagPaint.textAlign = Paint.Align.CENTER

        subPaint.textSize = sp(11)
        subPaint.textAlign = Paint.Align.LEFT
    }

    fun setItems(data: List<Item>) {
        items.clear()
        items.addAll(data)
        requestLayout()
        invalidate()
    }

    fun setOnItemClickListener(listener: (Int) -> Unit) {
        onItemClickListener = listener
        isClickable = listener != null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onItemClickListener == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val index = indexAt(event.y)
                if (index != null) {
                    performClick()
                    onItemClickListener?.invoke(index)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun indexAt(y: Float): Int? {
        for (index in items.indices) {
            val top = topPadding + index * (rowHeight + gap)
            if (y >= top && y <= top + rowHeight) return index
        }
        return null
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
                items.size * rowHeight +
                (items.size - 1) * gap +
                bottomPadding).toInt()
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return

        val width = width.toFloat()
        val maxCount = items.maxOf { it.count }
        val pill = rowHeight / 2f

        val rankOffset = items.indices.maxOf { rankWidth(it) } + rankGap
        val countReserve =
            items.maxOf { countPaint.measureText(countText(it)) } + countGap + sidePadding

        var maxCodeWidth = 0f
        var maxFlagWidth = 0f
        items.forEach { item ->
            val (text, flag) = splitLabelFlag(item.label)
            maxCodeWidth = maxOf(maxCodeWidth, labelPaint.measureText(text))
            if (flag.isNotEmpty()) {
                maxFlagWidth = maxOf(maxFlagWidth, flagPaint.measureText(flag))
            }
        }
        val labelReserve = (maxCodeWidth + flagGap + maxFlagWidth).coerceAtMost(width * 0.6f)
        val labelLeft = sidePadding + rankOffset
        val flagLeft = labelLeft + maxCodeWidth + flagGap
        val flagCenterX = flagLeft + maxFlagWidth / 2f
        val barLeft = labelLeft + labelReserve + barGap
        val usableBarWidth = (width - barLeft - countReserve).coerceAtLeast(0f)

        val barRect = RectF()

        items.forEachIndexed { index, item ->
            val barTop = topPadding + index * (rowHeight + gap)
            val barBottom = barTop + rowHeight
            val barWidth = usableBarWidth * item.count / maxCount.toFloat()
            val barRight = barLeft + barWidth

            drawRank(canvas, index, barTop, barBottom)

            val (text, flag) = splitLabelFlag(item.label)

            labelPaint.color = labelColor
            val label = if (labelPaint.measureText(text) > labelReserve) {
                TextUtils.ellipsize(
                    text, labelPaint, labelReserve, TextUtils.TruncateAt.END
                ).toString()
            } else {
                text
            }
            canvas.drawText(
                label,
                labelLeft,
                centeredBaseline(barTop, barBottom, labelPaint),
                labelPaint
            )

            if (flag.isNotEmpty()) {
                canvas.drawText(
                    flag,
                    flagCenterX,
                    flagBaseline(barTop, barBottom),
                    flagPaint
                )
            }

            barRect.set(barLeft, barTop, barRight, barBottom)
            barPaint.color = barColor
            canvas.drawRoundRect(barRect, pill, pill, barPaint)

            countPaint.color = countColor
            canvas.drawText(
                countText(item),
                barRight + countGap,
                centeredBaseline(barTop, barBottom, countPaint),
                countPaint
            )

            item.subLabel?.takeIf { it.isNotEmpty() }?.let { subLabel ->
                val subWidth = subPaint.measureText(subLabel)
                val subBaseline = centeredBaseline(barTop, barBottom, subPaint)
                if (barWidth - 2 * barInset >= subWidth) {
                    subPaint.textAlign = Paint.Align.LEFT
                    subPaint.color = backgroundColor
                    canvas.drawText(
                        subLabel,
                        barLeft + barInset,
                        subBaseline,
                        subPaint
                    )
                } else {
                    subPaint.textAlign = Paint.Align.RIGHT
                    subPaint.color = labelColor
                    subPaint.isFakeBoldText = true
                    canvas.drawText(subLabel, width - sidePadding, subBaseline, subPaint)
                    subPaint.isFakeBoldText = false
                }
            }
        }
    }

    private fun countText(item: Item): String =
        item.countLabel ?: item.count.toString()

    private fun rankFor(index: Int): Int =
        items.map { it.count }.distinct().count { it > items[index].count } + 1

    private fun rankWidth(index: Int): Float {
        val rank = rankFor(index)
        return when {
            rank <= 3 -> medalRadius * 2f
            rank <= 10 -> countPaint.measureText("$rank.")
            else -> 0f
        }
    }

    private fun drawRank(canvas: Canvas, index: Int, barTop: Float, barBottom: Float) {
        val rank = rankFor(index)
        if (rank <= 3) {
            val (color, rim) = when (rank) {
                1 -> MEDAL_GOLD to MEDAL_GOLD_RIM
                2 -> MEDAL_SILVER to MEDAL_SILVER_RIM
                else -> MEDAL_BRONZE to MEDAL_BRONZE_RIM
            }
            drawMedal(
                canvas, sidePadding, (barTop + barBottom) / 2f, medalRadius,
                color, rim
            )
        } else if (rank <= 10 && isFirstOfRankGroup(index)) {
            countPaint.color = countColor
            val text = "$rank."
            val centerX = sidePadding + medalRadius
            canvas.drawText(
                text,
                centerX - countPaint.measureText(text) / 2f,
                centeredBaseline(barTop, barBottom, countPaint),
                countPaint
            )
        }
    }

    private fun isFirstOfRankGroup(index: Int): Boolean =
        index == 0 || rankFor(index - 1) != rankFor(index)

    private fun drawMedal(
        canvas: Canvas,
        left: Float,
        verticalCenter: Float,
        radius: Float,
        color: Int,
        rim: Int
    ) {
        val cx = left + radius
        val cy = verticalCenter + radius * 0.14f

        ribbonPaint.color = rim
        val ribbon = Path().apply {
            moveTo(cx - radius * 0.16f, cy - radius * 0.60f)
            lineTo(cx - radius * 0.66f, cy - radius * 1.12f)
            lineTo(cx - radius * 0.38f, cy - radius * 1.04f)
            close()
            moveTo(cx + radius * 0.16f, cy - radius * 0.60f)
            lineTo(cx + radius * 0.66f, cy - radius * 1.12f)
            lineTo(cx + radius * 0.38f, cy - radius * 1.04f)
            close()
        }
        canvas.drawPath(ribbon, ribbonPaint)

        barPaint.color = color
        canvas.drawCircle(cx, cy, radius, barPaint)

        ringPaint.color = android.graphics.Color.WHITE
        ringPaint.strokeWidth = radius * 0.14f
        canvas.drawCircle(cx, cy, radius * 0.68f, ringPaint)
    }

    private fun centeredBaseline(top: Float, bottom: Float, paint: TextPaint): Float =
        top + (bottom - top - (paint.descent() - paint.ascent())) / 2f - paint.ascent()

    /**
     * Zeichnet die Flagge (Farb-Emoji) vertikal in der Reihe zentriert. Das
     * Emoji-Glyph erstreckt sich ausgehend von der Baseline nach oben, daher
     * wird die Baseline leicht nach unten korrigiert.
     */
    private fun flagBaseline(top: Float, bottom: Float): Float =
        centeredBaseline(top, bottom, flagPaint) + flagPaint.textSize * 0.20f

    /**
     * Trennt ein Label wie "DE 🇩🇪" in Ländercode und Flagge. Der Code wird als
     * normaler Text gezeichnet, die Flagge separat, damit Abstand und vertikale
     * Ausrichtung kontrollierbar sind.
     */
    private fun splitLabelFlag(label: String): Pair<String, String> {
        if (label.isEmpty()) return "" to ""
        var idx = label.length
        val parts = mutableListOf<Int>()
        while (idx > 0 && parts.size < 2) {
            val cp = label.codePointBefore(idx)
            parts.add(cp)
            idx -= Character.charCount(cp)
        }
        if (parts.size == 2 &&
            parts.all { it in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END }
        ) {
            return label.substring(0, idx) to String(parts.reversed().toIntArray(), 0, 2)
        }
        return label to ""
    }

    private fun dp(value: Int): Float = value * density

    private fun sp(value: Int): Float =
        value * density * minOf(resources.configuration.fontScale, 1.0f)

    private companion object {
        const val REGIONAL_INDICATOR_START = 0x1F1E6
        const val REGIONAL_INDICATOR_END = 0x1F1FF

        val MEDAL_GOLD = 0xFFD9A800.toInt()
        val MEDAL_GOLD_RIM = 0xFFB98A00.toInt()
        val MEDAL_SILVER = 0xFFC4C4C4.toInt()
        val MEDAL_SILVER_RIM = 0xFFA0A0A0.toInt()
        val MEDAL_BRONZE = 0xFFCD7F32.toInt()
        val MEDAL_BRONZE_RIM = 0xFFA4682A.toInt()
    }
}