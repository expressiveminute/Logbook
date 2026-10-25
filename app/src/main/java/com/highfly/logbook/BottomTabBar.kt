package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/** Platz der schwebenden Navigationsleiste über dem Seiteninhalt: Höhe (64 dp) plus unterer Abstand (8 dp). */
const val BOTTOM_NAV_SPACE_DP = 72f

/**
 * Schwebende Navigationsleiste als voll gerundete Pill (Design-Prinzip
 * angelehnt an Tomato, GPL-3.0 - eigene Umsetzung): Die Leiste selbst ist ein
 * erhabenes, rundes Element mit Abstand zu den Rändern. Standardmäßig werden
 * nur Icons angezeigt. Der ausgewählte Tab sitzt als Indikator-Pill in der
 * Leiste und zeigt Icon und Beschriftung nebeneinander. Beim Wechsel zwischen
 * den Tabs gleitet der Indikator passend zum neuen Tab (Breite und Inhalt
 * werden weich gemischt), beim Verlassen eines Tabs blendet er aus.
 */
class BottomTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Tab(
        val id: Int,
        @DrawableRes val iconRes: Int,
        @StringRes val labelRes: Int
    )

    private val tabs = mutableListOf<Tab>()
    private var selectedIndex = -1
    private var contentIndex = -1
    private var pressedIndex = -1

    /** 0..1, Ein-/Ausblenden des aufgesetzten Elements (Wachsen/Sinken). */
    private var elementProgress = 0f
    private var progressAnimator: ValueAnimator? = null

    /** Aktiver Slide zwischen zwei Tabs. */
    private var slideFrom = -1
    private var slideTo = -1
    private var slideProgress = 1f
    private var slideAnimator: ValueAnimator? = null

    private var onTabSelected: ((Int) -> Unit)? = null

    private val density get() = resources.displayMetrics.density

    private val barBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = sp(LABEL_TEXT_SIZE_SP)
    }

    private var background = 0
    private var pillColor = 0
    private var iconActiveColor = 0
    private var iconInactiveColor = 0
    private var labelActiveColor = 0

    init {
        // Schwebende Pill: Der Schatten folgt der gerundeten Form der Leiste.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val top = paddingTop
                val bottom = view.height - paddingBottom
                outline.setRoundRect(
                    paddingLeft, top, view.width - paddingRight, bottom,
                    (bottom - top) / 2f
                )
            }
        }
        elevation = dp(3f)
    }

    fun setTabs(data: List<Tab>) {
        tabs.clear()
        tabs.addAll(data)
        selectedIndex = -1
        contentIndex = -1
        elementProgress = 0f
        slideFrom = -1
        slideTo = -1
        slideProgress = 1f
        progressAnimator?.cancel()
        slideAnimator?.cancel()
        requestLayout()
        invalidate()
    }

    fun setOnTabSelectedListener(listener: (Int) -> Unit) {
        onTabSelected = listener
    }

    /** Ausgewählten Tab setzen; -1 blendet das Element aus. */
    fun setSelectedDestination(destId: Int, animate: Boolean = true) {
        val index = tabs.indexOfFirst { it.id == destId }
        if (index < 0) {
            if (selectedIndex >= 0) {
                contentIndex = selectedIndex
                selectedIndex = -1
                cancelSlide()
                animateProgress(0f, animate)
            }
            return
        }
        if (selectedIndex == index) return
        if (selectedIndex < 0) {
            contentIndex = index
            selectedIndex = index
            cancelSlide()
            animateProgress(1f, animate)
        } else {
            val from = nearestIndexTo(elementCenterX())
            slideFrom = from
            slideTo = index
            contentIndex = index
            selectedIndex = index
            if (elementProgress < 1f) animateProgress(1f, animate)
            animateSlide(from, index, animate)
        }
        announceForAccessibility(context.getString(tabs[index].labelRes))
    }

    private fun iconBitmap(tab: Tab): Bitmap? =
        ContextCompat.getDrawable(context, tab.iconRes)?.let {
            drawableToBitmap(it)
        }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val side = iconDrawSize.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val original = drawable.bounds
        drawable.setBounds(0, 0, side, side)
        drawable.draw(canvas)
        drawable.bounds = original
        return bitmap
    }

    private fun animateProgress(target: Float, animate: Boolean) {
        progressAnimator?.cancel()
        if (!animate) {
            elementProgress = target
            invalidate()
            return
        }
        val from = elementProgress
        ValueAnimator.ofFloat(from, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                elementProgress = it.animatedValue as Float
                requestLayout()
                invalidate()
            }
            start()
            progressAnimator = this
        }
    }

    private fun animateSlide(from: Int, to: Int, animate: Boolean) {
        slideProgress = 0f
        if (!animate) {
            slideProgress = 1f
            slideFrom = -1
            slideTo = -1
            invalidate()
            return
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                slideProgress = it.animatedValue as Float
                if (slideProgress >= 1f) {
                    slideFrom = -1
                    slideTo = -1
                }
                invalidate()
            }
            start()
            slideAnimator = this
        }
    }

    private fun cancelSlide() {
        slideAnimator?.cancel()
        slideFrom = -1
        slideTo = -1
        slideProgress = 1f
    }

    private fun slideEase(): Float =
        if (slideProgress >= 1f) 1f
        else ((1 - kotlin.math.cos(slideProgress * Math.PI)) / 2).toFloat()

    private val barHeight get() = dp(BAR_HEIGHT_DP)
    private val indicatorHeight get() = dp(INDICATOR_HEIGHT_DP)

    private val iconSize get() = dp(24f)
    private val iconDrawSize get() = iconSize.roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val height = (barHeight + paddingTop + paddingBottom).roundToInt()
        setMeasuredDimension(width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tabs.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = indexAt(event.x) ?: -1
                if (pressedIndex >= 0) {
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = indexAt(event.x)
                if (index != null && index == pressedIndex) {
                    performClick()
                    onTabSelected?.invoke(tabs[index].id)
                }
                pressedIndex = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun indexAt(x: Float): Int? {
        if (tabs.isEmpty()) return null
        val active = if (elementProgress > 0f) contentIndex else -1
        val centers = cellCenters(active)
        for (i in tabs.indices) {
            val half = cellWidth(i, active) / 2f
            if (x >= centers[i] - half && x < centers[i] + half) return i
        }
        return null
    }

    /** Zellbreite: Der aktive Tab als Indikator-Pill, alle anderen nur Icon. */
    private fun cellWidth(index: Int, activeIndex: Int): Float =
        if (index == activeIndex) pillWidthFor(index)
        else iconSize + 2f * dp(ICON_CELL_PADDING_DP)

    /** Breite der Indikator-Pill: Icon, Lücke, Beschriftung und Innenabstand. */
    private fun pillWidthFor(index: Int): Float {
        val label = fitLabelText(
            context.getString(tabs[index].labelRes),
            maxLabelWidth().coerceAtLeast(0f)
        )
        return iconSize + dp(ICON_LABEL_GAP_DP) + labelPaint.measureText(label) +
                2f * dp(INDICATOR_PADDING_H_DP)
    }

    /**
     * Maximale Breite der Beschriftung: Der verfügbare Platz der Leiste abzüglich
     * der Icon-Zellen der übrigen Tabs und des Indikator-Inhalts.
     */
    private fun maxLabelWidth(): Float {
        val others = (tabs.size - 1) * (iconSize + 2f * dp(ICON_CELL_PADDING_DP))
        return (width - paddingLeft - paddingRight) - others -
                iconSize - dp(ICON_LABEL_GAP_DP) - 2f * dp(INDICATOR_PADDING_H_DP)
    }

    /**
     * Zellen als Zeile über die ganze Leiste verteilt (Prinzip der schwebenden
     * Toolbar): Der aktive Tab ist die breite Indikator-Pill, die übrigen
     * schmalen Icon-Zellen. Zwischen allen Zellen und an beiden Rändern liegt
     * derselbe Abstand, damit die Icons gleichmäßig über die Leiste verteilt
     * sind statt in der Mitte zusammengedrängt mit großen Lücken an den
     * Seiten. Reicht der Platz nicht, rücken die Zellen zusammen (Abstand 0)
     * und die Zeile wird zentriert, damit nichts am Rand abgeschnitten wird.
     */
    private fun cellCenters(activeIndex: Int): List<Float> {
        val widths = tabs.indices.map { cellWidth(it, activeIndex) }
        val total = widths.sum()
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        val gap = ((right - left - total) / (tabs.size + 1)).coerceAtLeast(0f)
        var x = if (gap > 0f) {
            left + gap
        } else {
            ((left + right - total) / 2f).coerceIn(left, (right - total).coerceAtLeast(left))
        }
        val centers = ArrayList<Float>(tabs.size)
        for (w in widths) {
            centers += x + w / 2f
            x += w + gap
        }
        return centers
    }

    private fun elementCenterX(): Float {
        val sliding = slideFrom >= 0 && slideTo >= 0 && slideProgress < 1f
        val from = if (sliding) slideFrom else contentIndex
        val to = if (sliding) slideTo else contentIndex
        if (from !in tabs.indices && to !in tabs.indices) return 0f
        val fromCenter = if (from in tabs.indices) cellCenters(from)[from] else cellCenters(to)[to]
        val toCenter = if (to in tabs.indices) cellCenters(to)[to] else fromCenter
        val t = if (sliding) slideEase() else 1f
        return lerp(fromCenter, toCenter, t)
    }

    private fun nearestIndexTo(x: Float): Int {
        val active = if (elementProgress > 0f) contentIndex else -1
        val centers = cellCenters(active)
        return tabs.indices.minByOrNull { kotlin.math.abs(centers[it] - x) } ?: 0
    }

    override fun onDraw(canvas: Canvas) {
        refreshColors()

        val width = this.width.toFloat()
        val contentTop = paddingTop.toFloat()
        val contentBottom = height - paddingBottom.toFloat()
        val contentHeight = contentBottom - contentTop
        if (width <= 0f || contentHeight <= 0f) return

        // Schwebende Pill als Hintergrund der Leiste.
        barBackgroundPaint.color = background
        val barRadius = contentHeight / 2f
        canvas.drawRoundRect(
            RectF(paddingLeft.toFloat(), contentTop, width - paddingRight, contentBottom),
            barRadius,
            barRadius,
            barBackgroundPaint
        )

        if (tabs.isEmpty()) return

        val centerY = contentTop + contentHeight / 2f
        val sliding = slideFrom >= 0 && slideTo >= 0 && slideProgress < 1f
        val t = if (sliding) slideEase() else 1f
        val fromIndex = if (sliding) slideFrom else contentIndex
        val toIndex = if (sliding) slideTo else contentIndex
        val fromCenters = cellCenters(if (fromIndex in tabs.indices) fromIndex else -1)
        val toCenters = cellCenters(if (toIndex in tabs.indices) toIndex else -1)

        // In-Bar-Icons (statisch, gedimmt). Das Icon des aktiven Inhalts wird
        // nicht doppelt gezeichnet: Es sitzt bereits im Indikator. Beim Wechsel
        // gleiten die Zellen mit, damit die wachsende Pill niemanden überdeckt.
        tabs.forEachIndexed { index, tab ->
            val isContent = index == contentIndex && elementProgress > 0f
            if (isContent) return@forEachIndexed

            val bitmap = iconBitmap(tab) ?: return@forEachIndexed
            iconPaint.colorFilter = PorterDuffColorFilter(
                withAlpha(iconInactiveColor, 0.9f),
                PorterDuff.Mode.SRC_IN
            )
            val pressed = pressedIndex == index
            val scale = if (pressed) 0.88f else 1f
            val half = iconSize * scale / 2f
            val fromC = fromCenters.getOrElse(index) { 0f }
            val toC = toCenters.getOrElse(index) { fromC }
            val cx = lerp(fromC, toC, t)
            canvas.drawBitmap(bitmap, cx - half, centerY - half, iconPaint)
        }

        if (elementProgress <= 0f) return

        // Indikator-Pill: wächst beim Erscheinen, gleitet beim Wechsel samt
        // seiner Breite zum neuen Tab.
        val fromC = fromCenters.getOrElse(fromIndex) { 0f }
        val toC = toCenters.getOrElse(toIndex) { fromC }
        val ex = lerp(fromC, toC, t)
        val fromHalf = if (fromIndex in tabs.indices) pillWidthFor(fromIndex) / 2f else 0f
        val toHalf = if (toIndex in tabs.indices) pillWidthFor(toIndex) / 2f else fromHalf
        val halfW = lerp(fromHalf, toHalf, t)
        val indicatorTop = contentTop + (contentHeight - indicatorHeight) / 2f
        pillPaint.color = withAlpha(pillColor, elementProgress)
        canvas.save()
        canvas.scale(1f, 0.4f + 0.6f * elementProgress, ex, indicatorTop + indicatorHeight / 2f)
        canvas.drawRoundRect(
            RectF(ex - halfW, indicatorTop, ex + halfW, indicatorTop + indicatorHeight),
            indicatorHeight / 2f,
            indicatorHeight / 2f,
            pillPaint
        )
        canvas.restore()

        // Inhalt des Indikators: Icon und Beschriftung nebeneinander, beim
        // Wechsel weich gemischt (Crossfade).
        drawIndicatorRow(canvas, fromIndex, ex, centerY, 1f - t)
        drawIndicatorRow(canvas, toIndex, ex, centerY, t)
    }

    /** Zeichnet Icon und Beschriftung eines Tabs als Zeile, zentriert auf [cx]. */
    private fun drawIndicatorRow(
        canvas: Canvas,
        index: Int,
        cx: Float,
        centerY: Float,
        alpha: Float
    ) {
        if (index < 0 || index >= tabs.size || alpha <= 0f) return
        val label = fitLabelText(
            context.getString(tabs[index].labelRes),
            maxLabelWidth().coerceAtLeast(0f)
        )
        val labelWidth = labelPaint.measureText(label)
        val gap = dp(ICON_LABEL_GAP_DP)
        val totalWidth = iconSize + gap + labelWidth
        val startX = cx - totalWidth / 2f
        val bitmap = iconBitmap(tabs[index]) ?: return
        iconPaint.colorFilter = PorterDuffColorFilter(
            withAlpha(iconActiveColor, alpha * elementProgress),
            PorterDuff.Mode.SRC_IN
        )
        val half = iconSize / 2f
        canvas.drawBitmap(bitmap, startX, centerY - half, iconPaint)
        labelPaint.color = withAlpha(labelActiveColor, alpha * elementProgress)
        val baseline = centerY - (labelPaint.ascent() + labelPaint.descent()) / 2f
        canvas.drawText(label, startX + iconSize + gap + labelWidth / 2f, baseline, labelPaint)
    }

    /**
     * Verkleinert die Beschriftung, bis sie in [maxWidth] passt. Ohne das
     * ragt ein langer Name (z. B. "Einstellungen") auf schmalen Displays über
     * das aufgesetzte Element hinaus. Notfalls wird am Ende gekürzt.
     */
    private fun fitLabelText(raw: String, maxWidth: Float): String {
        val baseSize = sp(LABEL_TEXT_SIZE_SP)
        labelPaint.textSize = baseSize
        if (maxWidth <= 0f || labelPaint.measureText(raw) <= maxWidth) return raw

        val minSize = dp(MIN_LABEL_TEXT_DP)
        var size = baseSize
        while (size > minSize) {
            size = maxOf(minSize, size * 0.94f)
            labelPaint.textSize = size
            if (labelPaint.measureText(raw) <= maxWidth) return raw
        }
        return TextUtils.ellipsize(
            raw,
            TextPaint(labelPaint),
            maxWidth,
            TextUtils.TruncateAt.END
        ).toString()
    }

    private fun refreshColors() {
        background = attr(com.google.android.material.R.attr.colorSurfaceContainer)
        pillColor = attr(com.google.android.material.R.attr.colorPrimaryContainer)
        iconActiveColor = attr(com.google.android.material.R.attr.colorOnPrimaryContainer)
        iconInactiveColor = attr(com.google.android.material.R.attr.colorOnSurfaceVariant)
        labelActiveColor = attr(com.google.android.material.R.attr.colorOnPrimaryContainer)
    }

    private fun attr(key: Int): Int =
        MaterialColors.getColor(this, key)

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val alpha = android.graphics.Color.alpha(from) +
                ((android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * t).roundToInt()
        val red = android.graphics.Color.red(from) +
                ((android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t).roundToInt()
        val green = android.graphics.Color.green(from) +
                ((android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t).roundToInt()
        val blue = android.graphics.Color.blue(from) +
                ((android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t).roundToInt()
        return android.graphics.Color.argb(
            alpha.coerceIn(0, 255),
            red.coerceIn(0, 255),
            green.coerceIn(0, 255),
            blue.coerceIn(0, 255)
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun lerp(from: Float, to: Float, t: Float): Float =
        from + (to - from) * t

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Int): Float =
        value * density * resources.configuration.fontScale.coerceAtMost(MAX_CHART_FONT_SCALE)

    private companion object {
        /** Obergrenze fuer die Systemschrift in Diagrammen. */
        const val MAX_CHART_FONT_SCALE = 1.3f

        /** Fixe Höhe der Leiste (ohne Inset-Padding). */
        const val BAR_HEIGHT_DP = 64f

        /** Höhe der Indikator-Pill innerhalb der Leiste. */
        const val INDICATOR_HEIGHT_DP = 48f

        /** Innenabstand der Indikator-Pill links und rechts. */
        const val INDICATOR_PADDING_H_DP = 16f

        /** Abstand zwischen Icon und Beschriftung im Indikator. */
        const val ICON_LABEL_GAP_DP = 8f

        /** Innenabstand einer inaktiven Icon-Zelle links und rechts. */
        const val ICON_CELL_PADDING_DP = 12f

        const val LABEL_TEXT_SIZE_SP = 11
        const val MIN_LABEL_TEXT_DP = 8f
    }
}