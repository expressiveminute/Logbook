package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentRadiationBinding

/**
 * Detailseite der Kachel "Höhenstrahlung": die Dosis aus kosmischer
 * Höhenstrahlung je Kalenderjahr, das neuste Jahr oben (siehe
 * [RadiationStats] und [RadiationCalculator]). Oben rechts im Kopf sitzt ein
 * Info-Knopf, der die Erklärseite zur Berechnung öffnet.
 *
 * Grundlage sind dieselben Einträge wie die Kachel: der Flugart-Filter gilt,
 * der Zeitraum-Filter des Dashboards nicht - die Kachel zeigt das laufende
 * Jahr, diese Seite listet alle Jahre.
 */
class RadiationFragment : LogbookFragment() {

    private var _binding: FragmentRadiationBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRadiationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Platz unter der schwebenden Navigationsleiste.
        binding.scrollRadiation.addBottomNavPadding()
        binding.btnRadiationBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnRadiationInfo.setOnClickListener {
            findNavController().navigate(R.id.action_radiation_to_info)
        }
        val context = requireContext()
        // Aktiver Flugart-Filter: Der Wert leuchtet in der Akzentfarbe.
        val typeKey = Settings.getFlightTypeFilterKey(context)
        val typeLabel = FlightTypeOptions.label(context, typeKey)
        binding.tvRadiationFilterInfo.text = FilterHighlight.accent(
            binding.tvRadiationFilterInfo,
            getString(R.string.radiation_filter_flight_type, typeLabel),
            typeLabel,
            typeKey != FlightTypeOptions.KEY_ALL
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
            val years = RadiationStats.byYear(entries) { code ->
                AirportData.location(appContext, code)
            }
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                render(years)
            }
        }.start()
    }

    /** Baut die Zeilen je Jahr, neustes Jahr oben. */
    private fun render(years: List<RadiationStats.YearDose>) {
        val locale = Settings.language(requireContext())
        binding.radiationYearsContainer.removeAllViews()
        for (year in years) {
            val row = layoutInflater.inflate(
                R.layout.item_radiation_year,
                binding.radiationYearsContainer,
                false
            )
            // Ohne Tausendertrennzeichen: ein Jahr gruppiert sich nicht.
            row.findViewById<TextView>(R.id.tv_radiation_year).text =
                String.format(locale, "%d", year.year)
            row.findViewById<TextView>(R.id.tv_radiation_year_hours).text = getString(
                R.string.radiation_year_hours,
                String.format(locale, "%,.1f", year.minutes / 60.0)
            )
            row.findViewById<TextView>(R.id.tv_radiation_year_value).text =
                RadiationCalculator.msvText(year.uSv, locale)
            binding.radiationYearsContainer.addView(row)
        }
        binding.tvRadiationEmpty.visibility = if (years.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        _binding = null
    }
}
