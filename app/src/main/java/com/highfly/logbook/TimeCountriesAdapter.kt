package com.highfly.logbook

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.highfly.logbook.databinding.ItemTimeCountryBinding
import com.highfly.logbook.databinding.ItemTimeEmptyBinding
import com.highfly.logbook.databinding.ItemTimeHomebaseBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Eine Zeile der Seite "Zeit in Ländern". */
sealed class TimeCountryItem {
    data class Country(
        val rank: Int,
        val name: String,
        val flag: String,
        val time: CountryTime
    ) : TimeCountryItem()

    object Empty : TimeCountryItem()

    /** Fusszeile ganz unten: Hinweis auf die Homebase, deren Land nicht zaehlt. */
    data class HomebaseNote(val text: String) : TimeCountryItem()
}

/** Formatierung der Zeitangaben auf der Seite, sprachabhaengig. */
object TimeCountryFormat {
    /**
     * Gesamtaufenthalt eines Landes als Tage aus den [hours] (Layover-Stunden
     * eingeschlossen) mit höchstens einer Nachkommastelle - dieselbe Basis und
     * Schreibweise wie die Dauer oben auf der Detailseite, damit Liste und
     * Detail dieselbe Zahl anzeigen und die Sortierung (nach Stunden) zur
     * Anzeige passt. Ein halber Tag (Ein- und Ausreise am selben Tag) steht
     * als "0,5 Tage"; erst ohne jede Stunde steht "< 1 Tag".
     */
    fun daysText(context: Context, hours: Long): String {
        if (hours <= 0) {
            return context.getString(R.string.time_countries_less_than_day)
        }
        val h = hours.toInt()
        return context.resources.getQuantityString(
            R.plurals.layover_time_unit_days,
            LayoverTimeScale.daysQuantity(h),
            LayoverTimeScale.daysText(h, Settings.language(context))
        )
    }

    fun visitsText(context: Context, visits: Int): String =
        if (visits == 1) {
            context.getString(R.string.time_countries_one_visit)
        } else {
            context.getString(R.string.time_countries_visits, visits)
        }

    fun dateText(context: Context, date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Settings.language(context)))

    /**
     * Beschriftung des Skalenendes der Zeitbalken, wie auf der Layover-Detailseite.
     * Mit [quarters] = false entfaellt die Quartals-Stufe:Dann endet die Skala
     * bei "3 Monate" statt "1 Quartal".
     */
    fun stepLabel(context: Context, hours: Int, quarters: Boolean = true): String {
        val units = LayoverTimeScale.scaleUnits(hours)
        return when (LayoverTimeScale.stepIndex(hours)) {
            0 -> context.getString(R.string.layover_time_step_day)
            1 -> context.getString(R.string.layover_time_step_week)
            2 -> context.getString(R.string.layover_time_step_month)
            3 -> if (quarters) {
                context.getString(R.string.layover_time_step_quarter)
            } else {
                context.resources.getQuantityString(
                    R.plurals.layover_time_unit_months, 3,
                    LayoverTimeScale.trimmedDecimal(3.0, Settings.language(context))
                )
            }
            else -> context.resources.getQuantityString(R.plurals.layover_time_step_year, units, units)
        }
    }

    /**
     * Dauer über der Zeitbalken: die verbrachte Zeit in Tagen, Wochen, Monaten
     * als Gesamtwert, durch Punkte getrennt (z. B. "83 Tage · 11,9 Wochen ·
     * 2,8 Monate"). Stunden werden nicht angezeigt; unter einem Tag steht
     * der Bruchteil eines Tages ("0,2 Tage"). Mit [quarters] = false entfallen
     * die Quartale, dafuer erscheinen Jahre, sobald mehr als ein Jahr erreicht
     * ist.
     */
    fun breakdownText(context: Context, hours: Int, quarters: Boolean = true): String {
        val totals = LayoverTimeScale.totals(hours)
        val step = LayoverTimeScale.stepIndex(hours)
        val locale = Settings.language(context)
        fun decimal(value: Double): String = LayoverTimeScale.trimmedDecimal(value, locale)
        fun part(resId: Int, value: Double): String {
            val text = decimal(value)
            val quantity = if (text == decimal(1.0)) 1 else 2
            return context.resources.getQuantityString(resId, quantity, text)
        }
        val parts = buildList {
            // Tage immer, damit auch unter einem Tag etwas dasteht.
            add(part(R.plurals.layover_time_unit_days, totals.days))
            if (step >= 2) add(part(R.plurals.layover_time_unit_weeks, totals.weeks))
            if (step >= 3) add(part(R.plurals.layover_time_unit_months, totals.months))
            if (quarters) {
                if (step >= 4) add(part(R.plurals.layover_time_unit_quarters, totals.quarters))
            } else if (hours > LayoverTimeScale.YEAR_HOURS) {
                add(part(R.plurals.layover_time_unit_years, totals.years))
            }
        }
        return parts.joinToString(" · ")
    }

    /** Transport-Kürzel in Anzeige-Reihenfolge. */
    val TRANSPORT_KEYS = listOf("car", "train", "bus", "ferry", "bike", "foot")

    /** String-Resource des Transport-Labels. */
    fun transportLabelRes(key: String): Int = when (key) {
        "train" -> R.string.transport_train
        "bus" -> R.string.transport_bus
        "ferry" -> R.string.transport_ferry
        "bike" -> R.string.transport_bike
        "foot" -> R.string.transport_foot
        else -> R.string.transport_car
    }

    /** Icon-Resource des Transportmittels. */
    fun transportIconRes(key: String): Int = when (key) {
        "train" -> R.drawable.ic_transport_train
        "bus" -> R.drawable.ic_transport_bus
        "ferry" -> R.drawable.ic_transport_ferry
        "bike" -> R.drawable.ic_transport_bike
        "foot" -> R.drawable.ic_transport_foot
        else -> R.drawable.ic_transport_car
    }

    /** RadioButton-ID der Transport-Auswahl im Ein-/Ausreise-Dialog -> Kürzel. */
    fun transportKey(radioId: Int): String? = when (radioId) {
        R.id.rb_transport_car -> "car"
        R.id.rb_transport_train -> "train"
        R.id.rb_transport_bus -> "bus"
        R.id.rb_transport_ferry -> "ferry"
        R.id.rb_transport_bike -> "bike"
        R.id.rb_transport_foot -> "foot"
        else -> null
    }

    /** Transport-Kürzel -> RadioButton-ID (zum Vorwählen beim Bearbeiten). */
    fun transportRadioId(key: String?): Int = when (key) {
        "train" -> R.id.rb_transport_train
        "bus" -> R.id.rb_transport_bus
        "ferry" -> R.id.rb_transport_ferry
        "bike" -> R.id.rb_transport_bike
        "foot" -> R.id.rb_transport_foot
        else -> R.id.rb_transport_car
    }

    /** Label des Transportmittels eines manuellen Grenzübertritts. */
    fun transportLabel(context: Context, transport: String?): String? = when (transport) {
        "car" -> context.getString(R.string.transport_car)
        "train" -> context.getString(R.string.transport_train)
        "bus" -> context.getString(R.string.transport_bus)
        "ferry" -> context.getString(R.string.transport_ferry)
        "bike" -> context.getString(R.string.transport_bike)
        "foot" -> context.getString(R.string.transport_foot)
        else -> null
    }
}

