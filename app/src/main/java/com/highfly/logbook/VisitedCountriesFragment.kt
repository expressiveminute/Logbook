package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.highfly.logbook.databinding.FragmentVisitedCountriesBinding
import java.text.Collator
import java.util.Locale

/**
 * Länderliste des Dashboards: alle Länder, in denen laut Logbuch geflogen
 * wurde, von oben nach unten mit Rang, Flagge, Anzeigename und der Zahl der
 * Flüge - die meistbesuchten Länder stehen oben. Ein Tipp auf ein Land öffnet
 * die Länderseite mit der Karte und den Verbindungen.
 *
 * Anders als die Checkliste der Rubbelkarte ist diese Liste eine reine
 * Übersicht: Sie berücksichtigt nur Flugeinträge, keine selbst abgehakten
 * oder abgewählten Länder.
 */
class VisitedCountriesFragment : Fragment() {

    private var _binding: FragmentVisitedCountriesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VisitedCountriesAdapter
    private var loadGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVisitedCountriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnVisitedCountriesBack.setOnClickListener { findNavController().navigateUp() }
        binding.visitedCountriesList.layoutManager = LinearLayoutManager(requireContext())
        adapter = VisitedCountriesAdapter(::openCountry)
        binding.visitedCountriesList.adapter = adapter
        binding.tvVisitedCountriesTitle.setText(R.string.countries_title)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /**
     * Lädt Umrisse und Einträge im Hintergrund: Das Asset ist knapp vier
     * Megabyte groß und darf die Seite beim Öffnen nicht blockieren. Ein
     * Generationszähler verwirft Ergebnisse einer bereits verlassenen Seite.
     */
    private fun load() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        val locale = resources.configuration.locales[0]

        Thread {
            val shapes = CountryShapes.load(appContext)
            val items = shapes?.let { list ->
                VisitedCountries.build(
                    countries = list,
                    entries = LogbookRepository.getFlownEntries(),
                    german = useGermanNames(locale),
                    collator = Collator.getInstance(locale),
                    countryOf = { AirportData.country(appContext, it) }
                )
            }
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                render(items)
            }
        }.start()
    }

    private fun render(items: List<VisitedCountries.Item>?) {
        val hasCountries = !items.isNullOrEmpty()
        binding.visitedCountriesList.visibility = if (hasCountries) View.VISIBLE else View.GONE
        binding.tvVisitedCountriesEmpty.visibility = if (hasCountries) View.GONE else View.VISIBLE
        binding.tvVisitedCountriesSubtitle.visibility = if (hasCountries) View.VISIBLE else View.GONE
        adapter.submit(items.orEmpty())
        if (hasCountries) {
            binding.tvVisitedCountriesSubtitle.text = resources.getQuantityString(
                R.plurals.visited_countries_subtitle,
                items!!.size,
                items.size
            )
        }
    }

    /** Ein Tipp auf ein Land öffnet dessen Länderseite mit der Karte. */
    private fun openCountry(iso2: String) {
        findNavController().navigate(
            R.id.action_visited_countries_to_country_detail,
            bundleOf(CountryDetailFragment.ARG_ISO2 to iso2)
        )
    }

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(locale: Locale): Boolean =
        locale.language.isEmpty() || locale.language == Settings.LANG_DE

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        _binding = null
    }
}