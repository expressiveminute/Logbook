package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.highfly.logbook.databinding.FragmentTravelBuddiesBinding

/**
 * Auswertung der Kachel "Reisebuddy": mit wem wie oft geflogen wurde, mit dem
 * am häufigsten geflogenen Buddy oben.
 *
 * Grundlage sind die Flugeinträge und das Feld "Reisebuddy" im Formular, siehe
 * [TravelBuddyStats]. Wie die Kachel selbst zählt die Seite die Periode des
 * Dashboards mit - sonst zeigte die Kachel eine Zahl und die Liste eine andere.
 */
class TravelBuddyFragment : Fragment() {

    private var _binding: FragmentTravelBuddiesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TravelBuddyAdapter
    private var loadGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTravelBuddiesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnTravelBuddyBack.setOnClickListener { findNavController().navigateUp() }
        binding.travelBuddyList.layoutManager = LinearLayoutManager(requireContext())
        adapter = TravelBuddyAdapter()
        binding.travelBuddyList.adapter = adapter
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
        val periodKey = Settings.getDefaultPeriodKey(appContext)
        val locale = resources.configuration.locales[0]

        Thread {
            val entries = DashboardStats.filterForPeriod(
                LogbookRepository.getFlownEntries(), periodKey
            )
            val buddies = TravelBuddyStats.build(entries, java.text.Collator.getInstance(locale))
            val flights = TravelBuddyStats.flightsWithBuddy(entries)
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                render(buddies, flights)
            }
        }.start()
    }

    private fun render(buddies: List<TravelBuddyStats.Buddy>, flights: Int) {
        val hasBuddies = buddies.isNotEmpty()
        binding.travelBuddyList.visibility = if (hasBuddies) View.VISIBLE else View.GONE
        binding.tvTravelBuddyEmpty.visibility = if (hasBuddies) View.GONE else View.VISIBLE
        adapter.submit(buddies)
        // Ohne eingetragenen Buddy wäre "0 Reisebuddies auf 0 Flügen" nur eine
        // Fehlermeldung in Zahlensprache, deshalb bleibt die Zeile dann leer.
        binding.tvTravelBuddySubtitle.text = if (hasBuddies) {
            getString(R.string.travel_buddies_subtitle, buddies.size, flights)
        } else {
            ""
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        _binding = null
    }
}
