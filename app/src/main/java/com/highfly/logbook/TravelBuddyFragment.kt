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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.highfly.logbook.databinding.FragmentTravelBuddiesBinding

/**
 * Auswertung der Kachel "Reisebuddy": mit wem wie oft geflogen wurde, mit dem
 * am häufigsten geflogenen Buddy oben. Oben rechts im Kopf sitzt eine Lupe wie
 * bei den Kacheln Layover, Flughäfen und Hangar: Sie klappt ein Suchfeld aus,
 * das die Liste nach dem Namen filtert.
 *
 * Grundlage sind die Flugeinträge und das Feld "Reisebuddy" im Formular, siehe
 * [TravelBuddyStats]. Wie die Kachel selbst zählt die Seite die Periode des
 * Dashboards mit - sonst zeigte die Kachel eine Zahl und die Liste eine andere.
 */
class TravelBuddyFragment : LogbookFragment(), BackPressAware {

    private var _binding: FragmentTravelBuddiesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TravelBuddyAdapter
    private var loadGeneration = 0

    /** Alle Reisebuddies vor der Suche, damit die Eingabe nur filtern muss. */
    private var allBuddies: List<TravelBuddyStats.Buddy> = emptyList()

    /**
     * Rang je Name in der ungefilterten Liste. In der Suche bleibt dieser
     * Rang stehen, statt der Position im Suchergebnis.
     */
    private var buddyRanks: Map<String, Int> = emptyMap()

    /** Aktueller Suchtext des Kopfs; filtert die Liste nach dem Namen. */
    private var searchQuery: String = ""

    /** Beschriftung der Suche im geschlossenen Zustand (Lupe). */
    private var searchClosedDescRes: Int = R.string.travel_buddy_search_desc

    /** Ob das Suchfeld oben im Kopf ausgeklappt ist. */
    private var searchOpen = false

    /** Laufende Aufklapp-/Einklappanimation des Suchfelds. */
    private var searchAnimator: ValueAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTravelBuddiesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.getString(KEY_SEARCH_QUERY)?.let { searchQuery = it }
        searchOpen = savedInstanceState?.getBoolean(KEY_SEARCH_OPEN) ?: false
        binding.btnTravelBuddyBack.setOnClickListener { findNavController().navigateUp() }
        binding.travelBuddyList.layoutManager = LinearLayoutManager(requireContext())
        adapter = TravelBuddyAdapter()
        adapter.onItemClick = { buddy ->
            val args = Bundle().apply { putString(TravelBuddyDetailFragment.ARG_NAME, buddy.name) }
            findNavController().navigate(R.id.action_travel_buddies_to_detail, args)
        }
        binding.travelBuddyList.adapter = adapter
        setupSearch()
    }

    /**
     * Lupe oben im Kopf, rechts im Header - wie bei den Kacheln Layover,
     * Flughäfen und Hangar ([TileDetailFragment]). Ein Tipp klappt links
     * daneben das Suchfeld aus, ein zweiter wieder ein und hebt den Filter auf.
     * Gesucht wird nach dem Namen des Reisebuddies.
     */
    private fun setupSearch() {
        binding.etSearch.setHint(R.string.travel_buddy_search_hint)
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
                            // alle Buddies.
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
        val locale = resources.configuration.locales[0]

        Thread {
            // Dieselbe Auswahl wie die Kachel: Flugart und Zeitraum.
            val entries = DashboardStats.dashboardEntries(appContext)
            val buddies = TravelBuddyStats.build(entries, java.text.Collator.getInstance(locale))
            val flights = TravelBuddyStats.flightsWithBuddy(entries)
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                render(buddies, flights)
            }
        }.start()
    }

    private fun render(buddies: List<TravelBuddyStats.Buddy>, flights: Int) {
        allBuddies = buddies
        // Rang der ungefilterten Liste: die Liste ist nach Flugzahl sortiert,
        // der Rang ist die Position darin. In der Suche bleibt er stehen.
        buddyRanks = buddies.withIndex().associate { (index, buddy) ->
            buddy.name to index + 1
        }
        val hasBuddies = buddies.isNotEmpty()
        // Ohne eingetragenen Buddy wäre "0 Reisebuddies auf 0 Flügen" nur eine
        // Fehlermeldung in Zahlensprache, deshalb bleibt die Zeile dann leer.
        binding.tvTravelBuddySubtitle.text = if (hasBuddies) {
            getString(R.string.travel_buddies_subtitle, buddies.size, flights)
        } else {
            ""
        }
        applyFilter()
    }

    /**
     * Filtert die Liste nach der Sucheingabe - im Namen des Buddies, ohne
     * Gross- und Kleinschreibung zu beachten. Ohne Eingabe ist die Liste
     * komplett. Der Balken einer Zeile bleibt am Maximum der ungefilterten
     * Liste gemessen, damit die Balken beim Tippen nicht mitspringen. In der
     * Suche trägt das Rangzeichen keine Medaille, sondern den Rang der
     * ungefilterten Liste.
     */
    private fun applyFilter() {
        val query = searchQuery.trim()
        val filtered = if (query.isEmpty()) {
            allBuddies
        } else {
            allBuddies.filter { it.name.contains(query, ignoreCase = true) }
        }
        binding.travelBuddyList.visibility =
            if (filtered.isNotEmpty()) View.VISIBLE else View.GONE
        if (filtered.isEmpty()) {
            binding.tvTravelBuddyEmpty.visibility = View.VISIBLE
            // Passen keine Namen zur Suche, ist das eine andere Meldung als
            // ein Logbuch ohne jeden Buddy.
            binding.tvTravelBuddyEmpty.setText(
                if (allBuddies.isEmpty()) {
                    R.string.travel_buddies_empty
                } else {
                    R.string.travel_buddy_search_empty
                }
            )
        } else {
            binding.tvTravelBuddyEmpty.visibility = View.GONE
        }
        adapter.showMedals = query.isEmpty()
        adapter.submit(filtered, allBuddies.maxOfOrNull { it.flights } ?: 0, buddyRanks)
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
    }
}
