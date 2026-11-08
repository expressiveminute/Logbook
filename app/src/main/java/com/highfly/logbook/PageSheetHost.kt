package com.highfly.logbook

import android.view.MotionEvent

/**
 * Host der eingebetteten Seite (Bottom-Sheet) der Rubbelkarte: Das Blatt im
 * [CountriesFragment] meldet Wischbewegungen am Griff oben hierher; der Host
 * (die Rubbelkarte) zieht die Seite auf, schnappt sie zurück oder schließt
 * sie (wie die Zurück-Taste).
 */
interface PageSheetHost {
    /** Touch-Ereignis auf dem Griff oben an der Seite; true, wenn verarbeitet. */
    fun onSheetTouch(event: MotionEvent): Boolean
}
