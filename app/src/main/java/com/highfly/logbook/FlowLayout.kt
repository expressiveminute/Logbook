package com.highfly.logbook

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Bricht seine Kinder in mehrere Zeilen um, wenn sie nicht mehr nebeneinander
 * passen. Wird fuer die angenommenen Reisebuddies im Formular genutzt: Jeder
 * per Enter bestätigte Name ist ein Chip im Layout, weitere Namen folgen in
 * derselben oder der nächsten Zeile.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val spacingPx = (8 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        var contentHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x + childWidth > width - paddingRight && x > paddingLeft) {
                x = paddingLeft
                y += lineHeight + spacingPx
                lineHeight = 0
            }
            lineHeight = maxOf(lineHeight, childHeight)
            contentHeight = y + lineHeight
            x += childWidth + spacingPx
        }
        val widthSize = resolveSize(width, widthMeasureSpec)
        val heightSize = resolveSize(paddingTop + contentHeight + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(widthSize, heightSize)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            if (x + childWidth + lp.rightMargin > width - paddingRight && x > paddingLeft) {
                x = paddingLeft
                y += lineHeight + spacingPx
                lineHeight = 0
            }
            child.layout(
                x + lp.leftMargin,
                y + lp.topMargin,
                x + lp.leftMargin + childWidth,
                y + lp.topMargin + childHeight
            )
            x += childWidth + lp.leftMargin + lp.rightMargin + spacingPx
            lineHeight = maxOf(lineHeight, childHeight + lp.topMargin + lp.bottomMargin)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): LayoutParams =
        when (p) {
            is LayoutParams -> p
            is MarginLayoutParams -> LayoutParams(p)
            else -> LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean =
        p is LayoutParams

    class LayoutParams : MarginLayoutParams {
        constructor(c: Context, attrs: AttributeSet?) : super(c, attrs)
        constructor(width: Int, height: Int) : super(width, height)
        constructor(source: ViewGroup.LayoutParams) : super(source)
    }
}