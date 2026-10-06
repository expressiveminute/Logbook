package com.highfly.logbook

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * Skala der Layover-Leiste auf der Detailseite.
 *
 * Die Leiste zeigt die im Layover verbrachte Zeit als Anteil einer Strecke, und
 * diese Strecke wächst mit der Zeit: Wer kürzer als einen Tag dort war, misst
 * in Tagen, wer länger, in Wochen, dann in Monaten, Quartalen und Jahren. So
 * steht die Leiste bei 30 Stunden nicht fast leer, nur weil die Anzeige auf
 * Jahre ausgelegt wäre - und ein Wert von mehr als einem Jahr passt trotzdem
 * hinein, weil die Skala dann in ganzen Jahren weiterwächst.
 *
 * Bewusst ohne Android-Bezug, damit die Umrechnung ohne Gerät geprüft werden
 * kann.
 */
object LayoverTimeScale {

    /**
     * Die Stufen der Leiste in aufsteigender Reihenfolge: Tag, Woche, Monat,
     * Quartal, Jahr. Eine Stufe ist jeweils das volle Ende der Leiste.
     */
    val STEPS = listOf(DAY_HOURS, WEEK_HOURS, MONTH_HOURS, QUARTER_HOURS, YEAR_HOURS)

    /** Ein Tag in Stunden, der Nenner der Tagesangabe und die erste Stufe. */
    const val DAY_HOURS = 24

    /** Eine Woche in Stunden, die zweite Stufe. */
    const val WEEK_HOURS = 168

    /** Ein Monat in Stunden, die dritte Stufe. */
    const val MONTH_HOURS = 720

    /** Ein Quartal in Stunden, die vierte Stufe. */
    const val QUARTER_HOURS = 2160

    /** Ein Jahr in Stunden, die letzte Stufe. */
    const val YEAR_HOURS = 8760

    /**
     * Nummer der Stufe, in der [hours] liegen: 0 Tag bis 4 Jahr. Genau ein Tag
     * ist noch ein Tag, erst darüber zählt die Leiste in Wochen.
     */
    fun stepIndex(hours: Int): Int {
        val h = hours.coerceAtLeast(0)
        val index = STEPS.indexOfFirst { h <= it }
        return if (index < 0) STEPS.lastIndex else index
    }

    /**
     * Ende der Skala für [hours] Stunden: die Stufe, in der die Zeit liegt.
     * Darüber hinaus wächst die Skala in ganzen Jahren, damit der Balken nicht
     * bei mehr als einem Jahr stehen bleibt.
     */
    fun scaleHours(hours: Int): Int {
        val h = hours.coerceAtLeast(0)
        STEPS.firstOrNull { h <= it }?.let { return it }
        return years(h) * YEAR_HOURS
    }

    /**
     * Anzahl der Jahre, die die Skala für [hours] Stunden umfasst. Für alles
     * unterhalb eines Jahres ist das 1.
     */
    fun scaleUnits(hours: Int): Int =
        if (hours.coerceAtLeast(0) <= YEAR_HOURS) 1 else years(hours)

    /**
     * Anteil der Leiste, den [hours] füllen, zwischen 0 und 1. Ohne [scale]
     * wird die Skala aus [hours] selbst bestimmt, siehe [scaleHours].
     */
    fun fraction(hours: Int, scale: Int = scaleHours(hours)): Float {
        if (scale <= 0) return 0f
        return min(1f, hours.toFloat() / scale)
    }

    /**
     * Stunden als Tage mit höchstens einer Nachkommastelle, in der Sprache der
     * App: deutsch "1,5", englisch "1.5". Ganze Tage stehen ohne Nachkomma da.
     */
    fun daysText(hours: Int, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
        }
        return format.format(hours / DAY_HOURS.toDouble())
    }

    /**
     * Singular oder Plural der Tagesangabe: Nur genau ein voller Tag heisst
     * "Tag". Schon "1,5" ist wieder die Mehrzahl, denn das ist "eineinhalb
     * Tage" und nicht "eineinhalb Tag".
     */
    fun daysQuantity(hours: Int): Int = if (hours == DAY_HOURS) 1 else 2

    /**
     * Die verbrachte Zeit in jeder Einheit als Gesamtwert - nicht als
     * Restkette: 423 Stunden sind 423 h, 17,6 Tage und 2,5 Wochen. Welche
     * Einheiten angezeigt werden, entscheidet die Detailseite anhand der
     * Skalenstufe.
     */
    data class Totals(
        val hours: Int,
        val days: Double,
        val weeks: Double,
        val months: Double,
        val quarters: Double,
        val years: Double
    )

    /**
     * Rechnet [hours] nicht in Reste, sondern in jede Einheit voll um: Der
     * Tageswert ist immer die gesamte Zeit durch 24, der Wochenwert immer
     * die gesamte Zeit durch 168.
     */
    fun totals(hours: Int): Totals {
        val h = hours.coerceAtLeast(0)
        return Totals(
            hours = h,
            days = h / DAY_HOURS.toDouble(),
            weeks = h / WEEK_HOURS.toDouble(),
            months = h / MONTH_HOURS.toDouble(),
            quarters = h / QUARTER_HOURS.toDouble(),
            years = h / YEAR_HOURS.toDouble()
        )
    }

    /**
     * Eine Zahl mit genau einer Nachkommastelle, in der Sprache der App:
     * deutsch "20,8", englisch "20.8". Eine volle Einheit bleibt "1,0".
     */
    fun oneDecimal(value: Double, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return format.format(value)
    }

    /** Aufgerundete Jahre für [hours] Stunden, mindestens eins. */
    private fun years(hours: Int): Int =
        ceil(hours.coerceAtLeast(0) / YEAR_HOURS.toDouble()).toInt().coerceAtLeast(1)
}