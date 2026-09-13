package com.highfly.logbook

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.DatePicker
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentAddEntryBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class AddEntryFragment : Fragment() {

    private var _binding: FragmentAddEntryBinding? = null

    private val binding get() = _binding!!

    private var selectedFlightTypeIndex: Int? = null
    private var selectedClassIndex: Int? = null
    private var prefilling = false

    private var editingEntryId: Long = -1L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentAddEntryBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editingEntryId = requireArguments().getLong("entryId", -1L)

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        setupFlightTypeTiles()
        setupClassTiles()
        setupAirlineAutoAdvance()
        setupAirportAutoAdvance()
        setupDateField()
        setupProgressiveReveal()
        setupOptionalSection()
        setupSaveAndDiscard()

        updateLayoverVisibility()

        val editingEntry = LogbookRepository.getEntry(editingEntryId)
        if (editingEntry != null) {
            binding.tvEntryTitle.text = getString(R.string.edit_entry_title)
            prefill(editingEntry)
        }
        refreshVisibility()
    }

    private fun setupFlightTypeTiles() {
        val tiles = listOf(
            binding.tileFlightPrivate,
            binding.tileFlightOnDuty,
            binding.tileFlightDeadhead,
            binding.tileFlightDutyTravel,
        )
        val labels = listOf(
            binding.labelFlightPrivate,
            binding.labelFlightOnDuty,
            binding.labelFlightDeadhead,
            binding.labelFlightDutyTravel,
        )

        configureHighlight(binding.flightGroupFrame, binding.flightHighlight, tiles.size)

        tiles.forEachIndexed { index, tile ->
            tile.setOnClickListener { selectFlightType(index) }
        }
        updateFlightLabels(labels, null)
    }

    private fun selectFlightType(index: Int) {
        selectedFlightTypeIndex = index
        val tiles = listOf(
            binding.tileFlightPrivate,
            binding.tileFlightOnDuty,
            binding.tileFlightDeadhead,
            binding.tileFlightDutyTravel,
        )
        val labels = listOf(
            binding.labelFlightPrivate,
            binding.labelFlightOnDuty,
            binding.labelFlightDeadhead,
            binding.labelFlightDutyTravel,
        )
        moveHighlight(
            binding.flightGroupFrame,
            binding.flightHighlight,
            tiles.size,
            index,
            MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorPrimaryContainer
            )
        )
        updateFlightLabels(labels, index)
        updateLayoverVisibility()
        refreshVisibility()
    }

    private fun updateLayoverVisibility() {
        val isCrew = Settings.getRole(requireContext()) == Settings.ROLE_CREW
        val isPrivate = selectedFlightTypeIndex == 0
        binding.cbLayover.visibility =
            if (isCrew && !isPrivate) View.VISIBLE else View.GONE
    }

    private fun selectClass(index: Int) {
        selectedClassIndex = index
        val tiles = listOf(
            binding.tileClassEconomy,
            binding.tileClassPremiumEconomy,
            binding.tileClassBusiness,
            binding.tileClassFirst,
        )
        val labels = listOf(
            binding.labelClassEconomy,
            binding.labelClassPremiumEconomy,
            binding.labelClassBusiness,
            binding.labelClassFirst,
        )
        val classColors = ClassColorSchemes.colorsFor(Settings.getClassScheme(requireContext()))
        moveHighlight(
            binding.classGroupFrame,
            binding.classHighlight,
            tiles.size,
            index,
            ContextCompat.getColor(requireContext(), classColors[index])
        )
        updateClassLabels(labels, index)
        refreshVisibility()
    }

    private fun setupClassTiles() {
        val tiles = listOf(
            binding.tileClassEconomy,
            binding.tileClassPremiumEconomy,
            binding.tileClassBusiness,
            binding.tileClassFirst,
        )
        val labels = listOf(
            binding.labelClassEconomy,
            binding.labelClassPremiumEconomy,
            binding.labelClassBusiness,
            binding.labelClassFirst,
        )

        configureHighlight(binding.classGroupFrame, binding.classHighlight, tiles.size)

        tiles.forEachIndexed { index, tile ->
            tile.setOnClickListener { selectClass(index) }
        }
        updateClassLabels(labels, null)
    }

    private fun prefill(entry: LogbookEntry) {
        prefilling = true
        val flightTypeLabels = listOf(
            R.string.flight_type_private,
            R.string.flight_type_on_duty,
            R.string.flight_type_deadhead,
            R.string.flight_type_duty_travel,
        )
        val classLabels = listOf(
            R.string.class_economy,
            R.string.class_premium_economy,
            R.string.class_business,
            R.string.class_first,
        )

        val flightTypeIndex = entry.flightType?.let { text ->
            flightTypeLabels.indexOfFirst { getString(it) == text }.takeIf { it != -1 }
        }
        if (flightTypeIndex != null) selectFlightType(flightTypeIndex)

        val classIndex = entry.classType?.let { text ->
            classLabels.indexOfFirst { getString(it) == text }.takeIf { it != -1 }
        }
        if (classIndex != null) selectClass(classIndex)

        binding.etAirline.setText(entry.airline)
        binding.etFlightNumber.setText(entry.flightNumber)
        binding.etDate.setText(entry.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))

        binding.airportSection.visibility = View.VISIBLE
        binding.etAirportFrom.setText(entry.fromAirport)
        binding.etAirportTo.setText(entry.toAirport)

        binding.etDistance.setText(entry.distanceKm?.toString().orEmpty())
        binding.etFlightTime.setText(entry.flightMinutes?.toString().orEmpty())
        binding.cbLayover.isChecked = entry.layover

        binding.etAircraftType.setText(entry.aircraftType)
        binding.etRegistration.setText(entry.registration)

        binding.etComment.setText(entry.comment)

        val hasOptional = listOf(
            entry.aircraftType,
            entry.registration,
            entry.comment
        ).any { !it.isNullOrBlank() }
        if (hasOptional) toggleOptionalBody(expanded = true)
        prefilling = false
    }

    private fun updateFlightLabels(labels: List<TextView>, selectedIndex: Int?) {
        val onPrimaryContainer = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer
        )
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        labels.forEachIndexed { index, label ->
            label.setTextColor(if (index == selectedIndex) onPrimaryContainer else onSurface)
        }
    }

    private fun updateClassLabels(labels: List<TextView>, selectedIndex: Int?) {
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        labels.forEachIndexed { index, label ->
            label.setTextColor(if (index == selectedIndex) Color.WHITE else onSurface)
        }
    }

    private fun configureHighlight(frame: FrameLayout, highlight: View, count: Int) {
        frame.doOnLayout {
            val cellWidth = it.width / count
            (highlight.layoutParams as FrameLayout.LayoutParams).width = cellWidth
        }
    }

    private fun moveHighlight(
        frame: FrameLayout,
        highlight: View,
        count: Int,
        index: Int,
        color: Int
    ) {
        frame.doOnLayout {
            val cellWidth = it.width / count
            (highlight.layoutParams as FrameLayout.LayoutParams).width = cellWidth

            highlight.background = roundedCellBackground(color)

            if (highlight.visibility != View.VISIBLE) {
                highlight.visibility = View.VISIBLE
                highlight.alpha = 0f
                highlight.animate().alpha(1f).setDuration(200).start()
            }

            highlight.animate()
                .translationX((cellWidth * index).toFloat())
                .setDuration(250)
                .start()
        }
    }

    private fun roundedCellBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(color)
        }

    private fun setupAirlineAutoAdvance() {
        binding.etAirline.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val upper = s.toString().uppercase()
                if (upper != s.toString()) {
                    s.replace(0, s.length, upper)
                    return
                }
                if (s.length == 2) {
                    focusAndShowKeyboard(binding.etFlightNumber)
                }
            }
        })
    }

    private fun setupAirportAutoAdvance() {
        binding.etAirportFrom.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val upper = s.toString().uppercase()
                if (upper != s.toString()) {
                    s.replace(0, s.length, upper)
                    return
                }
                if (s.length == 3) {
                    focusAndShowKeyboard(binding.etAirportTo)
                }
                autoFillRouteData()
            }
        })

        binding.etAirportTo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val upper = s.toString().uppercase()
                if (upper != s.toString()) {
                    s.replace(0, s.length, upper)
                }
                autoFillRouteData()
            }
        })
    }

    private fun autoFillRouteData() {
        if (prefilling) return
        val from = binding.etAirportFrom.text?.toString()?.trim().orEmpty()
        val to = binding.etAirportTo.text?.toString()?.trim().orEmpty()
        if (from.length != 3 || to.length != 3 || from == to) return
        val a = AirportData.location(requireContext(), from) ?: return
        val b = AirportData.location(requireContext(), to) ?: return
        val km = GeoMath.distanceKm(a, b)
        binding.etDistance.setText(km.roundToInt().toString())
        binding.etFlightTime.setText(GeoMath.flightMinutes(km).toString())
    }

    private fun setupProgressiveReveal() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                refreshVisibility()
            }
        }
        binding.etAirline.addTextChangedListener(watcher)
        binding.etFlightNumber.addTextChangedListener(watcher)
        binding.etDate.addTextChangedListener(watcher)
        binding.etAirportFrom.addTextChangedListener(watcher)
        binding.etAirportTo.addTextChangedListener(watcher)
        binding.etDistance.addTextChangedListener(watcher)
        binding.etFlightTime.addTextChangedListener(watcher)
    }

    private fun setupOptionalSection() {
        binding.tileOptionalHeader.setOnClickListener {
            hideKeyboard()
            toggleOptionalBody(expanded = null)
        }
    }

    private fun toggleOptionalBody(expanded: Boolean?) {
        val expand = expanded ?: (binding.optionalBody.visibility != View.VISIBLE)
        binding.optionalBody.visibility = if (expand) View.VISIBLE else View.GONE
        binding.ivOptionalArrow.animate()
            .rotation(if (expand) 180f else 0f)
            .setDuration(200)
            .start()
        if (expand && binding.btnSaveFlight.visibility == View.VISIBLE) {
            binding.optionalBody.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun refreshVisibility() {
        val hasType = selectedFlightTypeIndex != null
        val hasClass = selectedClassIndex != null
        val airline = binding.etAirline.text?.toString()?.trim().orEmpty()
        val flightNumber = binding.etFlightNumber.text?.toString()?.trim().orEmpty()
        val date = try {
            LocalDate.parse(
                binding.etDate.text?.toString()?.trim().orEmpty(),
                DATE_FORMAT
            )
            true
        } catch (e: Exception) {
            false
        }

        binding.classSection.visibility = if (hasType) View.VISIBLE else View.GONE
        binding.detailsSection.visibility = if (hasType && hasClass) View.VISIBLE else View.GONE

        val revealAirports = hasType && hasClass &&
            airline.isNotEmpty() && flightNumber.isNotEmpty() && date
        binding.airportSection.visibility = if (revealAirports) View.VISIBLE else View.GONE
        binding.optionalSection.visibility = if (revealAirports) View.VISIBLE else View.GONE

        val from = binding.etAirportFrom.text?.toString()?.trim().orEmpty()
        val to = binding.etAirportTo.text?.toString()?.trim().orEmpty()
        val distance = binding.etDistance.text?.toString()?.trim().orEmpty()
        val flightTime = binding.etFlightTime.text?.toString()?.trim().orEmpty()

        val allRequired = hasType && hasClass && airline.isNotEmpty() && flightNumber.isNotEmpty() &&
            date && from.isNotEmpty() && to.isNotEmpty() &&
            distance.isNotEmpty() && flightTime.isNotEmpty()

        val saveWasVisible = binding.btnSaveFlight.visibility == View.VISIBLE
        binding.btnSaveFlight.visibility = if (allRequired) View.VISIBLE else View.GONE
        binding.btnDiscardFlight.visibility = if (allRequired) View.VISIBLE else View.GONE
        if (allRequired && !saveWasVisible) {
            binding.btnSaveFlight.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }

    private fun setupSaveAndDiscard() {
        binding.btnSaveFlight.setOnClickListener { saveFlight() }
        binding.btnDiscardFlight.setOnClickListener { confirmDiscard() }
    }

    private fun selectedFlightType(): String? =
        selectedFlightTypeIndex?.let { index ->
            listOf(
                binding.labelFlightPrivate,
                binding.labelFlightOnDuty,
                binding.labelFlightDeadhead,
                binding.labelFlightDutyTravel,
            )[index].text.toString()
        }

    private fun selectedClassType(): String? =
        selectedClassIndex?.let { index ->
            listOf(
                binding.labelClassEconomy,
                binding.labelClassPremiumEconomy,
                binding.labelClassBusiness,
                binding.labelClassFirst,
            )[index].text.toString()
        }

    private fun saveFlight() {
        val missingLabels = mutableListOf<String>()

        if (selectedFlightTypeIndex == null) missingLabels += getString(R.string.flight_type_label)
        if (selectedClassIndex == null) missingLabels += getString(R.string.class_label)

        val airline = binding.etAirline.text?.toString()?.trim().orEmpty()
        val flightNumber = binding.etFlightNumber.text?.toString()?.trim().orEmpty()
        if (airline.isEmpty()) missingLabels += getString(R.string.airline_label)
        if (flightNumber.isEmpty()) missingLabels += getString(R.string.flight_number_label)

        val date = try {
            LocalDate.parse(
                binding.etDate.text?.toString()?.trim().orEmpty(),
                DateTimeFormatter.ofPattern("dd.MM.yyyy")
            )
        } catch (e: Exception) {
            null
        }
        if (date == null) missingLabels += getString(R.string.date_label)

        val from = binding.etAirportFrom.text?.toString()?.trim()?.uppercase().orEmpty()
        val to = binding.etAirportTo.text?.toString()?.trim()?.uppercase().orEmpty()
        if (from.isEmpty()) missingLabels += getString(R.string.airport_departure_label)
        if (to.isEmpty()) missingLabels += getString(R.string.airport_arrival_label)

        val distanceKm = binding.etDistance.text?.toString()?.trim()?.toIntOrNull()
        val flightMinutes = binding.etFlightTime.text?.toString()?.trim()?.toIntOrNull()

        if (missingLabels.isNotEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.save_flight_incomplete) + "\n" + missingLabels.joinToString(", "),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val entry = LogbookEntry(
            id = editingEntryId.takeIf { it >= 0 },
            date = date!!,
            flightType = selectedFlightType(),
            classType = selectedClassType(),
            fromAirport = from,
            toAirport = to,
            airline = airline.uppercase(),
            flightNumber = flightNumber,
            aircraftType = binding.etAircraftType.text?.toString()?.trim()?.ifEmpty { null },
            registration = binding.etRegistration.text?.toString()?.trim()?.uppercase()?.ifEmpty { null },
            distanceKm = distanceKm,
            flightMinutes = flightMinutes,
            layover = binding.cbLayover.isChecked,
            comment = binding.etComment.text?.toString()?.trim()?.ifEmpty { null }
        )
        if (editingEntryId >= 0) {
            LogbookRepository.updateEntry(entry)
        } else {
            LogbookRepository.addEntry(entry)
        }

        Toast.makeText(
            requireContext(),
            getString(R.string.flight_saved),
            Toast.LENGTH_SHORT
        ).show()
        findNavController().navigateUp()
    }

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.discard_confirm) { _, _ ->
                findNavController().navigateUp()
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    private fun setupDateField() {
        binding.etDate.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (isFormatting) return
                val formatted = formatDateMask(s.toString())
                if (formatted != s.toString()) {
                    isFormatting = true
                    s.replace(0, s.length, formatted)
                    isFormatting = false
                }
            }
        })

        binding.etDate.setOnClickListener { showDatePicker() }
        binding.etDate.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showDatePicker()
        }
    }

    private fun showDatePicker() {
        val now = LocalDate.now()
        val dialog = DatePickerDialog(
            requireContext(),
            { _: DatePicker, year: Int, month: Int, dayOfMonth: Int ->
                binding.etDate.setText(
                    LocalDate.of(year, month + 1, dayOfMonth)
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                )
            },
            now.year,
            now.monthValue - 1,
            now.dayOfMonth
        )
        dialog.datePicker.setOnDateChangedListener { _, year, month, dayOfMonth ->
            binding.etDate.setText(
                LocalDate.of(year, month + 1, dayOfMonth)
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            )
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun formatDateMask(input: String): String {
        val digits = input.filter { it.isDigit() }.take(8)
        return when {
            digits.length > 4 ->
                "${digits.substring(0, 2)}.${digits.substring(2, 4)}.${digits.substring(4)}"
            digits.length > 2 -> "${digits.substring(0, 2)}.${digits.substring(2)}"
            else -> digits
        }
    }

    private fun focusAndShowKeyboard(editText: EditText) {
        editText.requestFocus()
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}