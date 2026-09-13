package com.highfly.logbook

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.DialogInterface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentEntriesBinding
import com.highfly.logbook.databinding.ItemEntryBinding
import java.time.format.DateTimeFormatter

class EntriesFragment : Fragment() {

    private var _binding: FragmentEntriesBinding? = null

    private val binding get() = _binding!!

    private data class EntryFilter(
        val airline: String = "",
        val aircraftType: String = "",
        val departure: String = "",
        val arrival: String = "",
        val classType: String = "",
        val registration: String = "",
        val year: String = "",
    ) {
        fun isActive(): Boolean = listOf(
            airline, aircraftType, departure, arrival, classType, registration, year
        ).any { it.isNotBlank() }
    }

    private val allEntries = mutableListOf<LogbookEntry>()
    private val rows = mutableListOf<Row>()
    private var selectedIndex: Int? = null
    private var activeFilters = EntryFilter()

    private val touchSlop by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }
    private val longPressDelay = 2000L
    private val handler = Handler(Looper.getMainLooper())
    private var pendingLongPress: Runnable? = null

    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressFired = false
    private var dragStartTranslation = 0f

    private inner class Row(val binding: ItemEntryBinding, val entry: LogbookEntry)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentEntriesBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSearch()
        updateFilterIcon()
        binding.tileEntryFilter.setOnClickListener { showFilterDialog() }
    }

    override fun onResume() {
        super.onResume()
        renderEntries()
    }

    private fun setupSearch() {
        binding.etEntrySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                renderEntries()
            }
        })
    }

    private fun renderEntries() {
        allEntries.clear()
        rows.clear()
        selectedIndex = null
        binding.entriesContainer.removeAllViews()

        allEntries += LogbookRepository.getEntries()
        if (allEntries.isEmpty()) {
            binding.entriesContainer.addView(emptyText(R.string.entries_empty))
            return
        }

        val query = binding.etEntrySearch.text?.toString()?.trim().orEmpty().lowercase()
        val visible = allEntries.filter { entry ->
            val matchesQuery = query.isEmpty() || searchableText(entry).contains(query)
            val matchesFilters = filtersMatch(entry)
            matchesQuery && matchesFilters
        }
        if (visible.isEmpty()) {
            binding.entriesContainer.addView(emptyText(R.string.entries_no_results))
            return
        }

        val schemeColors = ClassColorSchemes.colorsFor(Settings.getClassScheme(requireContext()))

        visible.forEach { entry ->
            val item = ItemEntryBinding.inflate(
                layoutInflater, binding.entriesContainer, false
            )
            val row = Row(item, entry)
            rows += row
            populate(row, schemeColors)
            binding.entriesContainer.addView(item.root)
            bindInteractions(row)
        }
    }

    private fun emptyText(stringRes: Int): TextView =
        TextView(requireContext()).apply {
            text = getString(stringRes)
            textSize = 16f
            setPadding(16, 24, 16, 24)
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

    private fun filtersMatch(entry: LogbookEntry): Boolean =
        matches(activeFilters.airline, entry.airline) &&
            matches(activeFilters.aircraftType, entry.aircraftType) &&
            matches(activeFilters.departure, entry.fromAirport) &&
            matches(activeFilters.arrival, entry.toAirport) &&
            matches(activeFilters.classType, entry.classType) &&
            matches(activeFilters.registration, entry.registration) &&
            matches(activeFilters.year, entry.date.year.toString())

    private fun matches(filter: String, value: String?): Boolean {
        if (filter.isBlank()) return true
        return value?.lowercase()?.contains(filter.lowercase()) == true
    }

    private fun showFilterDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_filter, null)
        val filterAirline = dialogView.findViewById<AutoCompleteTextView>(R.id.filter_airline)
        val filterAircraftType = dialogView.findViewById<AutoCompleteTextView>(R.id.filter_aircraft_type)
        val filterDeparture = dialogView.findViewById<AutoCompleteTextView>(R.id.filter_departure)
        val filterArrival = dialogView.findViewById<AutoCompleteTextView>(R.id.filter_arrival)
        val filterClass = dialogView.findViewById<AutoCompleteTextView>(R.id.filter_class)
        val filterRegistration = dialogView.findViewById<AutoCompleteTextView>(R.id.filter_registration)
        val filterYear = dialogView.findViewById<AutoCompleteTextView>(R.id.filter_year)

        filterAirline.setAdapter(suggestionsAdapter { it.airline })
        filterAircraftType.setAdapter(suggestionsAdapter { it.aircraftType })
        filterDeparture.setAdapter(suggestionsAdapter { it.fromAirport })
        filterArrival.setAdapter(suggestionsAdapter { it.toAirport })
        filterClass.setAdapter(suggestionsAdapter { it.classType })
        filterRegistration.setAdapter(suggestionsAdapter { it.registration })
        filterYear.setAdapter(yearSuggestionsAdapter())

        filterAirline.setText(activeFilters.airline)
        filterAircraftType.setText(activeFilters.aircraftType)
        filterDeparture.setText(activeFilters.departure)
        filterArrival.setText(activeFilters.arrival)
        filterClass.setText(activeFilters.classType)
        filterRegistration.setText(activeFilters.registration)
        filterYear.setText(activeFilters.year)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.filter_title)
            .setView(dialogView)
            .setPositiveButton(R.string.filter_apply) { _, _ ->
                activeFilters = EntryFilter(
                    airline = filterAirline.text?.toString()?.trim().orEmpty(),
                    aircraftType = filterAircraftType.text?.toString()?.trim().orEmpty(),
                    departure = filterDeparture.text?.toString()?.trim().orEmpty(),
                    arrival = filterArrival.text?.toString()?.trim().orEmpty(),
                    classType = filterClass.text?.toString()?.trim().orEmpty(),
                    registration = filterRegistration.text?.toString()?.trim().orEmpty(),
                    year = filterYear.text?.toString()?.trim().orEmpty(),
                )
                updateFilterIcon()
                renderEntries()
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .setNeutralButton(R.string.filter_clear) { dialog, _ ->
                activeFilters = EntryFilter()
                updateFilterIcon()
                renderEntries()
                dialog.dismiss()
            }
            .create()
            .apply {
                setOnShowListener {
                    getButton(DialogInterface.BUTTON_POSITIVE)
                        .setTextColor(Color.parseColor("#4CAF50"))
                    val neutral = getButton(DialogInterface.BUTTON_NEUTRAL)
                    if (activeFilters.isActive()) {
                        neutral.setTextColor(Color.parseColor("#D32F2F"))
                    } else {
                        neutral.visibility = View.GONE
                    }
                }
                show()
            }
    }

    private fun suggestionsAdapter(value: (LogbookEntry) -> String?): ArrayAdapter<String> {
        val values = LogbookRepository.getEntries()
            .mapNotNull(value)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        return ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, values)
    }

    private fun yearSuggestionsAdapter(): ArrayAdapter<String> {
        val years = LogbookRepository.getEntries()
            .map { it.date.year }
            .distinct()
            .sortedDescending()
            .map { it.toString() }
        return ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, years)
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

    private fun populate(row: Row, schemeColors: List<Int>) {
        val item = row.binding
        val entry = row.entry

        val flightNo = listOfNotNull(
            entry.airline?.takeIf { it.isNotBlank() },
            entry.flightNumber?.takeIf { it.isNotBlank() },
        ).joinToString(" ")
        item.itemFlightNo.text = flightNo

        val airlineCode = entry.airline?.takeIf { it.isNotBlank() }
        val livery = airlineCode?.let { AirlineCatalog.loadLogo(requireContext(), it) }
        if (livery != null) {
            item.ivAirlineLivery.setImageBitmap(livery)
            item.ivAirlineLivery.visibility = View.VISIBLE
        }

        item.itemDate.text = entry.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

        item.itemRoute.text = "${entry.fromAirport} → ${entry.toAirport}"

        val meta = listOfNotNull(
            entry.aircraftType?.takeIf { it.isNotBlank() },
            entry.registration?.takeIf { it.isNotBlank() },
        )
        item.itemTypeMeta.text = meta.joinToString(" · ")

        item.itemFlightType.text = entry.flightType.orEmpty()

        item.itemClass.text = entry.classType.orEmpty()
        val classIndex = classColorIndex(entry.classType)
        if (classIndex != null && classIndex < schemeColors.size) {
            item.itemClass.setTextColor(
                ContextCompat.getColor(requireContext(), schemeColors[classIndex])
            )
        }

        val comment = entry.comment?.takeIf { it.isNotBlank() }
        if (comment == null) {
            item.itemComment.visibility = View.GONE
        } else {
            item.itemComment.visibility = View.VISIBLE
            item.itemComment.text = comment
        }
    }

    private fun bindInteractions(row: Row) {
        val root = row.binding.itemEntryRoot
        val card = row.binding.itemCard

        root.doOnLayout {
            row.binding.itemEntryActions.updateLayoutParams<ViewGroup.LayoutParams> {
                height = it.height
            }
        }

        row.binding.btnEditEntry.setOnClickListener { openEdit(row) }
        row.binding.btnDeleteEntry.setOnClickListener { confirmDelete(row) }

        root.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    val index = rows.indexOf(row)
                    if (selectedIndex == index) {
                        dragStartTranslation = card.translationX
                        longPressFired = true
                    } else {
                        longPressFired = false
                        scheduleLongPress(row)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                        cancelLongPress()
                    }
                    val index = rows.indexOf(row)
                    if (selectedIndex == index && longPressFired &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy)
                    ) {
                        val actionsWidth = row.binding.itemEntryActions.width.toFloat()
                        card.translationX =
                            (dragStartTranslation + dx).coerceIn(-actionsWidth, 0f)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    val index = rows.indexOf(row)
                    if (longPressFired && selectedIndex == index) {
                        val actionsWidth = row.binding.itemEntryActions.width.toFloat()
                        val open = card.translationX < -actionsWidth / 2
                        card.animate()
                            .translationX(if (open) -actionsWidth else 0f)
                            .setDuration(200)
                            .start()
                    } else if (!moved) {
                        val current = selectedIndex
                        when {
                            current == null -> Unit
                            current == index -> {
                                if (card.translationX != 0f) {
                                    card.animate().translationX(0f).setDuration(200).start()
                                }
                            }
                            else -> deselect()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> cancelLongPress()
            }
            true
        }
    }

    private fun scheduleLongPress(row: Row) {
        val runnable = Runnable {
            pendingLongPress = null
            longPressFired = true
            select(rows.indexOf(row))
        }
        pendingLongPress = runnable
        handler.postDelayed(runnable, longPressDelay)
    }

    private fun cancelLongPress() {
        pendingLongPress?.let { handler.removeCallbacks(it) }
        pendingLongPress = null
    }

    private fun select(index: Int) {
        if (index < 0 || index >= rows.size) return
        selectedIndex = index
        rows.forEachIndexed { i, row ->
            val selected = i == selectedIndex
            row.binding.itemCard.alpha = if (selected) 1f else 0.4f
            row.binding.itemCard.elevation = if (selected) 8f else 0f
        }
    }

    private fun deselect() {
        selectedIndex = null
        rows.forEach { row ->
            row.binding.itemCard.animate().translationX(0f).setDuration(200).start()
            row.binding.itemCard.alpha = 1f
            row.binding.itemCard.elevation = 0f
        }
    }

    private fun openEdit(row: Row) {
        val args = Bundle().apply { putLong("entryId", row.entry.id ?: -1L) }
        findNavController().navigate(R.id.action_entries_to_add_entry, args)
    }

    private fun confirmDelete(row: Row) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_entry_title)
            .setMessage(R.string.delete_entry_message)
            .setPositiveButton(R.string.delete_entry_confirm) { _, _ ->
                row.entry.id?.let { LogbookRepository.deleteEntry(it) }
                renderEntries()
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    private fun classColorIndex(classType: String?): Int? {
        val resIds = listOf(
            R.string.class_economy,
            R.string.class_premium_economy,
            R.string.class_business,
            R.string.class_first,
        )
        val text = classType ?: return null
        val index = resIds.indexOfFirst { getString(it) == text }
        return if (index == -1) null else index
    }

    override fun onDestroyView() {
        cancelLongPress()
        super.onDestroyView()
        _binding = null
    }
}