package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Rangzeichen der Länderliste: die ersten drei Plätze bekommen eine Medaille
 * in Gold, Silber und Bronze - dieselbe Zeichnung wie bei den Balken der
 * Dashboard-Kacheln in [BarChartView] -, die Plätze vier bis zehn eine
 * Platznummer. Danach bleibt das Feld leer, damit die Zeilen ruhig bleiben.
 *
 * Der Platz wird über [setRank] gesetzt und ist rein dekorativ: Die
 * Beschriftung der Zeile nennt den Rang für die Sprachausgabe mit. In der
 * Suche ([setRank] mit `showMedals = false`) zeichnet auch Platz 1 bis 3 nur
 * die Platznummer - eine Medaille passt nicht zu einem Suchergebnis, das
 * unter Umständen noch nie geflogen wurde.
 */
class RankBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var rank = 0

    /** Ob die Plätze 1 bis 3 als Medaille gezeichnet werden. */
    private var showMedals = true

    private val medalFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val medalRibbon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val medalRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = MaterialColors.getColor(
            this@RankBadgeView, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        textSize = 12f * resources.displayMetrics.density
    }

    /**
     * Setzt den Platz; Werte über [MAX_NUMBER_RANK] zeichnen nichts. Mit
     * `showMedals = false` (Suchmodus) wird auch für die Plätze 1 bis 3 nur
     * die Platznummer gezeichnet.
     */
    fun setRank(value: Int, showMedals: Boolean = true) {
        val next = value.coerceAtLeast(0)
        if (rank == next && this.showMedals == showMedals) return
        rank = next
        this.showMedals = showMedals
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when {
            rank == 0 -> Unit
            rank <= 3 && showMedals -> drawMedal(canvas)
            rank <= MAX_NUMBER_RANK -> drawNumber(canvas)
        }
    }

    /** Medaille wie [BarChartView.drawMedal]: Scheibe mit Schleife und Ring. */
    private fun drawMedal(canvas: Canvas) {
        val (color, rim) = when (rank) {
            1 -> MEDAL_GOLD to MEDAL_GOLD_RIM
            2 -> MEDAL_SILVER to MEDAL_SILVER_RIM
            else -> MEDAL_BRONZE to MEDAL_BRONZE_RIM
        }
        val centerX = width / 2f
        val radius = minOf(width, height) * MEDAL_SIZE_FRACTION
        val centerY = height / 2f + radius * 0.14f

        medalRibbon.color = rim
        val ribbon = Path().apply {
            moveTo(centerX - radius * 0.16f, centerY - radius * 0.60f)
            lineTo(centerX - radius * 0.66f, centerY - radius * 1.12f)
            lineTo(centerX - radius * 0.38f, centerY - radius * 1.04f)
            close()
            moveTo(centerX + radius * 0.16f, centerY - radius * 0.60f)
            lineTo(centerX + radius * 0.66f, centerY - radius * 1.12f)
            lineTo(centerX + radius * 0.38f, centerY - radius * 1.04f)
            close()
        }
        canvas.drawPath(ribbon, medalRibbon)

        medalFill.color = color
        canvas.drawCircle(centerX, centerY, radius, medalFill)

        medalRing.strokeWidth = radius * 0.14f
        canvas.drawCircle(centerX, centerY, radius * 0.68f, medalRing)
    }

    private fun drawNumber(canvas: Canvas) {
        val text = "$rank."
        canvas.drawText(
            text,
            width / 2f,
            height / 2f - (numberPaint.descent() + numberPaint.ascent()) / 2f,
            numberPaint
        )
    }

    private companion object {
        /** Medaillenfarben, identisch mit [BarChartView]. */
        const val MEDAL_GOLD = 0xFFD9A800.toInt()
        const val MEDAL_GOLD_RIM = 0xFFB98A00.toInt()
        const val MEDAL_SILVER = 0xFFC4C4C4.toInt()
        const val MEDAL_SILVER_RIM = 0xFFA0A0A0.toInt()
        const val MEDAL_BRONZE = 0xFFCD7F32.toInt()
        const val MEDAL_BRONZE_RIM = 0xFFA4682A.toInt()

        /** Darüber steht eine Platznummer, darunter bleibt das Feld leer. */
        const val MAX_NUMBER_RANK = 10

        /** Anteil der Badge-Kante, den der Medaillen-Radius füllt. */
        const val MEDAL_SIZE_FRACTION = 0.42f
    }
}