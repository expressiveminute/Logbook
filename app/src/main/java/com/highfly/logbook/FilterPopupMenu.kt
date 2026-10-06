package com.highfly.logbook

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.ItemFilterPopupBinding

/**
 * Das Menue eines der beiden Filterwoerter ueber den Kacheln des Dashboards
 * und das Hinweisfenster am blauen "i".
 *
 * Bewusst selbst gebaut und nicht als Standardmenue: Die Auswahl, die gerade
 * gilt, steht mit einem Haken in der Zeile, die Zeilen sind grosszuegig (48dp)
 * und das Menue selbst ist eine abgerundete Flaeche in der Farbe der Kacheln.
 * Ein Standardmenue zeigt dagegen nur eine Liste ohne erkennbare Auswahl.
 *
 * Der Rueckgabewert einer Auswahl ist ihr Schluessel, nicht ihre Beschriftung -
 * Eintraege koennen in verschiedenen Sprachen erfasst worden sein.
 *
 * Die Zeilen liegen in einer Liste in einer ScrollView und nicht in einer
 * ListView: Deren eigene Messung endet nach der ersten Zeile, sobald man ihre
 * Hoehe erfragen will, und das Fenster schnitt dann alles ausser dem gerade
 * Gewaehlten ab - also genau das, was ohnehin schon in der Ueberschrift steht.
 */
object FilterPopupMenu {

    private const val MENU_CORNER_DP = 16
    private const val MENU_ELEVATION_DP = 3
    private const val ITEM_INSET_DP = 4
    private const val MIN_WIDTH_DP = 200
    private const val HINT_MIN_WIDTH_DP = 260
    private const val MAX_HEIGHT_DP = 320
    private const val SCREEN_MARGIN_DP = 8
    private const val HEADER_SPACING_DP = 12

    private sealed interface Row {
        /** Erklaerung ueber den Zeilen, sie waehlt nichts aus. */
        data class Header(val text: CharSequence) : Row

        data class Option(val key: String, val label: String) : Row
    }

    /**
     * @param selectedKey der Schluessel, der aktuell gilt; seine Zeile bekommt
     *   den Haken.
     */
    fun showOptions(
        context: Context,
        anchor: View,
        keys: List<String>,
        labels: List<String>,
        selectedKey: String,
        onSelected: (String) -> Unit
    ) {
        if (keys.isEmpty()) return
        val rows = keys.zip(labels) { key, label -> Row.Option(key, label) }
        show(
            context, anchor, rows, selectedKey, MIN_WIDTH_DP,
            centerOnAnchor = false, onSelected = onSelected
        )
    }

    /**
     * Ein Fenster, das nur etwas erklaert: oben der Text, darunter die Zeilen
     * als Liste. Ein Tipp schliesst es, es wird nichts gewaehlt.
     */
    fun showInfo(
        context: Context,
        anchor: View,
        explanation: CharSequence,
        caption: CharSequence,
        lines: List<String>
    ) {
        val rows = buildList {
            add(Row.Header(explanation))
            if (lines.isNotEmpty()) {
                add(Row.Header(caption))
                lines.forEach { add(Row.Option(key = it, label = it)) }
            }
        }
        show(
            context, anchor, rows, null, HINT_MIN_WIDTH_DP,
            centerOnAnchor = true, onSelected = null
        )
    }

