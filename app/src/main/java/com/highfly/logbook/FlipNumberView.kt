package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isNotEmpty

/**
 * Eine Zahl als Reihe von [FlipDigitView]-Karten - eine Flip-Uhr wie an
 * einer alten Flughafen-Anzeigetafel. Ziffern liegen als einzelne Karten,
 * Trennzeichen (Punkt, Komma, Doppelpunkt) als schlichte Textfreie Zeichen
 * dazwischen. Nicht-Ziffern-Plätze (führende Leer-Karten) zeigen eine leere
 * Karte, wie eine echte Anzeigetafel vor dem Eintreffen der Zahl.
 *
 * [countTo] zählt von 0 auf einen Zielwert hoch (für den Auftritt einer
 * Story-Seite): Jeder Bildschritt formatiert den Zwischenwert neu, die
 * betroffenen Karten flippen mit - die Zahl rollt wie auf einer Tafel.
 * Wächst die Zahl, kommen neue Karten von links hinzu (rechtbündig), ohne
 * dass laufende Flips zurückgesetzt werden.
 *
 * Sind Systemanimationen abgeschaltet, erscheinen Zahlen sofort ohne Flip
 * und ohne Count-up - wie überall in der App.
 */
class FlipNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Höhe der Ziffernkarten in Pixeln; legt auch Breite und Abstände fest. */
    private var digitHeightPx = (DEFAULT_DIGIT_HEIGHT_DP * resources.displayMetrics.density).toInt()

    /** Ob Animationen laufen dürfen (Systemeinstellung, beim Anhängen gelesen). */
    private var animationsEnabled = true

    private var countAnimator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animationsEnabled = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        } catch (e: Settings.SettingNotFoundException) {
            true
        }
    }

    override fun onDetachedFromWindow() {
        countAnimator?.cancel()
        countAnimator = null
        super.onDetachedFromWindow()
    }

    /** Setzt die Höhe der Ziffernkarten (vor dem ersten [setText] aufrufen). */
    fun setDigitHeightPx(heightPx: Int) {
        if (heightPx <= 0 || heightPx == digitHeightPx) return
        digitHeightPx = heightPx
        if (isNotEmpty()) {
            // Layout komplett neu aufbauen, damit alle Karten die neue
            // Größe bekommen.
            val text = currentText()
            removeAllViews()
            appendText(text, animate = false)
        }
    }

    /** Die aktuell angezeigte Zeichenkette (Leer-Karten als Leerzeichen). */
    private fun currentText(): String = buildString {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            append(
                when (child) {
                    is FlipDigitView -> child.digit
                    is TextView -> child.text.toString()
                    else -> ""
                }
            )
        }
    }

    /**
     * Zeigt [text] an. Ändert sich nur der Inhalt, flippen die betroffenen
     * Karten; wächst die Zahl, kommen links neue Karten hinzu; schrumpft sie,
     * wird links mit Leer-Karten aufgefüllt, damit das Layout nicht springt.
     */
    fun setText(text: String, animate: Boolean = true) {
        if (text.isEmpty()) {
            removeAllViews()
            return
        }
        if (text.length == childCount) {
            updateDigits(text, animate)
            return
        }
        if (text.length < childCount) {
            updateDigits(text.padStart(childCount, ' '), animate)
            return
        }
        // Länger: bestehende Karten behalten und die neuen Zeichen von links
        // einfügen - laufende Flips der alten Karten bleiben erhalten.
        val extra = text.length - childCount
        updateDigits(text.substring(extra), animate)
        for (i in 0 until extra) {
            val child = makeChild(text[i])
            addView(child, i)
            if (child is FlipDigitView) child.setDigit(text[i], animate)
        }
        contentDescription = text
    }

    /** Zählt von 0 auf [target] hoch und formatiert jeden Zwischenwert. */
    fun countTo(
        target: Double,
        format: (Double) -> String,
        durationMs: Long = COUNT_UP_MS
    ) {
        countAnimator?.cancel()
        countAnimator = null
        if (!animationsEnabled || durationMs <= 0 || target <= 0) {
            setText(format(target), animate = animationsEnabled && target > 0)
            return
        }
        val animator = ValueAnimator.ofFloat(0f, target.toFloat()).apply {
            duration = durationMs
            addUpdateListener {
                setText(format((it.animatedValue as Float).toDouble()), animate = true)
            }
        }
        countAnimator = animator
        animator.start()
    }

    /**Aktualisiert die vorhandenen Kinder auf [text] (gleiche Länge). */
    private fun updateDigits(text: String, animate: Boolean) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val c = text[i]
            when (child) {
                is FlipDigitView -> {
                    if (c.isDigit()) {
                        child.setDigit(c, animate)
                    } else {
                        // An dieser Stelle steht ein Trennzeichen: Karte raus,
                        // Text dazwischen.
                        replaceChild(i, c, animate)
                    }
                }
                is TextView -> {
                    if (c.isDigit()) {
                        replaceChild(i, c, animate)
                    } else {
                        child.text = if (c == ' ') "" else c.toString()
                    }
                }
            }
        }
        contentDescription = text
    }

    /** Ersetzt das Kind an Position [index] gegen Karte oder Trennzeichen. */
    private fun replaceChild(index: Int, c: Char, animate: Boolean) {
        val replacement = makeChild(c)
        removeViewAt(index)
        addView(replacement, index)
        if (replacement is FlipDigitView) replacement.setDigit(c, animate)
    }

    /** Baut die Karten für [text] von Grund auf neu (Längenwechsel). */
    private fun appendText(text: String, animate: Boolean) {
        for (c in text) {
            addView(makeChild(c))
        }
        updateDigits(text, animate)
    }

    /** Erzeugt eine einzelne Karte (Ziffer) oder ein Trennzeichen. */
    private fun makeChild(c: Char): View {
        if (c.isDigit()) {
            val card = FlipDigitView(context)
            val width = (digitHeightPx * DIGIT_WIDTH_FACTOR).toInt()
            val params = LayoutParams(width, digitHeightPx)
            params.marginEnd = (digitHeightPx * DIGIT_GAP_FACTOR).toInt()
            card.layoutParams = params
            // Karte startet leer; setDigit klappt sie sichtbar (Flip-Effekt).
            return card
        }
        // Trennzeichen: schmaler Text, bei Punkt/Komma unten wie auf einer
        // echten Tafel, Doppelpunkt mittig.
        val text = TextView(context).apply {
            val width = (digitHeightPx * SEPARATOR_WIDTH_FACTOR).toInt()
            layoutParams = LayoutParams(width, digitHeightPx)
            setTextColor(ContextCompat.getColor(context, R.color.story_digit))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, digitHeightPx * DIGIT_SIZE_FACTOR)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            gravity = if (c == ',' || c == '.') {
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            } else {
                Gravity.CENTER
            }
            setPadding(0, 0, 0, if (c == ',' || c == '.') {
                (digitHeightPx * SEPARATOR_BOTTOM_FACTOR).toInt()
            } else 0)
            text = c.toString()
        }
        return text
    }

    companion object {
        /** Dauer des Count-up beim Seitenauftritt. */
        private const val COUNT_UP_MS = 1200L

        /** Standard-Kartenhöhe in dp (Story-Seiten setzen sie explizit). */
        private const val DEFAULT_DIGIT_HEIGHT_DP = 48f

        /** Kartenbreite relativ zur Kartenhöhe. */
        private const val DIGIT_WIDTH_FACTOR = 0.68f

        /** Abstand zwischen zwei Karten relativ zur Kartenhöhe. */
        private const val DIGIT_GAP_FACTOR = 0.10f

        /** Breite eines Trennzeichens relativ zur Kartenhöhe. */
        private const val SEPARATOR_WIDTH_FACTOR = 0.40f

        /** Schriftgröße relativ zur Kartenhöhe. */
        private const val DIGIT_SIZE_FACTOR = 0.62f

        /** Abstand eines Punkt/Komma vom unteren Rand (wie auf einer Tafel). */
        private const val SEPARATOR_BOTTOM_FACTOR = 0.16f
    }
}
