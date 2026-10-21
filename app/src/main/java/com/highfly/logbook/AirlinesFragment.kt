package com.highfly.logbook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.highfly.logbook.databinding.FragmentAirlinesBinding
import java.text.Collator

/**
 * Auswertung der Kachel "Fluggesellschaften": mit welcher Airline wie oft
 * geflogen wurde, mit der häufigsten oben. Die Zeilen sehen aus wie die der
 * Reisebuddy-Liste: Rangzeichen, Name, Flugzahl und ein diskreter Balken im
 * Hintergrund, gemessen an der häufigsten Airline.
 *
 * Oben rechts im Kopf sitzt eine Lupe wie bei den Flughäfen: Sie klappt ein
 * Suchfeld aus, das nach Code ("LH", "DLH") und Name ("Lufthansa") sucht - in
 * der Rangliste und im gesamten Katalog, sodass auch nie geflogene Airlines
 * erscheinen (dann mit 0 Flügen). Ein Tipp auf eine Zeile öffnet die
 * Detailseite der Airline ([AirlineDetailFragment]); beim Zurückkehren setzt
 * die Liste an der Stelle wieder auf, an der sie verlassen wurde (über die
 * Basisklasse [LogbookFragment]).
 *
 * Grundlage sind die Flugeinträge und der Airline-Katalog, siehe
 * [AirlineStats]. Wie die Kachel selbst zählt die Seite die Periode des
 * Dashboards mit - sonst zeigte die Kachel eine Zahl und die Liste eine andere.
 */
class AirlinesFragment : LogbookFragment(), BackPressAware {

    private var _binding: FragmentAirlinesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: AirlineAdapter
    private var loadGeneration = 0

    /** Alle geflogenen Airlines vor der Suche, damit die Eingabe nur filtern muss. */
    private var allAirlines: List<AirlineStats.Airline> = emptyList()

    /**
     * Rang je Airline-Code in der ungefilterten Liste. In der Suche bleibt
     * dieser Rang stehen, statt der Position im Suchergebnis.
     */
    private var airlineRanks: Map<String, Int> = emptyMap()

    /** Aktueller Suchtext des Kopfs; filtert die Liste nach Code und Name. */
    private var searchQuery: String = ""

    /** Beschriftung der Suche im geschlossenen Zustand (Lupe). */
    private var searchClosedDescRes: Int = R.string.airline_search_desc

    /** Ob das Suchfeld oben im Kopf ausgeklappt ist. */
    private var searchOpen = false

    /** Laufende Aufklapp-/Einklappanimation des Suchfelds. */
    private var searchAnimator: ValueAnimator? = null

