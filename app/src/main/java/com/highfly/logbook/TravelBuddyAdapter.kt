package com.highfly.logbook

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.highfly.logbook.databinding.ItemTravelBuddyBinding
import java.util.Locale

/**
 * Liste der Reisebuddies, absteigend nach Zahl der gemeinsamen Flüge. Jede
 * Zeile zeigt den Namen, einen Balken für den Anteil und die absolute Zahl.
 *
 * Die Zeilen sind nicht anklickbar: Ein Tipp auf einen Buddy zeigte die
 * Einträge dazu, und dafür gibt es die Länderliste auf der Weltkarte. Hier
 * zählt nur, wer wie oft dabei war.
 */
class TravelBuddyAdapter : RecyclerView.Adapter<TravelBuddyAdapter.ViewHolder>() {

    private var items: List<TravelBuddyStats.Buddy> = emptyList()

    /**
     * Höchste Flugzahl der Liste. Der Balken einer Zeile ist ihr Anteil
     * daran, damit der am häufigsten geflogene Buddy die volle Breite hat und
     * die übrigen sich daran messen lassen können. Ohne Buddy gibt es nichts
     * zu malen, und 0 als Nenner würde durch null teilen.
     */
    private var maxFlights: Int = 0

    fun submit(list: List<TravelBuddyStats.Buddy>) {
        items = list
        maxFlights = list.maxOfOrNull { it.flights } ?: 0
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTravelBuddyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemTravelBuddyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TravelBuddyStats.Buddy) {
            binding.tvTravelBuddyName.text = item.name
            binding.tvTravelBuddyCount.text =
                String.format(Locale.getDefault(), "%d", item.flights)
            val share = if (maxFlights > 0) {
                (item.flights * PERCENT_MAX / maxFlights)
            } else {
                0
            }
            binding.progressTravelBuddy.setProgressCompat(share, false)
        }
    }

    private companion object {
        /** [com.google.android.material.progressindicator.BaseProgressIndicator] zählt auf 100. */
        const val PERCENT_MAX = 100
    }
}
