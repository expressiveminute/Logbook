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
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.DatePicker
import android.widget.FrameLayout
import android.widget.ImageView
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
import java.util.Locale
import kotlin.math.roundToInt

class AddEntryFragment : Fragment() {

    private var _binding: FragmentAddEntryBinding? = null

    private val binding get() = _binding!!

    private var selectedFlightTypeIndex: Int? = null
    private var selectedClassIndex: Int? = null
    private var selectedDeadheadIndex: Int? = null
    private var prefilling = false

    private var editingEntryId: Long = -1L
    private var returnActive = false

    /**
     * Wird true, sobald der Speichern-Button im aktuellen Bearbeitungsdurchgang
     * sichtbar geworden ist. Verhindert, dass ein erneutes Sichtbarwerden nach
     * kurzem Löschen/Wieder-Eintippen eines Pflichtfeldes den Fokus aus dem
     * Eingabefeld reißt (Auto-Scroll zum Speichern-Button).
     */
    private var saveButtonRevealHandled = false

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
        setupDeadheadTiles()
        setupClassTiles()
        setupAirlineAutoAdvance()
        setupFlightNumberRoutePrefill()
        setupAircraftTypeFields()
        setupRegistrationUppercase()
        setupAirportAutoAdvance()
        setupFlightTimeConversion()
        setupDateField()
        setupReturnToggle()
        updateReturnUi()
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
        reorderFlightTypeTiles()
        val tiles = orderedFlightTypeTiles()
        val labels = orderedFlightTypeLabels()

        configureHighlight(binding.flightGroupFrame, binding.flightHighlight, tiles)

