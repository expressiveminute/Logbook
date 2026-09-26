package com.highfly.logbook

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.graphics.Color
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.highfly.logbook.databinding.FragmentEntriesBinding
import java.time.format.DateTimeFormatter

class EntriesFragment : Fragment(), ImeVisibilityAware {

    private var _binding: FragmentEntriesBinding? = null

    private val binding get() = _binding!!

    private data class EntryFilter(
        val travelType: String = "",
        val airline: String = "",
        val airport: String = "",
        val aircraftType: String = "",
        val registration: String = "",
        val classType: String = "",
    ) {
        fun isActive(): Boolean = listOf(
            travelType, airline, airport, aircraftType, registration, classType
        ).any { it.isNotBlank() }
    }

    private lateinit var adapter: EntryListAdapter

    private val allEntries = mutableListOf<LogbookEntry>()
    private var activeFilters = EntryFilter()
    private var dataLoaded = false
    private var loadGeneration = 0
    private var scrollToEntryId: Long? = null

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEntriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.entriesList.layoutManager = LinearLayoutManager(requireContext())
        adapter = EntryListAdapter(requireContext(), ::openEdit, ::confirmDelete)
        binding.entriesList.adapter = adapter
        binding.entriesList.itemAnimator?.changeDuration = 0
        setupSearch()
        consumePresetAirport()
        updateFilterIcon()
        binding.tileEntryFilter.setOnClickListener { showFilterDialog() }
        setupBackArrow()
    }

    /**
     * Oeffnet sich die Tastatur, schrumpft der Bereich der Liste und der
     * letzte Eintrag wird mittig abgeschnitten. Das sieht wie ein leeres
     * Eingabefeld aus, also schieben wir die Liste so weit, dass die
     * Unterkante genau an einer Kartengrenze liegt. doOnPreDraw statt post(),
     * weil die Liste erst im nachfolgenden Layout ihre neue Hoehe bekommt.
     */
    override fun onImeVisibilityChanged(visible: Boolean) {
        if (!visible) return
        binding.entriesList.doOnPreDraw { alignListBottomToItemGap() }
    }

    private fun alignListBottomToItemGap() {
        if (_binding == null) return
        val list = binding.entriesList
        val layoutManager = list.layoutManager as? LinearLayoutManager ?: return
        if (adapter.itemCount == 0) return
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (lastVisible == RecyclerView.NO_POSITION) return
        val lastView = layoutManager.findViewByPosition(lastVisible) ?: return
        if (lastView.bottom <= list.height - list.paddingBottom) return
        val target = if (lastVisible < adapter.itemCount - 1) lastVisible + 1 else lastVisible
        layoutManager.scrollToPositionWithOffset(target, list.height)
    }

    /**
     * Shows a back arrow when this fragment was opened from the world map,
     * letting the user return directly to the map.
     */
    private fun setupBackArrow() {
        val previousId = findNavController().previousBackStackEntry?.destination?.id
        if (previousId == R.id.nav_world_map) {
            binding.btnBackEntries.visibility = View.VISIBLE
            binding.btnBackEntries.setOnClickListener { findNavController().navigateUp() }
        } else {
            binding.btnBackEntries.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        consumePresetAirport()
        loadEntries()
    }

    /**
     * Loads the entry list in the background so the fragment stays responsive
     * during the (first) database read. A generation counter discards stale
     * results when this fragment is left and re-entered quickly.
     */
    private fun loadEntries() {
        val generation = ++loadGeneration
        binding.entriesProgress.visibility = View.VISIBLE

        val appContext = requireContext().applicationContext
        Thread {
            val entries = LogbookRepository.getEntries()
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                binding.entriesProgress.visibility = View.GONE
                allEntries.clear()
                allEntries += entries
                dataLoaded = true
                renderEntries()
            }
        }.start()
    }

    /**
     * Applies an optional airport preset passed by the world map (tap on an
     * airport -> "show all entries for this airport"). The airport is entered
     * into the search field, which already matches departures and arrivals.
     */
    private fun consumePresetAirport() {
        val airport = arguments?.getString("airportFilter").orEmpty().trim().uppercase()
        if (airport.isBlank()) return
        if (binding.etEntrySearch.text?.toString()?.trim()?.uppercase() != airport) {
            binding.etEntrySearch.setText(airport)
        }
        arguments?.remove("airportFilter")
        renderEntries()
    }

    private fun setupSearch() {
        binding.etEntrySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                searchRunnable?.let(searchHandler::removeCallbacks)
                val runnable = Runnable { renderEntries() }
                searchRunnable = runnable
                searchHandler.postDelayed(runnable, 250)
            }
        })
    }

    private fun renderEntries() {
        val query = binding.etEntrySearch.text?.toString()?.trim().orEmpty().lowercase()
        val visible = allEntries.filter { entry ->
            val matchesQuery = query.isEmpty() || searchableText(entry).contains(query)
            val matchesFilters = filtersMatch(entry)
            matchesQuery && matchesFilters
        }
        adapter.schemeColors =
            ClassColorSchemes.textColorsFor(Settings.getClassScheme(requireContext()))
        adapter.submit(visible)
        binding.entriesEmpty.setText(
            if (allEntries.isEmpty()) R.string.entries_empty else R.string.entries_no_results
        )
        binding.entriesEmpty.visibility =
            if (dataLoaded && visible.isEmpty()) View.VISIBLE else View.GONE

        val pendingScroll = scrollToEntryId
        if (pendingScroll != null) {
            scrollToEntryId = null
            val target = visible.indexOfFirst { it.id == pendingScroll }
            if (target >= 0) {
                binding.entriesList.post {
                    val layoutManager =
                        binding.entriesList.layoutManager as? LinearLayoutManager
                            ?: return@post
                    layoutManager.scrollToPosition(target)
                    binding.entriesList.post {
                        val item = layoutManager.findViewByPosition(target) ?: return@post
                        val listCenter = binding.entriesList.height / 2f
                        val itemCenter = item.top + item.height / 2f
                        binding.entriesList.scrollBy(0, (itemCenter - listCenter).toInt())
                    }
                }
            }
        }
    }

    private fun searchableText(entry: LogbookEntry): String =
        listOf(
            entry.airline,
            entry.flightNumber,
            entry.fromAirport,
            entry.toAirport,
            entry.aircraftType,
            entry.registration,
            entry.comment,
            entry.flightType,
            entry.classType,
            entry.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
        ).filterNotNull().joinToString(" ").lowercase()

    private fun filtersMatch(entry: LogbookEntry): Boolean {
        val travelFilter = activeFilters.travelType
        if (travelFilter.isNotBlank()) {
            val target = ChartData.normalizeFlightType(travelFilter)
            if (target != null && ChartData.normalizeFlightType(entry.flightType) != target) {
                return false
            }
        }
        val airlineFilter = activeFilters.airline
        if (airlineFilter.isNotBlank() && entry.airline?.uppercase() != airlineFilter.uppercase()) {
            return false
        }
        val airportFilter = activeFilters.airport
        if (airportFilter.isNotBlank()) {
            val target = airportFilter.uppercase()
            if (entry.fromAirport.uppercase() != target && entry.toAirport.uppercase() != target) {
                return false
            }
        }
        val aircraftFilter = activeFilters.aircraftType
        if (aircraftFilter.isNotBlank() &&
            entry.aircraftType?.uppercase() != aircraftFilter.uppercase()
        ) {
            return false
        }
        val registrationFilter = activeFilters.registration
        if (registrationFilter.isNotBlank() &&
            entry.registration?.uppercase() != registrationFilter.uppercase()
        ) {
            return false
        }
        val classFilter = activeFilters.classType
        if (classFilter.isNotBlank()) {
            val target = ChartData.normalizeClassType(classFilter)
            if (target != null && ChartData.normalizeClassType(entry.classType) != target) {
                return false
            }
        }
        return true
    }

    private fun showFilterDialog() {
        val sheetView = layoutInflater.inflate(R.layout.sheet_entry_filter, null)
        val filterTravelType =
            sheetView.findViewById<AutoCompleteTextView>(R.id.filter_travel_type)
        val filterAirline =
            sheetView.findViewById<AutoCompleteTextView>(R.id.filter_airline)
        val filterAirport =
            sheetView.findViewById<AutoCompleteTextView>(R.id.filter_airport)
        val filterAircraftType =
            sheetView.findViewById<AutoCompleteTextView>(R.id.filter_aircraft_type)
        val filterRegistration =
            sheetView.findViewById<AutoCompleteTextView>(R.id.filter_registration)
        val filterClassType =
            sheetView.findViewById<AutoCompleteTextView>(R.id.filter_class_type)

        filterTravelType.setAdapter(labelAdapter())
        filterAirline.setAdapter(distinctValuesAdapter { it.airline?.uppercase() })
        filterAirport.setAdapter(airportAdapter())
        filterAircraftType.setAdapter(distinctValuesAdapter { it.aircraftType })
        filterRegistration.setAdapter(distinctValuesAdapter { it.registration })
        filterClassType.setAdapter(classAdapter())

        listOf(
            filterTravelType, filterAirline, filterAirport,
            filterAircraftType, filterRegistration, filterClassType
        ).forEach { highlightFilterValue(it) }

        filterTravelType.setText(activeFilters.travelType, false)
        filterAirline.setText(activeFilters.airline, false)
        filterAirport.setText(activeFilters.airport, false)
        filterAircraftType.setText(activeFilters.aircraftType, false)
        filterRegistration.setText(activeFilters.registration, false)
        filterClassType.setText(activeFilters.classType, false)

        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(sheetView)

        sheetView.findViewById<View>(R.id.btn_filter_clear).apply {
            visibility = if (activeFilters.isActive()) View.VISIBLE else View.GONE
            setOnClickListener {
                activeFilters = EntryFilter()
                updateFilterIcon()
                renderEntries()
                dialog.dismiss()
            }
        }
        sheetView.findViewById<View>(R.id.btn_filter_apply).setOnClickListener {
            activeFilters = EntryFilter(
                travelType = filterTravelType.text?.toString()?.trim().orEmpty(),
                airline = filterAirline.text?.toString()?.trim().orEmpty(),
                airport = filterAirport.text?.toString()?.trim().orEmpty(),
                aircraftType = filterAircraftType.text?.toString()?.trim().orEmpty(),
                registration = filterRegistration.text?.toString()?.trim().orEmpty(),
                classType = filterClassType.text?.toString()?.trim().orEmpty(),
            )
            updateFilterIcon()
            renderEntries()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun labelAdapter(): ArrayAdapter<String> =
        ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            listOf(
                getString(R.string.flight_type_private),
                getString(R.string.flight_type_on_duty),
                getString(R.string.flight_type_deadhead),
                getString(R.string.flight_type_ferry),
                getString(R.string.flight_type_ground_transfer),
                getString(R.string.flight_type_duty_travel),
            )
        )

    private fun classAdapter(): ArrayAdapter<String> =
        ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            listOf(
                getString(R.string.class_economy),
                getString(R.string.class_premium_economy),
                getString(R.string.class_business),
                getString(R.string.class_first),
                getString(R.string.class_jump),
            )
        )

    private fun distinctValuesAdapter(
        selector: (LogbookEntry) -> String?
    ): ArrayAdapter<String> {
        val values = allEntries
            .mapNotNull(selector)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        return ArrayAdapter(
            requireContext(), android.R.layout.simple_dropdown_item_1line, values
        )
    }

    private fun airportAdapter(): ArrayAdapter<String> {
        val values = allEntries
            .flatMap { listOf(it.fromAirport, it.toAirport) }
            .filter { it.isNotBlank() }
            .map { it.uppercase() }
            .distinct()
            .sorted()
        return ArrayAdapter(
            requireContext(), android.R.layout.simple_dropdown_item_1line, values
        )
    }

    /**
     * Paints the text of a filter field in the accent colour while it holds a
     * value the user entered, so active criteria stand out from empty fields.
     */
    private fun highlightFilterValue(field: AutoCompleteTextView) {
        val accent = MaterialColors.getColor(
            field, com.google.android.material.R.attr.colorPrimary
        )
        val normal = field.currentTextColor
        fun refresh() {
            val active = field.text?.toString()?.trim().orEmpty().isNotBlank()
            field.setTextColor(if (active) accent else normal)
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()
    }

    private fun updateFilterIcon() {
        val accent = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorPrimary
        )
        val surfaceContainerLow = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorSurfaceContainerLow
        )
        val onSurfaceVariant = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        val outlineVariant = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOutlineVariant
        )

        val active = activeFilters.isActive()
        binding.tileEntryFilter.setCardBackgroundColor(if (active) accent else surfaceContainerLow)
        binding.tileEntryFilter.strokeColor = if (active) accent else outlineVariant
        binding.ivFilterIcon.setColorFilter(if (active) Color.WHITE else onSurfaceVariant)
    }

    private fun openEdit(entry: LogbookEntry) {
        adapter.dismissOpenRow()
        scrollToEntryId = entry.id
        val args = Bundle().apply { putLong("entryId", entry.id ?: -1L) }
        findNavController().navigate(R.id.action_entries_to_add_entry, args)
    }

    private fun confirmDelete(entry: LogbookEntry) {
        adapter.dismissOpenRow()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_entry_title)
            .setMessage(R.string.delete_entry_message)
            .setPositiveButton(R.string.delete_entry_confirm) { _, _ ->
                deleteWithUndo(entry)
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    private fun deleteWithUndo(entry: LogbookEntry) {
        val id = entry.id ?: return
        LogbookRepository.deleteEntry(id)
        allEntries.removeAll { it.id == id }
        renderEntries()
        Snackbar.make(binding.root, R.string.entry_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                val restored = entry.copy(id = null)
                LogbookRepository.addEntry(restored)
                allEntries += restored
                allEntries.sortByDescending { it.date }
                renderEntries()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchHandler.removeCallbacksAndMessages(null)
        searchRunnable = null
        loadGeneration++
        _binding = null
    }
}