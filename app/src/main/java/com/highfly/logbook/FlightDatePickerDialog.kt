package com.highfly.logbook

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormatSymbols
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Calendar

/**
 * Kalender-Dialog zum Auswählen eines Datums. Die als [flightDates]
 * übergebenen Tage (z. B. das Datum, das für den bearbeiteten Zeitraum schon
 * existiert) bekommen einen dezenten Kreis in der Akzentfarbe - so kann man
 * sich beim Nachtragen/Bearbeiten von Ein- und Ausreise orientieren.
 * Der gewählte Tag ist voll gefüllt, heute ist fett in der Akzentfarbe. Ersetzt
 * an den Grenzübertritt-Stellen den DatePickerDialog, dessen Kalender-Modus die
 * Tage auf Canvas zeichnet und keine Tagesmarkierung erlaubt.
 */
object FlightDatePickerDialog {

    /** Deckkraft der Flugtags-Markierung (dezenter Kreis). */
    private const val FLIGHT_MARK_ALPHA = 0x2E

    /** Zeigt den Kalender und ruft [onPick] mit dem gewählten Datum (OK). */
    fun show(
        context: Context,
        initial: LocalDate,
        flightDates: Set<LocalDate>,
        onPick: (LocalDate) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_flight_date_picker, null)
        val tvTitle = view.findViewById<TextView>(R.id.tv_cal_title)
        val btnPrev = view.findViewById<View>(R.id.btn_cal_prev)
        val btnNext = view.findViewById<View>(R.id.btn_cal_next)
        val weekdayGrid = view.findViewById<GridLayout>(R.id.grid_cal_weekdays)
        val dayGrid = view.findViewById<GridLayout>(R.id.grid_cal_days)

        val locale = Settings.language(context)
        val primary = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimary)
        val onSurface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)
        val outline = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOutline)
        val today = LocalDate.now()
        val density = context.resources.displayMetrics.density

        // Erster Wochentag der Sprache (Mo in de, So in en-US) und die
        // Wochentagsköpfe in Spaltenreihenfolge.
        val firstDow = Calendar.getInstance(locale).firstDayOfWeek
        val symbols = DateFormatSymbols.getInstance(locale)
        repeat(7) { k ->
            val calDow = (firstDow - 1 + k) % 7 + 1
            weekdayGrid.addView(TextView(context).apply {
                text = symbols.shortWeekdays[calDow]
                gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(outline)
                layoutParams = cellParams(0, k, density)
            })
        }

        var visibleMonth = YearMonth.from(initial)
        var selected = initial

        fun render() {
            tvTitle.text = visibleMonth.atDay(1)
                .format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
            dayGrid.removeAllViews()
            val first = visibleMonth.atDay(1)
            // Calendar-DayOfWeek (So=1..Sa=7) der ersten Spalte.
            val calFirst = if (first.dayOfWeek == DayOfWeek.SUNDAY) 1 else first.dayOfWeek.value + 1
            val offset = (calFirst - firstDow + 7) % 7
            val len = visibleMonth.lengthOfMonth()
            for (i in 0 until 42) {
                val dayNum = i - offset + 1
                val date = first.plusDays((dayNum - 1).toLong())
                val inMonth = dayNum in 1..len
                val cell = TextView(context).apply {
                    gravity = Gravity.CENTER
                    text = String.format(locale, "%d", date.dayOfMonth)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    layoutParams = cellParams(i / 7, i % 7, density)
                }
                when {
                    !inMonth -> {
                        cell.setTextColor(withAlpha(outline, 0x60))
                        cell.isClickable = false
                    }
                    date == selected -> {
                        cell.setTextColor(onPrimary)
                        cell.background = circle(primary)
                        cell.setOnClickListener { selected = date; render() }
                    }
                    date in flightDates -> {
                        // Flugtag: dezenter Kreis in der Akzentfarbe.
                        cell.setTextColor(onSurface)
                        cell.background = circle(withAlpha(primary, FLIGHT_MARK_ALPHA))
                        cell.setOnClickListener { selected = date; render() }
                    }
                    date == today -> {
                        cell.setTextColor(primary)
                        cell.setTypeface(null, Typeface.BOLD)
                        cell.setOnClickListener { selected = date; render() }
                    }
                    else -> {
                        cell.setTextColor(onSurface)
                        cell.setOnClickListener { selected = date; render() }
                    }
                }
                dayGrid.addView(cell)
            }
        }

        btnPrev.setOnClickListener { visibleMonth = visibleMonth.minusMonths(1); render() }
        btnNext.setOnClickListener { visibleMonth = visibleMonth.plusMonths(1); render() }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setNegativeButton(R.string.role_switch_cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            onPick(selected)
            dialog.dismiss()
        }
        render()
    }

    /** Zell-Layout im 7-spaltigen Raster: gleiche Spaltenbreite per Gewicht. */
    private fun cellParams(row: Int, col: Int, density: Float): GridLayout.LayoutParams =
        GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(col, 1f)).apply {
            width = 0
            height = (40 * density).toInt()
            val m = (2 * density).toInt()
            setMargins(m, m, m, m)
        }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
