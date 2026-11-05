package com.highfly.logbook

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.ItemStateRowBinding

/**
 * Liste der Bundesstaaten/-länder (Deutschland/USA) auf der halbhohen Seite
 * unter der Rubbelkarte: Auge zum Zoomen auf den Staat, Name, darunter die
 * Hauptstadt, Flugzeugsymbol bei angeflogenem Staat und ein Kästchen zum
 * händischen Abhaken - wie in der Länderliste.
 *
 * Die View-Holder werden wiederverwendet, deshalb liest jeder Klick über
 * [RecyclerView.ViewHolder.getAdapterPosition] den Staat neu aus der Liste.
 */
class StateRowAdapter(
    items: List<StateRow>,
    private val onZoom: (StateRow) -> Unit,
    private val onToggle: (StateRow) -> Unit
) : RecyclerView.Adapter<StateRowAdapter.ViewHolder>() {

    /** Meldet die Zahl der markierten Staaten, sobald sich die Liste ändert. */
    var onCheckedCountChanged: ((Int) -> Unit)? = null

    /** Eine Zeile der Staaten-Liste. */
    data class StateRow(
        val code: String,
        /** Anzeigename in der Sprache der App. */
        val name: String,
        /** Hauptstadt in der Sprache der App, leer wenn unbekannt. */
        val capital: String,
        /** Angeflogen laut Logbuch, erkennbar am Flugzeugsymbol. */
        val fromFlight: Boolean,
        /** Angeflogen oder selbst abgehakt, damit auf der Karte eingefärbt. */
        val visited: Boolean
    )

    private var items: List<StateRow> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStateRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    /** Schaltet genau einen Staat um, ohne die ganze Liste neu zu zeichnen. */
    fun replace(updated: StateRow) {
        val index = items.indexOfFirst { it.code.equals(updated.code, ignoreCase = true) }
        if (index < 0) return
        if (items[index] == updated) return
        items = items.toMutableList().also { it[index] = updated }
        notifyItemChanged(index)
        onCheckedCountChanged?.invoke(items.count { it.visited })
    }

    private fun itemAt(holder: ViewHolder): StateRow? =
        items.getOrNull(holder.adapterPosition)

    inner class ViewHolder(private val binding: ItemStateRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // Zeile und Kästchen lösen denselben Vorgang aus, wie in der
            // Länderliste: das Kästchen schluckt den Tipp, deshalb feuert der
            // Zeilen-Listener nur beim Tippen auf den Namen.
            binding.root.setOnClickListener { itemAt(this)?.let(onToggle) }
            binding.cbState.setOnClickListener { itemAt(this)?.let(onToggle) }
            binding.ivStateZoom.setOnClickListener { itemAt(this)?.let(onZoom) }
        }

        fun bind(item: StateRow) {
            binding.tvStateName.text = item.name
            // Wie in der Länderliste tritt ein „erledigter" Eintrag zurück.
            binding.tvStateName.setTextColor(
                ColorUtils.setAlphaComponent(
                    MaterialColors.getColor(
                        binding.root,
                        if (item.visited) {
                            com.google.android.material.R.attr.colorOnSurfaceVariant
                        } else {
                            com.google.android.material.R.attr.colorOnSurface
                        }
                    ),
                    if (item.visited) ALPHA_VISITED else ALPHA_OPEN
                )
            )
            if (item.capital.isNotEmpty()) {
                binding.tvStateCapital.visibility = View.VISIBLE
                binding.tvStateCapital.text =
                    binding.root.context.getString(R.string.state_capital_label, item.capital)
            } else {
                binding.tvStateCapital.visibility = View.GONE
            }
            binding.ivStateFlight.visibility =
                if (item.fromFlight) View.VISIBLE else View.GONE
            binding.cbState.isChecked = item.visited
        }
    }

    private companion object {
        const val ALPHA_OPEN = 255
        const val ALPHA_VISITED = 90
    }
}
