package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject

class WorldMapPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class HorizonEvent(
        val seg: Int,
        val entry: Boolean,
        val alpha: Double,
        val sx: Float,
        val sy: Float
    )

    private companion object {
        const val ZERO_EPS = 1e-9
        const val ARC_STEP = 0.06
        const val PERIOD_MILLIS = 24000L
        const val START_LON_RAD = 20.0 * PI / 180.0
    }

    private val twoPi = 2.0 * PI

    private val density get() = resources.displayMetrics.density

    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var accentColor = 0
    private var landRings: List<DoubleArray>? = null

    private val runPath = Path()

    private val events = ArrayList<HorizonEvent>()
    private var scratchVis = BooleanArray(0)
    private var scratchX = FloatArray(0)
    private var scratchY = FloatArray(0)

    private var running = false
    private var startMillis = 0L
    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimationDelayed(this, 33)
        }
    }

    init {
        Thread {
            landRings = loadRings(context)
            ensureScratch(landRings?.maxOf { it.size / 2 } ?: 0)
            post { invalidate() }
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView == this) updateAnim()
    }

    private fun updateAnim() {
        val should = isAttachedToWindow && visibility == VISIBLE && isShown
        if (should && !running) {
            running = true
            startMillis = SystemClock.uptimeMillis()
            invalidate()
            postOnAnimationDelayed(frameTick, 33)
        } else if (!should) {
            stopAnim()
        }
    }

    private fun stopAnim() {
        running = false
        removeCallbacks(frameTick)
    }

    fun setColors(globe: Int, arc: Int) {
        accentColor = arc
        landPaint.color = arc
        rimPaint.color = arc
        rimPaint.alpha = 140
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = (minOf(w, h) / 2f) * 0.94f
        val cx = w / 2f
        val cy = h / 2f

        val elapsed = (SystemClock.uptimeMillis() - startMillis).coerceAtLeast(0L)
        val phi = START_LON_RAD + (elapsed % PERIOD_MILLIS) * twoPi / PERIOD_MILLIS

        canvas.drawCircle(cx, cy, r, oceanPaint)

        val clip = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)

        landRings?.let { rings ->
            for (ring in rings) {
                drawLandRing(canvas, ring, phi, cx, cy, r)
            }
        }

        canvas.restore()

        shadowPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.TRANSPARENT, Color.argb(110, 0, 0, 0)),
            floatArrayOf(0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, shadowPaint)
        shadowPaint.shader = null

        canvas.drawCircle(cx, cy, r, rimPaint)
    }

    private fun drawLandRing(canvas: Canvas, ring: DoubleArray, phi: Double, cx: Float, cy: Float, r: Float) {
        val n = ring.size / 2
        if (n < 3) return
        ensureScratch(n)
        val vis = scratchVis
        val xs = scratchX
        val ys = scratchY
        var anyVisible = false
        for (i in 0 until n) {
            val lon = ring[i * 2]
            val lat = ring[i * 2 + 1]
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val z = cosLat * cos(lon - phi)
            vis[i] = z > ZERO_EPS
            if (vis[i]) anyVisible = true
            xs[i] = (cx + cosLat * sin(lon - phi) * r).toFloat()
            ys[i] = (cy - sinLat * r).toFloat()
        }
        if (!anyVisible) return

        events.clear()
        for (i in 0 until n) {
            val j = (i + 1) % n
            if (vis[i] != vis[j]) {
                val alpha = horizonAlpha(
                    ring[i * 2], ring[i * 2 + 1],
                    ring[j * 2], ring[j * 2 + 1], phi
                )
                events.add(
                    HorizonEvent(
                        i, vis[j], alpha,
                        (cx + cos(alpha) * r).toFloat(),
                        (cy - sin(alpha) * r).toFloat()
                    )
                )
            }
        }

        runPath.reset()
        if (events.isEmpty()) {
            for (i in 0 until n) {
                if (i == 0) runPath.moveTo(xs[i], ys[i]) else runPath.lineTo(xs[i], ys[i])
            }
            runPath.close()
        } else {
            var first = 0
            while (first < events.size && !events[first].entry) first++
            if (first >= events.size) return
            val ecount = events.size
            val runs = ecount / 2
            for (j in 0 until runs) {
                val e = events[(first + 2 * j) % ecount]
                val x = events[(first + 2 * j + 1) % ecount]
                runPath.moveTo(e.sx, e.sy)
                var vcount = x.seg - e.seg
                if (vcount <= 0) vcount += n
                if (vcount > n) vcount = n
                val start = e.seg + 1
                for (k in 0 until vcount) {
                    val v = (start + k) % n
                    runPath.lineTo(xs[v], ys[v])
                }
                runPath.lineTo(x.sx, x.sy)
                appendHorizonArc(x.alpha, e.alpha, cx, cy, r)
                runPath.close()
            }
        }
        canvas.drawPath(runPath, landPaint)
    }

    private fun horizonAlpha(
        lonA: Double, latA: Double,
        lonB: Double, latB: Double,
        phi: Double
    ): Double {
        val ca = cos(latA)
        val sa = sin(latA)
        val cb = cos(latB)
        val sb = sin(latB)
        val fpA = lonA - phi
        val fpB = lonB - phi
        val fa = ca * cos(fpA)
        val fb = cb * cos(fpB)
        val t = fa / (fa - fb)
        val qy = ca * sin(fpA) + t * (cb * sin(fpB) - ca * sin(fpA))
        val qz = sa + t * (sb - sa)
        val len = sqrt(qy * qy + qz * qz)
        return atan2(qz / len, qy / len)
    }

    private fun appendHorizonArc(from: Double, to: Double, cx: Float, cy: Float, r: Float) {
        var delta = to - from
        delta = ((delta + PI) % twoPi + twoPi) % twoPi - PI
        if (abs(delta) < ZERO_EPS) return
        val steps = min(64, max(2, ceil(abs(delta) / ARC_STEP).toInt()))
        for (s in 1..steps) {
            val a = from + delta * s / steps
            runPath.lineTo((cx + cos(a) * r).toFloat(), (cy - sin(a) * r).toFloat())
        }
    }

    private fun ensureScratch(minSize: Int) {
        if (scratchVis.size >= minSize) return
        scratchVis = BooleanArray(minSize)
        scratchX = FloatArray(minSize)
        scratchY = FloatArray(minSize)
    }

    private fun loadRings(context: Context): List<DoubleArray> {
        val jsonText = context.assets.open("world_land.json")
            .bufferedReader().use { it.readText() }
        val root = JSONObject(jsonText)
        val features = root.getJSONArray("features")
        val rings = mutableListOf<DoubleArray>()
        for (i in 0 until features.length()) {
            val geom = features.getJSONObject(i).getJSONObject("geometry")
            when (geom.getString("type")) {
                "Polygon" -> parseRing(
                    geom.getJSONArray("coordinates").getJSONArray(0), rings
                )
                "MultiPolygon" -> {
                    val polys = geom.getJSONArray("coordinates")
                    for (j in 0 until polys.length()) {
                        parseRing(polys.getJSONArray(j).getJSONArray(0), rings)
                    }
                }
            }
        }
        return rings
    }

    private fun parseRing(ring: org.json.JSONArray, out: MutableList<DoubleArray>) {
        val n = ring.length()
        if (n < 3) return
        val arr = DoubleArray(n * 2)
        for (i in 0 until n) {
            val pt = ring.getJSONArray(i)
            arr[i * 2] = Math.toRadians(pt.getDouble(0))
            arr[i * 2 + 1] = Math.toRadians(pt.getDouble(1))
        }
        out.add(arr)
    }
}
