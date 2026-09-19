package com.highfly.logbook

/**
 * Shared occupancy map that keeps country, city and other map labels from
 * overlapping when they are drawn on the same map.
 *
 * All label overlays use one instance. The overlay that is drawn first in the
 * overlay stack calls [beginFrame] once per redraw, so every frame starts with
 * an empty occupancy map; the remaining overlays only reserve (and query)
 * space.
 */
class LabelCollision {

    private val rects = ArrayList<FloatArray>()

    fun beginFrame() {
        rects.clear()
    }

    private fun isFree(left: Float, top: Float, right: Float, bottom: Float, pad: Float): Boolean {
        val l = left - pad
        val t = top - pad
        val r = right + pad
        val b = bottom + pad
        for (i in rects.indices) {
            val rc = rects[i]
            if (l < rc[2] && r > rc[0] && t < rc[3] && b > rc[1]) return false
        }
        return true
    }

    private fun occupy(left: Float, top: Float, right: Float, bottom: Float) {
        rects.add(floatArrayOf(left, top, right, bottom))
    }

    /**
     * Reserves space for one label, trying several staggered positions so that
     * as many labels as possible are shown side by side or offset from each
     * other instead of overlapping.
     *
     * [baselineShiftY] is the vertical step used for staggering, [maxShiftLines]
     * how many lines up/down are tried and [mirrorTextLeft] an alternative left
     * edge (e.g. the label mirrored to the other side of its anchor point) or
     * null when only vertical staggering is allowed. The returned
     * FloatArray is [textLeft, baselineY] to draw at, or null when no free
     * position was found.
     */
    fun place(
        textLeft: Float,
        baseline: Float,
        textWidth: Float,
        textHeight: Float,
        pad: Float,
        baselineShiftY: Float,
        maxShiftLines: Int,
        mirrorTextLeft: Float?,
        screenWidth: Float,
        screenHeight: Float
    ): FloatArray? {
        val candidates = ArrayList<Pair<Float, Float>>()
        fun add(x: Float, dy: Float) {
            candidates.add(x to dy)
        }
        add(textLeft, 0f)
        mirrorTextLeft?.let { add(it, 0f) }
        for (i in 1..maxShiftLines) {
            add(textLeft, -baselineShiftY * i)
            add(textLeft, baselineShiftY * i)
            mirrorTextLeft?.let { m ->
                add(m, -baselineShiftY * i)
                add(m, baselineShiftY * i)
            }
        }
        for ((x, dy) in candidates) {
            val baselineY = baseline + dy
            val top = baselineY - textHeight
            if (top + pad < 0 || baselineY - pad > screenHeight) continue
            if (x + pad < 0 || x + textWidth + pad > screenWidth) continue
            if (isFree(x, top, x + textWidth, baselineY, pad)) {
                occupy(x, top, x + textWidth, baselineY)
                return floatArrayOf(x, baselineY)
            }
        }
        return null
    }
}