        tiles.forEachIndexed { visualIndex, tile ->
            tile.setOnClickListener { selectFlightType(flightTypeVisualOrder()[visualIndex]) }
        }
        updateFlightLabels(labels, null)
    }

    /**
     * Semantische Indizes -> Kacheln. Die sichtbare Reihenfolge kann je nach Rolle
     * abweichen (Crew: On Duty, Deadhead, Dienstreise, Privat; Passagier: wie im
     * Layout), daher werden Kacheln/Labels immer über diese Zuordnung aufgelöst.
     */
    private fun flightTypeTilesByIndex(): Map<Int, View> = mapOf(
        PRIVATE_INDEX to binding.tileFlightPrivate,
        ON_DUTY_INDEX to binding.tileFlightOnDuty,
        DEADHEAD_INDEX to binding.tileFlightDeadhead,
        DUTY_TRAVEL_INDEX to binding.tileFlightDutyTravel,
    )

    private fun flightTypeLabelsByIndex(): Map<Int, TextView> = mapOf(
        PRIVATE_INDEX to binding.labelFlightPrivate,
        ON_DUTY_INDEX to binding.labelFlightOnDuty,
        DEADHEAD_INDEX to binding.labelFlightDeadhead,
        DUTY_TRAVEL_INDEX to binding.labelFlightDutyTravel,
    )

    private fun flightTypeVisualOrder(): IntArray =
        if (Settings.getRole(requireContext()) == Settings.ROLE_CREW) {
            intArrayOf(ON_DUTY_INDEX, DEADHEAD_INDEX, DUTY_TRAVEL_INDEX, PRIVATE_INDEX)
        } else {
            intArrayOf(PRIVATE_INDEX, ON_DUTY_INDEX, DEADHEAD_INDEX, DUTY_TRAVEL_INDEX)
        }

    private fun orderedFlightTypeTiles(): List<View> =
        flightTypeVisualOrder().map { flightTypeTilesByIndex()[it]!! }

    private fun orderedFlightTypeLabels(): List<TextView> =
        flightTypeVisualOrder().map { flightTypeLabelsByIndex()[it]!! }

    private fun reorderFlightTypeTiles() {
        val container = binding.flightTypeContainer
        val ordered = orderedFlightTypeTiles()
        for (tile in ordered) container.removeView(tile)
        for (tile in ordered) container.addView(tile)
    }

    private fun setupDeadheadTiles() {
        val tiles = deadheadTiles()
        val labels = deadheadLabels()

        configureHighlight(binding.deadheadGroupFrame, binding.deadheadHighlight, tiles)

        tiles.forEachIndexed { index, tile ->
            tile.setOnClickListener { selectDeadheadType(index) }
        }
        updateFlightLabels(labels, null)
    }

    private fun deadheadTiles() = listOf(
        binding.tileDeadheadDeadhead,
        binding.tileDeadheadFerry,
        binding.tileDeadheadGroundTransfer,
    )

    private fun deadheadLabels() = listOf(
        binding.labelDeadheadDeadhead,
        binding.labelDeadheadFerry,
        binding.labelDeadheadGroundTransfer,
    )

    private fun selectDeadheadType(index: Int) {
        selectedDeadheadIndex = index
        moveHighlight(
            binding.deadheadGroupFrame,
            binding.deadheadHighlight,
            deadheadTiles(),
            index,
            ContextCompat.getColor(requireContext(), R.color.type_deadhead_bg)
        )
        updateFlightLabels(deadheadLabels(), index)
    }

    private fun flightTypeColorRes(index: Int): Int = when (index) {
        0 -> R.color.type_private_bg
        1 -> R.color.type_on_duty_bg
        2 -> R.color.type_deadhead_bg
        else -> R.color.type_duty_travel_bg
    }

    /**
     * Wenn in der ersten Zeile "Deadhead" gewählt ist, erscheint darunter eine
     * zweite Zeile, die die Reiseart genauer festlegt (Deadhead/Ferry/Ground
     * Transfer). Standardmäßig ist dort "Deadhead" ausgewählt.
     */
    private fun updateDeadheadRowVisibility() {
        val isDeadhead = selectedFlightTypeIndex == DEADHEAD_INDEX
        binding.deadheadSection.visibility = if (isDeadhead) View.VISIBLE else View.GONE
        if (isDeadhead && selectedDeadheadIndex == null) {
            selectDeadheadType(0)
        }
    }

    /**
     * Das Feld "Funktion" erscheint nur bei den Reisearten On Duty und Deadhead
     * und wird automatisch mit der in den Einstellungen gewählten Crew-Funktion
     * befüllt (sofern es noch leer ist).
     */
    private fun updateFunctionVisibility() {
        val show = selectedFlightTypeIndex == ON_DUTY_INDEX ||
            selectedFlightTypeIndex == DEADHEAD_INDEX
        binding.functionSection.visibility = if (show) View.VISIBLE else View.GONE
        if (show && !prefilling && binding.etFunction.text.isNullOrBlank()) {
            binding.etFunction.setText(Settings.selectedCrewFunctionLabel(requireContext()))
        }
    }

    private fun selectFlightType(index: Int) {
        selectedFlightTypeIndex = index
        val tiles = orderedFlightTypeTiles()
        val labels = orderedFlightTypeLabels()
        val visualIndex = flightTypeVisualOrder().indexOf(index)
        moveHighlight(
            binding.flightGroupFrame,
            binding.flightHighlight,
            tiles,
            visualIndex,
            ContextCompat.getColor(requireContext(), flightTypeColorRes(index))
        )
        updateFlightLabels(labels, visualIndex)
        updateDeadheadRowVisibility()
        updateFunctionVisibility()
        updateAirlineVisibility()
        updatePreferredClass()
        updateLayoverVisibility()
        refreshVisibility()
    }

    /**
     * Ist in den Einstellungen eine bevorzugte Arbeitsposition (Reiseklasse)
     * hinterlegt, wird sie bei "On Duty" automatisch vorausgewählt (sofern noch
     * keine Reiseklasse gewählt wurde).
     */
    private fun updatePreferredClass() {
        if (prefilling) return
        if (selectedFlightTypeIndex != ON_DUTY_INDEX) return
        if (selectedClassIndex != null) return
        val preferred = Settings.getPreferredClassIndex(requireContext())
        if (preferred in 0..3) {
            selectClass(preferred)
        }
    }

    private fun updateLayoverVisibility() {
        val isCrew = Settings.getRole(requireContext()) == Settings.ROLE_CREW
        val isPrivate = selectedFlightTypeIndex == PRIVATE_INDEX
        binding.cbLayover.visibility =
            if (isCrew && !isPrivate) View.VISIBLE else View.GONE
    }

    /**
     * Bei den Reisearten On Duty, Deadhead und Dienstreise wird das Feld
     * "Fluggesellschaft" automatisch mit der in den Einstellungen hinterlegten
     * Fluggesellschaft vorbefüllt (sofern es noch leer ist).
     */
    private fun updateAirlineVisibility() {
        val prefill = selectedFlightTypeIndex == ON_DUTY_INDEX ||
            selectedFlightTypeIndex == DEADHEAD_INDEX ||
            selectedFlightTypeIndex == DUTY_TRAVEL_INDEX
        if (prefill && !prefilling && binding.etAirline.text.isNullOrBlank()) {
            val airline = Settings.getAirline(requireContext())
            if (airline.isNotBlank()) {
                binding.etAirline.setText(airline)
            }
        }
    }

    private fun selectClass(index: Int) {
        selectedClassIndex = index
        val tiles = classTiles()
        val classColors = ClassColorSchemes.colorsFor(Settings.getClassScheme(requireContext()))
        moveHighlight(
            binding.classGroupFrame,
            binding.classHighlight,
            tiles,
            index,
            ContextCompat.getColor(requireContext(), classColors[index])
        )
        updateClassLabels(classLabels(), index)
        refreshVisibility()
    }

    private fun classTiles() = listOf(
        binding.tileClassEconomy,
        binding.tileClassPremiumEconomy,
        binding.tileClassBusiness,
        binding.tileClassFirst,
        binding.tileClassJump,
    )

    private fun classLabels() = listOf(
        binding.labelClassEconomy,
        binding.labelClassPremiumEconomy,
        binding.labelClassBusiness,
        binding.labelClassFirst,
        binding.labelClassJump,
    )

    private fun setupClassTiles() {
        configureHighlight(binding.classGroupFrame, binding.classHighlight, classTiles())

        classTiles().forEachIndexed { index, tile ->
            tile.setOnClickListener { selectClass(index) }
        }
        updateClassLabels(classLabels(), null)
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
            R.string.class_jump,
        )

        val normalizedFlightType = ChartData.normalizeFlightType(entry.flightType)
        val flightTypeIndex = normalizedFlightType?.let { text ->
            flightTypeLabels.indexOfFirst {
                ChartData.normalizeFlightType(getString(it)) == text
            }.takeIf { it != -1 }
        }
        if (flightTypeIndex != null) {
            selectFlightType(flightTypeIndex)
        } else {
            val detailLabels = listOf(
                R.string.flight_type_deadhead,
                R.string.flight_type_ferry,
                R.string.flight_type_ground_transfer,
            )
            val detailIndex = normalizedFlightType?.let { text ->
                detailLabels.indexOfFirst {
                    ChartData.normalizeFlightType(getString(it)) == text
                }.takeIf { it != -1 }
            }
            if (detailIndex != null) {
                selectFlightType(DEADHEAD_INDEX)
                selectDeadheadType(detailIndex)
            }
        }

        val normalizedClassType = ChartData.normalizeClassType(entry.classType)
        val classIndex = normalizedClassType?.let { text ->
            classLabels.indexOfFirst {
                ChartData.normalizeClassType(getString(it)) == text
            }.takeIf { it != -1 }
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

        binding.etFunction.setText(entry.function)
        binding.etComment.setText(entry.comment)

        val hasOptional = listOf(
            entry.aircraftType,
            entry.registration,
            entry.comment,
            entry.function
        ).any { !it.isNullOrBlank() }
        if (hasOptional) toggleOptionalBody(expanded = true)
        validateAirport(entry.fromAirport, binding.etAirportFrom, binding.ivAirportCheckFrom)
        validateAirport(entry.toAirport, binding.etAirportTo, binding.ivAirportCheckTo)
        updateCountryFlag(entry.fromAirport, binding.tvCountryFlagFrom)
        updateCountryFlag(entry.toAirport, binding.tvCountryFlagTo)
        prefilling = false
    }

    private fun updateFlightLabels(labels: List<TextView>, selectedIndex: Int?) {
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        labels.forEachIndexed { index, label ->
            label.setTextColor(if (index == selectedIndex) Color.WHITE else onSurface)
        }
    }

    private fun updateClassLabels(labels: List<TextView>, selectedIndex: Int?) {
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        labels.forEachIndexed { index, label ->
            label.setTextColor(if (index == selectedIndex) Color.WHITE else onSurface)
        }
        binding.ivClassJump.setColorFilter(
            if (selectedIndex == CLASS_JUMP_INDEX) Color.WHITE else onSurface
        )
    }

    private fun configureHighlight(frame: FrameLayout, highlight: View, tiles: List<View>) {
        frame.doOnLayout {
            val anchor = tiles.firstOrNull() ?: return@doOnLayout
            (highlight.layoutParams as FrameLayout.LayoutParams).width = anchor.width
            highlight.translationX = frame.offsetTo(anchor)
        }
    }

    private fun moveHighlight(
        frame: FrameLayout,
        highlight: View,
        tiles: List<View>,
        index: Int,
        color: Int
    ) {
        frame.doOnLayout {
            val tile = tiles.getOrNull(index) ?: return@doOnLayout
            (highlight.layoutParams as FrameLayout.LayoutParams).width = tile.width
            highlight.layoutParams = highlight.layoutParams

            highlight.background = roundedCellBackground(color)

            if (highlight.visibility != View.VISIBLE) {
                highlight.visibility = View.VISIBLE
                highlight.alpha = 0f
                highlight.animate().alpha(1f).setDuration(200).start()
            }

            highlight.animate()
                .translationX(frame.offsetTo(tile))
                .setDuration(250)
                .start()
        }
    }

    private fun FrameLayout.offsetTo(target: View): Float {
        val framePos = IntArray(2)
        val targetPos = IntArray(2)
        getLocationInWindow(framePos)
        target.getLocationInWindow(targetPos)
        return (targetPos[0] - framePos[0]).toFloat()
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
                prefillRegistration()
                prefillRouteFromHistory()
            }
        })
    }

    private fun setupFlightNumberRoutePrefill() {
        binding.etFlightNumber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                prefillRouteFromHistory()
            }
        })
    }

    /**
     * Wenn Fluggesellschaft und Flugnummer bereits in gespeicherten Einträgen
     * vorkommen, werden Abflug und Ankunft automatisch mit der am häufigsten
     * gespeicherten Strecke übernommen. Eigene Eingaben werden nicht überschrieben.
     */
    private fun prefillRouteFromHistory() {
        if (editingEntryId >= 0) return
        if (!binding.etAirportFrom.text.isNullOrBlank() || !binding.etAirportTo.text.isNullOrBlank()) {
            return
        }
        val airline = binding.etAirline.text?.toString()?.trim().orEmpty()
        val flightNumber = binding.etFlightNumber.text?.toString()?.trim().orEmpty()
        if (airline.isEmpty() || flightNumber.isEmpty()) return
        val route = mostFrequentRoute(LogbookRepository.getEntries(), airline, flightNumber)
            ?: return
        binding.etAirportFrom.setText(route.from)
        binding.etAirportTo.setText(route.to)
    }

    private fun setupAircraftTypeFields() {
        setupAircraftTypeField(binding.etAircraftType, binding.etRegistration)
        setupAircraftTypeField(binding.etHinflugAircraft, binding.etHinflugRegistration)
        setupAircraftTypeField(binding.etRueckflugAircraft, binding.etRueckflugRegistration)
    }

    private fun setupRegistrationUppercase() {
        setupUppercaseWatcher(binding.etRegistration)
        setupUppercaseWatcher(binding.etHinflugRegistration)
        setupUppercaseWatcher(binding.etRueckflugRegistration)
    }

    private fun setupUppercaseWatcher(editText: EditText) {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val upper = s.toString().uppercase()
                if (upper != s.toString()) {
                    s.replace(0, s.length, upper)
                }
            }
        })
    }

    private fun setupAircraftTypeField(aircraft: EditText, registration: EditText) {
        aircraft.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            private var completed = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (isFormatting) return
                val completion = if (!completed) aircraftCompletion(s.toString()) else null
                if (completion != null) {
                    completed = true
                    isFormatting = true
                    s.replace(0, s.length, completion)
                    isFormatting = false
                    prefillRegistration(aircraft, registration)
                    focusAndShowKeyboard(registration)
                    registration.setSelection(registration.length())
                    return
                }
                val formatted = formatAircraftType(s.toString())
                if (formatted != s.toString()) {
                    isFormatting = true
                    s.replace(0, s.length, formatted)
                    isFormatting = false
                }
                prefillRegistration(aircraft, registration)
            }
        })
    }

    private fun aircraftCompletion(input: String): String? {
        val prefix = input.trim().uppercase()
        if (prefix.length < 3) return null
        return AIRCRAFT_AUTOCOMPLETE[prefix]
    }

    /**
     * Normalisiert den Flugzeugtyp so, dass nur der erste Buchstabe groß und der
     * Rest klein geschrieben wird, z. B. "A320", "B737" oder "Atr72".
     */
    private fun formatAircraftType(input: String): String {
        if (input.isEmpty()) return input
        return input.first().uppercaseChar() + input.substring(1).lowercase()
    }

    /**
     * Bei "LH" als Fluggesellschaft wird die Registrierung automatisch vorbefüllt:
     * Flugzeugtyp mit "A..." -> "D-AI" und mit "B..." -> "D-AB".
     */
    private fun prefillRegistration() {
        prefillRegistration(binding.etAircraftType, binding.etRegistration)
        prefillRegistration(binding.etHinflugAircraft, binding.etHinflugRegistration)
        prefillRegistration(binding.etRueckflugAircraft, binding.etRueckflugRegistration)
    }

    private fun prefillRegistration(aircraft: EditText, registration: EditText) {
        if (registration.text?.isNotEmpty() == true) return
        if (!airlineIsLh()) return
        val prefix = when {
            aircraft.text?.toString()?.trim()?.startsWith("A", ignoreCase = true) == true -> "D-AI"
            aircraft.text?.toString()?.trim()?.startsWith("B", ignoreCase = true) == true -> "D-AB"
            else -> return
        }
        registration.setText(prefix)
    }

    private fun airlineIsLh(): Boolean =
        binding.etAirline.text?.toString()?.trim().equals("LH", ignoreCase = true)

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
                validateAirport(s.toString(), binding.etAirportFrom, binding.ivAirportCheckFrom)
                updateCountryFlag(s.toString(), binding.tvCountryFlagFrom)
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
                    return
                }
                validateAirport(s.toString(), binding.etAirportTo, binding.ivAirportCheckTo)
                updateCountryFlag(s.toString(), binding.tvCountryFlagTo)
                autoFillRouteData()
            }
        })
    }

    private fun validateAirport(code: String, editText: EditText, check: ImageView) {
        val exists = code.length == 3 &&
            AirportData.location(requireContext(), code) != null
        if (exists) {
            val wasVisible = check.visibility == View.VISIBLE
            if (!wasVisible) {
                check.visibility = View.INVISIBLE
                check.alpha = 0f
                check.scaleX = 0.3f
                check.scaleY = 0.3f
            }
            editText.doOnLayout {
                positionCheckBehindCode(editText, check, code)
                if (!wasVisible) {
                    check.visibility = View.VISIBLE
                    check.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(280)
                        .setInterpolator(OvershootInterpolator())
                        .start()
                }
            }
        } else if (check.visibility != View.GONE) {
            check.animate().cancel()
            check.visibility = View.GONE
        }
    }

    private fun positionCheckBehindCode(editText: EditText, check: ImageView, code: String) {
        val parentLayout = editText.parent as? ViewGroup
        val textStartX = (parentLayout?.paddingLeft ?: 0) + editText.paddingLeft
        val textWidth = editText.paint.measureText(code)
        val gap = 5f * resources.displayMetrics.density
        check.translationX = textStartX + textWidth + gap

        val checkHalfHeight = 8f * resources.displayMetrics.density
        check.translationY = editText.top + editText.height / 2f - checkHalfHeight
    }

    private fun updateCountryFlag(code: String, flag: TextView) {
        val iso = if (code.length == 3) AirportData.country(requireContext(), code) else null
        if (iso != null) {
            flag.text = AirportData.flagEmoji(iso)
            if (flag.visibility != View.VISIBLE) {
                flag.visibility = View.VISIBLE
                flag.alpha = 0f
                flag.scaleX = 0.6f
                flag.scaleY = 0.6f
                flag.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }
        } else if (flag.visibility != View.GONE) {
            flag.animate().cancel()
            flag.visibility = View.GONE
        }
    }

    private fun setupFlightTimeConversion() {
        binding.etFlightTime.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                updateFlightTimeDisplay()
            }
        })
        updateFlightTimeDisplay()
    }

    private fun updateFlightTimeDisplay() {
        val minutes = binding.etFlightTime.text?.toString()?.trim()?.toIntOrNull()
        if (minutes != null && minutes > 0) {
            val hours = minutes / 60
            val restMinutes = minutes % 60
            binding.tvFlightTimeHours.text =
                String.format(Locale.GERMANY, "%d:%02d h", hours, restMinutes)
            binding.tvFlightTimeEqual.visibility = View.VISIBLE
            binding.tvFlightTimeHours.visibility = View.VISIBLE
        } else {
            binding.tvFlightTimeEqual.visibility = View.GONE
            binding.tvFlightTimeHours.visibility = View.GONE
        }
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
        binding.etDateReturn.addTextChangedListener(watcher)
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

    private fun setupReturnToggle() {
        binding.tvAddReturn.setOnClickListener { activateReturn() }
        binding.btnRemoveReturn.setOnClickListener { deactivateReturn() }
    }

    private fun activateReturn() {
        if (returnActive) return
        returnActive = true
        val aircraft = binding.etAircraftType.text?.toString()?.trim().orEmpty()
        val registration = binding.etRegistration.text?.toString()?.trim().orEmpty()
        val comment = binding.etComment.text?.toString()?.trim().orEmpty()
        binding.etHinflugAircraft.setText(aircraft)
        binding.etHinflugRegistration.setText(registration)
        binding.etHinflugComment.setText(comment)
        binding.etRueckflugAircraft.setText(aircraft)
        binding.etRueckflugRegistration.setText(registration)
        binding.etRueckflugComment.setText(comment)
        val hinflugDate = binding.etDate.text?.toString()?.trim().orEmpty()
        if (hinflugDate.isNotEmpty()) {
            binding.etDateReturn.setText(hinflugDate)
        }
        updateReturnUi()
        showDatePicker(binding.etDateReturn)
    }

    private fun deactivateReturn() {
        if (!returnActive) return
        returnActive = false
        binding.etAircraftType.setText(binding.etHinflugAircraft.text?.toString()?.trim().orEmpty())
        binding.etRegistration.setText(binding.etHinflugRegistration.text?.toString()?.trim().orEmpty())
        binding.etComment.setText(binding.etHinflugComment.text?.toString()?.trim().orEmpty())
        updateReturnUi()
    }

    private fun updateReturnUi() {
        val editing = editingEntryId >= 0
        binding.returnSection.visibility = if (editing) View.GONE else View.VISIBLE
        binding.tvAddReturn.visibility = if (returnActive) View.GONE else View.VISIBLE
        binding.tileDateReturn.visibility = if (returnActive) View.VISIBLE else View.GONE
        binding.tvDateLabel.setText(if (returnActive) R.string.hinflight_label else R.string.date_label)
        binding.optionalSingleBlock.visibility = if (returnActive) View.GONE else View.VISIBLE
        binding.optionalSplitBlock.visibility = if (returnActive) View.VISIBLE else View.GONE
        binding.btnSaveFlight.setText(
            if (editing) R.string.btn_update_flight
            else if (returnActive) R.string.btn_save_flights
            else R.string.btn_save_flight
        )
        binding.btnDiscardFlight.setText(if (returnActive) R.string.btn_discard_flights else R.string.btn_discard_flight)
        refreshVisibility()
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

        val returnDate = if (returnActive) {
            try {
                LocalDate.parse(
                    binding.etDateReturn.text?.toString()?.trim().orEmpty(),
                    DATE_FORMAT
                )
                true
            } catch (e: Exception) {
                false
            }
        } else true

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

        binding.tvDistanceUnit.visibility = if (distance.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvFlightTimeMin.visibility = if (flightTime.isNotEmpty()) View.VISIBLE else View.GONE

        val allRequired = hasType && hasClass && airline.isNotEmpty() && flightNumber.isNotEmpty() &&
            date && returnDate && from.isNotEmpty() && to.isNotEmpty() &&
            distance.isNotEmpty() && flightTime.isNotEmpty()

        val saveWasVisible = binding.btnSaveFlight.visibility == View.VISIBLE
        if (saveWasVisible) {
            saveButtonRevealHandled = true
        }
        binding.btnSaveFlight.visibility = if (allRequired) View.VISIBLE else View.GONE
        binding.btnDiscardFlight.visibility = if (allRequired) View.VISIBLE else View.GONE
        if (allRequired && !saveWasVisible && !saveButtonRevealHandled && editingEntryId < 0) {
            saveButtonRevealHandled = true
            binding.btnSaveFlight.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        private const val PRIVATE_INDEX = 0
        private const val ON_DUTY_INDEX = 1
        private const val DEADHEAD_INDEX = 2
        private const val DUTY_TRAVEL_INDEX = 3
        private const val CLASS_JUMP_INDEX = 4
        private val AIRCRAFT_AUTOCOMPLETE = mapOf(
            "A35" to "A350-900",
            "A38" to "A380-800",
        )
    }

    private fun setupSaveAndDiscard() {
        binding.btnSaveFlight.setOnClickListener { saveFlight() }
        binding.btnDiscardFlight.setOnClickListener { confirmDiscard() }
    }

    private fun selectedFlightType(): String? {
        val topLevelIndex = selectedFlightTypeIndex ?: return null
        if (topLevelIndex == DEADHEAD_INDEX) {
            val detailIndex = selectedDeadheadIndex ?: 0
            return deadheadLabels()[detailIndex].text.toString()
        }
        return flightTypeLabelsByIndex()[topLevelIndex]?.text?.toString()
    }

    private fun selectedClassType(): String? =
        selectedClassIndex?.let { index ->
            classLabels()[index].text.toString()
        }

    private fun nextFlightNumber(current: String?): String? {
        val number = current?.trim()?.toIntOrNull() ?: return current
        return (number + 1).toString()
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

        val returnDate = if (returnActive) {
            try {
                LocalDate.parse(
                    binding.etDateReturn.text?.toString()?.trim().orEmpty(),
                    DateTimeFormatter.ofPattern("dd.MM.yyyy")
                )
            } catch (e: Exception) {
                null
            }
        } else null
        if (returnActive && returnDate == null) missingLabels += getString(R.string.return_flight_label)

        val from = binding.etAirportFrom.text?.toString()?.trim()?.uppercase().orEmpty()
        val to = binding.etAirportTo.text?.toString()?.trim()?.uppercase().orEmpty()
        if (from.isEmpty()) missingLabels += getString(R.string.airport_departure_label)
        if (to.isEmpty()) missingLabels += getString(R.string.airport_arrival_label)

        val distanceText = binding.etDistance.text?.toString()?.trim().orEmpty()
        val flightTimeText = binding.etFlightTime.text?.toString()?.trim().orEmpty()
        val distanceKm = distanceText.toIntOrNull()
        val flightMinutes = flightTimeText.toIntOrNull()
        if (distanceText.isNotEmpty() && distanceKm == null) missingLabels += getString(R.string.flight_distance_label)
        if (flightTimeText.isNotEmpty() && flightMinutes == null) missingLabels += getString(R.string.flight_time_label)

        if (missingLabels.isNotEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.save_flight_incomplete) + "\n" + missingLabels.joinToString(", "),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val confirmedDate = date ?: return

        val aircraftType = (if (returnActive) binding.etHinflugAircraft else binding.etAircraftType)
            .text?.toString()?.trim()?.ifEmpty { null }
        val registration = (if (returnActive) binding.etHinflugRegistration else binding.etRegistration)
            .text?.toString()?.trim()?.uppercase()?.ifEmpty { null }
        val comment = (if (returnActive) binding.etHinflugComment else binding.etComment)
            .text?.toString()?.trim()?.ifEmpty { null }
        val function = binding.etFunction.text?.toString()?.trim()?.ifEmpty { null }

        val entry = LogbookEntry(
            id = editingEntryId.takeIf { it >= 0 },
            date = confirmedDate,
            flightType = selectedFlightType(),
            classType = selectedClassType(),
            fromAirport = from,
            toAirport = to,
            airline = airline.uppercase(),
            flightNumber = flightNumber,
            aircraftType = aircraftType,
            registration = registration,
            distanceKm = distanceKm,
            flightMinutes = flightMinutes,
            layover = binding.cbLayover.isChecked,
            fromCountry = AirportData.country(requireContext(), from),
            toCountry = AirportData.country(requireContext(), to),
            function = function,
            comment = comment
        )
        if (editingEntryId >= 0) {
            LogbookRepository.updateEntry(entry)
        } else {
            LogbookRepository.addEntry(entry)
        }

        if (returnActive && returnDate != null) {
            val returnEntry = LogbookEntry(
                id = null,
                date = returnDate,
                flightType = entry.flightType,
                classType = entry.classType,
                fromAirport = to,
                toAirport = from,
                airline = entry.airline,
                flightNumber = nextFlightNumber(entry.flightNumber),
                aircraftType = binding.etRueckflugAircraft.text?.toString()?.trim()?.ifEmpty { null },
                registration = binding.etRueckflugRegistration.text?.toString()?.trim()?.uppercase()?.ifEmpty { null },
                distanceKm = entry.distanceKm,
                flightMinutes = entry.flightMinutes,
                layover = false,
                fromCountry = entry.toCountry,
                toCountry = entry.fromCountry,
                function = entry.function,
                comment = binding.etRueckflugComment.text?.toString()?.trim()?.ifEmpty { null }
            )
            LogbookRepository.addEntry(returnEntry)
        }

        Toast.makeText(
            requireContext(),
            getString(if (returnActive) R.string.flights_saved else R.string.flight_saved),
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
        setupDateEditText(binding.etDate)
        setupDateEditText(binding.etDateReturn)
    }

    private fun setupDateEditText(editText: EditText) {
        editText.addTextChangedListener(object : TextWatcher {
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

        editText.setOnClickListener { showDatePicker(editText) }
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showDatePicker(editText)
        }
    }

    private fun showDatePicker(target: EditText) {
        val initial = try {
            LocalDate.parse(
                target.text?.toString()?.trim().orEmpty(),
                DATE_FORMAT
            )
        } catch (e: Exception) {
            LocalDate.now()
        }
        val dialog = DatePickerDialog(
            requireContext(),
            { _: DatePicker, year: Int, month: Int, dayOfMonth: Int ->
                target.setText(
                    LocalDate.of(year, month + 1, dayOfMonth)
                        .format(DATE_FORMAT)
                )
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        )
        dialog.datePicker.setOnDateChangedListener { _, year, month, dayOfMonth ->
            target.setText(
                LocalDate.of(year, month + 1, dayOfMonth)
                    .format(DATE_FORMAT)
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