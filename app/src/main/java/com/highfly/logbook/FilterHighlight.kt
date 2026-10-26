package com.highfly.logbook

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Hebt den Wert eines aktiven Filters in der Akzentfarbe hervor. Auf dem
 * Dashboard leuchtet das Filterwort bei aktivem Filter (siehe
 * `MainActivity.updateFilterGlow`); auf den Kachelseiten mit Filter im Kopf
 * (CO2, Hoehenstrahlung, Passagiere, Distanz, Zeit) wird derselbe Wert hier
 * in der Akzentfarbe angezeigt, damit ebenso sichtbar ist, dass die Seite
 * gefiltert ist. Der Rest des Textes behaelt seine gedämpfte Farbe.
 */
object FilterHighlight {

    /**
     * Faerbt das Vorkommen von [value] in [text] in die Akzentfarbe, wenn der
     * zugehoerige Filter aktiv ist; sonst bleibt der Text unveraendert. [view]
     * dient nur der Farbloesung (Theme). Kann verkettet werden, um mehrere
     * Werte in einem Text hervorzuheben (z. B. den Zeitraum und die Flugart in
     * der Unterzeile von Distanz und Zeit).
     */
    fun accent(
        view: View,
        text: CharSequence,
        value: String,
        active: Boolean
    ): CharSequence {
        if (!active || value.isEmpty()) return text
        val start = text.toString().indexOf(value)
        if (start < 0) return text
        val accent = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorPrimary
        )
        // SpannableString uebernimmt die Spans einer etwaigen Vorstufe, das
        // Verkketten mehrerer Werte geht also nicht verloren.
        return SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(accent),
                start,
                start + value.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
