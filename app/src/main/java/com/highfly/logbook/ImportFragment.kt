package com.highfly.logbook

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentImportBinding
import java.time.format.DateTimeFormatter

class ImportFragment : LogbookFragment() {

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

        // Unter dem Inhalt bleibt scrollbarer Leerraum für die schwebende
        // Navigationsleiste.
        binding.importScroll.addBottomNavPadding()

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

    /**
     * Korrekturdialog für einen Konflikt-Eintrag. Zeigt nur die relevanten
     * Felder: Fluggesellschaft und Flugnummer in einer Reihe, darunter FROM
     * und TO als Textfelder nebeneinander und darunter zwei Kacheln im Stil
     * von "Neuer Flug", die die Codes groß zeigen. Ungültige Werte tragen ein
     * rotes X im Textfeld; oben rechts steht der Fortschritt "Eintrag x von y"
     * in der Akzentfarbe, darunter die Warnzeile mit rotem Dreieck und Grund.
     *
     * Unten rechts sitzt der fette Knopf "Prüfen": Erst wenn alle Werte ok
     * sind, wird er zu "Übernehmen & weiter" bzw. beim letzten Eintrag zu
     * "Übernehmen & beenden". Links daneben bleiben "Überspringen" und
     * "Schließen".
     */
    /**
     * Korrekturdialog für einen Konflikt-Eintrag. Oben im Header steht links
     * der Titel, rechts daneben "y von z" in der Akzentfarbe und ganz rechts
     * ein X (schließt erst nach Bestätigung). Darunter die Warnzeile mit rotem
     * Dreieck und Grund, dann Fluggesellschaft + Flugnummer in einer Reihe.
     * Die Flughafen stehen als Spalten: oben der Originalwert aus dem
     * Datensatz als Text (mit rotem X, wenn er ungültig war - so bleibt
     * sichtbar, was es war), darunter eine Kachel im Stil von "Neuer Flug" mit
     * leerem Eingabefeld für den eigenen Eintrag. Ganz unten ein voller
     * "Prüfen"-Button im Stil von "Flug speichern": Beim Klick schrumpft er
     * und dreht sich im Uhrzeigersinn (laden), danach wird das Drehbild zu
     * einem grünen Haken ("Neue Daten valide.") oder einem roten X
     * ("Neue Daten ungültig"). Bei validen Daten erscheint unten links der
     * fette Button "Übernehmen & weiter" bzw. "Übernehmen & beenden".
     */
    private fun showReviewDialog(index: Int) {
        val ctx = requireContext()
        val entry = ImportSession.entries.getOrNull(index) ?: return
        val total = ImportSession.conflictIndexes.size
        val position = ImportSession.conflictIndexes.indexOf(index) + 1
        val primary = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorPrimary
        )
        val errorColor = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorError
        )
        val onSurfaceVariant = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant
        )

        var dialog: AlertDialog? = null
        var saveButton: Button? = null
        var validated: LogbookEntry? = null
        var checking = false
        var resultShown = false
        var spin: ObjectAnimator? = null

        // Header: Titel links, "y von z" rechts in der Akzentfarbe, X ganz rechts.
        val closeX = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(onSurfaceVariant)
            contentDescription = getString(R.string.import_review_close)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                setMargins(dp(12), 0, 0, 0)
            }
        }
        val titleView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(12), dp(4))
        }
        titleView.addView(TextView(ctx).apply {
            text = getString(R.string.import_review_title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        })
        titleView.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        })
        titleView.addView(TextView(ctx).apply {
            text = getString(R.string.import_review_progress_short, position, total)
            textSize = 14f
            setTextColor(primary)
            setTypeface(null, Typeface.BOLD)
        })
        titleView.addView(closeX)

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
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        scroll.addView(container)

        // Warnzeile: rotes Warndreieck mit dem Grund dahinter.
        val warningText = TextView(ctx).apply {
            textSize = 13f
            setTextColor(errorColor)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(dp(8), 0, 0, 0) }
        }
        val warningRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_warning)
                setColorFilter(errorColor)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            })
            addView(warningText)
        }
        container.addView(warningRow)

        fun showReasons(reasons: List<String>) {
            warningRow.visibility = if (reasons.isEmpty()) View.GONE else View.VISIBLE
            warningText.text = reasons.joinToString("\n")
        }

        // Zeile 1: Fluggesellschaft und Flugnummer in einer Reihe.
        val airlineCell = addFieldCell(
            R.string.airline_label, entry.airline, InputType.TYPE_CLASS_TEXT, maxLength = 2
        )
        airlineCell.edit.addTextChangedListener(upperCaseWatcher())
        val flightNoCell = addFieldCell(
            R.string.flight_number_label, entry.flightNumber,
            InputType.TYPE_CLASS_NUMBER, maxLength = 6
        )
        container.addView(fieldRow(ctx, airlineCell.cell, flightNoCell.cell))

        // Zeile 2: Flughafen-Spalten - oben der Originalwert als Text (mit rotem
        // X bei ungültigem Datensatz), darunter die Kachel mit leerem Feld.
        fun originalInvalid(code: String, other: String): Boolean {
            val c = code.trim().uppercase()
            if (c.isBlank()) return true
            if (c == other.trim().uppercase()) return true
            return !ImportProcessor.isKnownAirport(ctx, c)
        }
        val fromCol = airportColumn(
            entry.fromAirport, R.string.airport_departure_label,
            originalInvalid(entry.fromAirport, entry.toAirport)
        )
        val toCol = airportColumn(
            entry.toAirport, R.string.airport_arrival_label,
            originalInvalid(entry.toAirport, entry.fromAirport)
        )
        fromCol.second.addTextChangedListener(upperCaseWatcher())
        toCol.second.addTextChangedListener(upperCaseWatcher())
        container.addView(fieldRow(ctx, fromCol.first, toCol.first))

        // Voller Prüf-Button im Stil von "Flug speichern".
        val saveBg = ContextCompat.getColor(ctx, R.color.button_save_bg)
        val checkButton = MaterialButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
            text = getString(R.string.import_review_check)
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            backgroundTintList = ColorStateList.valueOf(saveBg)
            cornerRadius = dp(16)
            minHeight = dp(56)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        container.addView(checkButton)

        fun candidate(): LogbookEntry {
            val from = fromCol.second.text.toString().trim().uppercase()
            val to = toCol.second.text.toString().trim().uppercase()
            return entry.copy(
                fromAirport = from,
                toAirport = to,
                airline = airlineCell.edit.text.toString().trim().uppercase().ifEmpty { null },
                flightNumber = flightNoCell.edit.text.toString().trim().ifEmpty { null }
            )
        }

        /** Rote X an den Eingabefeldern + Warnzeile live aus den Feldern. */
        fun updateMarks() {
            val from = fromCol.second.text.toString().trim().uppercase()
            val to = toCol.second.text.toString().trim().uppercase()
            val sameRoute = from.isNotEmpty() && from == to
            fun airportBad(code: String): Boolean =
                (code.isNotEmpty() && (!ImportProcessor.isKnownAirport(ctx, code) || sameRoute)) ||
                    (resultShown && code.isBlank())
            fromCol.third.visibility = if (airportBad(from)) View.VISIBLE else View.GONE
            toCol.third.visibility = if (airportBad(to)) View.VISIBLE else View.GONE
            airlineCell.errorIcon.visibility = if (
                !ImportProcessor.isKnownAirline(ctx, airlineCell.edit.text.toString().trim())
            ) View.VISIBLE else View.GONE
            showReasons(ImportProcessor.conflictReasons(ctx, candidate()))
        }

        /** Setzt Button und Save-Knopf nach einer Änderung wieder auf "Prüfen". */
        fun resetToCheckState() {
            if (!resultShown && !checking) return
            resultShown = false
            checking = false
            validated = null
            spin?.cancel()
            checkButton.animate().cancel()
            checkButton.rotation = 0f
            checkButton.scaleX = 1f
            checkButton.scaleY = 1f
            checkButton.icon = null
            checkButton.backgroundTintList = ColorStateList.valueOf(saveBg)
            checkButton.text = getString(R.string.import_review_check)
            checkButton.isEnabled = true
            saveButton?.visibility = View.GONE
        }

        fun refresh() {
            updateMarks()
            resetToCheckState()
        }

        fun save() {
            val updated = validated ?: return
            ImportSession.entries[index] = updated
            ImportSession.conflictIndexes.remove(index)
            render()
            dialog?.dismiss()
            if (ImportSession.conflictIndexes.isNotEmpty()) {
                showReviewDialog(ImportSession.conflictIndexes.first())
            } else {
                Toast.makeText(ctx, R.string.import_review_all_done, Toast.LENGTH_SHORT).show()
            }
        }

        fun finishCheck() {
            checking = false
            resultShown = true
            val reasons = ImportProcessor.conflictReasons(ctx, candidate())
            updateMarks()
            checkButton.isEnabled = true
            checkButton.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
            if (reasons.isEmpty()) {
                validated = ImportProcessor.enrich(ctx, candidate())
                showReasons(emptyList())
                checkButton.icon = AppCompatResources.getDrawable(ctx, R.drawable.ic_check)
                checkButton.iconTint = ColorStateList.valueOf(Color.WHITE)
                checkButton.backgroundTintList = ColorStateList.valueOf(saveBg)
                checkButton.text = getString(R.string.import_review_valid)
                val last = ImportSession.conflictIndexes.size <= 1
                saveButton?.visibility = View.VISIBLE
                saveButton?.text = getString(
                    if (last) R.string.import_review_save_exit else R.string.import_review_save
                )
                saveButton?.setOnClickListener { save() }
            } else {
                validated = null
                saveButton?.visibility = View.GONE
                checkButton.icon = AppCompatResources.getDrawable(ctx, R.drawable.ic_close)
                checkButton.iconTint = ColorStateList.valueOf(Color.WHITE)
                checkButton.backgroundTintList = ColorStateList.valueOf(errorColor)
                checkButton.text = getString(R.string.import_review_invalid_result)
            }
        }

        fun startCheck() {
            if (checking) return
            checking = true
            resultShown = false
            checkButton.isEnabled = false
            checkButton.text = ""
            checkButton.icon = AppCompatResources.getDrawable(ctx, R.drawable.ic_progress_circle)
            checkButton.iconTint = ColorStateList.valueOf(Color.WHITE)
            // Button klein machen und im Uhrzeigersinn drehen (laden).
            checkButton.animate().scaleX(0.35f).scaleY(0.35f).setDuration(250).start()
            val animator = ObjectAnimator.ofFloat(checkButton, View.ROTATION, 0f, 360f).apply {
                duration = 700
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                interpolator = LinearInterpolator()
            }
            spin = animator
            animator.start()
            // Drehung mindestens ~0,8 s laufen lassen, dann das Ergebnis zeigen.
            checkButton.postDelayed({
                if (dialog?.isShowing != true || !checking) return@postDelayed
                animator.cancel()
                val target = Math.ceil(checkButton.rotation.toDouble() / 360.0).toFloat() * 360f
                checkButton.animate().rotation(target).setDuration(150)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { finishCheck() }
                    .start()
            }, 800)
        }

        val refreshWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) { refresh() }
        }
        listOf(airlineCell.edit, flightNoCell.edit, fromCol.second, toCol.second).forEach {
            it.addTextChangedListener(refreshWatcher)
        }

        dialog = MaterialAlertDialogBuilder(ctx)
            .setCustomTitle(titleView)
            .setView(scroll)
            .setNeutralButton(R.string.import_review_skip, null)
            .setNegativeButton(R.string.import_review_save, null)
            .show()
        saveButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        saveButton.visibility = View.GONE
        saveButton.setTypeface(saveButton.typeface, Typeface.BOLD)
        closeX.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.import_review_close_confirm_title)
                .setMessage(R.string.import_review_close_confirm_message)
                .setPositiveButton(R.string.import_review_close) { _, _ -> dialog?.dismiss() }
                .setNegativeButton(R.string.discard_cancel, null)
                .show()
        }
        checkButton.setOnClickListener { startCheck() }
        refresh()
    }

    private class FieldCell(
        val cell: LinearLayout,
        val edit: EditText,
        val errorIcon: ImageView,
        var tileValue: TextView? = null
    )

    private fun addFieldCell(
        labelRes: Int,
        value: String?,
        inputType: Int,
        maxLength: Int
    ): FieldCell {
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
        val errorColor = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorError
        )

        val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cell.addView(TextView(ctx).apply {
            text = getString(labelRes)
            textSize = 14f
            setTextColor(onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(12), 0, dp(2)) }
        })
        val editRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val edit = EditText(ctx).apply {
            setText(value.orEmpty())
            this.inputType = inputType
            textSize = 16f
            setTextColor(onSurface)
            backgroundTintList = ColorStateList.valueOf(outlineVariant)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setSingleLine(true)
            minHeight = dp(44)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
        }
        val errorIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(errorColor)
            visibility = View.GONE
            contentDescription = getString(R.string.import_review_invalid_field_desc)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                setMargins(dp(6), 0, dp(4), 0)
            }
        }
        editRow.addView(edit)
        editRow.addView(errorIcon)
        cell.addView(editRow)
        return FieldCell(cell, edit, errorIcon)
    }

    /** Zwei Zellen nebeneinander in einer Reihe (je halbe Breite, 8 dp Abstand). */
    private fun fieldRow(ctx: Context, first: View, second: View): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(first.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = dp(8) }
            })
            addView(second.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
        }

    /**
     * Kachel im Stil von "Neuer Flug" (gefüllter grauer Grund, 16 dp Ecken,
     * Beschriftung oben, Wert groß mittig): zeigt den Flughafencode groß an.
     * Ein Tipp darauf fokussiert das zugehörige Textfeld.
     */
    /**
     * Eine Flughafen-Spalte: oben der Originalwert aus dem Datensatz als Text
     * (mit rotem X, wenn er ungültig war - so bleibt sichtbar, was es war),
     * darunter eine Kachel im Stil von "Neuer Flug" mit leerem Eingabefeld für
     * den eigenen Eintrag. Zurückgegeben: Spalte, Eingabefeld, rotes X am Feld.
     */
    private fun airportColumn(
        originalCode: String,
        labelRes: Int,
        originalInvalid: Boolean
    ): Triple<LinearLayout, EditText, ImageView> {
        val ctx = requireContext()
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        val onSurfaceVariant = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        val errorColor = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorError
        )

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val origRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        origRow.addView(TextView(ctx).apply {
            text = originalCode.ifBlank { getString(R.string.import_review_summary_unknown) }
            textSize = 15f
            setTextColor(onSurfaceVariant)
            setTypeface(null, Typeface.BOLD)
        })
        origRow.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(errorColor)
            visibility = if (originalInvalid) View.VISIBLE else View.GONE
            contentDescription = getString(R.string.import_review_invalid_field_desc)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        })
        column.addView(origRow)

        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_input_tile_filled)
            setPadding(dp(28), dp(10), dp(28), dp(12))
            minimumHeight = dp(72)
        }
        val labelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(TextView(ctx).apply {
            text = getString(labelRes)
            textSize = 12f
            setTextColor(onSurfaceVariant)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        val inputX = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(errorColor)
            visibility = View.GONE
            contentDescription = getString(R.string.import_review_invalid_field_desc)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        labelRow.addView(inputX)
        tile.addView(labelRow)
        val edit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 28f
            setTextColor(onSurface)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setSingleLine(true)
            setBackground(null)
            hint = getString(labelRes)
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(2), 0, 0)
        }
        tile.addView(edit)
        column.addView(tile)
        return Triple(column, edit, inputX)
    }

    private fun upperCaseWatcher(): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            val upper = s.toString().uppercase()
            if (upper != s.toString()) s.replace(0, s.length, upper)
        }
    }


    private fun implementData() {
        if (ImportSession.entries.isEmpty()) return
        val mode = ImportSession.mode ?: return
        val ready = ImportSession.entries.map { it.copy(id = null) }
        // Ab zwei Nutzern zuerst fragen, in wessen Logbuch die Daten kommen.
        // Der Import läuft immer auf dem echten Logbuch, nie auf der
        // Demodatenbank.
        val ids = Users.ids(requireContext())
        if (ids.size > 1) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.import_choose_user_title)
                .setItems(ids.map { Users.label(requireContext(), it) }.toTypedArray()) { _, which ->
                    confirmImport(mode, ids[which], ready)
                }
                .setNegativeButton(R.string.discard_cancel, null)
                .show()
        } else {
            confirmImport(mode, ids.first(), ready)
        }
    }

    /**
     * Überschreiben ersetzt wirklich alles: Die Ziel-Datenbank wird geleert
     * und die Passphrase DANGER ZONE muss getippt werden, bevor es losgeht.
     * Ergänzen fügt nur hinzu und verlangt keine Passphrase.
     */
    private fun confirmImport(mode: ImportMode, userId: Int, ready: List<LogbookEntry>) {
        if (mode != ImportMode.OVERWRITE) {
            performImport(mode, userId, ready)
            return
        }
        PassphraseConfirm.show(
            context = requireContext(),
            titleRes = R.string.import_overwrite_confirm_title,
            message = getString(R.string.import_overwrite_confirm_message),
            phrase = PassphraseConfirm.PHRASE_DANGER_ZONE,
            confirmRes = R.string.import_overwrite_confirm_action,
            onConfirmed = { performImport(mode, userId, ready) }
        )
    }

    private fun performImport(mode: ImportMode, userId: Int, ready: List<LogbookEntry>) {
        when (mode) {
            ImportMode.OVERWRITE -> LogbookRepository.replaceAllForUser(requireContext(), userId, ready)
            ImportMode.APPEND -> LogbookRepository.addEntriesForUser(requireContext(), userId, ready)
        }
        ImportSession.reset()
        Toast.makeText(requireContext(), R.string.import_implement_done, Toast.LENGTH_SHORT).show()
        DashboardEvents.onFilterChanged?.invoke()
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