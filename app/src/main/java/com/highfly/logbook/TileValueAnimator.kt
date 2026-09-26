package com.highfly.logbook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Zählt Kachel- und Diagrammwerte sichtbar von 0 auf ihren Zielwert hoch.
 *
 * Die Zieltexte werden gehalten, solange eine Animation läuft: Layouts, die
 * ihren Wert vermessen müssen (z. B. [android.view.ViewTreeObserver]-
 * Anpassungen der Kachelhöhe), brauchen den END-Wert statt des gerade
 * animierten Zwischenwerts.
 */
object TileValueAnimator {

    private const val DURATION_MS = 900L

    private val animators = mutableListOf<ValueAnimator>()
    private val finalTexts = mutableMapOf<TextView, CharSequence>()

    private data class AnimatedParts(val number: Double, val decimals: Int, val suffix: String)

    /**
     * Zählt den führenden Zahlenwert eines Wertes von 0 auf den Zielwert hoch.
     * Erkennbare Formate (deutsch): "22", "73,3", "884,234", "1.234", "10 (5%)".
     */
    fun animate(tv: TextView, finalText: CharSequence?) {
        if (finalText.isNullOrEmpty()) {
            tv.text = finalText ?: ""
            return
        }
        val parts = parseAnimated(finalText.toString())
        if (parts == null) {
            tv.text = finalText
            return
        }

        val formatter = DecimalFormat(
            "#,##0",
            DecimalFormatSymbols.getInstance(Locale.GERMANY)
        ).apply {
            minimumFractionDigits = parts.decimals
            maximumFractionDigits = parts.decimals
        }
        val target = parts.number

        finalTexts[tv] = finalText
        tv.text = formatter.format(0.0) + parts.suffix

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                tv.text = formatter.format(target * t) + parts.suffix
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    tv.text = finalText
                    finalTexts.remove(tv)
                    animators.remove(animation as ValueAnimator)
                }
            })
            start()
        }
        animators += animator
    }

    /** Zieltext, solange [animate] noch nicht fertig ist, sonst null. */
    fun finalTextOf(tv: TextView): CharSequence? = finalTexts[tv]

    /** Beendet alle laufenden Animationen, z. B. beim Verlassen der Ansicht. */
    fun cancelAll() {
        val running = animators.toList()
        animators.clear()
        running.forEach { it.cancel() }
        finalTexts.clear()
    }

    private fun parseAnimated(text: String): AnimatedParts? {
        val normalized = text.replace("\u00A0", " ").replace("\u2009", " ")
        val rawMatch = Regex("^[0-9., ]*").find(normalized)?.value ?: return null
        if (rawMatch.isEmpty()) return null
        val match = rawMatch.trim()
        val suffix = normalized.substring(rawMatch.length)

        // Deutsch: ',' ist IMMER das Dezimaltrennzeichen, '.' nur Tausender-
        // trenner ("1.234,567"). Ohne ',' ist die Zahl ganzzahlig.
        val comma = match.lastIndexOf(',')
        if (comma >= 0) {
            val decimals = match.length - 1 - comma
            val intPart = match.substring(0, comma).replace(".", "")
            val intValue = intPart.toLongOrNull() ?: return null
            var value = intValue.toDouble()
            if (decimals > 0) {
                val fracValue = match.substring(comma + 1).toLongOrNull() ?: return null
                value += fracValue / Math.pow(10.0, decimals.toDouble())
            }
            return AnimatedParts(value, decimals, suffix)
        }
        val value = match.replace(".", "").toLongOrNull() ?: return null
        return AnimatedParts(value.toDouble(), 0, suffix)
    }
}
