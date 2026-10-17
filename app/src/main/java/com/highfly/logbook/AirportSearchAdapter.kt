package com.highfly.logbook

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/**
 * Adapter des Suchergebnis-Popups der Flughäfen-Kachel. Jede Zeile zeigt den
 * Dreilettercode und daneben Stadt und Name. Die Stadt kommt in der gerade
 * gültigen Sprache aus [AirportDirectory.Entry.displayCity].
 */
class AirportSearchAdapter : BaseAdapter() {

    private var items: List<AirportDirectory.Entry> = emptyList()

    fun submit(list: List<AirportDirectory.Entry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): AirportDirectory.Entry = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_airport_search, parent, false)
        val entry = items[position]
        view.findViewById<TextView>(R.id.tv_airport_search_code).text = entry.iata
        val place = view.findViewById<TextView>(R.id.tv_airport_search_place)
        val label = listOfNotNull(
            entry.displayCity?.takeIf { it.isNotBlank() },
            entry.name?.takeIf { it.isNotBlank() && it != entry.displayCity }
        ).joinToString(", ")
        place.text = label
        place.visibility = if (label.isBlank()) View.GONE else View.VISIBLE
        return view
    }
}