/**
 * Liste der Seite "Zeit in Ländern": Länder mit Aufenthaltsdauer, darunter
 * die Fusszeile mit dem Homebase-Hinweis.
 */
class TimeCountriesAdapter(
    private val context: Context,
    private val onCountryClick: (iso2: String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var allItems: List<TimeCountryItem> = emptyList()
    private var query = ""

    var items: List<TimeCountryItem> = emptyList()
        private set

    /** Setzt die komplette Liste und wendet den aktuellen Suchtext an. */
    fun submit(value: List<TimeCountryItem>) {
        allItems = value
        applyQuery()
    }

    /** Filtert die Länder nach [value] (Landesname); leer zeigt alles. */
    fun setQuery(value: String) {
        query = value
        applyQuery()
    }

    private fun applyQuery() {
        items = if (query.isBlank()) {
            allItems
        } else {
            // Beim Suchen nur die passenden Länder, ohne Fusszeile.
            allItems.filter {
                it is TimeCountryItem.Country && it.name.contains(query, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is TimeCountryItem.Country -> TYPE_COUNTRY
        is TimeCountryItem.Empty -> TYPE_EMPTY
        is TimeCountryItem.HomebaseNote -> TYPE_HOMEBASE
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_COUNTRY -> CountryVH(ItemTimeCountryBinding.inflate(inflater, parent, false))
            TYPE_EMPTY -> EmptyVH(ItemTimeEmptyBinding.inflate(inflater, parent, false))
            else -> HomebaseVH(ItemTimeHomebaseBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is TimeCountryItem.Country -> (holder as CountryVH).bind(item)
            is TimeCountryItem.HomebaseNote -> (holder as HomebaseVH).bind(item)
            else -> {}
        }
    }

    private inner class CountryVH(val b: ItemTimeCountryBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: TimeCountryItem.Country) {
            // Medaille fuer Rang 1-3, danach Zahl - bis Rang 10. Ab Rang 11
            // bleibt der Platz frei (INVISIBLE), damit Flagge und Name
            // untereinander stehen.
            b.badgeCountryRank.setRank(item.rank, showMedals = true)
            b.badgeCountryRank.visibility =
                if (item.rank <= RANK_MAX_SHOWN) View.VISIBLE else View.INVISIBLE
            b.tvCountryFlag.text = item.flag
            b.tvCountryName.text = item.name
            // Dieselbe stundenbasierte Zahl wie auf der Detailseite (Sortierung
            // der Liste läuft ebenfalls über die Stunden).
            b.tvCountryDays.text = TimeCountryFormat.daysText(context, item.time.totalHours)
            // "y Aufenthalte"; bei einem offenen Zeitraum (nur Hin- oder nur
            // Rueckflug in den Daten) hintenangestellt "Offene Zeiträume".
            val visits = TimeCountryFormat.visitsText(context, item.time.visits)
            b.tvCountryDetail.text = if (item.time.hasOpenPeriods) {
                visits + " · " + context.getString(R.string.time_countries_open_periods)
            } else {
                visits
            }
            // Tipp auf die Zeile öffnet die Detailseite des Landes.
            b.root.setOnClickListener { onCountryClick(item.time.iso2) }
        }
    }

    private class EmptyVH(b: ItemTimeEmptyBinding) : RecyclerView.ViewHolder(b.root)

    private class HomebaseVH(val b: ItemTimeHomebaseBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: TimeCountryItem.HomebaseNote) {
            b.tvTimeHomebase.text = item.text
        }
    }

    private companion object {
        const val TYPE_COUNTRY = 0
        const val TYPE_EMPTY = 1
        const val TYPE_HOMEBASE = 2

        /** Bis zu diesem Rang wird das Badge in der Länderliste gezeigt. */
        const val RANK_MAX_SHOWN = 10
    }
}
