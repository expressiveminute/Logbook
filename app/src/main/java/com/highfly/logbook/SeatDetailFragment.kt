package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentSeatDetailBinding

/**
 * Detailseite der Kachel "Sitzplatz": Schema eines Flugzeugs von oben mit
 * einer Heatmap der erfassten Sitzplätze. Die Kabineneinteilung (Reihen,
 * Klassenabschnitte, Sitzbuchstaben) richtet sich nach dem häufigsten
 * Flugzeugtyp unter den Einträgen mit Sitzplatz ([AircraftLayout]).
 *
 * Grundlage sind dieselben Einträge wie die Kachel: der Flugart-Filter gilt,
 * der Zeitraum-Filter des Dashboards nicht. Ausgewertet werden nur Einträge
 * mit einem Sitzplatz, siehe [SeatStats].
 */
class SeatDetailFragment : LogbookFragment() {

    private var _binding: FragmentSeatDetailBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSeatDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Platz unter der schwebenden Navigationsleiste.
        binding.scrollSeat.addBottomNavPadding()
        binding.btnSeatBack.setOnClickListener { findNavController().navigateUp() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /**
     * Liest die Einträge im Hintergrund, wertet die Sitzplätze aus und baut
     * die Ansicht. Ein Generationszähler verwirft Ergebnisse einer bereits
     * verlassenen Seite.
     */
    private fun load() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext

        Thread {
            // Dieselbe Auswahl wie die Kachel: Flugart-Filter, aber kein
            // Zeitraum – die Seite wertet alle Jahre aus.
            val entries = DashboardStats.flightTypeEntries(appContext)
            // Häufigstes Kabinenlayout unter den Einträgen mit Sitzplatz
            val layout = AircraftLayout.mostCommonLayout(entries)
            val summary = SeatStats.summarize(entries, layout)
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                render(summary, layout)
            }
        }.start()
    }

    /** Baut Heatmap und Kennzahlen aus der Auswertung. */
    private fun render(summary: SeatStats.Summary, layout: AircraftLayout.Layout) {
        // Untertitel: Anzahl der erfassten Sitzplätze und Flugzeugtyp
        val typeInfo = if (layout !== AircraftLayout.DEFAULT) {
            // Zeige den Flugzeugtyp nur an, wenn ein bekannter Typ erkannt wurde
            " · ${layout.totalRows} Reihen"
        } else {
            ""
        }
        binding.tvSeatSubtitle.text = if (summary.total > 0) {
            resources.getQuantityString(R.plurals.seat_detail_count, summary.total, summary.total) + typeInfo
        } else {
            getString(R.string.seat_detail_empty_hint)
        }

        if (summary.total == 0) {
            binding.seatHeatmap.visibility = View.GONE
            binding.cardSeatStats.visibility = View.GONE
            binding.tvSeatEmpty.visibility = View.VISIBLE
            return
        }

        binding.tvSeatEmpty.visibility = View.GONE
        binding.seatHeatmap.visibility = View.VISIBLE
        binding.cardSeatStats.visibility = View.VISIBLE

        // Layout und Heatmap setzen
        binding.seatHeatmap.setLayout(layout)
        binding.seatHeatmap.setHeatmap(summary.seatHeat)

        // Kennzahlen aufbauen
        val container = binding.seatStatsContainer
        container.removeAllViews()

        addStatRow(container, getString(R.string.seat_stat_total), summary.total.toString())

        summary.mostCommonRow?.let { row ->
            val letter = summary.mostCommonLetter ?: ' '
            addStatRow(
                container,
                getString(R.string.seat_stat_most_common),
                "$row$letter"
            )
        }

        addStatRow(
            container,
            getString(R.string.seat_stat_window),
            "${(summary.windowFraction * 100).toInt()} %"
        )

        addStatRow(
            container,
            getString(R.string.seat_stat_aisle),
            "${(summary.aisleFraction * 100).toInt()} %"
        )

        // Durchschnittliche Position: vorne/hinten als Prozentsatz
        val frontPercent = (summary.avgFrontFraction * 100).toInt()
        addStatRow(
            container,
            getString(R.string.seat_stat_avg_position),
            getString(R.string.seat_stat_front_percent, frontPercent)
        )
    }

    /** Fügt eine Kennzahlen-Zeile im Stil von item_airport_fact hinzu. */
    private fun addStatRow(container: LinearLayout, label: String, value: String) {
        val ctx = requireContext()
        val row = LayoutInflater.from(ctx).inflate(R.layout.item_airport_fact, container, false)
        row.findViewById<TextView>(R.id.tv_fact_label).text = label
        row.findViewById<TextView>(R.id.tv_fact_value).text = value
        container.addView(row)
    }
}
