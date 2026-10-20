package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.highfly.logbook.databinding.FragmentPassengersBinding
import kotlin.math.roundToInt

/**
 * Detailseite der Kachel "Passagiere": die geschätzte Passagierzahl je
 * Kalenderjahr, das neuste Jahr oben (siehe [PassengersCalculator] und
 * [AircraftSeats]). Oben rechts im Kopf sitzt ein Adjust-Knopf, der die
 * Auslastung der Schätzung einstellt (50–100 %, Schritt 5 %, Standard 80 %).
 *
 * Grundlage sind dieselben Einträge wie die Kachel: der Flugart-Filter gilt,
 * der Zeitraum-Filter des Dashboards nicht - die Kachel zählt alle Jahre,
 * diese Seite listet sie einzeln.
 */
class PassengersFragment : Fragment() {

    private var _binding: FragmentPassengersBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPassengersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnPassengersBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnPassengersAdjust.setOnClickListener { showLoadFactorDialog() }
        val context = requireContext()
        binding.tvPassengersFilterInfo.text = getString(
            R.string.passengers_filter_flight_type,
            FlightTypeOptions.label(context, Settings.getFlightTypeFilterKey(context))
        )
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /**
     * Liest die Einträge im Hintergrund, weil die Datenbank auf der
     * Hauptthread zu langsam wäre. Ein Generationszähler verwirft Ergebnisse
     * einer bereits verlassenen Seite.
     */
    private fun load() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext

        Thread {
            // Dieselbe Auswahl wie die Kachel: Flugart-Filter, aber kein
            // Zeitraum - die Seite listet alle Jahre.
            val entries = DashboardStats.flightTypeEntries(appContext)
            val catalog = AircraftSeats.load(appContext)
            val loadFactor = Settings.getPassengerLoadFactor(appContext)
            val summary = PassengersCalculator.summary(entries, loadFactor, catalog)
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                render(summary, loadFactor)
            }
        }.start()
    }

    /** Baut die Zeilen je Jahr, neustes Jahr oben, Balken zum stärksten Jahr. */
    private fun render(summary: PassengersCalculator.Summary, loadFactorPercent: Int) {
        val locale = Settings.language(requireContext())
        binding.tvPassengersSubtitle.text = getString(
            R.string.passengers_detail_subtitle,
            loadFactorPercent
        )
        binding.passengersYearsContainer.removeAllViews()
        val maxPassengers = summary.years.maxOfOrNull { it.passengers } ?: 0
        for (year in summary.years) {
            val row = layoutInflater.inflate(
                R.layout.item_passenger_year,
                binding.passengersYearsContainer,
                false
            )
            // Ohne Tausendertrennzeichen: ein Jahr gruppiert sich nicht.
            row.findViewById<TextView>(R.id.tv_passenger_year).text =
                String.format(locale, "%d", year.year)
            row.findViewById<TextView>(R.id.tv_passenger_year_flights).text = getString(
                R.string.time_route_flights,
                year.flights
            )
            // Summen auf volle Tausender gerundet - die Schätzung trägt keine
            // Genauigkeit auf Hunderter (siehe [PassengersCalculator]).
            row.findViewById<TextView>(R.id.tv_passenger_year_value).text =
                PassengersCalculator.passengersText(
                    PassengersCalculator.roundToThousands(year.passengers),
                    locale
                )
            val bar = row.findViewById<LinearProgressIndicator>(R.id.bar_passenger_year)
            bar.progress = if (maxPassengers > 0) {
                (year.passengers.toDouble() / maxPassengers * 100.0).roundToInt()
            } else {
                0
            }
            binding.passengersYearsContainer.addView(row)
        }
        binding.tvPassengersEmpty.visibility =
            if (summary.years.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Dialog zum Einstellen der Auslastung (50–100 %, Schritt 5 %). */
    private fun showLoadFactorDialog() {
        val context = requireContext()
        val dialogView = layoutInflater.inflate(
            R.layout.dialog_passenger_load_factor,
            null
        )
        val valueText = dialogView.findViewById<TextView>(R.id.tv_load_factor_value)
        val slider = dialogView.findViewById<Slider>(R.id.slider_load_factor)
        // Ganze Prozent in 5er-Schritten wie der Kachel-Schieber, siehe
        // [DashboardEditFragment.setupSlider].
        slider.valueFrom = PassengersCalculator.MIN_LOAD_FACTOR_PERCENT.toFloat()
        slider.valueTo = PassengersCalculator.MAX_LOAD_FACTOR_PERCENT.toFloat()
        slider.stepSize = PassengersCalculator.LOAD_FACTOR_STEP_PERCENT.toFloat()
        val current = Settings.getPassengerLoadFactor(context)
        slider.value = current.toFloat()
        valueText.text = loadFactorPercentText(current)
        slider.addOnChangeListener { _, value, _ ->
            valueText.text = loadFactorPercentText(value.roundToInt())
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.passengers_adjust_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Erst bei OK wird gespeichert; dann lädt die Seite mit der
                // neuen Auslastung neu.
                Settings.setPassengerLoadFactor(context, slider.value.roundToInt())
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadFactorPercentText(percent: Int): String =
        getString(R.string.passengers_load_factor_percent, percent)

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        _binding = null
    }
}
