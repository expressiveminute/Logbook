package com.highfly.logbook

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.highfly.logbook.databinding.FragmentTimeCountriesBinding
import java.time.LocalDate

/**
 * Seite "Zeit in Ländern" (letzter Tab der Navigationsleiste): Aufenthaltsdauer
 * pro Land aus den Flugeinträgen - ein Flug ins Land ist das Betreten, ein Flug
 * hinaus das Verlassen, dazwischen liegt die Zeit im Land (tageweise, die
 * Einträge haben nur ein Datum). Nur Layover- und Privat-Flüge zählen
 * ([CountryTimeStats.isPrivateFlight]/[CountryTimeStats.layoverHoursOf]). Das Land der Homebase wird nicht gezaehlt;
 * der Hinweis darauf steht als Fusszeile ganz unten auf der Seite. Rechts im
 * Kopf sitzt eine Lupe, die ein Suchfeld nach Land einblendet.
 *
 * Landweg-Aufenthalte (Auto, Bus, Fähre) stehen nicht im Logbuch und werden
 * ueber den FAB manuell erfasst ([showStayDialog]); sie fließen als
 * zusätzliche Ereignisse in die Rechnung ein ([CountryTimeStats]). Gespeichert
 * werden sie in den Einstellungen ([Settings.getManualBorderCrossings]), der
 * JSON-Export nimmt sie pauschal mit.
 */
class TimeCountriesFragment : LogbookFragment(), BackPressAware {

    private var _binding: FragmentTimeCountriesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TimeCountriesAdapter
    private var loadGeneration = 0

    /** Suchfeld offen oder zu; steuert Lupe und Titelfeld. */
    private var searchActive = false

    /** Alle Länder für Namen und Flaggen, im Hintergrund geladen. */
    private var countries: List<CountryShapes.Country> = emptyList()

    /** Manuell erfasste Grenzübertritte, wie zuletzt gerendert. */
    private var manual: List<BorderCrossing> = emptyList()

    /** Datumsangaben aller Flüge im Logbuch - markiert die Flugtage im Kalender. */
    private var flightDates: Set<LocalDate> = emptySet()

    /** Sortierung der Länderliste: [SORT_DURATION] (Standard) oder [SORT_VISITS]. */
    private var sortKey: String = SORT_DURATION

