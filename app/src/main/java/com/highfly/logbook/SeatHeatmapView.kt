package com.highfly.logbook

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

/**
 * Schema eines Flugzeugs von oben mit Heatmap der erfassten Sitzplätze.
 *
 * Das Flugzeug wird als Silhouette im Stil des Material-Icons „flight" gezeichnet:
 * abgerundete Nase und abgerundetes Heck, schlanker Rumpf, gepfeilte Tragflächen
 * und Höhenleitwerk am Heck.
 * Die Kabine ist in Abschnitte nach Reiseklasse geteilt (z. B. First, Business,
 * Premium Eco, Economy), dezent mit den jeweiligen Klassenfarben ausgefüllt –
 * ausschließlich innerhalb des Rumpfes. Nicht belegte Reihen (z. B. 12–14 bei
 * der A330) werden ausgeblendet, damit die Abstände zwischen den Klassen
 * gleich groß sind; beschriftet werden nur Klassen mit erfassten Sitzplätzen.
 *
 * Darüber liegt die Heatmap: Jeder erfasste Sitzplatz erscheint als farbige
 * Fläche mit festem Kern um seine Position. Je häufiger ein Platz geflogen
 * wurde, desto kräftiger die Farbe und desto größer die Fläche.
 *
 * Grundlage ist ein [AircraftLayout.Layout], das anhand des Flugzeugtyps ermittelt
 * wird ([AircraftLayout.resolve]). Es bestimmt die Anzahl der Reihen, die
 * Klassenabschnitte und die Sitzbuchstaben (A ist immer links am Fenster, der
 * rechte Fensterplatz ist F bei 3+3 oder K bei 3+4+3).
 *
 * Hat ein Eintrag eine Reiseklasse, die laut Layout nicht zur Reihe passt (z. B.
 * Reihe 20A in Business auf einer A320 mit Business Reihe 1–6), landet der
 * Sitzplatz trotzdem im passenden Klassenbereich. Alle Sitzplätze eines
 * Bereichs werden proportional auf dessen Reihen verteilt (mit einem halben
 * Reihenabstand zum Rand), sodass die Abstände der Reihen zueinander erhalten
 * bleiben und kein Punkt den Klassenbereich verlässt.
 */
class SeatHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Dezente Deckkraft der Kabinen-Hintergrundfarben. */
        private const val SECTION_ALPHA = 0.15f

        /** Basis-Deckkraft einer Heatmap-Fläche (einmalig geflogener Platz). */
        private const val HEAT_MIN_ALPHA = 0.55f

        /** Maximale Heatmap-Deckkraft (häufigster Platz). */
        private const val HEAT_MAX_ALPHA = 0.95f

        /** Basis-Radius einer Heatmap-Fläche relativ zur Sitz-Zellengröße. */
        private const val HEAT_MIN_RADIUS = 0.8f

        /** Maximaler Radius einer Heatmap-Fläche relativ zur Sitz-Zellengröße. */
        private const val HEAT_MAX_RADIUS = 2.2f

        /** Weichzeichnung der Heatmap-Flächen. */
        private const val HEAT_BLUR_DP = 2f

        /**
         * Index des Abschnitts, in den ein Sitzplatz der Reihe [row] mit der
         * Klasse [className] gehört. Die Klasse gewinnt (z. B. Reihe 20A in
         * Business auf einer A320 → Business-Abschnitt). Ohne passende Klasse
         * entscheidet die Reihe; Reihen in einer Lücke (z. B. 12–14 bei der
         * A330) oder hinter dem letzten Abschnitt gehören zum Abschnitt davor,
         * Reihen vor dem ersten Abschnitt zum ersten. So landet jeder
         * Sitzplatz in einem Klassenbereich.
         */
        fun sectionIndexFor(
            layout: AircraftLayout.Layout,
            row: Int,
            className: String?
        ): Int {
            className?.let { name ->
                layout.sectionForClass(name)?.let { return layout.sections.indexOf(it) }
            }
            layout.sectionForRow(row)?.let { return layout.sections.indexOf(it) }
            // Reihe in einer Lücke oder hinter dem letzten Abschnitt:
            // der letzte Abschnitt, der vor der Reihe endet.
            var last = 0
            for (i in layout.sections.indices) {
                if (layout.sections[i].endRow < row) last = i else break
            }
            return last
        }

        /**
         * Relative Position (0..1) der Reihe [row] innerhalb eines Abschnitts,
         * der bei [startRow] beginnt und [sectionRows] Reihen umfasst.
         * [seatRows] sind die Reihen, die in diesem Abschnitt tatsächlich
         * Sitzplätze haben.
         *
         * Liegen alle belegten Reihen innerhalb des Abschnitts, bleibt die
         * Position unverändert (Mitte der Reihe). Liegen Reihen außerhalb
         * (z. B. Reihe 20 in Business auf einer A320), werden die belegten
         * Reihen proportional auf den Abschnitt gestaucht, sodass alle
         * Sitzplätze im Klassenbereich bleiben – mit einem halben
         * Reihenabstand zum Rand. Reihenfolge und Abstandsverhältnisse
         * der Reihen zueinander bleiben erhalten.
         */
        fun relativeRowPosition(
            startRow: Int,
            sectionRows: Int,
            seatRows: List<Int>,
            row: Int
        ): Float {
            val rows = sectionRows.coerceAtLeast(1)
            val raw = (row - startRow + 0.5f) / rows
            val half = 0.5f / rows
            if (seatRows.isEmpty()) return raw.coerceIn(half, 1f - half)
            val lo = seatRows.minOf { (it - startRow + 0.5f) / rows }
            val hi = seatRows.maxOf { (it - startRow + 0.5f) / rows }
            return when {
                // Alle belegten Reihen innerhalb des Abschnitts:
                // Position wie gehabt (Mitte der Reihe).
                lo >= half && hi <= 1f - half -> raw
                // Nur eine Reihe außerhalb: an den jeweiligen Rand ziehen.
                hi <= lo -> if (raw > 1f) 1f - half else half
                // Reihen außerhalb: proportional auf den nutzbaren Bereich
                // stauchen, Reihenfolge und Abstände bleiben erhalten.
                else -> half + (raw - lo) / (hi - lo) * (1f - 2f * half)
            }
        }
    }

    /** Aktuelles Kabinenlayout. */
    private var layout: AircraftLayout.Layout = AircraftLayout.DEFAULT

    /** Häufigkeit und Klasse je Sitzplatz ("1A" → SeatHeatData). */
    private var seatHeat: Map<String, SeatStats.SeatHeatData> = emptyMap()

    /** Höchstwert der Heatmap, für die Farbskalierung. */
    private var maxCount: Int = 1

    // --- Paints ---

    private val planeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MaterialColors.getColor(
            this@SeatHeatmapView,
            com.google.android.material.R.attr.colorSurfaceContainerLow
        )
    }

    private val planeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = MaterialColors.getColor(
            this@SeatHeatmapView,
            com.google.android.material.R.attr.colorOutline
        )
    }

    private val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val heatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(HEAT_BLUR_DP * resources.displayMetrics.density, BlurMaskFilter.Blur.NORMAL)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.density
        color = MaterialColors.getColor(
            this@SeatHeatmapView,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        )
    }

    private val cockpitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MaterialColors.getColor(
            this@SeatHeatmapView,
            com.google.android.material.R.attr.colorSurfaceContainerHighest
        )
    }

    private val accentColor: Int = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorPrimary
    )

    private val classColors: Map<String, Int> = mapOf(
        "First" to ContextCompat.getColor(context, R.color.class_first_bg),
        "Business" to ContextCompat.getColor(context, R.color.class_business_bg),
        "Premium Eco" to ContextCompat.getColor(context, R.color.class_premium_economy_bg),
        "Economy" to ContextCompat.getColor(context, R.color.class_economy_bg)
    )

    /** Setzt das Kabinenlayout und zeichnet neu. */
    fun setLayout(newLayout: AircraftLayout.Layout) {
        layout = newLayout
        invalidate()
    }

    /** Setzt die Heatmap-Daten. Sitzplätze ohne Eintrag werden nicht übergeben. */
    fun setHeatmap(heat: Map<String, SeatStats.SeatHeatData>) {
        seatHeat = heat
        maxCount = heat.values.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density
        val totalRows = layout.totalRows

        // --- Abmessungen ---
        // Das Flugzeug ist zentriert auf dem Bildschirm; rechts daneben
        // stehen die Bereichsbeschriftungen (First, Business, …).
        val margin = 16f * density
        val planeLeft = margin
        val planeRight = w - margin
        val planeTop = margin
        val planeBottom = h - margin
        val planeWidth = planeRight - planeLeft
        val planeHeight = planeBottom - planeTop
        val planeCenterX = w / 2f

        // Rumpf-Breite: breit genug für Sitzplätze, aber schlank.
        val fuselageWidth = planeWidth * 0.22f
        val fuselageLeft = planeCenterX - fuselageWidth / 2f
        val fuselageRight = planeCenterX + fuselageWidth / 2f

        // --- Proportionen (an ic_flight angelehnt) ---
        // Nase: abgerundet, ~6 % der Flugzeuglänge
        val noseHeight = planeHeight * 0.06f
        // Cockpit-Bereich: klein, ~4 %
        val cockpitHeight = planeHeight * 0.04f
        // Kabine: von nach dem Cockpit bis zum Leitwerk
        val cabinTop = planeTop + noseHeight + cockpitHeight
        val tailRootY = planeTop + planeHeight * 0.82f
        val cabinBottom = tailRootY
        val cabinHeight = cabinBottom - cabinTop
        val rowHeight = cabinHeight / totalRows

        // Tragflächen: gepfeilt, Wurzel bei ~32 %, Spitze bei ~50–56 %.
        // Halbe Spannweite ~42 % der Flugzeugbreite → Flügel breiter als Leitwerk.
        val wingRootLeadY = planeTop + planeHeight * 0.32f
        val wingTipLeadY = planeTop + planeHeight * 0.50f
        val wingTipTrailY = planeTop + planeHeight * 0.56f
        val wingRootTrailY = planeTop + planeHeight * 0.52f
        val halfWingspan = planeWidth * 0.42f

        // Höhenleitwerk: gepfeilt, Wurzel bei ~82 %, Spitze bei ~88–92 %.
        // Im ic_flight ist das Leitwerk etwa 37 % der Spannweite der Tragfläche.
        val tailRootLeadY = tailRootY
        val tailTipLeadY = planeTop + planeHeight * 0.88f
        val tailTipTrailY = planeTop + planeHeight * 0.92f
        val tailRootTrailY = planeTop + planeHeight * 0.90f
        val halfTailspan = halfWingspan * 0.40f

        // Heck: läuft abgerundet auf die Mitte zu
        val tailConeY = planeBottom

        // --- Flugzeug-Silhouette (ein Pfad, wie ic_flight) ---
        // Vorne (Nase) und hinten (Heck) abgerundet statt spitz zulaufend.
        val planePath = Path().apply {
            // Start an der Nase (rechte Hälfte, die Rundung folgt zum Schluss)
            moveTo(fuselageRight, planeTop + noseHeight)

            // Rechte Seite: Rumpf → Tragfläche → Leitwerk → Heck
            // Rumpf vorne (gerade Seite)
            lineTo(fuselageRight, wingRootLeadY)
            // Tragfläche: gepfeilte Vorderkante bis zur Spitze
            lineTo(planeCenterX + halfWingspan, wingTipLeadY)
            // Tragflächen-Spitze (kurze Kante)
            lineTo(planeCenterX + halfWingspan, wingTipTrailY)
            // Tragfläche: gepfeilte Hinterkante zurück zum Rumpf
            lineTo(fuselageRight, wingRootTrailY)
            // Rumpf hinten (gerade Seite bis zum Leitwerk)
            lineTo(fuselageRight, tailRootLeadY)
            // Höhenleitwerk: gepfeilte Vorderkante bis zur Spitze
            lineTo(planeCenterX + halfTailspan, tailTipLeadY)
            // Leitwerk-Spitze
            lineTo(planeCenterX + halfTailspan, tailTipTrailY)
            // Leitwerk: Hinterkante zurück zum Rumpf
            lineTo(fuselageRight, tailRootTrailY)
            // Heck: weit abgerundet zur Mitte. Die Kontrollpunkte sitzen senkrecht
            // unter den Seiten, dadurch geht die Rundung nahtlos in den geraden
            // Rumpf über und erreicht die volle Länge.
            val tailDrop = (tailConeY - tailRootTrailY) / 3f
            cubicTo(fuselageRight, tailConeY + tailDrop, fuselageLeft, tailConeY + tailDrop,
                fuselageLeft, tailRootTrailY)

            // Linke Seite: Leitwerk → Tragfläche → Rumpf → Nase
            // Höhenleitwerk: Hinterkante zurück (gespiegelt)
            lineTo(planeCenterX - halfTailspan, tailTipTrailY)
            // Leitwerk-Spitze
            lineTo(planeCenterX - halfTailspan, tailTipLeadY)
            // Höhenleitwerk: Vorderkante zurück zum Rumpf
            lineTo(fuselageLeft, tailRootLeadY)
            // Rumpf hinten (gerade Seite)
            lineTo(fuselageLeft, wingRootTrailY)
            // Tragfläche: gepfeilte Hinterkante bis zur Spitze
            lineTo(planeCenterX - halfWingspan, wingTipTrailY)
            // Tragflächen-Spitze
            lineTo(planeCenterX - halfWingspan, wingTipLeadY)
            // Tragfläche: gepfeilte Vorderkante zurück zum Rumpf
            lineTo(fuselageLeft, wingRootLeadY)
            // Rumpf vorne (gerade Seite)
            lineTo(fuselageLeft, planeTop + noseHeight)
            // Nase: weit abgerundet. Die Kontrollpunkte sitzen senkrecht über den
            // Seiten, dadurch geht die Rundung nahtlos in den geraden Rumpf über
            // und erreicht die volle Länge.
            cubicTo(fuselageLeft, planeTop - noseHeight / 3f, fuselageRight, planeTop - noseHeight / 3f,
                fuselageRight, planeTop + noseHeight)
            close()
        }

        // --- Rumpf-Innenpfad (für Clipping: Kabinenbereiche + Heatmap) ---
        // Der Innenpfad folgt dem Rumpf, ohne Tragflächen und Leitwerk.
        // Vorne (Nase) und hinten (Heck) abgerundet wie ein echtes Flugzeug.
        val fuselageInnerPath = Path().apply {
            val noseR = rowHeight * 1.5f // Rundung vorne
            val tailR = rowHeight * 1.5f // Rundung hinten
            // Start an der linken Seite unterhalb der Bugrundung
            moveTo(fuselageLeft, cabinTop + noseR)
            // Bug: abgerundete Kurve von links nach rechts über die Spitze
            quadTo(planeCenterX, cabinTop, fuselageRight, cabinTop + noseR)
            // Rechte Seite gerade nach unten
            lineTo(fuselageRight, cabinBottom - tailR)
            // Heck: abgerundete Kurve von rechts nach links unter der Spitze
            quadTo(planeCenterX, cabinBottom, fuselageLeft, cabinBottom - tailR)
            // Linke Seite zurück nach oben (close schliesst automatisch)
            close()
        }

        // --- Flugzeug zeichnen ---
        canvas.drawPath(planePath, planeFillPaint)

        // --- Kabinenbereiche + Heatmap (geclippt auf den Rumpf-Innenraum) ---
        canvas.save()
        canvas.clipPath(fuselageInnerPath)

        // Sitzbereich innerhalb des Rumpfes
        val seatAreaLeft = fuselageLeft + fuselageWidth * 0.10f
        val seatAreaRight = fuselageRight - fuselageWidth * 0.10f
        val seatAreaWidth = seatAreaRight - seatAreaLeft

        // Gang-Breiten
        val seatLetters = layout.seatLetters
        val aisleAfter = layout.aisleAfter
        val aisleWidth = seatAreaWidth * 0.06f
        val totalAisleWidth = aisleWidth * aisleAfter.size.coerceAtLeast(1)
        val seatsWidth = seatAreaWidth - totalAisleWidth
        val cellWidth = seatsWidth / seatLetters.size

        // X-Position eines Sitzes (Mittelpunkt)
        fun seatCenterX(letterIndex: Int): Float {
            var x = seatAreaLeft
            for (i in 0 until letterIndex) {
                x += cellWidth
                if (i in aisleAfter) x += aisleWidth
            }
            return x + cellWidth / 2f
        }

        // --- Anzeige-Geometrie der Klassenabschnitte ---
        // Nicht belegte Reihen (z. B. 12–14 bei der A330) werden ausgeblendet:
        // Die belegten Reihen dehnen sich gleichmäßig über die Kabine, dadurch
        // sind die Abstände zwischen den Klassen überall gleich groß.
        val sectionGap = 2f * density
        val sectionRows = layout.sections.map { (it.endRow - it.startRow + 1).coerceAtLeast(1) }
        val assignedRows = sectionRows.sum().coerceAtLeast(1)
        val blockRowHeight = cabinHeight / assignedRows
        val sectionBlockTops = FloatArray(layout.sections.size)
        val sectionBlockHeights = FloatArray(layout.sections.size)
        run {
            var y = cabinTop
            for (i in layout.sections.indices) {
                val h = sectionRows[i] * blockRowHeight
                sectionBlockTops[i] = y
                sectionBlockHeights[i] = h
                y += h
            }
        }

        // Reihen mit Sitzplätzen je Abschnitt. Sitze, deren Klasse laut Layout
        // nicht zur Reihe passt (z. B. Reihe 20A in Business auf einer A320),
        // landen über sectionIndexFor im genannten Klassenbereich.
        val blockRows = Array(layout.sections.size) { mutableListOf<Int>() }
        for ((seatKey, heatData) in seatHeat) {
            val parsed = SeatStats.parse(seatKey, layout) ?: continue
            if (parsed.letter !in seatLetters) continue
            blockRows[sectionIndexFor(layout, parsed.row, heatData.className)]
                .add(parsed.row)
        }

        // Y-Mitte der Reihe [row] innerhalb des Abschnitts [sectionIdx].
        // Die belegten Reihen werden proportional auf den Abschnitt verteilt
        // (mit halbem Reihenabstand zum Rand), damit die Relationen der
        // Reihen zueinander erhalten bleiben und kein Punkt den
        // Klassenbereich verlässt.
        fun blockCenterY(sectionIdx: Int, row: Int): Float {
            val section = layout.sections[sectionIdx]
            val relRow = relativeRowPosition(
                section.startRow, sectionRows[sectionIdx], blockRows[sectionIdx], row
            )
            return sectionBlockTops[sectionIdx] + relRow * sectionBlockHeights[sectionIdx]
        }

        // Welche Abschnitte haben mindestens einen erfassten Sitzplatz?
        // (genau die Abschnitte, in denen Punkte gezeichnet werden)
        val sectionsWithSeats =
            BooleanArray(layout.sections.size) { blockRows[it].isNotEmpty() }

        // --- Klassenabschnitte (mit kleinem Abstand zwischen den Klassen) ---
        for (i in layout.sections.indices) {
            val color = classColors[layout.sections[i].className] ?: continue
            // Kleiner Abstand zwischen den Abschnitten: oben und unten
            // je die Hälfte der Lücke einziehen.
            val top = sectionBlockTops[i] + sectionGap / 2f
            val bottom = sectionBlockTops[i] + sectionBlockHeights[i] - sectionGap / 2f
            sectionPaint.color = Color.argb(
                (255 * SECTION_ALPHA).toInt(),
                Color.red(color), Color.green(color), Color.blue(color)
            )
            canvas.drawRect(fuselageLeft, top, fuselageRight, bottom, sectionPaint)
        }

        // --- Heatmap ---
        if (seatHeat.isNotEmpty()) {
            for ((seatKey, heatData) in seatHeat) {
                val parsed = SeatStats.parse(seatKey, layout) ?: continue
                val letterIdx = seatLetters.indexOf(parsed.letter)
                if (letterIdx < 0) continue

                val seatX = seatCenterX(letterIdx)

                // Y-Position: Im Klassenbereich des Eintrags (die Klasse
                // gewinnt, sonst die Reihe); Reihen außerhalb des Abschnitts
                // werden proportional hineingestaucht.
                val seatY = blockCenterY(
                    sectionIndexFor(layout, parsed.row, heatData.className), parsed.row
                )

                val fraction = heatData.count.toFloat() / maxCount
                val alpha = HEAT_MIN_ALPHA + fraction * (HEAT_MAX_ALPHA - HEAT_MIN_ALPHA)
                val radius = (HEAT_MIN_RADIUS + fraction * (HEAT_MAX_RADIUS - HEAT_MIN_RADIUS)) *
                    cellWidth * 0.5f

                // Solider Kern mit weichem Rand: Der Punkt bleibt auch bei
                // geringer Häufigkeit gut sichtbar.
                heatPaint.shader = RadialGradient(
                    seatX, seatY, radius,
                    intArrayOf(
                        Color.argb((255 * alpha).toInt(), Color.red(accentColor),
                            Color.green(accentColor), Color.blue(accentColor)),
                        Color.argb((255 * alpha * 0.55f).toInt(), Color.red(accentColor),
                            Color.green(accentColor), Color.blue(accentColor)),
                        Color.argb(0, Color.red(accentColor),
                            Color.green(accentColor), Color.blue(accentColor))
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(seatX, seatY, radius, heatPaint)
            }
        }

        canvas.restore()

        // --- Cockpit (dezente Markierung vorne im Rumpf) ---
        val cockpitWidth = fuselageWidth * 0.55f
        val cockpitTop = planeTop + noseHeight * 0.25f
        val cockpitBottom = planeTop + noseHeight + cockpitHeight * 0.5f
        val cockpitPath = Path().apply {
            moveTo(planeCenterX, cockpitTop)
            lineTo(planeCenterX + cockpitWidth / 2f, cockpitTop + (cockpitBottom - cockpitTop) * 0.3f)
            lineTo(planeCenterX + cockpitWidth / 2f, cockpitBottom)
            lineTo(planeCenterX - cockpitWidth / 2f, cockpitBottom)
            lineTo(planeCenterX - cockpitWidth / 2f, cockpitTop + (cockpitBottom - cockpitTop) * 0.3f)
            close()
        }
        canvas.drawPath(cockpitPath, cockpitPaint)

        // --- Flugzeug-Umriss (saubere Kante über allem) ---
        canvas.drawPath(planePath, planeStrokePaint)

        // --- Bereichsbeschriftungen rechts ---
        // Nur Abschnitte mit erfassten Sitzplätzen werden beschriftet: Ohne
        // Eintrag in einer Klasse (z. B. nie First geflogen) erscheint auch
        // deren Schriftzug nicht.
        labelPaint.textAlign = Paint.Align.LEFT
        for (i in layout.sections.indices) {
            if (!sectionsWithSeats[i]) continue
            val section = layout.sections[i]
            val centerY = sectionBlockTops[i] + sectionBlockHeights[i] / 2f
            val label = when (section.className) {
                "First" -> context.getString(R.string.class_first)
                "Business" -> context.getString(R.string.class_business)
                "Premium Eco" -> context.getString(R.string.class_premium_economy_short)
                "Economy" -> context.getString(R.string.class_economy)
                else -> section.className
            }
            canvas.drawText(
                label,
                fuselageRight + 4f * density,
                centerY + labelPaint.textSize * 0.35f,
                labelPaint
            )
        }

    }
}
