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

    private var items: List<VisitedCountries.Item> = emptyList()

    /** Meistbesuchtes Land der Liste; daran misst der Hintergrundbalken. */
    private var maxFlights = 0

    fun submit(list: List<VisitedCountries.Item>) {
        items = list
        maxFlights = list.maxOfOrNull { it.flights } ?: 0
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
            val rank = VisitedCountries.rankOf(item, items)
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