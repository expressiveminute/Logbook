package com.highfly.logbook

import android.view.View

/**
 * Skalierung der Kachelhöhen mit der Systemschrift. Wird vom Dashboard und von
 * der Bearbeiten-Seite geteilt, damit die Kacheln an beiden Stellen gleich
 * hoch sind. Die vom Nutzer gewählte Kachel-Grösse wirkt nicht hier, sondern
 * als gleichmässiges Schrumpfen der fertigen Kachel.
 */
object DashboardTileScaling {

    /** Obergrenze für die Schrift-Skalierung der Kachelhöhen. */
    const val MAX_FONT_SCALE = 1.4f

    /** Faktor aus der Systemschrift, begrenzt, damit die Kacheln nicht ausufern. */
    fun fontScale(view: View): Float =
        view.resources.configuration.fontScale.coerceIn(1f, MAX_FONT_SCALE)
}
