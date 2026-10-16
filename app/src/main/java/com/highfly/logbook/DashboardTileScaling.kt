package com.highfly.logbook

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Skalierung der Kachelhöhen mit der Systemschrift und Ausgleich der
 * waagerechten Kachelschrumpfung. Wird vom Dashboard und von der
 * Bearbeiten-Seite geteilt, damit die Kacheln an beiden Stellen gleich
 * aussehen. Die vom Nutzer gewählte Kachel-Grösse wirkt nicht hier, sondern
 * als gleichmässiges Schrumpfen der fertigen Kachel.
 */
object DashboardTileScaling {

    /** Obergrenze für die Schrift-Skalierung der Kachelhöhen. */
    const val MAX_FONT_SCALE = 1.4f

    /** Faktor aus der Systemschrift, begrenzt, damit die Kacheln nicht ausufern. */
    fun fontScale(view: View): Float =
        view.resources.configuration.fontScale.coerceIn(1f, MAX_FONT_SCALE)

    /**
     * Verkleinert den Inhalt einer Kachel waagerecht um den Gegenfaktor. Die
     * Kachel selbst schrumpft waagerecht um [DashboardPrefs.horizontalScale],
     * senkrecht aber um die Kachel-Grösse; ungleichmässig skaliert würden Zahlen
     * und Buchstaben in die Breite gezogen bzw. gestaucht. Der Gegenfaktor lässt
     * den Inhalt auf beiden Achsen gleichmässig schrumpfen, sodass jede Form -
     * auch Weltkugel-Vorschau und Kreisdiagramm - erhalten bleibt.
     *
     * Damit der verkleinerte Inhalt die Kachel trotzdem ganz ausfüllt, bekommt
     * er die um den Gegenfaktor grössere Layoutbreite und wird mittig
     * ausgerichtet. Sonst rückte gerade bei breiten Kacheln der ganze Inhalt von
     * den Rändern weg: die Überschrift stand nicht mehr am linken Kachelrand wie
     * bei den einspaltigen Kacheln und die Zahlen sassen eingerückt. Nach dem
     * Skalieren deckt der Inhalt die Kachel wieder genau ab; bei einspaltigen
     * Kacheln ist der Faktor 1 und alles bleibt unverändert.
     */
    fun scaleContent(tile: ViewGroup, compensation: Float) {
        val content = tile.getChildAt(0) ?: return
        val factor = compensation.coerceIn(0f, 1f)
        content.scaleX = factor
        val params = content.layoutParams ?: return
        val targetWidth = if (factor >= 1f) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            (tile.width / factor).roundToInt()
        }
        if (params.width != targetWidth) {
            params.width = targetWidth
            (params as? FrameLayout.LayoutParams)?.gravity = Gravity.CENTER_HORIZONTAL
            content.layoutParams = params
        }
    }
}
