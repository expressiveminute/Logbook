package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * Moderne, schmale Navigationsleiste. Standardmäßig werden nur Icons
 * angezeigt. Der ausgewählte Tab ragt als abgerundetes Element nach oben über
 * die Leistenkante hinaus und zeigt dort Icon und Beschriftung. Beim Wechsel
 * zwischen den Tabs gleitet das Element passend zum neuen Tab (mit weicher
 * Icon-/Beschriftungs-Mischung), beim Verlassen eines Tabs sinkt es wieder in
 * die Leiste zurück.
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
    private val iconBitmaps = mutableMapOf<Int, Bitmap>()
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

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = sp(11)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var background = 0
    private var dividerColor = 0
    private var pillColor = 0
    private var iconActiveColor = 0
    private var iconInactiveColor = 0
    private var labelActiveColor = 0

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
        iconBitmaps.clear()
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

    /** Tauscht das Icon eines Tabs aus (z. B. Profil-Avatar). */
    fun setTabIcon(destId: Int, drawable: Drawable?) {
        val index = tabs.indexOfFirst { it.id == destId }
        if (index < 0 || drawable == null) return
        iconBitmaps[destId] = drawableToBitmap(drawable)
        invalidate()
    }

    private fun iconBitmap(tab: Tab): Bitmap? =
        iconBitmaps[tab.id] ?: run {
            ContextCompat.getDrawable(context, tab.iconRes)?.let {
                drawableToBitmap(it).also { bmp -> iconBitmaps[tab.id] = bmp }
            }
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

    private val baseBarHeight get() = dp(44f)
    private val risenBarHeight get() = dp(60f)
    private val currentBarHeight get() = baseBarHeight + (risenBarHeight - baseBarHeight) * elementProgress

    private val iconSize get() = dp(24f)
    private val iconDrawSize get() = iconSize.roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val height = (currentBarHeight + paddingTop + paddingBottom).roundToInt()
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
        val width = width.toFloat()
        if (x < paddingLeft || x > width - paddingRight) return null
        val slot = (width - paddingLeft - paddingRight) / tabs.size
        val index = ((x - paddingLeft) / slot).toInt()
        return index.takeIf { it in tabs.indices }
    }

    private fun tabCenterX(index: Int): Float {
        if (tabs.isEmpty()) return 0f
        val slot = (width - paddingLeft - paddingRight) / tabs.size.toFloat()
        return paddingLeft + slot * (index + 0.5f)
    }

    private fun elementCenterX(): Float {
        val sliding = slideFrom >= 0 && slideTo >= 0 && slideProgress < 1f
        return when {
            sliding -> lerp(tabCenterX(slideFrom), tabCenterX(slideTo), slideEase())
            selectedIndex >= 0 -> tabCenterX(selectedIndex)
            contentIndex >= 0 -> tabCenterX(contentIndex)
            else -> tabCenterX(0)
        }
    }

    private fun nearestIndexTo(x: Float): Int =
        tabs.indices.minByOrNull { kotlin.math.abs(tabCenterX(it) - x) } ?: 0

    override fun onDraw(canvas: Canvas) {
        refreshColors()

        canvas.drawColor(background)

        val width = this.width.toFloat()
        val barTop = paddingTop.toFloat()

        dividerPaint.color = dividerColor
        dividerPaint.strokeWidth = dp(0.7f)
        canvas.drawLine(paddingLeft.toFloat(), barTop, width - paddingRight, barTop, dividerPaint)

        if (tabs.isEmpty()) return

        val slotWidth = (width - paddingLeft - paddingRight) / tabs.size

        val moundTop = barTop + dp(4f)
        val moundBottom = barTop + currentBarHeight - dp(4f)
        val moundHeight = moundBottom - moundTop
        val inBarIconCenterY = barTop + dp(22f)
        val innerIconCenterY = moundTop + moundHeight * 0.32f
        val labelCenterY = moundTop + moundHeight * 0.76f

        val sliding = slideFrom >= 0 && slideTo >= 0 && slideProgress < 1f

        // In-Bar-Icons (statisch, gedimmt). Das Icon des aktiven Inhalts wird
        // nicht doppelt gezeichnet: Es sitzt bereits im aufgesetzten Element.
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
            val cx = tabCenterX(index)
            canvas.drawBitmap(bitmap, cx - half, inBarIconCenterY - half, iconPaint)
        }

        if (elementProgress <= 0f) return

        val ex = elementCenterX()
        val iconY = lerp(inBarIconCenterY, innerIconCenterY, elementProgress)

        pillPaint.color = withAlpha(pillColor, elementProgress)
        val pillScaleY = 0.4f + 0.6f * elementProgress
        val halfW = dp(32f)
        val moundPath = Path().apply {
            addRoundRect(
                RectF(ex - halfW, moundTop, ex + halfW, moundBottom),
                dp(20f),
                dp(20f),
                Path.Direction.CW
            )
        }
        canvas.save()
        canvas.scale(1f, pillScaleY, ex, moundBottom)
        canvas.drawPath(moundPath, pillPaint)
        canvas.restore()

        if (sliding) {
            val t = slideEase()
            drawCenteredIcon(canvas, slideFrom, ex, iconY, 1f - t)
            drawCenteredIcon(canvas, slideTo, ex, iconY, t)
            drawCenteredLabel(canvas, slideFrom, ex, labelCenterY, 1f - t)
            drawCenteredLabel(canvas, slideTo, ex, labelCenterY, t)
        } else {
            drawCenteredIcon(canvas, contentIndex, ex, iconY, 1f)
            drawCenteredLabel(canvas, contentIndex, ex, labelCenterY, 1f)
        }
    }

    private fun drawCenteredIcon(canvas: Canvas, index: Int, cx: Float, cy: Float, alpha: Float) {
        if (index < 0 || index >= tabs.size) return
        val bitmap = iconBitmap(tabs[index]) ?: return
        iconPaint.colorFilter = PorterDuffColorFilter(
            withAlpha(iconActiveColor, alpha * elementProgress),
            PorterDuff.Mode.SRC_IN
        )
        val half = iconSize / 2f
        canvas.drawBitmap(bitmap, cx - half, cy - half, iconPaint)
    }

    private fun drawCenteredLabel(canvas: Canvas, index: Int, cx: Float, cy: Float, alpha: Float) {
        if (index < 0 || index >= tabs.size) return
        labelPaint.color = withAlpha(labelActiveColor, alpha * elementProgress)
        val baseline = cy - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(context.getString(tabs[index].labelRes), cx, baseline, labelPaint)
    }

    private fun refreshColors() {
        background = attr(com.google.android.material.R.attr.colorSurfaceContainer)
        dividerColor = attr(com.google.android.material.R.attr.colorOutlineVariant)
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
        value * density * minOf(resources.configuration.fontScale, 1.0f)
}