package com.highfly.logbook

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.highfly.logbook.databinding.ItemAddCrossingBinding
import com.highfly.logbook.databinding.ItemManualCrossingBinding
import com.highfly.logbook.databinding.ItemTimeCountryBinding
import com.highfly.logbook.databinding.ItemTimeEmptyBinding
import com.highfly.logbook.databinding.ItemTimeSectionBinding
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
    object SectionCrossings : TimeCountryItem()

    data class Crossing(
        val index: Int,
        val crossing: BorderCrossing,
        val name: String,
        val flag: String
    ) : TimeCountryItem()

    object AddCrossing : TimeCountryItem()
}

/** Formatierung der Zeitangaben auf der Seite, sprachabhaengig. */
object TimeCountryFormat {
    fun daysText(context: Context, days: Long): String = when {
        days <= 0 -> context.getString(R.string.time_countries_less_than_day)
        days == 1L -> context.getString(R.string.time_countries_one_day)
        else -> context.getString(R.string.time_countries_days, days)
    }

    fun visitsText(context: Context, visits: Int): String =
        if (visits == 1) {
            context.getString(R.string.time_countries_one_visit)
        } else {
            context.getString(R.string.time_countries_visits, visits)
        }

    fun dateText(context: Context, date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Settings.language(context)))

    /** Tage als Stunden für den Layover-Balken ([LayoverTimeBarView]). */
    fun barHours(days: Long): Int =
        (days.coerceAtLeast(0) * LayoverTimeScale.DAY_HOURS).toInt()

    /** Beschriftung des Skalenendes der Zeitbalken, wie auf der Layover-Detailseite. */
    fun stepLabel(context: Context, hours: Int): String {
        val units = LayoverTimeScale.scaleUnits(hours)
        return when (LayoverTimeScale.stepIndex(hours)) {
            0 -> context.getString(R.string.layover_time_step_day)
            1 -> context.getString(R.string.layover_time_step_week)
            2 -> context.getString(R.string.layover_time_step_month)
            3 -> context.getString(R.string.layover_time_step_quarter)
            else -> context.resources.getQuantityString(R.plurals.layover_time_step_year, units, units)
        }
    }

    /**
     * Dauer über der Zeitbalken: die verbrachte Zeit in Tagen, Wochen, Monaten
     * als Gesamtwert, durch Punkte getrennt (z. B. "83 Tage · 11,9 Wochen ·
     * 2,8 Monate"). Stunden werden nicht angezeigt; unter einem Tag steht
     * der Bruchteil eines Tages ("0,2 Tage").
     */
    fun breakdownText(context: Context, hours: Int): String {
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
            if (step >= 4) add(part(R.plurals.layover_time_unit_quarters, totals.quarters))
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
 * die manuell erfassten Landweg-Grenzübertritte und der Knopf zum Hinzufügen.
 */
class TimeCountriesAdapter(
    private val context: Context,
    private val onCountryClick: (iso2: String) -> Unit,
    private val onDeleteCrossing: (index: Int) -> Unit,
    private val onAddCrossing: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<TimeCountryItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is TimeCountryItem.Country -> TYPE_COUNTRY
        is TimeCountryItem.Empty -> TYPE_EMPTY
        is TimeCountryItem.SectionCrossings -> TYPE_SECTION
        is TimeCountryItem.Crossing -> TYPE_CROSSING
        is TimeCountryItem.AddCrossing -> TYPE_ADD
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_COUNTRY -> CountryVH(ItemTimeCountryBinding.inflate(inflater, parent, false))
            TYPE_EMPTY -> EmptyVH(ItemTimeEmptyBinding.inflate(inflater, parent, false))
            TYPE_SECTION -> SectionVH(ItemTimeSectionBinding.inflate(inflater, parent, false))
            TYPE_CROSSING -> CrossingVH(ItemManualCrossingBinding.inflate(inflater, parent, false))
            else -> AddVH(ItemAddCrossingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is TimeCountryItem.Country -> (holder as CountryVH).bind(item)
            is TimeCountryItem.Crossing -> (holder as CrossingVH).bind(item)
            is TimeCountryItem.AddCrossing -> (holder as AddVH).bind()
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
            b.tvCountryDays.text = TimeCountryFormat.daysText(context, item.time.totalDays)
            val visits = TimeCountryFormat.visitsText(context, item.time.visits)
            val since = item.time.openSince
            b.tvCountryDetail.text = if (since != null) {
                context.getString(
                    R.string.time_countries_open_since,
                    TimeCountryFormat.dateText(context, since)
                ) + " · " + visits
            } else {
                visits
            }
            // Tipp auf die Zeile öffnet die Detailseite des Landes.
            b.root.setOnClickListener { onCountryClick(item.time.iso2) }
        }
    }

    private class EmptyVH(b: ItemTimeEmptyBinding) : RecyclerView.ViewHolder(b.root)

    private class SectionVH(b: ItemTimeSectionBinding) : RecyclerView.ViewHolder(b.root)

    private inner class CrossingVH(val b: ItemManualCrossingBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: TimeCountryItem.Crossing) {
            val c = item.crossing
            b.tvCrossingFlag.text = item.flag
            b.tvCrossingCountry.text = item.name
            val dateText = TimeCountryFormat.dateText(context, c.date)
            b.tvCrossingDate.text = context.getString(
                if (c.enter) R.string.time_countries_enter_on else R.string.time_countries_exit_on,
                dateText
            )
            b.btnDeleteCrossing.setOnClickListener { onDeleteCrossing(item.index) }
        }
    }

    private inner class AddVH(val b: ItemAddCrossingBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind() {
            b.btnAddCrossing.setOnClickListener { onAddCrossing() }
        }
    }

    private companion object {
        const val TYPE_COUNTRY = 0
        const val TYPE_EMPTY = 1
        const val TYPE_SECTION = 2
        const val TYPE_CROSSING = 3
        const val TYPE_ADD = 4

        /** Bis zu diesem Rang wird das Badge in der Länderliste gezeigt. */
        const val RANK_MAX_SHOWN = 10
    }
}
