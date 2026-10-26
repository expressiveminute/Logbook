package com.highfly.logbook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
 *
 * Ein gestylter Anhang wie "(5 %)" oder "×" (kleiner gesetzt, andere Farbe)
 * bliebe während des Zählens in voller Größe stehen, wenn er einfach
 * angehängt würde, und schrumpfte erst am Ende auf seine Zielgröße. Deshalb
 * zählt während der Animation nur die Zahl selbst; der Anhang wird erst
 * danach eingeblendet.
 */
object TileValueAnimator {

    private const val DURATION_MS = 900L

    /** Einblenddauer des gestylten Anhangs nach dem Zählen. */
    private const val SUFFIX_FADE_MS = 300L

    private val animators = mutableListOf<ValueAnimator>()
    private val finalTexts = mutableMapOf<TextView, CharSequence>()

    internal data class AnimatedParts(val number: Double, val decimals: Int, val suffix: String)

    /**
     * Zählt den führenden Zahlenwert eines Wertes von 0 auf den Zielwert hoch.
     * Erkennbare Formate richten sich nach der App-Sprache: deutsch "22",
     * "73,3", "884,234", "1.234", englisch "22", "73.3", "1,234.56"; dazu
     * Anhänge wie "10 (5%)" oder "0,951 ×".
     *
     * Ein gestylter Anhang wie "(5 %)" oder "×" (kleiner, anderfarbig, siehe
     * [FirstFragment.buildValueText]) bleibt während des Zählens ausgeblendet
     * und wird erst nach dem Hochzählen eingeblendet. Vorher als Klartext
     * angehängt stünde er in voller Größe neben der Zahl und schrumpfte erst
     * am Ende auf seine Zielgröße.
     */
    /**
     * Setzt den Zieltext. Mit [animate] = false erscheint der Wert sofort
     * ohne Hochzählen - für das erneute Anzeigen eines bereits berechneten
     * Werts (z. B. Dashboard-Zwischenspeicher), damit kein Lag entsteht.
     */
    fun animate(tv: TextView, finalText: CharSequence?, animate: Boolean = true) {
        if (!animate) {
            finalTexts.remove(tv)
            tv.text = finalText ?: ""
            return
        }
        if (finalText.isNullOrEmpty()) {
            tv.text = finalText ?: ""
            return
        }
        val parts = parseAnimated(finalText.toString(), Settings.language(tv.context))
        if (parts == null) {
            tv.text = finalText
            return
        }

        val formatter = DecimalFormat(
            "#,##0",
            DecimalFormatSymbols.getInstance(Settings.language(tv.context))
        ).apply {
            minimumFractionDigits = parts.decimals
            maximumFractionDigits = parts.decimals
        }
        val target = parts.number

        // Der Anhang ist nur dann spannbar, wenn der Zieltext ihn gestaltet
        // (ForegroundColor-/RelativeSizeSpan). Nur dann braucht er das späte
        // Einblenden; ein Klartext-Anhang wächst nicht und schrumpft nicht.
        val suffixStart = finalText.length - parts.suffix.length
        val styledSuffix = parts.suffix.isNotEmpty() && finalText is Spanned &&
            finalText.getSpans(
                suffixStart, finalText.length, ForegroundColorSpan::class.java
            ).isNotEmpty()

        finalTexts[tv] = finalText
        tv.text = if (styledSuffix) {
            formatter.format(0.0)
        } else {
            formatter.format(0.0) + parts.suffix
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // Waehrend des Zaehlens nur die Zahl; der Anhang kommt danach.
                tv.text = if (styledSuffix) {
                    formatter.format(target * t)
                } else {
                    formatter.format(target * t) + parts.suffix
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    animators.remove(animation as ValueAnimator)
                    if (cancelled) {
                        // Abbruch durch cancelAll: Zieltext direkt setzen.
                        tv.text = finalText
                        finalTexts.remove(tv)
                        return
                    }
                    if (styledSuffix) {
                        fadeInSuffix(tv, finalText, suffixStart)
                    } else {
                        tv.text = finalText
                        finalTexts.remove(tv)
                    }
                }
            })
            start()
        }
        animators += animator
    }

    /**
     * Blendet den gestylten Anhang (Prozent, Einheit) nach dem Hochzählen ein,
     * während die Zahl stehen bleibt.
     */
    private fun fadeInSuffix(tv: TextView, finalText: CharSequence, suffixStart: Int) {
        val fade = ValueAnimator.ofInt(0, 255).apply {
            duration = SUFFIX_FADE_MS
            addUpdateListener {
                val alpha = it.animatedValue as Int
                tv.text = suffixWithAlpha(finalText, suffixStart, alpha)
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
        animators += fade
    }

    /** Der Zieltext, dessen Anhang ab [suffixStart] mit [alpha] gezeichnet wird. */
    private fun suffixWithAlpha(
        finalText: CharSequence,
        suffixStart: Int,
        alpha: Int
    ): CharSequence {
        val builder = SpannableStringBuilder(finalText)
        builder.getSpans(
            suffixStart, finalText.length, ForegroundColorSpan::class.java
        ).forEach { span ->
            val color = span.foregroundColor
            val blended = Color.argb(
                alpha, Color.red(color), Color.green(color), Color.blue(color)
            )
            builder.removeSpan(span)
            builder.setSpan(
                ForegroundColorSpan(blended),
                suffixStart,
                finalText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return builder
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

    /**
     * Zerlegt den Zieltext in Zahl und Anhang. Das Trennzeichen richtet sich
     * nach [locale]: Deutsch schreibt Komma als Dezimal- und Punkt als
     * Tausenderzeichen, Englisch umgekehrt. Ohne diese Unterscheidung würde die
     * Zahl im englischen "0.951" als 951 gelesen und falsch hochgezählt.
     */
    internal fun parseAnimated(text: String, locale: Locale): AnimatedParts? {
        val normalized = text.replace("\u00A0", " ").replace("\u2009", " ")
        val rawMatch = Regex("^[0-9., ]*").find(normalized)?.value ?: return null
        if (rawMatch.isEmpty()) return null
        val match = rawMatch.trim()
        val suffix = normalized.substring(rawMatch.length)

        val symbols = DecimalFormatSymbols.getInstance(locale)
        val decimal = symbols.decimalSeparator
        val grouping = symbols.groupingSeparator

        val decimalIndex = match.lastIndexOf(decimal)
        if (decimalIndex >= 0) {
            val decimals = match.length - 1 - decimalIndex
            val intPart = match.substring(0, decimalIndex).replace(grouping.toString(), "")
            val intValue = intPart.toLongOrNull() ?: return null
            var value = intValue.toDouble()
            if (decimals > 0) {
                val fracValue = match.substring(decimalIndex + 1).toLongOrNull() ?: return null
                value += fracValue / Math.pow(10.0, decimals.toDouble())
            }
            return AnimatedParts(value, decimals, suffix)
        }
        val value = match.replace(grouping.toString(), "").toLongOrNull() ?: return null
        return AnimatedParts(value.toDouble(), 0, suffix)
    }
}
