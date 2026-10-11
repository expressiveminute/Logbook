package com.highfly.logbook

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.highfly.logbook.databinding.ItemVisitedCountryBinding

/**
 * Liste der besuchten Länder: Rangzeichen, Flagge, Name und die Zahl der
 * Flüge, nach der die Liste sortiert ist, dahinter ein Pfeil. Ein Tipp auf
 * die Zeile öffnet die Länderseite.
 */
class VisitedCountriesAdapter(
    private val onCountryClick: (String) -> Unit
) : RecyclerView.Adapter<VisitedCountriesAdapter.ViewHolder>() {

    /** Alle Länder; daran messen sich Rangzeichen und Hintergrundbalken. */
    private var allItems: List<VisitedCountries.Item> = emptyList()

    /** Sichtbare Länder; bei aktiver Suche sind es die Treffer. */
    private var items: List<VisitedCountries.Item> = emptyList()

    /** Meistbesuchtes Land der Liste; daran misst der Hintergrundbalken. */
    private var maxFlights = 0

    private var query = ""

    fun submit(list: List<VisitedCountries.Item>) {
        allItems = list
        maxFlights = list.maxOfOrNull { it.flights } ?: 0
        applyQuery()
    }

    /** Filtert die Liste nach [query]; ein leerer Suchtext zeigt alles. */
    fun setQuery(value: String) {
        query = value
        applyQuery()
    }

    private fun applyQuery() {
        items = if (query.isBlank()) {
            allItems
        } else {
            allItems.filter { it.name.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVisitedCountryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemVisitedCountryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                items.getOrNull(adapterPosition)?.let { onCountryClick(it.iso2) }
            }
        }

        fun bind(item: VisitedCountries.Item) {
            // Der Rang bezieht sich immer auf die komplette Liste, damit die
            // Medaille auch bei aktiver Suche zum echten Listenplatz passt.
            val rank = VisitedCountries.rankOf(item, allItems)
            binding.badgeVisitedCountryRank.setRank(rank)
            // Hintergrundbalken: Anteil der Flugzahl am meistbesuchten Land.
            binding.visitedCountryBar.setFraction(
                if (maxFlights > 0) item.flights.toFloat() / maxFlights else 0f
            )
            binding.tvVisitedCountryFlag.text = item.flag
            binding.tvVisitedCountryName.text = item.name
            binding.tvVisitedCountryFlights.text = binding.root.context.resources
                .getQuantityString(
                    R.plurals.visited_country_flights,
                    item.flights,
                    item.flights
                )
            binding.root.contentDescription = binding.root.context.getString(
                R.string.visited_country_desc,
                item.name,
                rank,
                item.flights
            )
        }
    }
}