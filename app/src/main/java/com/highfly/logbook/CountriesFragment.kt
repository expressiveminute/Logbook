package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.highfly.logbook.databinding.FragmentCountriesBinding
import java.text.Collator
import java.util.Locale

/**
 * Länderliste hinter dem Stift auf der Rubbelkarte: alle Länder der Welt als
 * Checkliste von oben nach unten, Flagge, Auge, Name und Kästchen.
 *
 * Angeflogene Länder sind abgehakt und tragen rechts neben dem Namen ein
 * Flugzeugsymbol. Sie lassen sich abwählen: Dann stehen sie wie unangeflogene
 * Länder da, behalten aber ihr Flugzeugsymbol, und zählen nicht mehr auf der
 * Karte - bis sie wieder angehakt oder erneut angeflogen werden. Alle anderen
 * Länder kann der Nutzer selbst abhaken; sie erscheinen dann auf der Karte.
 *
 * Mit dem Argument [ARG_CONTINENT] zeigt die Seite nur die Länder eines
 * Kontinents (bzw. der EU) und trägt dessen Namen als Überschrift.
 *
 * Es gibt zwei Anzeigemodi:
 * - als eigene Seite (Stift oben rechts auf der Rubbelkarte, oder Navigation):
 *   volle Bildschirmseite, das Auge ist ausgeblendet, da keine Karte dahinter
 *   liegt;
 * - eingebettet ([ARG_EMBEDDED]) in der unteren Hälfte der Rubbelkarte, wenn
 *   man auf eine Kachel tippt: Die Kugel bleibt oben sichtbar, das Auge je
 *   Zeile zoomt sie auf das Land und hebt es hervor ([MapZoomHost]).
 *
 * Mit [ARG_STATES] zeigt die Seite statt Ländern die Bundesstaaten/-länder
 * dieses Landes (Kacheln Deutschland/USA) mit Hauptstadt.
 */
class CountriesFragment : LogbookFragment() {

    private var _binding: FragmentCountriesBinding? = null
    private val binding get() = _binding!!

    private var adapter: RecyclerView.Adapter<*>? = null
    private var countryAdapter: CountryChecklistAdapter? = null
    private var stateAdapter: StateRowAdapter? = null
    private var loadGeneration = 0

    /**
     * Kontinent als Schlüssel aus [Continents], oder null für die ganze Welt.
     * Aus den Argumenten gelesen, weil das Fragment auch nach einer
     * Zustandswiederherstellung ohne Navigation neu aufgebaut werden kann.
     */
    private val continent: String?
        get() = arguments?.getString(ARG_CONTINENT)?.trim()?.takeIf { it.isNotEmpty() }

    /** Eingebettet in der unteren Hälfte der Rubbelkarte, siehe Klasse. */
    private val embedded: Boolean
        get() = arguments?.getBoolean(ARG_EMBEDDED) == true

    /** Land, dessen Bundesstaaten/-länder gezeigt werden sollen ("DE"/"US"). */
    private val statesCountry: String?
        get() = arguments?.getString(ARG_STATES)?.trim()?.takeIf { it.isNotEmpty() }

