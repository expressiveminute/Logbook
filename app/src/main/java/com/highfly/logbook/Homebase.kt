package com.highfly.logbook

/**
 * Die Homebase: der meistgenutzte Flughafen des Logbuchs - der Flughafen,
 * den man immer wieder braucht. Als Nutzung zählt jede Bewegung: jeder
 * Start und jede Landung. Bei Gleichstand gewinnt der alphabetisch erste
 * Code, damit das Ergebnis eindeutig bleibt.
 *
 * Die App merkt sich die Homebase zentral in [current]: [LogbookRepository]
 * berechnet sie bei jedem Neuladen der Einträge neu, sodass sie überall in
 * der App aktuell ist, ohne dass jede Seite sie selbst ermitteln müsste.
 * Ohne Android-Abhängigkeiten, damit [HomebaseTest] die Rechnung prüfen
 * kann.
 */
object Homebase {

    /** Zuletzt berechnete Homebase; `null`, solange das Logbuch leer ist. */
    var current: String? = null
        private set

    /**
     * Der meistgenutzte Flughafen in [entries] als Dreilettercode (trimmt,
     * gross geschrieben) oder `null`, wenn kein Eintrag einen Flughafen
     * nennt.
     */
    fun homebaseOf(entries: List<LogbookEntry>): String? {
        if (entries.isEmpty()) return null
        val movements = HashMap<String, Int>()
        for (entry in entries) {
            val from = entry.fromAirport.trim().uppercase()
            if (from.isNotEmpty()) movements[from] = (movements[from] ?: 0) + 1
            val to = entry.toAirport.trim().uppercase()
            if (to.isNotEmpty()) movements[to] = (movements[to] ?: 0) + 1
        }
        val most = movements.values.maxOrNull() ?: return null
        return movements.filterValues { it == most }.keys.minOrNull()
    }

    /** Berechnet die Homebase aus [entries] und merkt sie sich als [current]. */
    fun update(entries: List<LogbookEntry>) {
        current = homebaseOf(entries)
    }
}
