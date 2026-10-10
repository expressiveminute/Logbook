package com.highfly.logbook

object DashboardEvents {

    /**
     * Meldet, dass sich einer der Filter der Kachelzeile geaendert hat. Die
     * Kacheln des Dashboards lesen beide Filter selbst aus den Einstellungen,
     * deshalb reicht das Signal - mehr Daten muessen nicht uebergeben werden.
     */
    var onFilterChanged: (() -> Unit)? = null
}