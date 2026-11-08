package com.highfly.logbook

import android.os.Bundle

/**
 * Filter der Kartenansichten (Weltkarte [WorldMapFragment] und Weltkugel
 * [GlobeFragment]), siehe `sheet_map_filter.xml`. Feldwerte sind die
 * Anzeigetexte (bzw. "yes"/"no" beim Layover); leer heisst "kein Filter".
 */
data class MapFilter(
    val flightType: String = "",
    val classType: String = "",
    val aircraftType: String = "",
    val layover: String = "",
    val travelBuddy: String = "",
    val airline: String = ""
) {
    fun isActive(): Boolean =
        listOf(flightType, classType, aircraftType, layover, travelBuddy, airline)
            .any { it.isNotBlank() }

    /**
     * [resolveAirline] löst einen gespeicherten Airline-Text auf den
     * kanonischen Schlüssel auf (siehe [AirlineStats.keyOf]), damit "LH"
     * und "DLH" denselben Filter treffen.
     */
    fun matches(entry: LogbookEntry, resolveAirline: (String?) -> String?): Boolean {
        if (flightType.isNotBlank() &&
            entry.flightType?.lowercase()?.contains(flightType.lowercase()) != true
        ) return false
        if (classType.isNotBlank() &&
            entry.classType?.lowercase()?.contains(classType.lowercase()) != true
        ) return false
        if (aircraftType.isNotBlank() &&
            entry.aircraftType?.lowercase()?.contains(aircraftType.lowercase()) != true
        ) return false
        if (layover == "yes" && !entry.layover) return false
        if (layover == "no" && entry.layover) return false
        if (!TravelBuddyStats.matches(entry.travelBuddy, travelBuddy)) return false
        if (airline.isNotBlank() &&
            resolveAirline(entry.airline) != resolveAirline(airline)
        ) return false
        return true
    }

    /** Schreibt den Filter in ein Bundle (Wechsel zwischen 2D-Karte und Kugel). */
    fun writeTo(bundle: Bundle) {
        bundle.putString("mapFilter.flightType", flightType)
        bundle.putString("mapFilter.classType", classType)
        bundle.putString("mapFilter.aircraftType", aircraftType)
        bundle.putString("mapFilter.layover", layover)
        bundle.putString("mapFilter.travelBuddy", travelBuddy)
        bundle.putString("mapFilter.airline", airline)
    }

    companion object {
        /** Navigationsargument: 2D-Karte (Weltkarte) immer im Querformat öffnen. */
        const val ARG_FORCE_LANDSCAPE = "forceLandscape"

        /** Liest den Filter aus einem Bundle; ohne Keys ein leerer Filter. */
        fun fromBundle(bundle: Bundle?): MapFilter {
            if (bundle == null) return MapFilter()
            fun get(key: String) = bundle.getString(key).orEmpty()
            return MapFilter(
                flightType = get("mapFilter.flightType"),
                classType = get("mapFilter.classType"),
                aircraftType = get("mapFilter.aircraftType"),
                layover = get("mapFilter.layover"),
                travelBuddy = get("mapFilter.travelBuddy"),
                airline = get("mapFilter.airline")
            )
        }
    }
}