    private fun show(
        context: Context,
        anchor: View,
        rows: List<Row>,
        selectedKey: String?,
        minWidthDp: Int,
        centerOnAnchor: Boolean,
        onSelected: ((String) -> Unit)?
    ) {
        val metrics = context.resources.displayMetrics
        fun dp(value: Int): Int = (value * metrics.density).toInt()

        val popup = PopupWindow(context)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Der Rand haelt die Zeilen von den runden Ecken des Menues weg:
            // Eine Welle, die bis in die Ecke reicht, schneidet dort ein Eck
            // aus dem Abgerundeten heraus.
            setPadding(
                dp(ITEM_INSET_DP), dp(ITEM_INSET_DP),
                dp(ITEM_INSET_DP), dp(ITEM_INSET_DP)
            )
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        rows.forEach { row ->
            list.addView(
                rowView(
                    context = context,
                    row = row,
                    selectedKey = selectedKey,
                    labelColor = MaterialColors.getColor(
                        anchor, com.google.android.material.R.attr.colorOnSurface
                    ),
                    headerColor = MaterialColors.getColor(
                        anchor, com.google.android.material.R.attr.colorOnSurfaceVariant
                    ),
                    headerSpacingPx = dp(HEADER_SPACING_DP)
                ) { key ->
                    popup.dismiss()
                    if (key != null) onSelected?.invoke(key)
                }
            )
        }
        popup.contentView = scroll
        popup.width = maxOf(anchor.width, dp(minWidthDp))
        // Die Hoehe wird gemessen statt geschaetzt: Eine Erklaerung umbricht
        // ueber mehrere Zeilen, eine reine Optionsliste nicht. Was nicht in das
        // Fenster passt, scrollt, statt aus dem Bild zu laufen.
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popup.height = minOf(scroll.measuredHeight, dp(MAX_HEIGHT_DP))
        popup.apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = dp(MENU_ELEVATION_DP).toFloat()
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(MENU_CORNER_DP).toFloat()
                setColor(
                    MaterialColors.getColor(
                        anchor,
                        com.google.android.material.R.attr.colorSurfaceContainerHigh
                    )
                )
            })
        }

        // Das Fenster haengt unmittelbar unter dem Wort, an dem es aufgegangen
        // ist: Die linken Kanten liegen uebereinander, sonst wirkt es wie ein
        // zweites, loses Fenster. Nur wenn es damit ueber den Bildschirmrand
        // hinausginge, wandert es in den Bild hinein.
        //
        // Das Hinweisfenster haengt mittig unter dem "i", das selbst in der
        // Mitte steht; ein Buendig ausgerichtetes Fenster stuende dann weit
        // neben seinem Ausloeser.
        //
        // Gerechnet wird in Fenstkoordinaten und erst am Ende auf den Versatz
        // umgerechnet, den showAsDropDown relativ zum Feld erwartet. Ein
        // Versatz auf der Fensterskala waere um die Position des Feldes zu
        // gross - das Menue landete rechts aus dem Bild.
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val desiredLeft = location[0] +
            if (centerOnAnchor) anchor.width / 2 - popup.width / 2 else 0
        val leftMargin = dp(SCREEN_MARGIN_DP)
        val maxLeft =
            (metrics.widthPixels - popup.width - leftMargin).coerceAtLeast(leftMargin)
        val left = desiredLeft.coerceIn(leftMargin, maxLeft)
        popup.showAsDropDown(anchor, left - location[0], 0)
    }

    /**
     * Eine Zeile des Fensters. [onPick] bekommt den Schluessel der Zeile oder
     * null bei einer Kopfzeile - dann wird nur geschlossen.
     */
    private fun rowView(
        context: Context,
        row: Row,
        selectedKey: String?,
        labelColor: Int,
        headerColor: Int,
        headerSpacingPx: Int,
        onPick: (String?) -> Unit
    ): View {
        val binding = ItemFilterPopupBinding.inflate(
            LayoutInflater.from(context), null, false
        )
        val label = binding.tvFilterPopupLabel
        when (row) {
            is Row.Header -> {
                label.text = row.text
                label.setTextColor(headerColor)
                label.setPaddingRelative(label.paddingStart, 0, label.paddingEnd, headerSpacingPx)
                binding.ivFilterPopupCheck.visibility = View.GONE
            }
            is Row.Option -> {
                label.text = row.label
                label.setTextColor(labelColor)
                binding.ivFilterPopupCheck.visibility =
                    if (row.key == selectedKey) View.VISIBLE else View.GONE
            }
        }
        binding.root.setOnClickListener { onPick((row as? Row.Option)?.key) }
        return binding.root
    }
}