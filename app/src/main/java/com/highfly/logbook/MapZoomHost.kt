package com.highfly.logbook

/**
 * Schnittstelle der Rubbelkarte an die halbhohe Länder-/Staaten-Seite, die
 * unten auf ihrem Bildschirm liegt: Die Seite meldet, wohin die Kugel zoomen
 * soll (Auge je Zeile) und dass sich der Stand geändert hat (Abhaken).
 */
interface MapZoomHost {

    /** Kugel auf das Land zoomen und seine Grenzen hervorheben. */
    fun zoomToCountry(iso2: String)

    /** Kugel auf den Bundesstaat/das Bundesland zoomen und hervorheben. */
    fun zoomToState(code: String)

    /** Ein Land wurde ab- bzw. angehakt: Karte und Kacheln neu laden. */
    fun onCountriesChanged()
}
