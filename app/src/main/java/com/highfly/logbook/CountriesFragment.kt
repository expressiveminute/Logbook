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
 * Flugzeugsymbol. Sie lassen sich abwählen: Dann stehen sie wie unangeflogene
 * Länder da, behalten aber ihr Flugzeugsymbol, und zählen nicht mehr auf der
 * Karte - bis sie wieder angehakt oder erneut angeflogen werden. Alle anderen
 * Länder kann der Nutzer selbst abhaken; sie erscheinen dann auf der Karte.
 *
 * Mit dem Argument [ARG_CONTINENT] zeigt die Seite nur die Länder eines
 * Kontinents und trägt dessen Namen als Überschrift. Das ist der Weg von den
 * Kontinent-Kacheln unter der Rubbelkarte - dieselbe Liste, nur gefiltert.
 */
class CountriesFragment : Fragment() {

    private var _binding: FragmentCountriesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: CountryChecklistAdapter
    private var loadGeneration = 0

    /**
     * Kontinent als Schlüssel aus [Continents], oder null für die ganze Welt.
     * Aus den Argumenten gelesen, weil das Fragment auch nach einer
     * Zustandswiederherstellung ohne Navigation neu aufgebaut werden kann.
     */
    private val continent: String?
        get() = arguments?.getString(ARG_CONTINENT)?.trim()?.takeIf { it.isNotEmpty() }

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
        // Ohne Filter steht "Länder" über der Liste, mit Filter der Kontinent.
        binding.tvCountriesTitle.setText(
            continent?.let { Continents.nameRes(it) } ?: R.string.countries_title
        )
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /**
     * Die Länderliste hängt sich an die Meldung über Änderungen an. Ein Tipp
     * in der anderen Liste - dem Kontinent hier oder der mit allen Ländern -
     * soll hier ohne Umweg über das Zurückkommen sichtbar werden.
     *
     * Angemeldet wird im [onStart] und abgemeldet im [onStop], solange die Seite
     * wirklich sichtbar ist: [Settings.addManualCountriesListener] hält die Seite
     * nur schwach, trotzdem soll sie im Hintergrund nichts mehr laden.
     */
    private val onManualCountriesChanged: () -> Unit = { if (isAdded && !ownChange) load() }

    /**
     * Steht, während diese Seite selbst etwas abgehakt hat. Sie trägt den
     * Tipp mit [CountryChecklistAdapter.replace] selbst ein und darf sich dafür
     * nicht zusätzlich die ganze Liste neu laden - das würde die Liste springen
     * lassen und jeden Tipp spürbar machen. Die Meldung selbst ist synchron,
     * die Sperre ist also zuverlässig weg, bevor [toggle] endet.
     */
    private var ownChange = false

    override fun onStart() {
        super.onStart()
        Settings.addManualCountriesListener(onManualCountriesChanged)
    }

    override fun onStop() {
        Settings.removeManualCountriesListener(onManualCountriesChanged)
        // Läuft noch ein Ladevorgang, kommt sein Ergebnis nicht mehr in eine
        // Seite, die niemand mehr sieht.
        loadGeneration++
        super.onStop()
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
        val continent = continent

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
                    collator = Collator.getInstance(locale),
                    continent = continent,
                    hiddenIso2 = Settings.getHiddenCountries(appContext)
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
        // wäre nur eine Fehlermeldung in Zahlensprache. Ein Kontinent ganz ohne
        // gelistete Länder ist ebenfalls möglich, dann bleibt die Liste leer.
        if (!hasCountries) binding.tvCountriesSubtitle.text = ""
    }

    private fun showCount(checked: Int) {
        binding.tvCountriesSubtitle.text =
            getString(R.string.countries_subtitle, checked, adapter.itemCount)
    }

    /**
     * Ein Tipp auf ein Land speichert den neuen Zustand und schaltet die Zeile
     * sofort um. Ein angeflogenes Land wandert dabei in die [Settings.getHiddenCountries]-
     * Liste, wenn es abgewählt wird, und wieder heraus, wenn es angehakt wird -
     * der Flugeintrag selbst bleibt unberührt. Die Karte liest die Liste erst
     * wieder beim Öffnen der Rubbelkarte, deshalb ist hier keine weitere
     * Nachricht nötig.
     */
    private fun toggle(item: CountryChecklist.Item) {
        val context = requireContext().applicationContext
        val manual = Settings.getManualCountries(context).toMutableSet()
        val hidden = Settings.getHiddenCountries(context).toMutableSet()
        if (item.checked) {
            if (item.fromFlight) {
                hidden.add(item.iso2)
            } else {
                manual.remove(item.iso2)
            }
        } else {
            hidden.remove(item.iso2)
            if (!item.fromFlight) {
                manual.add(item.iso2)
            }
        }
        ownChange = true
        try {
            Settings.setManualCountries(context, manual)
            Settings.setHiddenCountries(context, hidden)
        } finally {
            ownChange = false
        }
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

    companion object {
        /** Name des Navigationsarguments, siehe `nav_graph.xml`. */
        const val ARG_CONTINENT = "continent"
    }
}
