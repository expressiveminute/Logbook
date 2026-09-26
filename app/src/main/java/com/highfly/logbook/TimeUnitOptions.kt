package com.highfly.logbook

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan

/**
 * Einheiten der Zeitanzeige: Schlüssel, die in den Einstellungen gespeichert
 * werden, und ihr Bezug zu [DashboardStats.TimeUnit].
 */
object TimeUnitOptions {

    const val KEY_HOURS = "hours"
    const val KEY_DAYS = "days"
    const val KEY_MONTHS = "months"
    const val KEY_YEARS = "years"

    fun keys(): List<String> = listOf(
        KEY_HOURS,
        KEY_DAYS,
        KEY_MONTHS,
        KEY_YEARS
    )

    fun label(context: Context, key: String): String = when (key) {
        KEY_DAYS -> context.getString(R.string.tile_time_unit_d)
        KEY_MONTHS -> context.getString(R.string.tile_time_unit_mo)
        KEY_YEARS -> context.getString(R.string.tile_time_unit_y)
        else -> context.getString(R.string.tile_time_unit_h)
    }

    fun keyForLabel(context: Context, label: String): String? =
        keys().firstOrNull { this.label(context, it) == label }

    fun unit(key: String): DashboardStats.TimeUnit = when (key) {
        KEY_DAYS -> DashboardStats.TimeUnit.DAYS
        KEY_MONTHS -> DashboardStats.TimeUnit.MONTHS
        KEY_YEARS -> DashboardStats.TimeUnit.YEARS
        else -> DashboardStats.TimeUnit.HOURS
    }

    /**
     * Titel der Zeit-Kachel, z. B. "Zeit [in Stunden]". Die Einheit steht in
     * Klammern hinter dem Namen, weil die Kachel selbst nur die Zahl zeigt.
     */
    fun tileTitle(context: Context, unit: DashboardStats.TimeUnit): CharSequence {
        val inRes = when (unit) {
            DashboardStats.TimeUnit.HOURS -> R.string.tile_time_in_hours
            DashboardStats.TimeUnit.DAYS -> R.string.tile_time_in_days
            DashboardStats.TimeUnit.MONTHS -> R.string.tile_time_in_months
            DashboardStats.TimeUnit.YEARS -> R.string.tile_time_in_years
        }
        val name = context.getString(R.string.tile_time)
        val parts = SpannableStringBuilder()
            .append(name)
            .append(" [")
            .append(context.getString(inRes))
            .append("]")
        parts.setSpan(
            StyleSpan(Typeface.NORMAL),
            name.length,
            parts.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return parts
    }
}
