package com.highfly.logbook

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentImportBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ImportFragment : Fragment() {

    private var _binding: FragmentImportBinding? = null
    private val binding get() = _binding!!

    private var processing = false

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        loadFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            ImportSession.reset()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBackImport.setOnClickListener { findNavController().navigateUp() }

        binding.tileModeOverwrite.setOnClickListener { selectMode(ImportMode.OVERWRITE) }
        binding.tileModeAppend.setOnClickListener { selectMode(ImportMode.APPEND) }

        binding.tileFilePicker.setOnClickListener { openFilePicker() }
        binding.tvConflictsBlue.setOnClickListener { openReview() }
        binding.tileReview.setOnClickListener { openReview() }
        binding.tileImplement.setOnClickListener { implementData() }

        render()
    }

    private fun selectMode(mode: ImportMode) {
        if (ImportSession.processed || ImportSession.loadedCount > 0) return
        ImportSession.mode = mode
        render()
    }

    private fun openFilePicker() {
        if (ImportSession.mode == null) return
        pickerLauncher.launch(
            arrayOf(
                "text/csv",
                "text/comma-separated-values",
                "application/csv",
                "application/vnd.ms-excel",
                "text/plain",
                "*/*"
            )
        )
    }

    private fun loadFile(uri: Uri) {
        processing = true
        render()
        val appContext = requireContext().applicationContext
        Thread {
            try {
                val content = appContext.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                val parsed = if (content != null) LogbookCsv.fromCsv(content) else emptyList()
                val result = ImportProcessor.process(appContext, parsed)
                requireActivity().runOnUiThread {
                    ImportSession.entries.clear()
                    ImportSession.entries.addAll(result.entries)
                    ImportSession.conflictIndexes.clear()
                    ImportSession.conflictIndexes.addAll(result.conflictIndexes)
                    ImportSession.loadedCount = result.entries.size
                    ImportSession.processed = true
                    processing = false
                    render()
                    Toast.makeText(
                        requireContext(),
                        if (result.entries.isEmpty()) R.string.import_empty else R.string.import_ready,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    processing = false
                    render()
                    Toast.makeText(requireContext(), R.string.import_error, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun render() {
        val mode = ImportSession.mode
        highlight(binding.tileModeOverwrite, binding.labelModeOverwrite, mode == ImportMode.OVERWRITE)
        highlight(binding.tileModeAppend, binding.labelModeAppend, mode == ImportMode.APPEND)

        val locked = ImportSession.loadedCount > 0
        binding.tileModeOverwrite.isEnabled = !locked
        binding.tileModeAppend.isEnabled = !locked

        binding.tvModeOverwriteHint.visibility =
            if (mode == ImportMode.OVERWRITE) View.VISIBLE else View.GONE
        binding.tvModeAppendHint.visibility =
            if (mode == ImportMode.APPEND) View.VISIBLE else View.GONE

        binding.step2Container.visibility = if (mode != null) View.VISIBLE else View.GONE

        val hasResults = ImportSession.loadedCount > 0
        binding.step3Container.visibility = if (hasResults) View.VISIBLE else View.GONE
        binding.step4Container.visibility = if (hasResults && !processing) View.VISIBLE else View.GONE
        binding.loadingRow.visibility = if (processing) View.VISIBLE else View.GONE

        if (hasResults) {
            binding.tvLoadedSuccess.text =
                getString(R.string.import_loaded_count, ImportSession.loadedCount)
            val remaining = ImportSession.remainingConflicts()
            binding.tvConflictsBlue.visibility = if (remaining > 0) View.VISIBLE else View.GONE
            binding.tvConflictsBlue.text = getString(R.string.import_conflicts_count, remaining)
            binding.tileReview.visibility = if (remaining > 0) View.VISIBLE else View.GONE
            binding.tvReviewSubtitle.text = getString(R.string.import_review_tile_subtitle, remaining)
        }
    }

    private fun openReview() {
        if (ImportSession.conflictIndexes.isEmpty()) return
        showConflictList()
    }

    /**
     * Zeigt eine scrollbare Liste aller offenen Konflikte. Jede Zeile fasst
     * den Eintrag zusammen (Datum, Strecke, Grund); ein Tipp darauf öffnet
     * den Bearbeitungsdialog für genau diesen Eintrag.
     */
    private fun showConflictList() {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        scroll.addView(container)

        ImportSession.conflictIndexes.forEachIndexed { pos, index ->
            val entry = ImportSession.entries.getOrNull(index) ?: return@forEachIndexed
            val reason = ImportProcessor.conflictReason(ctx, entry)
            container.addView(conflictRow(entry, reason) { showReviewDialog(index) })
        }

        if (container.childCount == 0) return

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.import_review_tile_title)
            .setView(scroll)
            .setNegativeButton(R.string.import_review_close, null)
            .show()
    }

    private fun conflictRow(
        entry: LogbookEntry,
        reason: String?,
        onClick: () -> Unit
    ): com.google.android.material.card.MaterialCardView {
        val ctx = requireContext()
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        val warn = ContextCompat.getColor(ctx, R.color.import_warn)
        val from = entry.fromAirport.ifBlank { getString(R.string.import_review_summary_unknown) }
        val to = entry.toAirport.ifBlank { getString(R.string.import_review_summary_unknown) }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        content.addView(
            TextView(ctx).apply {
                text = getString(
                    R.string.import_review_summary_route,
                    entry.date.format(DATE_FORMAT),
                    "$from → $to"
                )
                textSize = 15f
                setTextColor(onSurface)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
        )
        if (reason != null) {
            content.addView(
                TextView(ctx).apply {
                    text = reason
                    textSize = 13f
                    setTextColor(warn)
                    setPadding(dp(2), dp(3), dp(2), 0)
                }
            )
        }

        return com.google.android.material.card.MaterialCardView(ctx).apply {
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            cardElevation = 0f
            setRadius(dp(16).toFloat())
            strokeWidth = dp(1)
            strokeColor = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorOutlineVariant
            )
            setCardBackgroundColor(
                MaterialColors.getColor(
                    binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh
                )
            )
            setOnClickListener { onClick() }
            addView(content)
        }
    }

    private fun showReviewDialog(index: Int) {
        val ctx = requireContext()
        val entry = ImportSession.entries.getOrNull(index) ?: return
        val total = ImportSession.conflictIndexes.size
        val position = ImportSession.conflictIndexes.indexOf(index) + 1
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        val warn = ContextCompat.getColor(ctx, R.color.import_warn)

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        scroll.addView(container)

        container.addView(
            TextView(ctx).apply {
                text = getString(R.string.import_review_progress, position, total)
                textSize = 14f
                setTextColor(warn)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
        )

        val summaryFrom =
            entry.fromAirport.ifBlank { getString(R.string.import_review_summary_unknown) }
        val summaryTo =
            entry.toAirport.ifBlank { getString(R.string.import_review_summary_unknown) }
        val reason = ImportProcessor.conflictReason(ctx, entry)
        container.addView(
            TextView(ctx).apply {
                text = getString(
                    R.string.import_review_summary_route,
                    summaryFrom,
                    summaryTo
                )
                textSize = 18f
                setTextColor(onSurface)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(10), dp(4), dp(2))
            }
        )
        if (reason != null) {
            container.addView(
                TextView(ctx).apply {
                    text = reason
                    textSize = 13f
                    setTextColor(warn)
                    setPadding(dp(4), dp(2), dp(4), dp(6))
                }
            )
        }

        val etDate = addField(
            container, R.string.date_label, entry.date.format(DATE_FORMAT),
            InputType.TYPE_CLASS_TEXT, maxLength = 10
        ).apply { addTextChangedListener(dateMaskWatcher) }

        val etFrom = addField(
            container, R.string.airport_departure_label, entry.fromAirport,
            InputType.TYPE_CLASS_TEXT, maxLength = 3
        ).apply { addTextChangedListener(upperCaseWatcher()) }

        val etTo = addField(
            container, R.string.airport_arrival_label, entry.toAirport,
            InputType.TYPE_CLASS_TEXT, maxLength = 3
        ).apply { addTextChangedListener(upperCaseWatcher()) }

        val etAirline = addField(
            container, R.string.airline_label, entry.airline,
            InputType.TYPE_CLASS_TEXT, maxLength = 2
        ).apply { addTextChangedListener(upperCaseWatcher()) }

        val etFlightNumber = addField(
            container, R.string.flight_number_label, entry.flightNumber,
            InputType.TYPE_CLASS_NUMBER
        )

        val etDistance = addField(
            container, R.string.flight_distance_label, entry.distanceKm?.toString(),
            InputType.TYPE_CLASS_NUMBER
        )

        val etFlightTime = addField(
            container, R.string.flight_time_label, entry.flightMinutes?.toString(),
            InputType.TYPE_CLASS_NUMBER
        )

        val etFlightType = addField(
            container, R.string.flight_type_label, entry.flightType,
            InputType.TYPE_CLASS_TEXT
        )

        val etClassType = addField(
            container, R.string.class_label, entry.classType,
            InputType.TYPE_CLASS_TEXT
        )

        val etAircraft = addField(
            container, R.string.aircraft_type_label, entry.aircraftType,
            InputType.TYPE_CLASS_TEXT
        )

        val etRegistration = addField(
            container, R.string.registration_label, entry.registration,
            InputType.TYPE_CLASS_TEXT
        ).apply { addTextChangedListener(upperCaseWatcher()) }

        val etComment = addField(
            container, R.string.comment_label, entry.comment,
            InputType.TYPE_CLASS_TEXT, multiLine = true
        )

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.import_review_title)
            .setView(scroll)
            .setPositiveButton(R.string.import_review_save) { _, _ ->
                val updated = buildUpdatedEntry(
                    entry, etDate, etFrom, etTo, etAirline, etFlightNumber,
                    etDistance, etFlightTime, etFlightType, etClassType,
                    etAircraft, etRegistration, etComment
                )
                if (updated == null) {
                    Toast.makeText(ctx, R.string.import_review_invalid, Toast.LENGTH_SHORT).show()
                    showReviewDialog(index)
                } else {
                    ImportSession.entries[index] = updated
                    ImportSession.conflictIndexes.remove(index)
                    render()
                    if (ImportSession.conflictIndexes.isNotEmpty()) {
                        showReviewDialog(ImportSession.conflictIndexes.first())
                    } else {
                        Toast.makeText(ctx, R.string.import_review_all_done, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton(R.string.import_review_skip, null)
            .setNegativeButton(R.string.import_review_close, null)
            .show()
    }

    private fun buildUpdatedEntry(
        original: LogbookEntry,
        etDate: EditText,
        etFrom: EditText,
        etTo: EditText,
        etAirline: EditText,
        etFlightNumber: EditText,
        etDistance: EditText,
        etFlightTime: EditText,
        etFlightType: EditText,
        etClassType: EditText,
        etAircraft: EditText,
        etRegistration: EditText,
        etComment: EditText
    ): LogbookEntry? {
        val date = try {
            LocalDate.parse(etDate.text.toString().trim(), DATE_FORMAT)
        } catch (e: Exception) {
            return null
        }
        val from = etFrom.text.toString().trim().uppercase()
        val to = etTo.text.toString().trim().uppercase()
        if (from.isEmpty() || to.isEmpty()) return null

        val base = original.copy(
            date = date,
            fromAirport = from,
            toAirport = to,
            airline = etAirline.text.toString().trim().uppercase().ifEmpty { null },
            flightNumber = etFlightNumber.text.toString().trim().ifEmpty { null },
            distanceKm = etDistance.text.toString().trim().toIntOrNull(),
            flightMinutes = etFlightTime.text.toString().trim().toIntOrNull(),
            flightType = etFlightType.text.toString().trim().ifEmpty { null },
            classType = etClassType.text.toString().trim().ifEmpty { null },
            aircraftType = etAircraft.text.toString().trim().ifEmpty { null },
            registration = etRegistration.text.toString().trim().uppercase().ifEmpty { null },
            comment = etComment.text.toString().trim().ifEmpty { null }
        )
        return ImportProcessor.enrich(requireContext(), base)
    }

    private fun addField(
        container: LinearLayout,
        labelRes: Int,
        value: String?,
        inputType: Int,
        maxLength: Int = -1,
        multiLine: Boolean = false
    ): EditText {
        val ctx = requireContext()
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        val onSurfaceVariant = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        val outlineVariant = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOutlineVariant
        )

        container.addView(
            TextView(ctx).apply {
                text = getString(labelRes)
                textSize = 14f
                setTextColor(onSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(12), 0, dp(2)) }
            }
        )

        val edit = EditText(ctx).apply {
            setText(value.orEmpty())
            this.inputType =
                if (multiLine) inputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE else inputType
            textSize = 16f
            setTextColor(onSurface)
            backgroundTintList = ColorStateList.valueOf(outlineVariant)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setSingleLine(!multiLine)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (!multiLine) minHeight = dp(44)
            if (multiLine) {
                minLines = 3
                maxLines = 5
                gravity = android.view.Gravity.TOP
            }
            if (maxLength != -1) {
                filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            }
        }
        container.addView(edit)
        return edit
    }

    private fun upperCaseWatcher(): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            val upper = s.toString().uppercase()
            if (upper != s.toString()) s.replace(0, s.length, upper)
        }
    }

    private val dateMaskWatcher = object : TextWatcher {
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

    private fun implementData() {
        if (ImportSession.entries.isEmpty()) return
        val mode = ImportSession.mode ?: return
        val ready = ImportSession.entries.map { it.copy(id = null) }
        when (mode) {
            ImportMode.OVERWRITE -> LogbookRepository.replaceAll(ready)
            ImportMode.APPEND -> LogbookRepository.addEntries(ready)
        }
        ImportSession.reset()
        Toast.makeText(requireContext(), R.string.import_implement_done, Toast.LENGTH_SHORT).show()
        DashboardEvents.onPeriodChanged?.invoke()
        findNavController().navigateUp()
    }

    private fun highlight(
        card: com.google.android.material.card.MaterialCardView,
        label: TextView,
        active: Boolean
    ) {
        val primaryContainer = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorPrimaryContainer
        )
        val onPrimaryContainer = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer
        )
        val surfaceContainerLow = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorSurfaceContainerLow
        )
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        val outlineVariant = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOutlineVariant
        )

        card.setCardBackgroundColor(
            if (active) primaryContainer else surfaceContainerLow
        )
        card.strokeColor =
            if (active) primaryContainer else outlineVariant
        label.setTextColor(
            if (active) onPrimaryContainer else onSurface
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}