    /** Zuletzt gerendertes Ergebnis samt Homebase, fuer die Umsortierung ohne Neuladen. */
    private var lastResult: List<CountryTime> = emptyList()
    private var lastHomeIata: String? = null
    private var lastHomeIso: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimeCountriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Die gewählte Sortierung überlebt eine Neuerstellung der Ansicht.
        savedInstanceState?.getString(KEY_SORT)?.let { key ->
            if (key == SORT_DURATION || key == SORT_VISITS) sortKey = key
        }
        // Die letzte Zeile bleibt über der schwebenden Navigationsleiste sichtbar.
        binding.rvTimeCountries.addBottomNavPadding()
        binding.rvTimeCountries.layoutManager = LinearLayoutManager(requireContext())
        adapter = TimeCountriesAdapter(
            requireContext(),
            onCountryClick = ::openCountry
        )
        binding.rvTimeCountries.adapter = adapter
        // Lupe rechts im Kopf: blendet ein Suchfeld über den Titel ein.
        binding.btnTimeCountriesSearch.setOnClickListener { toggleSearch(!searchActive) }
        binding.etTimeCountriesSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.setQuery(s?.toString().orEmpty())
            }
        })
        binding.etTimeCountriesSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        // Sortierung unter der Lupe: Dauer (Standard) oder Aufenthalte.
        renderSortLabel()
        binding.tvTimeSort.setOnClickListener { anchor ->
            FilterPopupMenu.showOptions(
                requireContext(),
                anchor,
                listOf(SORT_DURATION, SORT_VISITS),
                listOf(
                    getString(R.string.layover_sort_duration),
                    getString(R.string.time_countries_sort_visits)
                ),
                sortKey
            ) { key ->
                if (key != sortKey) {
                    sortKey = key
                    renderSortLabel()
                    // Ohne Neuladen umsortieren, das Ergebnis ist noch da.
                    if (lastResult.isNotEmpty()) {
                        render(lastResult, lastHomeIata, lastHomeIso)
                    } else {
                        loadData()
                    }
                }
            }
        }
        // FAB wie auf dem Dashboard: manueller Aufenthalt (Land + Ein-/Ausreise).
        binding.fabAddStay.addBottomNavMargin()
        binding.fabAddStay.setOnClickListener { showStayDialog() }
        // Wie auf dem Dashboard blendet sich der Plus-Knopf aus, sobald die
        // Liste ganz nach unten scrollt.
        binding.rvTimeCountries.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateFabVisibility()
            }
        })
        binding.rvTimeCountries.post { updateFabVisibility() }
    }

    /**
     * Blendet den FAB aus, wenn die Liste ganz unten angekommen ist, und
     * wieder ein, sobald weiter nach oben oder unten gescrollt werden kann -
     * dieselbe Logik wie auf dem Dashboard ([FirstFragment.updateFabVisibility]).
     */
    private fun updateFabVisibility() {
        if (_binding == null) return
        val list = binding.rvTimeCountries
        val contentScrolls = list.canScrollVertically(-1) || list.canScrollVertically(1)
        val showFab = !contentScrolls || list.canScrollVertically(1)
        val fab = binding.fabAddStay
        val target = if (showFab) View.VISIBLE else View.GONE
        if (fab.visibility == target) return
        fab.animate().cancel()

        if (showFab) {
            fab.visibility = View.VISIBLE
            fab.alpha = 0f
            fab.scaleX = 1.15f
            fab.scaleY = 1.15f
            fab.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start()
        } else {
            fab.animate()
                .scaleX(1.15f)
                .scaleY(1.15f)
                .alpha(0.85f)
                .setDuration(130)
                .withEndAction {
                    fab.animate()
                        .scaleX(0f)
                        .scaleY(0f)
                        .alpha(0f)
                        .setDuration(120)
                        .withEndAction { fab.visibility = View.GONE }
                        .start()
                }
                .start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SORT, sortKey)
    }

    /** Beschriftung des Sortier-Werts rechts unter der Lupe. */
    private fun renderSortLabel() {
        binding.tvTimeSort.text = getString(
            if (sortKey == SORT_VISITS) {
                R.string.time_countries_sort_visits
            } else {
                R.string.layover_sort_duration
            }
        )
        // Sortier-Wert in der Akzentfarbe des Schemas.
        binding.tvTimeSort.setTextColor(AirlineSchemes.accentColor(requireContext()))
    }

    override fun onResume() {
        super.onResume()
        // Beim Zurückkehren nach einem bearbeiteten Eintrag neu rechnen.
        loadData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Lädt Logbuch, Länder und manuelle Übertritte im Hintergrund und wertet
     * die Aufenthalte aus. Ein Generationszähler verwirft veraltete Ergebnisse.
     */
    private fun loadData() {
        if (_binding == null) return
        val generation = ++loadGeneration
        binding.timeCountriesProgress.visibility = View.VISIBLE
        val appContext = requireContext().applicationContext
        Thread {
            val entries = LogbookRepository.getEntries()
            val shapes = CountryShapes.load(appContext).orEmpty()
            val crossings = Settings.getManualBorderCrossings(appContext)
            // Homebase.current wurde von getEntries() gerade neu berechnet.
            val homeIata = Homebase.current
            val homeIso = homeIata?.let { AirportData.country(appContext, it) }?.trim()?.uppercase()
            val result = CountryTimeStats.evaluate(
                entries,
                countryOf = { AirportData.country(appContext, it) },
                homeIso2 = homeIso,
                manual = crossings,
                today = LocalDate.now()
            )
            val dates = entries.filter { DashboardStats.isFlying(it) }
                .map { it.date }.toSet()
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                binding.timeCountriesProgress.visibility = View.GONE
                countries = shapes
                manual = crossings
                flightDates = dates
                render(result, homeIata, homeIso)
            }
        }.start()
    }

    private fun render(result: List<CountryTime>, homeIata: String?, homeIso: String?) {
        lastResult = result
        lastHomeIata = homeIata
        lastHomeIso = homeIso
        // Sortierung: Standard nach Aufenthaltsdauer (Stunden, dann Tage),
        // bei "Aufenthalte" nach der Zahl der Aufenthalte.
        val sorted = if (sortKey == SORT_VISITS) {
            result.sortedWith(
                compareByDescending<CountryTime> { it.visits }
                    .thenByDescending { it.totalHours }
                    .thenBy { it.iso2 }
            )
        } else {
            result.sortedWith(
                compareByDescending<CountryTime> { it.totalHours }
                    .thenByDescending { it.totalDays }
                    .thenBy { it.iso2 }
            )
        }
        val de = useGermanNames()
        val nameOf: (String) -> String = { iso ->
            countries.firstOrNull { it.iso2.equals(iso, ignoreCase = true) }
                ?.let { if (de) it.nameDe else it.nameEn }
                ?: iso
        }

        // Balken im Header wie auf der Layover-Detailseite: Zeit im Ausland
        // als Dauer (Tage/Wochen/Monate, ohne Quartale) über einer sich
        // füllenden Leiste. Grundlage sind die Stunden (Layover-Stunden
        // eingeschlossen), nicht nur die vollen Tage.
        val ctx = requireContext()
        val abroadHours = result.sumOf { it.totalHours }.toInt()
        binding.tvAbroadValue.text = TimeCountryFormat.breakdownText(ctx, abroadHours, quarters = false)
        binding.timeAbroadBar.setLayoverHours(
            abroadHours, TimeCountryFormat.stepLabel(ctx, abroadHours, quarters = false)
        )

        val items = ArrayList<TimeCountryItem>()
        sorted.forEachIndexed { i, time ->
            items += TimeCountryItem.Country(
                rank = i + 1,
                name = nameOf(time.iso2),
                flag = AirportData.flagEmoji(time.iso2),
                time = time
            )
        }
        if (result.isEmpty()) items += TimeCountryItem.Empty
        // Homebase-Hinweis als Fusszeile ganz unten auf der Seite.
        if (homeIata != null) {
            items += TimeCountryItem.HomebaseNote(
                getString(
                    R.string.time_countries_homebase,
                    homeIata,
                    homeIso?.let(nameOf) ?: homeIso.orEmpty()
                )
            )
        }
        adapter.submit(items)
        // Die Liste kann durch die neuen Zeilen scrollbar geworden sein.
        binding.rvTimeCountries.post { updateFabVisibility() }
    }

    /**
     * Blendet das Suchfeld ein oder aus. Beim Öffnen gleitet es von rechts
     * über den Titel nach links, beim Schließen in die Gegenrichtung; die
     * Lupe wechselt zum Schliessen-X. Geöffnet löscht ein Tipp auf das X die
     * Eingabe und schließt das Feld.
     */
    private fun toggleSearch(open: Boolean) {
        if (open == searchActive) return
        searchActive = open
        val frame = binding.timeCountriesSearchContainer
        val distance = frame.width.toFloat().coerceAtLeast(1f)
        val search = binding.etTimeCountriesSearch
        val title = binding.tvTimeCountriesTitle
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
            binding.btnTimeCountriesSearch.setIconResource(R.drawable.ic_close)
            binding.btnTimeCountriesSearch.contentDescription =
                getString(R.string.search_clear_desc)
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
            binding.btnTimeCountriesSearch.setIconResource(R.drawable.ic_search)
            binding.btnTimeCountriesSearch.contentDescription =
                getString(R.string.time_countries_search_open)
            adapter.setQuery("")
        }
    }

    private fun hideKeyboard() {
        ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.etTimeCountriesSearch.windowToken, 0)
    }

    /**
     * Die Zurück-Taste räumt zuerst das Suchfeld ab (wie das Schliessen-X) und
     * navigiert erst beim zweiten Druck zurück.
     */
    override fun onBackPressed(): Boolean {
        if (!searchActive) return false
        toggleSearch(false)
        return true
    }

    /** Öffnet die Detailseite eines Landes (Klick auf die Zeile). */
    private fun openCountry(iso2: String) {
        findNavController().navigate(
            R.id.action_time_countries_to_country_time_detail,
            Bundle().apply { putString("iso2", iso2) }
        )
    }

    /**
     * Popup für einen kompletten manuellen Aufenthalt (FAB unten rechts): Land,
     * Einreisedatum mit Transportmittel, Ausreisedatum mit Transportmittel.
     * Auch wenn Hin- und Rückweg nicht per Flugzeug waren. Wird als zwei
     * manuelle Grenzübertritte gespeichert und als Aufenthalt gezählt.
     */
    private fun showStayDialog() {
        val ctx = requireContext()
        val de = useGermanNames()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_manual_stay, null)
        // Kacheln im Dialog: Sekundär-Container durch Primärfarbe ersetzen.
        DashboardTileColors.replaceSecondaryCards(view)
        val act = view.findViewById<MaterialAutoCompleteTextView>(R.id.act_stay_country)
        val actIn = view.findViewById<MaterialAutoCompleteTextView>(R.id.act_transport_in)
        val actOut = view.findViewById<MaterialAutoCompleteTextView>(R.id.act_transport_out)
        val tvIn = view.findViewById<TextView>(R.id.tv_stay_in_date)
        val tvOut = view.findViewById<TextView>(R.id.tv_stay_out_date)

        val names = countries.map { if (de) it.nameDe else it.nameEn }
        act.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, names))

        // Transport-Dropdowns mit Icon je Option.
        val keys = TimeCountryFormat.TRANSPORT_KEYS
        val labels = keys.map { getString(TimeCountryFormat.transportLabelRes(it)) }
        fun transportAdapter(): ArrayAdapter<String> =
            object : ArrayAdapter<String>(ctx, android.R.layout.simple_dropdown_item_1line, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = super.getView(position, convertView, parent) as TextView
                    tv.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        TimeCountryFormat.transportIconRes(keys[position]), 0, 0, 0
                    )
                    tv.compoundDrawablePadding =
                        (8 * resources.displayMetrics.density).toInt()
                    return tv
                }

                override fun getDropDownView(
                    position: Int, convertView: View?, parent: ViewGroup
                ): View = getView(position, convertView, parent)
            }
        actIn.setAdapter(transportAdapter())
        actOut.setAdapter(transportAdapter())
        actIn.setText(labels[0], false)
        actOut.setText(labels[0], false)

        var inDate = LocalDate.now()
        var outDate = inDate.plusDays(14)
        tvIn.text = TimeCountryFormat.dateText(ctx, inDate)
        tvOut.text = TimeCountryFormat.dateText(ctx, outDate)
        tvIn.setOnClickListener {
            // Kalender mit markierten Flugtagen zur Orientierung.
            FlightDatePickerDialog.show(ctx, inDate, flightDates) { d ->
                inDate = d
                tvIn.text = TimeCountryFormat.dateText(ctx, inDate)
                if (outDate < inDate) {
                    outDate = inDate.plusDays(14)
                    tvOut.text = TimeCountryFormat.dateText(ctx, outDate)
                }
            }
        }
        tvOut.setOnClickListener {
            FlightDatePickerDialog.show(ctx, outDate, flightDates) { d ->
                outDate = d
                tvOut.text = TimeCountryFormat.dateText(ctx, outDate)
            }
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.stay_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.role_switch_cancel, null)
            .setPositiveButton(R.string.users_add_action, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = act.text.toString().trim()
            val iso = countries.firstOrNull {
                (if (de) it.nameDe else it.nameEn).equals(name, ignoreCase = true)
            }?.iso2
            if (iso == null) {
                Toast.makeText(ctx, R.string.time_countries_pick_country, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (outDate < inDate) {
                Toast.makeText(ctx, R.string.stay_dates_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val tIn = keys.getOrNull(labels.indexOf(actIn.text.toString()))
            val tOut = keys.getOrNull(labels.indexOf(actOut.text.toString()))
            val updated = manual +
                BorderCrossing(iso, inDate, enter = true, transport = tIn) +
                BorderCrossing(iso, outDate, enter = false, transport = tOut)
            Settings.setManualBorderCrossings(ctx, updated)
            dialog.dismiss()
            loadData()
        }
    }

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(): Boolean {
        val language = resources.configuration.locales[0].language
        return language.isEmpty() || language == Settings.LANG_DE
    }

    private companion object {
        /** Sortierung nach Gesamtaufenthaltsdauer (Standard). */
        const val SORT_DURATION = "duration"

        /** Sortierung nach der Zahl der Aufenthalte. */
        const val SORT_VISITS = "visits"

        const val KEY_SORT = "time_countries_sort"
    }
}
