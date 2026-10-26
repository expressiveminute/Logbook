package com.highfly.logbook

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.highfly.logbook.databinding.ItemAirlineBinding

/**
 * Liste der Fluggesellschaften, absteigend nach Zahl der Flüge. Jede Zeile
 * zeigt - im Aufbau der Reisebuddy-Liste - das Rangzeichen, den Namen und die
 * Codes der Airline, die Zahl der Flüge und einen Pfeil; im Hintergrund liegt
 * der Anteil an der häufigsten Airline als diskreter Balken.
 *
 * Ein Tipp auf eine Zeile öffnet die Detailseite der Airline
 * ([AirlineDetailFragment]).
 */
class AirlineAdapter : RecyclerView.Adapter<AirlineAdapter.ViewHolder>() {

    private var items: List<AirlineStats.Airline> = emptyList()

    /**
     * Höchste Flugzahl, an der sich der Balken einer Zeile misst. Wird sie
     * nicht übergeben, ist es die der übergebenen Liste; beim Filtern bleibt
     * so der Massstab der Gesamtliste erhalten und die Balken springen nicht
     * mit. Ohne Airline gibt es nichts zu malen, und 0 als Nenner würde durch
     * null teilen.
     */
    private var maxFlights: Int = 0

    /**
     * Zweithöchste Flugzahl der Gesamtliste (kommt vom Aufrufer, der die
     * ungefilterte Liste kennt). Hat die häufigste Airline mehr als dreimal
     * so viele Flüge wie die zweithäufigste, würde ihr Balken die ganze
     * Breite füllen und die Abstufung darunter wäre nicht mehr erkennbar.
     * Dann reicht der Balken der Spitzen-Airline über den rechten Rand
     * hinaus und die übrigen Balken messen sich an der zweithöchsten Zahl.
     */
    private var secondMaxFlights: Int = 0

    /**
     * Ob die Spitzen-Airline den Bildschirmrand überragt. Dann bekommt ihr
     * Balken einen Anteil über 1 und die übrigen messen sich am zweiten Wert.
     */
    private var topOverhang = false

    /**
     * Rang je Airline-Code aus der ungefilterten Liste. In der Suche bleibt
     * dieser Rang stehen, statt der Position im Suchergebnis; eine Airline
     * ohne Flug (nicht in der Liste) trägt keinen Rang (0).
     */
    private var ranks: Map<String, Int> = emptyMap()

    /**
     * Ob die Medaillen gezeichnet werden. In der Suche (false) zeigt das
     * Rangzeichen nur die Platznummer.
     */
    var showMedals: Boolean = true

    fun submit(
        list: List<AirlineStats.Airline>,
        maxFlights: Int = list.maxOfOrNull { it.flights } ?: 0,
        secondMaxFlights: Int = 0,
        ranks: Map<String, Int> = emptyMap()
    ) {
        items = list
        this.maxFlights = maxFlights
        this.secondMaxFlights = secondMaxFlights
        this.ranks = ranks
        // Hat die Spitzen-Airline mehr als dreimal so viele Flüge wie die
        // zweithäufigste, überragt ihr Balken den rechten Rand und die
        // übrigen Balken messen sich am zweiten Wert.
        topOverhang = maxFlights > 0 && secondMaxFlights > 0 &&
            maxFlights > 3 * secondMaxFlights
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAirlineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], ranks[items[position].code] ?: 0)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemAirlineBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                items.getOrNull(adapterPosition)?.let { onItemClick(it) }
            }
        }

        fun bind(item: AirlineStats.Airline, rank: Int) {
            binding.badgeAirlineRank.setRank(rank, showMedals)
            // Bei Überhang messen sich alle Balken an der zweithöchsten
            // Flugzahl: Die Spitzen-Airline bekommt einen Anteil über 1
            // (Balken über den rechten Rand hinaus), die übrigen zeigen ihre
            // Abstufung untereinander.
            val fraction = when {
                topOverhang && secondMaxFlights > 0 ->
                    item.flights.toFloat() / secondMaxFlights
                maxFlights > 0 -> item.flights.toFloat() / maxFlights
                else -> 0f
            }
            binding.airlineBar.setFraction(fraction)
            binding.tvAirlineName.text = item.name
            binding.tvAirlineCodes.text = codesText(item)
            val countText = binding.root.context.resources.getQuantityString(
                R.plurals.airline_flights, item.flights, item.flights
            )
            binding.tvAirlineCount.text = countText
            // Ohne Rang (nie geflogene Airline in der Suche) entfällt der Platz
            // in der Beschriftung - "Rang 0" gäbe es nicht zu sprechen.
            binding.root.contentDescription = if (rank > 0) {
                binding.root.context.getString(
                    R.string.airline_desc,
                    item.name,
                    rank,
                    countText
                )
            } else {
                binding.root.context.getString(
                    R.string.airline_desc_unranked,
                    item.name,
                    countText
                )
            }
        }

        /** "LH · DLH"; ohne ICAO-Code nur der Code selbst. */
        private fun codesText(item: AirlineStats.Airline): String =
            listOfNotNull(item.code, item.icao?.takeIf { it != item.code })
                .joinToString(" · ")
    }

    /** Wird beim Tipp auf eine Zeile mit der gewählten Airline aufgerufen. */
    var onItemClick: (AirlineStats.Airline) -> Unit = {}
}
