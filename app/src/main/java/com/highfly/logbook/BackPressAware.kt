package com.highfly.logbook

/**
 * Seiten, die die Zurück-Taste selbst behandeln wollen. MainActivity leitet
 * jeden Druck der Zurück-Taste zuerst an das aktuelle Ziel der Navigation
 * weiter: Ist dort ein Suchfeld offen, wird es geschlossen und die Eingabe
 * gelöscht, und erst ein zweiter Druck navigiert zurück. Erstattet die Seite
 * `false`, fällt der Druck an den NavController durch.
 */
interface BackPressAware {
    /**
     * @return `true`, wenn die Zurück-Taste hier verarbeitet wurde und keine
     * Navigation stattfinden soll; sonst `false`.
     */
    fun onBackPressed(): Boolean
}
