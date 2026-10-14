package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Dekorativer Hintergrund: kleine Flugzeuge ziehen langsam und in großen,
 * weichen Kurven über den Bildschirm. Hinter jedem Flugzeug bleibt eine
 * gestrichelte Spur, die mit der Zeit verblasst.
 *
 * Die Ansicht ist als Unterlage gedacht: Sie zeichnet nur Hintergrund und
 * Spuren, nicht über den Inhalt. Sie ist weder anklickbar noch fokussierbar,
 * Tippen geht also ungehindert an die darüberliegenden Listen und Kacheln.
 *
 * Die Animation läuft nur, solange die Ansicht sichtbar ist
 * ([onWindowVisibilityChanged]) und die Systemanimationen aktiv sind; sonst
 * wird kein Frame gezeichnet. Die Bahn selbst ist eine zufällige kubische Kurve
 * zwischen zwei gegenüberliegenden Bildrändern, einmal in [PATH_SAMPLES]
 * Stützstellen zerlegt. Aus dieser festen Punktfolge kommen Position und
 * Flugrichtung des Symbols, die Spur besteht aus zeitgestempelten Punkten, die
 * nach [TRAIL_FADE_MS] verschwunden sind.
 */
class PlaneBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * Ein aufgezeichneter Punkt der verblassenden Spur mit seinem Alter. [dist]
     * ist die Strecke vom Bahnanfang bis hierher und dient als fester Anker für
     * das Strichmuster, siehe [drawTrail].
     */
    private class TrailPoint(
        val x: Float,
        val y: Float,
        val timestamp: Long,
        val dist: Float
    )

    /** Ein Flugzeug samt seiner festen Bahn und der bisherigen Spur. */
    private class Plane(
        var startTime: Long,
        val durationMs: Long,
        val points: FloatArray,
        /** Kumulierte Strecke bis zu jeder Stützstelle, für [arcLengthAt]. */
        val cumulative: FloatArray,
        val startEdge: Int,
        val trail: MutableList<TrailPoint> = ArrayList(),
        var lastTrailAt: Long = 0L,
        var progress: Float = 0f,
        var angle: Float = 0f,
        var active: Boolean = true
    )

    private val random = Random()
    private val planes = mutableListOf<Plane>()

    private var animator: ValueAnimator? = null
    private var started = false
    private var lastSpawnAt = 0L

    private var density = 1f
    private var planeSize = 0f

    private val planeDrawable: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_flight)?.mutate()

    private val planeBounds = Rect()
    private val trailPath = Path()

    /** Strichmuster der Spur (an, aus) und dessen Gesamtlänge. */
    private var dashIntervals = FloatArray(0)
    private var dashPeriod = 1f

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        density = resources.displayMetrics.density
        planeSize = PLANE_SIZE_DP * density
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        planeDrawable?.setTint(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        )
        trailPaint.color = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        trailPaint.strokeWidth = 1.2f * density
        dashIntervals = floatArrayOf(3f * density, 3.5f * density)
        dashPeriod = dashIntervals.sum()
        trailPaint.pathEffect = DashPathEffect(dashIntervals, 0f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tryStart()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        tryStart()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) tryStart() else stop()
    }

    /**
     * Startet die Animation, sobald die Ansicht angebunden, sichtbar und
     * vermessen ist. Ohne diese Wache liefe der erste Aufruf (bei Größe 0)
     * ins Leere und es bliebe nichts zu sehen.
     */
    private fun tryStart() {
        if (started) return
        if (!isAttachedToWindow) return
        if (windowVisibility != VISIBLE) return
        if (width == 0 || height == 0) return
        if (animationsDisabled()) return

        started = true
        planes.clear()
        val now = SystemClock.uptimeMillis()
        lastSpawnAt = now
        // Beim Öffnen der Seite starten mehrere Flugzeuge verteilt über alle
        // vier Ränder (siehe [addPlane], das den Anflug-Vorsprung setzt).
        val offset = random.nextInt(4)
        repeat(INITIAL_PLANE_COUNT) { i ->
            addPlane(now, (offset + i) % 4)
        }

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TICK_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { onTick() }
        }
        animator?.start()
    }

    private fun stop() {
        if (!started) return
        started = false
        animator?.cancel()
        animator = null
        planes.clear()
        invalidate()
    }

    private fun onTick() {
        if (!started) return
        val now = SystemClock.uptimeMillis()
        if (planes.count { it.active } < MAX_PLANES && now - lastSpawnAt >= SPAWN_INTERVAL_MS) {
            addPlane(now, pickEdge())
            lastSpawnAt = now
        }
        invalidate()
    }

    /**
     * Wählt einen Startrand, den kein gerade fliegendes Flugzeug benutzt,
     * damit die Flugzeuge möglichst aus verschiedenen Richtungen kommen. Sind
     * alle vier Richtungen belegt, ist der Rand wieder frei wählbar.
     */
    private fun pickEdge(): Int {
        val used = planes.filter { it.active }.mapTo(mutableSetOf()) { it.startEdge }
        val free = (0..3).filter { it !in used }
        return if (free.isEmpty()) random.nextInt(4) else free[random.nextInt(free.size)]
    }

    /** Ob der Nutzer Systemanimationen abgeschaltet hat (Bedienungshilfen). */
    private fun animationsDisabled(): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    } catch (e: Exception) {
        false
    }

    private fun addPlane(now: Long, startEdge: Int) {
        if (width == 0 || height == 0) return
        val plane = createPlane(startEdge)
        // Die Bahn beginnt ausserhalb des Bildes ([edgePoint] mit Rand).
        // [entryFraction] nennt den Anteil, bei dem sie das Bild betritt; der
        // Vorsprung zieht die Startzeit so weit zurück, dass das Flugzeug rund
        // [ENTER_DELAY_MS] nach dem Erscheinen am Rand einfliegt statt erst
        // nach der ganzen Anflugstrecke.
        val entry = entryFraction(plane.points, width.toFloat(), height.toFloat()) *
            plane.durationMs
        val delay = ENTER_DELAY_MS + random.nextInt(ENTER_STAGGER_MS)
        plane.startTime = now + delay - entry.toLong()
        planes.add(plane)
    }

    /**
     * Baut eine neue Bahn: Start- und Zielpunkt liegen einander gegenüber,
     * beide außerhalb des Bildes. Die beiden Kontrollpunkte werden unabhängig
     * senkrecht zur Verbindung verschoben, wodurch große Bögen und S-Kurven
     * entstehen. Die Bahn kommt deshalb aus jeder Himmelsrichtung und verlässt
     * das Bild an der gegenüberliegenden Seite. Die Startzeit setzt [addPlane].
     */
    private fun createPlane(startEdge: Int): Plane {
        val w = width.toFloat()
        val h = height.toFloat()
        val margin = max(w, h) * 0.12f

        val endEdge = (startEdge + 2) % 4
        val start = edgePoint(startEdge, w, h, margin)
        val end = edgePoint(endEdge, w, h, margin)

        val dx = end[0] - start[0]
        val dy = end[1] - start[1]
        val len = hypot(dx, dy).coerceAtLeast(1f)
        // Beide Kontrollpunkte weichen unabhängig seitlich aus. Gleiche
        // Richtung ergibt einen Bogen, entgegengesetzte eine S-Kurve mit zwei
        // Bögen. Die Stellen der Auslenkung streuen, damit die Bahnen sich
        // unterscheiden.
        val t1 = 0.2f + random.nextFloat() * 0.18f
        val t2 = 0.62f + random.nextFloat() * 0.18f
        val bend1 = (random.nextFloat() * 2f - 1f) * CURVE_BEND * len
        val bend2 = (random.nextFloat() * 2f - 1f) * CURVE_BEND * len
        val controls = curveControls(start[0], start[1], end[0], end[1], t1, t2, bend1, bend2)
        val c1x = controls[0]
        val c1y = controls[1]
        val c2x = controls[2]
        val c2y = controls[3]

        val points = sampleCubic(
            start[0], start[1], c1x, c1y, c2x, c2y, end[0], end[1], PATH_SAMPLES
        )
        val cumulative = cumulativeLengths(points)

        val speed = PLANE_SPEED_PX_PER_S * (0.8f + random.nextFloat() * 0.4f)
        val duration = max(MIN_DURATION_MS, (cumulative.last() / speed * 1000f).toLong())
        return Plane(
            startTime = 0L,
            durationMs = duration,
            points = points,
            cumulative = cumulative,
            startEdge = startEdge
        )
    }

    /** Ein zufälliger Punkt auf dem Bildrand [edge] (0 oben, 1 rechts, …). */
    private fun edgePoint(edge: Int, w: Float, h: Float, margin: Float): FloatArray =
        when (edge) {
            0 -> floatArrayOf(random.nextFloat() * w, -margin)
            1 -> floatArrayOf(w + margin, random.nextFloat() * h)
            2 -> floatArrayOf(random.nextFloat() * w, h + margin)
            else -> floatArrayOf(-margin, random.nextFloat() * h)
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!started) return
        val now = SystemClock.uptimeMillis()

        for (plane in planes) {
            val elapsed = now - plane.startTime
            if (elapsed < 0) continue
            plane.progress = (elapsed.toFloat() / plane.durationMs).coerceIn(0f, 1f)
            val pos = pointAt(plane.points, plane.progress)
            plane.angle = planeRotation(plane.points, plane.progress)

            if (plane.progress < 1f && now - plane.lastTrailAt >= TRAIL_SAMPLE_MS) {
                plane.trail.add(
                    TrailPoint(
                        pos[0], pos[1], now,
                        arcLengthAt(plane.cumulative, plane.progress)
                    )
                )
                plane.lastTrailAt = now
            } else if (plane.progress >= 1f) {
                plane.active = false
            }
        }

        // Alte Spurpunkte verwerfen; Flugzeuge ohne Spur und ohne Flug sind
        // fertig und fallen aus der Liste.
        for (plane in planes) {
            while (plane.trail.isNotEmpty() &&
                now - plane.trail.first().timestamp > TRAIL_FADE_MS
            ) {
                plane.trail.removeAt(0)
            }
        }
        planes.removeAll { !it.active && it.trail.isEmpty() }

        for (plane in planes) drawTrail(canvas, plane, now)
        for (plane in planes) if (plane.active) drawPlane(canvas, plane)
    }

    private fun drawPlane(canvas: Canvas, plane: Plane) {
        val drawable = planeDrawable ?: return
        val pos = pointAt(plane.points, plane.progress)
        val half = planeSize / 2f
        canvas.save()
        canvas.rotate(plane.angle, pos[0], pos[1])
        planeBounds.set(
            (pos[0] - half).toInt(),
            (pos[1] - half).toInt(),
            (pos[0] + half).toInt(),
            (pos[1] + half).toInt()
        )
        drawable.bounds = planeBounds
        drawable.draw(canvas)
        canvas.restore()
    }

    /**
     * Zeichnet die Spur in einigen Blöcken. Jeder Block bekommt den
     * Verblassungsgrad seines jüngsten Punktes, sodass die Linie vom Flugzeug
     * nach hinten weich ausläuft. Wenige Blöcke genügen und sparen Zeichenaufrufe.
     *
     * Das Strichmuster ist an der Bahn verankert: Der Muster-Offset kommt aus
     * der Strecke vom Bahnanfang bis zum Blockbeginn ([TrailPoint.dist]). Ohne
     * diesen Anker begönne das Muster an jeder, sich ständig verschiebenden
     * Blockgrenze neu und die Striche würden wandern.
     */
    private fun drawTrail(canvas: Canvas, plane: Plane, now: Long) {
        val trail = plane.trail
        if (trail.size < 2) return
        val chunk = max(2, (trail.size + TRAIL_CHUNKS - 1) / TRAIL_CHUNKS)
        var start = 0
        while (start < trail.size - 1) {
            val end = min(trail.size, start + chunk)
            val newest = trail[end - 1]
            val life = 1f - (now - newest.timestamp).toFloat() / TRAIL_FADE_MS
            val alpha = (life.coerceIn(0f, 1f) * TRAIL_MAX_ALPHA).toInt()
            if (alpha > 0) {
                trailPaint.pathEffect = DashPathEffect(
                    dashIntervals,
                    trail[start].dist % dashPeriod
                )
                trailPath.reset()
                trailPath.moveTo(trail[start].x, trail[start].y)
                for (i in start + 1 until end) {
                    trailPath.lineTo(trail[i].x, trail[i].y)
                }
                trailPaint.alpha = alpha
                canvas.drawPath(trailPath, trailPaint)
            }
            start = end - 1
        }
    }

    /** Öffentlich, damit die reine Geometrie ohne Android testbar ist. */
    companion object {
        /** Höchstzahl gleichzeitig sichtbarer Flugzeuge. */
        const val MAX_PLANES = 6

        /** Stützstellen der kubischen Bahn. */
        const val PATH_SAMPLES = 48

        /**
         * Seitliche Auslenkung der Kontrollpunkte als Anteil der Sehnenlänge.
         * Gross genug für deutlich geschwungene Bahnen, klein genug, dass die
         * Kurve das Bild nicht für längere Zeit verlässt.
         */
        const val CURVE_BEND = 0.28f

        /** Kubische Bézier an Parameter [t] (0..1); Ergebnis `[x, y]`. */
        fun cubicPoint(
            x0: Float, y0: Float,
            c1x: Float, c1y: Float,
            c2x: Float, c2y: Float,
            x1: Float, y1: Float,
            t: Float
        ): FloatArray {
            val u = 1f - t
            val a = u * u * u
            val b = 3f * u * u * t
            val c = 3f * u * t * t
            val d = t * t * t
            return floatArrayOf(
                a * x0 + b * c1x + c * c2x + d * x1,
                a * y0 + b * c1y + c * c2y + d * y1
            )
        }

        /**
         * Kontrollpunkte einer geschwungenen Bahn von `(x0, y0)` nach `(x1, y1)`.
         * [bend1] und [bend2] sind seitliche Auslenkungen senkrecht zur Sehne
         * (in Pixeln, positiv in Richtung der Normalen nach links). Gleiche
         * Vorzeichen geben einen Bogen, entgegengesetzte eine S-Kurve.
         * Ergebnis: `[c1x, c1y, c2x, c2y]`.
         */
        fun curveControls(
            x0: Float, y0: Float, x1: Float, y1: Float,
            t1: Float, t2: Float, bend1: Float, bend2: Float
        ): FloatArray {
            val dx = x1 - x0
            val dy = y1 - y0
            val len = hypot(dx, dy)
            if (len == 0f) return floatArrayOf(x0, y0, x0, y0)
            val nx = -dy / len
            val ny = dx / len
            return floatArrayOf(
                x0 + dx * t1 + nx * bend1,
                y0 + dy * t1 + ny * bend1,
                x0 + dx * t2 + nx * bend2,
                y0 + dy * t2 + ny * bend2
            )
        }

        /** [samples] Stützstellen der Kubik; `x, y` je Punkt hintereinander. */
        fun sampleCubic(
            x0: Float, y0: Float,
            c1x: Float, c1y: Float,
            c2x: Float, c2y: Float,
            x1: Float, y1: Float,
            samples: Int
        ): FloatArray {
            val points = FloatArray(samples * 2)
            for (i in 0 until samples) {
                val t = i / (samples - 1f)
                val p = cubicPoint(x0, y0, c1x, c1y, c2x, c2y, x1, y1, t)
                points[i * 2] = p[0]
                points[i * 2 + 1] = p[1]
            }
            return points
        }

        /** Länge der Punktfolge [points] in Pixeln. */
        fun pathLength(points: FloatArray): Float {
            val count = points.size / 2
            var length = 0f
            for (i in 1 until count) {
                length += hypot(
                    points[i * 2] - points[(i - 1) * 2],
                    points[i * 2 + 1] - points[(i - 1) * 2 + 1]
                )
            }
            return length
        }

        /**
         * Kumulierte Strecke bis zu jeder Stützstelle von [points]; das letzte
         * Element ist die Gesamtlänge. Dient als fester Bezug für das
         * Strichmuster der Spur.
         */
        fun cumulativeLengths(points: FloatArray): FloatArray {
            val count = points.size / 2
            val result = FloatArray(count)
            for (i in 1 until count) {
                result[i] = result[i - 1] + hypot(
                    points[i * 2] - points[(i - 1) * 2],
                    points[i * 2 + 1] - points[(i - 1) * 2 + 1]
                )
            }
            return result
        }

        /**
         * Strecke vom Bahnanfang bis zum Anteil [fraction] (0..1), linear
         * zwischen den kumulierten Längen [cumulative] interpoliert.
         */
        fun arcLengthAt(cumulative: FloatArray, fraction: Float): Float {
            val last = cumulative.size - 1
            if (last <= 0) return 0f
            val pos = fraction.coerceIn(0f, 1f) * last
            val index = pos.toInt().coerceIn(0, last - 1)
            val t = pos - index
            return cumulative[index] + (cumulative[index + 1] - cumulative[index]) * t
        }

        /**
         * Anteil (0..1) der Bahn [points], bei dem sie erstmals das Rechteck
         * `0..width` / `0..height` betritt. Die Bahn beginnt ausserhalb des
         * Bildes; mit diesem Wert lässt sich die Startzeit so wählen, dass ein
         * Flugzeug erst nach der gewünschten Zeit sichtbar wird.
         */
        fun entryFraction(points: FloatArray, width: Float, height: Float): Float {
            val count = points.size / 2
            if (count < 2) return 0f
            for (i in 1 until count) {
                val x1 = points[i * 2]
                val y1 = points[i * 2 + 1]
                if (x1 < 0f || x1 > width || y1 < 0f || y1 > height) continue
                val x0 = points[(i - 1) * 2]
                val y0 = points[(i - 1) * 2 + 1]
                return (i - 1 + entryT(x0, y0, x1, y1, width, height)) / (count - 1f)
            }
            return 0f
        }

        /** Anteil auf dem Abschnitt `(x0,y0)->(x1,y1)`, ab dem er im Bild liegt. */
        private fun entryT(
            x0: Float, y0: Float, x1: Float, y1: Float,
            width: Float, height: Float
        ): Float {
            val tx = when {
                x0 < 0f -> (0f - x0) / (x1 - x0)
                x0 > width -> (width - x0) / (x1 - x0)
                else -> 0f
            }
            val ty = when {
                y0 < 0f -> (0f - y0) / (y1 - y0)
                y0 > height -> (height - y0) / (y1 - y0)
                else -> 0f
            }
            return max(tx, ty).coerceIn(0f, 1f)
        }

        /**
         * Position auf [points] bei [fraction] (0..1), linear zwischen den
         * beiden benachbarten Stützstellen; Ergebnis `[x, y]`.
         */
        fun pointAt(points: FloatArray, fraction: Float): FloatArray {
            val last = points.size / 2 - 1
            if (last < 0) return floatArrayOf(0f, 0f)
            val pos = fraction.coerceIn(0f, 1f) * last
            val index = pos.toInt().coerceIn(0, (last - 1).coerceAtLeast(0))
            val t = pos - index
            val next = (index + 1).coerceAtMost(last)
            return floatArrayOf(
                points[index * 2] + (points[next * 2] - points[index * 2]) * t,
                points[index * 2 + 1] + (points[next * 2 + 1] - points[index * 2 + 1]) * t
            )
        }

        /**
         * Drehwinkel des nach Norden zeigenden Symbols an [fraction], damit es
         * in Fahrtrichtung schaut. Nutzt dieselbe Drehung wie die Streckenkarte.
         */
        fun planeRotation(points: FloatArray, fraction: Float): Float {
            val last = points.size / 2 - 1
            if (last < 0) return 0f
            val index = (fraction.coerceIn(0f, 1f) * last).toInt().coerceIn(0, last)
            val from = (index - 1).coerceAtLeast(0)
            val to = (index + 1).coerceAtMost(last)
            return RouteMiniMapView.planeRotation(
                points[to * 2] - points[from * 2],
                points[to * 2 + 1] - points[from * 2 + 1]
            )
        }

        /** Zahl der Flugzeuge, die beim Öffnen der Seite starten. */
        private const val INITIAL_PLANE_COUNT = 6

        /** Nach dieser Zeit (ms) soll ein Flugzeug am Rand erscheinen. */
        private const val ENTER_DELAY_MS = 1000L

        /** Kleiner zusätzlicher Versatz beim Einflug (ms). */
        private const val ENTER_STAGGER_MS = 150

        /** Abstand zwischen zwei neuen Flugzeugen (ms). */
        private const val SPAWN_INTERVAL_MS = 2500L

        /** Grundgeschwindigkeit der Flugzeuge in Pixeln je Sekunde. */
        private const val PLANE_SPEED_PX_PER_S = 20f

        /** Kürzeste Flugdauer, auch bei sehr kurzer Strecke. */
        private const val MIN_DURATION_MS = 20000L

        /** Nach dieser Zeit ist ein Spurpunkt verschwunden (ms). */
        private const val TRAIL_FADE_MS = 32000L

        /** Abstand zweier aufgezeichneter Spurpunkte (ms). */
        private const val TRAIL_SAMPLE_MS = 50L

        /** Zahl der Blöcke, in die die Spur zum Verblassen geteilt wird. */
        private const val TRAIL_CHUNKS = 10

        /** Höchste Deckkraft der Spur (0..255), also direkt am Flugzeug. */
        private const val TRAIL_MAX_ALPHA = 150

        /** Takt des Frame-Animators (ms). */
        private const val TICK_MS = 1000L

        /** Kantenlänge des Flugzeugsymbols (dp). */
        private const val PLANE_SIZE_DP = 16f
    }
}
