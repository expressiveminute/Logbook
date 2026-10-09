package com.highfly.logbook

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.doOnLayout

/**
 * Der farbige Rahmen hinter der jeweils gewaehlten Kachel einer Auswahlzeile.
 *
 * Er steht als eigenes Objekt, weil mehrere Seiten dieselbe Zeile zeigen:
 * "Neuer Flug" fuer Reiseart und Reiseklasse, die Kachel-Detailseite fuer die
 * Reiseart, nach der die Kachel "Reiseklasse" ihre Klassen aufschluesselt.
 * Breite und Position richten sich nach der gewaehlten Kachel, deren Masse erst
 * nach dem ersten Layout-Durchlauf feststehen.
 */
object SelectionHighlight {

    /** Setzt Breite und Startposition des Rahmens ohne Animation. */
    fun configure(frame: FrameLayout, highlight: View, tiles: List<View>) {
        frame.doOnLayout {
            val anchor = tiles.firstOrNull() ?: return@doOnLayout
            (highlight.layoutParams as FrameLayout.LayoutParams).width = anchor.width
            highlight.translationX = frame.offsetTo(anchor)
        }
    }

    /** Faehrt den Rahmen zur gewaehlten Kachel und faerbt ihn ein. */
    fun move(
        frame: FrameLayout,
        highlight: View,
        tiles: List<View>,
        index: Int,
        color: Int
    ) {
        frame.doOnLayout {
            val tile = tiles.getOrNull(index) ?: return@doOnLayout
            (highlight.layoutParams as FrameLayout.LayoutParams).width = tile.width
            highlight.layoutParams = highlight.layoutParams

            highlight.background = roundedCellBackground(frame, color)

            if (highlight.visibility != View.VISIBLE) {
                highlight.visibility = View.VISIBLE
                highlight.alpha = 0f
                highlight.animate().alpha(1f).setDuration(200).start()
            }

            highlight.animate()
                .translationX(frame.offsetTo(tile))
                .setDuration(250)
                .start()
        }
    }

    private fun FrameLayout.offsetTo(target: View): Float {
        val framePos = IntArray(2)
        val targetPos = IntArray(2)
        getLocationInWindow(framePos)
        target.getLocationInWindow(targetPos)
        return (targetPos[0] - framePos[0]).toFloat()
    }

    private fun roundedCellBackground(view: View, color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 16f * view.resources.displayMetrics.density
            setColor(color)
        }
}
