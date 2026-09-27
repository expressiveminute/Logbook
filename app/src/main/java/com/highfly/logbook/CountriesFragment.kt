package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.highfly.logbook.databinding.FragmentCountriesBinding
import java.text.Collator
import java.util.Locale

/**
 * Länderliste hinter dem Stift auf der Rubbelkarte: alle Länder der Welt als
 * Checkliste von oben nach unten, Flagge, Name und Kästchen.
 *
 * Angeflogene Länder sind abgehakt und tragen links neben dem Kästchen ein
 * Flugzeugsymbol, lassen sich aber nicht abwählen - sie stehen wegen der
 * Einträge auf der Karte. Alle anderen Länder kann der Nutzer selbst abhaken;
 * sie erscheinen dann ebenfalls auf der Karte.
 */
class CountriesFragment : Fragment() {

    private var _binding: FragmentCountriesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: CountryChecklistAdapter
    private var loadGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCountriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCountriesBack.setOnClickListener { findNavController().navigateUp() }
        binding.countriesList.layoutManager = LinearLayoutManager(requireContext())
        adapter = CountryChecklistAdapter(::toggle)
        adapter.onCheckedCountChanged = ::showCount
        binding.countriesList.adapter = adapter
        binding.countriesList.itemAnimator?.changeDuration = 0
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
                val flightIso2 = RubbelkarteStats.summarize(LogbookRepository.getEntries()) { iata ->
                    AirportData.country(appContext, iata)
                }.countries
                CountryChecklist.build(
                    countries = list,
                    flightIso2 = flightIso2,
                    manualIso2 = Settings.getManualCountries(appContext),
                    german = useGermanNames(locale),
                    collator = Collator.getInstance(locale)
                )
            }
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                render(items)
            }
        }.start()
    }

    private fun render(items: List<CountryChecklist.Item>?) {
        val hasCountries = !items.isNullOrEmpty()
        binding.countriesList.visibility = if (hasCountries) View.VISIBLE else View.GONE
        binding.tvCountriesEmpty.visibility = if (hasCountries) View.GONE else View.VISIBLE
        adapter.submit(items.orEmpty())
        // Ohne Länder steht unter der Überschrift nichts, "0 von 0 Ländern"
        // wäre nur eine Fehlermeldung in Zahlensprache.
        if (!hasCountries) binding.tvCountriesSubtitle.text = ""
    }

    private fun showCount(checked: Int) {
        binding.tvCountriesSubtitle.text =
            getString(R.string.countries_subtitle, checked, adapter.itemCount)
    }

    /**
     * Ein selbst abgehaktes Land wird gespeichert und in der Liste sofort
     * umgeschaltet. Die Karte liest die Liste erst wieder beim Öffnen der
     * Rubbelkarte, deshalb ist hier keine weitere Nachricht nötig.
     */
    private fun toggle(item: CountryChecklist.Item) {
        if (!item.toggleable) return
        val context = requireContext().applicationContext
        val manual = Settings.getManualCountries(context)
        val updated = if (item.checked) manual - item.iso2 else manual + item.iso2
        Settings.setManualCountries(context, updated)
        adapter.replace(item.copy(checked = !item.checked))
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