    /** Die Rubbelkarte, wenn die Seite eingebettet ist, siehe [MapZoomHost]. */
    private val mapHost: MapZoomHost?
        get() = parentFragment as? MapZoomHost

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCountriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCountriesBack.setOnClickListener {
            if (embedded) {
                // Eingebettet schließt der Pfeil nur die halbhohe Seite.
                parentFragmentManager.popBackStack()
            } else {
                findNavController().navigateUp()
            }
        }
        // Platz unter der schwebenden Navigationsleiste: eingebettet reicht
        // die Seite bis zum unteren Bildschirmrand, die Leiste schwebt darüber.
        binding.countriesList.addBottomNavPadding()
        binding.countriesList.layoutManager = LinearLayoutManager(requireContext())
        binding.countriesList.itemAnimator?.changeDuration = 0
        // Ohne Filter steht "Länder" über der Liste, mit Filter der Kontinent.
        // Im Staaten-Modus trägt die Seite den Landesnamen, den die Umrisse
        // erst beim Laden liefern.
        binding.tvCountriesTitle.setText(
            continent?.let { Continents.nameRes(it) }
                ?: statesCountry?.let { R.string.countries_title }
                ?: R.string.countries_title
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
        val statesCountry = statesCountry

        Thread {
            val shapes = CountryShapes.load(appContext)
            val entries = LogbookRepository.getFlownEntries()
            val german = useGermanNames(locale)
            val collator = Collator.getInstance(locale)
            val items = shapes?.let { list ->
                if (statesCountry != null) {
                    null // Staaten-Modus, siehe unten
                } else {
                    val flightIso2 = RubbelkarteStats.summarize(entries) { iata ->
                        AirportData.country(appContext, iata)
                    }.countries
                    CountryChecklist.build(
                        countries = list,
                        flightIso2 = flightIso2,
                        manualIso2 = Settings.getManualCountries(appContext),
                        german = german,
                        collator = collator,
                        continent = continent,
                        hiddenIso2 = Settings.getHiddenCountries(appContext)
                    )
                }
            }
            val stateRows = if (statesCountry != null) {
                val states = StateShapes.load(appContext).orEmpty()
                    .filter { it.iso2 == statesCountry }
                val flown = RubbelkarteStats.summarize(entries) { iata ->
                    AirportData.country(appContext, iata)
                }.countries
                // fromFlight zählt nur echte Flüge (Flugzeugsymbol), visited
                // zusätzlich die selbst abgehakten Staaten minus die abgewählten.
                val flownStates = StateStats.summarize(
                    entries,
                    { iata -> AirportData.region(appContext, iata) },
                    flown
                ).states
                val summary = StateStats.summarize(
                    entries,
                    { iata -> AirportData.region(appContext, iata) },
                    flown,
                    Settings.getManualStates(appContext),
                    Settings.getHiddenStates(appContext)
                )
                states.map { state ->
                    val code = state.code.uppercase()
                    StateRowAdapter.StateRow(
                        code = state.code,
                        name = if (german) state.nameDe else state.nameEn,
                        capital = if (german) state.capDe else state.capEn,
                        fromFlight = code in flownStates,
                        visited = code in summary.states
                    )
                }.sortedWith(compareBy(collator) { it.name })
            } else {
                null
            }
            val title = statesCountry?.let { code ->
                shapes?.firstOrNull { it.iso2 == code }?.let { if (german) it.nameDe else it.nameEn }
            }
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                if (title != null) binding.tvCountriesTitle.text = title
                render(items, stateRows)
            }
        }.start()
    }

    private fun render(
        items: List<CountryChecklist.Item>?,
        stateRows: List<StateRowAdapter.StateRow>?
    ) {
        if (stateRows != null) {
            binding.countriesList.visibility = View.VISIBLE
            binding.tvCountriesEmpty.visibility = View.GONE
            val rows = stateRows
            val stateAdapter = StateRowAdapter(
                rows,
                onZoom = { row -> mapHost?.zoomToState(row.code) },
                onToggle = ::toggleState
            )
            stateAdapter.onCheckedCountChanged = ::showStateCount
            adapter = stateAdapter
            this.stateAdapter = stateAdapter
            binding.countriesList.adapter = adapter
            binding.tvCountriesSubtitle.text = getString(
                R.string.sheet_visited_count,
                rows.count { it.visited },
                rows.size
            )
            return
        }
        val hasCountries = !items.isNullOrEmpty()
        binding.countriesList.visibility = if (hasCountries) View.VISIBLE else View.GONE
        binding.tvCountriesEmpty.visibility = if (hasCountries) View.GONE else View.VISIBLE
        val checklist = CountryChecklistAdapter(::toggle)
        // Das Auge nur eingebettet: dort liegt die Kugel dahinter und kann
        // zoomen. Auf der vollen Seite gäbe es kein Ziel für den Zoom.
        if (embedded) {
            checklist.onZoom = { item -> mapHost?.zoomToCountry(item.iso2) }
        }
        checklist.onCheckedCountChanged = ::showCount
        // Der Adapter muss vor dem submit stehen: submit meldet die Zahl der
        // abgehakten Länder über showCount, und die liest itemCount am Adapter.
        countryAdapter = checklist
        stateAdapter = null
        adapter = checklist
        binding.countriesList.adapter = checklist
        checklist.submit(items.orEmpty())
        // Ohne Länder steht unter der Überschrift nichts, "0 von 0 Ländern"
        // wäre nur eine Fehlermeldung in Zahlensprache. Ein Kontinent ganz ohne
        // gelistete Länder ist ebenfalls möglich, dann bleibt die Liste leer.
        if (!hasCountries) binding.tvCountriesSubtitle.text = ""
    }

    private fun showCount(checked: Int) {
        binding.tvCountriesSubtitle.text =
            getString(R.string.countries_subtitle, checked, countryAdapter?.itemCount ?: 0)
    }

    /** Wie [showCount], aber für die Staatenliste ("x of y visited"). */
    private fun showStateCount(visited: Int) {
        binding.tvCountriesSubtitle.text =
            getString(R.string.sheet_visited_count, visited, stateAdapter?.itemCount ?: 0)
    }

    /**
     * Ein Tipp auf ein Land speichert den neuen Zustand und schaltet die Zeile
     * sofort um. Ein angeflogenes Land wandert dabei in die [Settings.getHiddenCountries]-
     * Liste, wenn es abgewählt wird, und wieder heraus, wenn es angehakt wird -
     * der Flugeintrag selbst bleibt unberührt. Ist die Seite eingebettet, lädt
     * die Rubbelkarte danach Karte und Kacheln neu.
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
        countryAdapter?.replace(item.copy(checked = !item.checked))
        if (embedded) mapHost?.onCountriesChanged()
    }

    /**
     * Ein Tipp auf einen Staat in der Staatenliste: derselbe Mechanismus wie bei
     * den Ländern - selbst abgehakt landet der Staat in
     * [Settings.getManualStates], ein abgewählter angeflogener in
     * [Settings.getHiddenStates]. Eingebettet lädt die Rubbelkarte danach Karte
     * und Kacheln neu.
     */
    private fun toggleState(row: StateRowAdapter.StateRow) {
        val context = requireContext().applicationContext
        val code = row.code.trim().uppercase()
        val manual = Settings.getManualStates(context).toMutableSet()
        val hidden = Settings.getHiddenStates(context).toMutableSet()
        if (row.visited) {
            if (row.fromFlight) {
                hidden.add(code)
            } else {
                manual.remove(code)
            }
        } else {
            hidden.remove(code)
            if (!row.fromFlight) {
                manual.add(code)
            }
        }
        ownChange = true
        try {
            Settings.setManualStates(context, manual)
            Settings.setHiddenStates(context, hidden)
        } finally {
            ownChange = false
        }
        stateAdapter?.replace(row.copy(visited = !row.visited))
        if (embedded) mapHost?.onCountriesChanged()
    }

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(locale: Locale): Boolean =
        locale.language.isEmpty() || locale.language == Settings.LANG_DE

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        adapter = null
        countryAdapter = null
        stateAdapter = null
        _binding = null
    }

    companion object {
        /** Name des Navigationsarguments, siehe `nav_graph.xml`. */
        const val ARG_CONTINENT = "continent"

        /** Eingebettet in der unteren Hälfte der Rubbelkarte (Kachel-Tipp). */
        const val ARG_EMBEDDED = "embedded"

        /** Zeigt die Bundesstaaten/-länder dieses Landes statt Ländern ("DE"/"US"). */
        const val ARG_STATES = "states"
    }
}