    /** Sortierung der Namen, in der Sprache der App. */
    private var collator: Collator = Collator.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAirlinesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.getString(KEY_SEARCH_QUERY)?.let { searchQuery = it }
        searchOpen = savedInstanceState?.getBoolean(KEY_SEARCH_OPEN) ?: false
        binding.btnAirlinesBack.setOnClickListener { findNavController().navigateUp() }
        binding.airlineList.layoutManager = LinearLayoutManager(requireContext())
        adapter = AirlineAdapter()
        adapter.onItemClick = { airline ->
            findNavController().navigate(
                R.id.action_airlines_to_detail,
                bundleOf(AirlineDetailFragment.ARG_CODE to airline.code)
            )
        }
        binding.airlineList.adapter = adapter
        setupSearch()
    }

    /**
     * Lupe oben im Kopf, rechts im Header - wie bei den Kacheln Layover,
     * Flughäfen und Hangar ([TileDetailFragment]) und bei den Reisebuddies.
     * Ein Tipp klappt links daneben das Suchfeld aus, ein zweiter wieder ein
     * und hebt den Filter auf. Gesucht wird nach Code und Name der Airline.
     */
    private fun setupSearch() {
        binding.etSearch.setHint(R.string.airline_search_hint)
        // Text vor dem Beobachter setzen: die wiederhergestellte Suche soll
        // nicht schon hier, sondern erst beim Eintreffen der Liste greifen.
        binding.etSearch.setText(searchQuery)
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                applyFilter()
            }
        })
        binding.ivSearch.setOnClickListener {
            toggleSearch(!searchOpen)
        }
        updateSearchIcon(searchOpen)
        if (searchOpen) {
            binding.etSearch.visibility = View.VISIBLE
            binding.etSearch.alpha = 1f
            setSearchProgress(1f)
        }
    }

    /**
     * Tauscht das Symbol der Suche: Lupe geschlossen, X geoeffnet. Das X
     * schliesst das Feld und loescht die Eingabe (siehe [toggleSearch]).
     */
    private fun updateSearchIcon(open: Boolean) {
        binding.ivSearch.setImageResource(
            if (open) R.drawable.ic_close else R.drawable.ic_search
        )
        binding.ivSearch.contentDescription = getString(
            if (open) R.string.search_clear_desc else searchClosedDescRes
        )
    }

    /**
     * Klappt das Suchfeld aus oder ein. Beim Oeffnen waechst es aus der Lupe
     * heraus nach links ueber den Titel, beim Schliessen laeuft es rueckwaerts
     * und leert die Suche erst am Ende - so springt die Liste nicht schon
     * waehrend der Animation auf den vollen Bestand zurueck.
     */
    private fun toggleSearch(open: Boolean) {
        searchAnimator?.cancel()
        searchOpen = open
        updateSearchIcon(open)
        if (open) {
            binding.etSearch.visibility = View.VISIBLE
            binding.etSearch.requestFocus()
            binding.etSearch.post {
                if (_binding != null) showSearchKeyboard()
            }
        } else {
            hideSearchKeyboard()
        }
        searchAnimator = ValueAnimator.ofFloat(if (open) 0f else 1f, if (open) 1f else 0f)
            .apply {
                duration = SEARCH_ANIMATION_MS
                addUpdateListener { animation ->
                    setSearchProgress(animation.animatedValue as Float)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!open) {
                            binding.etSearch.visibility = View.GONE
                            // Leeren loest den Beobachter aus und zeigt wieder
                            // alle Airlines.
                            binding.etSearch.setText("")
                        }
                    }
                })
                start()
            }
    }

    /**
     * Oeffnungsgrad des Suchfelds als gegenlaeufige Gewichte: Bei 0 traegt der
     * Titel den Rest und das Feld bleibt ohne Breite, bei 1 fuellt das Feld
     * den Platz bis zur Lupe und der Titel verschwindet. Die Lupe rechts
     * bleibt dabei stehen.
     */
    private fun setSearchProgress(progress: Float) {
        val field = binding.etSearch
        if (progress > 0f) field.visibility = View.VISIBLE
        field.alpha = progress
        val fieldParams = field.layoutParams as LinearLayout.LayoutParams
        fieldParams.weight = progress
        field.requestLayout()
        val titleParams = binding.titleGroup.layoutParams as LinearLayout.LayoutParams
        titleParams.weight = 1f - progress
        binding.titleGroup.requestLayout()
    }

    private fun showSearchKeyboard() {
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearchKeyboard() {
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    /**
     * Die Zurück-Taste räumt zuerst das Suchfeld ab (wie das Schliessen-X) und
     * navigiert erst beim zweiten Druck zurück.
     */
    override fun onBackPressed(): Boolean {
        if (!searchOpen) return false
        toggleSearch(false)
        return true
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
        collator = Collator.getInstance(resources.configuration.locales[0])

        Thread {
            // Dieselbe Auswahl wie die Kachel: Flugart und Zeitraum.
            val entries = DashboardStats.dashboardEntries(appContext)
            val airlines = AirlineStats.build(entries, { AirlineCatalog.resolve(appContext, it) }, collator)
            val flights = AirlineStats.flightsWithAirline(entries)
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                render(airlines, flights)
            }
        }.start()
    }

    private fun render(airlines: List<AirlineStats.Airline>, flights: Int) {
        allAirlines = airlines
        // Rang der ungefilterten Liste: die Liste ist nach Flugzahl sortiert,
        // der Rang ist die Position darin. In der Suche bleibt er stehen.
        airlineRanks = airlines.withIndex().associate { (index, airline) ->
            airline.code to index + 1
        }
        // Ohne eingetragene Airline wäre "0 Fluggesellschaften auf 0 Flügen"
        // nur eine Fehlermeldung in Zahlensprache, deshalb bleibt die Zeile
        // dann leer.
        binding.tvAirlinesSubtitle.text = if (airlines.isNotEmpty()) {
            getString(R.string.airlines_subtitle, airlines.size, flights)
        } else {
            ""
        }
        applyFilter()
    }

    /**
     * Filtert die Liste nach der Sucheingabe - im IATA-Code, ICAO-Code und
     * Namen der Airline, ohne Gross- und Kleinschreibung zu beachten. Ohne
     * Eingabe ist die Rangliste komplett.
     *
     * Mit Eingabe durchsucht der Katalog alle hinterlegten Airlines, wie das
     * Suchfeld der Flughäfen-Kachel den Flughafenbestand: auch nie geflogene
     * Airlines erscheinen, dann mit 0 Flügen. Eine geflogene Airline, die der
     * Katalog nicht kennt, steht als eigener Rohtext in der Liste und bleibt
     * über die Suche auffindbar. Der Balken einer Zeile bleibt am Maximum der
     * ungefilterten Liste gemessen, damit die Balken beim Tippen nicht
     * mitspringen. In der Suche trägt das Rangzeichen keine Medaille, sondern
     * den Rang der ungefilterten Liste - eine Airline ohne Flug (nicht in der
     * Liste) trägt keinen Rang.
     */
    private fun applyFilter() {
        val query = searchQuery.trim()
        val filtered = if (query.isEmpty()) {
            allAirlines
        } else {
            val flownByCode = allAirlines.associateBy { it.code }
            val catalogMatches = AirlineCatalog.search(
                requireContext(), query, AIRLINE_SEARCH_LIMIT
            )
            val rows = catalogMatches.map { info ->
                flownByCode[info.iata] ?: AirlineStats.Airline(
                    code = info.iata,
                    name = info.name,
                    icao = info.icao,
                    flights = 0,
                    minutes = 0,
                    km = 0,
                    destinations = 0,
                    aircraftTypes = 0,
                    firstFlight = null,
                    lastFlight = null
                )
            }
            // Auch eine geflogene Airline, die das Limit des Katalogs oder den
            // Katalog ganz verfehlt, soll bei passender Suche erscheinen.
            val catalogCodes = catalogMatches.map { it.iata }.toHashSet()
            val extra = allAirlines.filter {
                it.code !in catalogCodes &&
                    AirlineStats.matchesQuery(it.code, it.icao, it.name, query)
            }
            (rows + extra).sortedWith(
                compareBy<AirlineStats.Airline> {
                    AirlineStats.searchScore(it.code, it.icao, it.name, query)
                }
                    .thenByDescending { it.flights }
                    .thenBy(collator) { it.name }
            )
        }
        binding.airlineList.visibility =
            if (filtered.isNotEmpty()) View.VISIBLE else View.GONE
        if (filtered.isEmpty()) {
            binding.tvAirlinesEmpty.visibility = View.VISIBLE
            // Passen keine Airlines zur Suche, ist das eine andere Meldung als
            // ein Logbuch ohne jede Airline.
            binding.tvAirlinesEmpty.setText(
                if (allAirlines.isEmpty()) {
                    R.string.airlines_empty
                } else {
                    R.string.airline_search_empty
                }
            )
        } else {
            binding.tvAirlinesEmpty.visibility = View.GONE
        }
        adapter.showMedals = query.isEmpty()
        adapter.submit(filtered, allAirlines.maxOfOrNull { it.flights } ?: 0, airlineRanks)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SEARCH_QUERY, searchQuery)
        outState.putBoolean(KEY_SEARCH_OPEN, searchOpen)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        searchAnimator?.cancel()
        _binding = null
    }

    private companion object {
        const val KEY_SEARCH_QUERY = "searchQuery"
        const val KEY_SEARCH_OPEN = "searchOpen"

        /** Dauer der Aufklapp-/Einklappanimation des Suchfelds. */
        const val SEARCH_ANIMATION_MS = 220L

        /**
         * Obergrenze der Suchtreffer. Der Katalog umfasst über eintausend
         * Airlines; ohne Grenze würde eine kurze Eingabe die Liste endlos lang
         * machen.
         */
        const val AIRLINE_SEARCH_LIMIT = 40
    }
}
