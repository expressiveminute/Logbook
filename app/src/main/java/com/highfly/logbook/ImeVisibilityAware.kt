package com.highfly.logbook

/**
 * Fragmente, die darauf reagieren wollen, dass die Tastatur den sichtbaren
 * Bereich verkleinert. MainActivity wertet die Insets aus und leitet den
 * Zustand weiter, weil es sie selbst konsumiert.
 */
interface ImeVisibilityAware {
    fun onImeVisibilityChanged(visible: Boolean)
}
