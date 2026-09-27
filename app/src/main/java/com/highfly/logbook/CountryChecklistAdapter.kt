package com.highfly.logbook

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.ItemCountryBinding

/**
 * Liste aller Länder als Checkliste. Die View-Holder werden wiederverwendet,
 * deshalb liest jeder Klick über [RecyclerView.ViewHolder.getAdapterPosition]
 * das Land neu aus der Liste - ein im Holder eingefrorenes Land wäre nach dem
 * Abhaken eines anderen schon veraltet.
 */
class CountryChecklistAdapter(
    private val onToggle: (CountryChecklist.Item) -> Unit
) : RecyclerView.Adapter<CountryChecklistAdapter.ViewHolder>() {

    private var items: List<CountryChecklist.Item> = emptyList()

    /** Meldet die Zahl der abgehakten Länder, sobald sich die Liste ändert. */
    var onCheckedCountChanged: ((Int) -> Unit)? = null

    fun submit(list: List<CountryChecklist.Item>) {
        items = list
        notifyDataSetChanged()
        notifyCount()
    }

    /**
     * Ersetzt genau dieses Land, ohne die ganze Liste neu zu zeichnen. Nach dem
     * Abhaken eines Landes ändert sich nur dessen Zeile.
     */
    fun replace(updated: CountryChecklist.Item) {
        val index = items.indexOfFirst { it.iso2 == updated.iso2 }
        if (index < 0) return
        if (items[index] == updated) return
        items = items.toMutableList().also { it[index] = updated }
        notifyItemChanged(index)
        notifyCount()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCountryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    private fun notifyCount() {
        onCheckedCountChanged?.invoke(CountryChecklist.checkedCount(items))
    }

    private fun itemAt(holder: ViewHolder): CountryChecklist.Item? =
        items.getOrNull(holder.adapterPosition)

    inner class ViewHolder(private val binding: ItemCountryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // Zeile und Kästchen lösen denselben Vorgang aus. Das Kästchen
            // schluckt den Tipp, deshalb feuert der Zeilen-Listener nur beim
            // Tippen auf den Namen.
            binding.root.setOnClickListener { itemAt(this)?.let(onToggle) }
            binding.cbCountry.setOnClickListener { itemAt(this)?.let(onToggle) }
        }

        fun bind(item: CountryChecklist.Item) {
            binding.tvCountryFlag.text = item.flag
            binding.tvCountryName.text = item.name
            binding.tvCountryName.setTextColor(
                MaterialColors.getColor(
                    binding.root,
                    if (item.checked) {
                        com.google.android.material.R.attr.colorOnSurface
                    } else {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    }
                )
            )
            binding.ivCountryFlight.visibility =
                if (item.fromFlight) View.VISIBLE else View.GONE
            // Die Kästchen der angeflogenen Länder sind gesetzt, aber gesperrt:
            // Der Eintrag, der sie begründet, kann jederzeit gelöscht werden.
            binding.cbCountry.isChecked = item.checked
            binding.cbCountry.isEnabled = item.toggleable
            binding.root.isClickable = item.toggleable
            binding.root.isFocusable = item.toggleable
        }
    }
}
