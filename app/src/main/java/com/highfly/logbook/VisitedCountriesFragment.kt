package com.highfly.logbook

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
 *
 * Rechts in der Kopfzeile sitzt eine Lupe: Sie blendet ein Suchfeld über den
 * Titel ein, das sich von rechts nach links öffnet und die Liste nach dem
 * Ländernamen filtert. Kommt der Nutzer von einer Länderseite zurück, bleibt
 * die Liste an der Stelle stehen, an der er das Land angetippt hat.
 */
class VisitedCountriesFragment : Fragment() {

    private var _binding: FragmentVisitedCountriesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VisitedCountriesAdapter
    private var loadGeneration = 0

    /** Suchfeld offen oder zu; steuert Lupe und Titelumschaltung. */
    private var searchActive = false

    /**
     * Position, an der die Liste beim Zurückkommen wieder aufsetzt. Sie wird
     * beim Verlassen der Seite gemerkt und nach dem Neuladen wiederhergestellt,
     * damit ein Tipp auf ein Land nicht oben in der Liste endet.
     */
    private var savedScrollPosition = RecyclerView.NO_POSITION
    private var savedScrollOffset = 0

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
        binding.btnVisitedCountriesSearch.setOnClickListener { toggleSearch(!searchActive) }
        binding.etCountrySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.setQuery(s?.toString().orEmpty())
            }
        })
        binding.etCountrySearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
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
        // Rückkehr von einer Länderseite: an der gemerkten Stelle aufsetzen.
        if (hasCountries && savedScrollPosition != RecyclerView.NO_POSITION) {
            val position = savedScrollPosition
            val offset = savedScrollOffset
            savedScrollPosition = RecyclerView.NO_POSITION
            val list = binding.visitedCountriesList
            list.post {
                (list.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(position, offset)
            }
        }
    }

    /** Ein Tipp auf ein Land öffnet dessen Länderseite mit der Karte. */
    private fun openCountry(iso2: String) {
        findNavController().navigate(
            R.id.action_visited_countries_to_country_detail,
            bundleOf(CountryDetailFragment.ARG_ISO2 to iso2)
        )
    }

    /**
     * Blendet das Suchfeld ein oder aus. Beim Öffnen gleitet es von rechts
     * über die Titelfläche nach links, beim Schließen in die Gegenrichtung;
     * die Titelleiste fadet dabei passend aus bzw. ein.
     */
    private fun toggleSearch(open: Boolean) {
        if (open == searchActive) return
        searchActive = open
        val frame = binding.headerSearchContainer
        val distance = frame.width.toFloat().coerceAtLeast(1f)
        val search = binding.etCountrySearch
        val title = binding.llVisitedCountriesTitle
        if (open) {
            search.visibility = View.VISIBLE
            search.translationX = distance
            search.alpha = 0f
            search.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(220)
                .start()
            title.animate().alpha(0f).setDuration(160)
                .withEndAction { title.visibility = View.GONE }
                .start()
            binding.btnVisitedCountriesSearch.setIconResource(R.drawable.ic_close)
            binding.btnVisitedCountriesSearch.contentDescription =
                getString(R.string.visited_countries_search_close)
            search.requestFocus()
            val imm = ContextCompat.getSystemService(
                requireContext(), InputMethodManager::class.java
            )
            imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        } else {
            search.clearFocus()
            hideKeyboard()
            search.animate()
                .translationX(distance)
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    search.visibility = View.GONE
                    search.setText("")
                }
                .start()
            title.visibility = View.VISIBLE
            title.alpha = 0f
            title.animate().alpha(1f).setDuration(160).start()
            binding.btnVisitedCountriesSearch.setIconResource(R.drawable.ic_search)
            binding.btnVisitedCountriesSearch.contentDescription =
                getString(R.string.visited_countries_search_open)
            adapter.setQuery("")
        }
    }

    private fun hideKeyboard() {
        ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.etCountrySearch.windowToken, 0)
    }

    /**
     * Merkt sich die Scrollposition der Liste, bevor die Seite verlassen
     * wird - zum Beispiel für den Tipp auf ein Land, nach dem die Liste an
     * derselben Stelle wieder erscheinen soll.
     */
    private fun saveScrollPosition() {
        val layoutManager =
            binding.visitedCountriesList.layoutManager as? LinearLayoutManager ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        savedScrollPosition = position.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        val first = layoutManager.findViewByPosition(position)
        savedScrollOffset = if (first != null) {
            first.top - binding.visitedCountriesList.paddingTop
        } else {
            0
        }
    }

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(locale: Locale): Boolean =
        locale.language.isEmpty() || locale.language == Settings.LANG_DE

    override fun onDestroyView() {
        saveScrollPosition()
        super.onDestroyView()
        loadGeneration++
        _binding = null
    }
}