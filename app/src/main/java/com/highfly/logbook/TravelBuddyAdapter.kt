package com.highfly.logbook

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.highfly.logbook.databinding.ItemTravelBuddyBinding

/**
 * Liste der Reisebuddies, absteigend nach Zahl der gemeinsamen Flüge. Jede
 * Zeile zeigt - im Aufbau der Länderliste - das Rangzeichen, den Namen, die
 * Zahl der gemeinsamen Flüge und einen Pfeil; im Hintergrund liegt der
 * Anteil am häufigsten geflogenen Buddy als diskreter Balken.
 *
 * Ein Tipp auf eine Zeile öffnet die Detailseite mit der gemeinsamen Karte und
 * dem ersten und letzten gemeinsamen Flug ([TravelBuddyDetailFragment]).
 */
class TravelBuddyAdapter : RecyclerView.Adapter<TravelBuddyAdapter.ViewHolder>() {

    private var items: List<TravelBuddyStats.Buddy> = emptyList()

    /**
     * Höchste Flugzahl, an der sich der Balken einer Zeile misst. Wird sie
     * nicht übergeben, ist es die der übergebenen Liste; beim Filtern bleibt
     * so der Massstab der Gesamtliste erhalten und die Balken springen nicht
     * mit. Ohne Buddy gibt es nichts zu malen, und 0 als Nenner würde durch
     * null teilen.
     */
    private var maxFlights: Int = 0

    fun submit(
        list: List<TravelBuddyStats.Buddy>,
        maxFlights: Int = list.maxOfOrNull { it.flights } ?: 0
    ) {
        items = list
        this.maxFlights = maxFlights
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTravelBuddyBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position + 1)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemTravelBuddyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                items.getOrNull(adapterPosition)?.let { onItemClick(it) }
            }
        }

        fun bind(item: TravelBuddyStats.Buddy, rank: Int) {
            binding.badgeTravelBuddyRank.setRank(rank)
            binding.travelBuddyBar.setFraction(
                if (maxFlights > 0) item.flights.toFloat() / maxFlights else 0f
            )
            binding.tvTravelBuddyName.text = item.name
            binding.tvTravelBuddyCount.text = binding.root.context.resources
                .getQuantityString(R.plurals.travel_buddy_flights, item.flights, item.flights)
            binding.root.contentDescription = binding.root.context.getString(
                R.string.travel_buddy_desc,
                item.name,
                rank,
                item.flights
            )
        }
    }

    /** Wird beim Tipp auf eine Zeile mit dem gewählten Buddy aufgerufen. */
    var onItemClick: (TravelBuddyStats.Buddy) -> Unit = {}
}
