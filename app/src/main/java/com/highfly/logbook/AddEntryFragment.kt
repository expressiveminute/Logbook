package com.highfly.logbook

import android.animation.ValueAnimator
import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.Selection
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.DatePicker
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentAddEntryBinding
import com.highfly.logbook.databinding.ItemBuddyChipBinding
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class AddEntryFragment : LogbookFragment() {

    private var _binding: FragmentAddEntryBinding? = null

    private val binding get() = _binding!!

    /** Die globale Rolle bestimmt Reisearten, Funktion und Layover. */
    private val role: Role get() = Settings.getRole(requireContext())

    private var selectedFlightTypeIndex: Int? = null
    private var selectedClassIndex: Int? = null
    private var selectedDeadheadIndex: Int? = null
    private var prefilling = false

    private var editingEntryId: Long = -1L
    private var returnActive = false

    /**
     * Die zuletzt aus der Historie eingesetzte Strecke. Nur sie darf auch
     * wieder entfernt werden, wenn sich die Flugnummer ändert – eigene
     * Eingaben in Abflug und Ankunft bleiben davon unberührt.
     */
    private var suggestedRoute: Route? = null

    /**
     * Läuft true, während der Vorschlag selbst schreibt: Die Flughafen-Watcher
     * dürfen das dann nicht als Tippen werten und den Vorschlag löschen.
     */
    private var writingSuggestedRoute = false

    /**
     * Soft-Input-Modus des Fensters, bevor das Formular ihn fuer die Dauer der
     * Bearbeitung versteckt. Wird in onDestroyView wiederhergestellt.
     */
    private var previousSoftInputMode = 0

    /**
     * Wird true, sobald der Speichern-Button im aktuellen Bearbeitungsdurchgang
     * sichtbar geworden ist. Verhindert, dass ein erneutes Sichtbarwerden nach
     * kurzem Löschen/Wieder-Eintippen eines Pflichtfeldes den Fokus aus dem
     * Eingabefeld reißt (Auto-Scroll zum Speichern-Button).
     */
    private var saveButtonRevealHandled = false

    /**
     * Die zuletzt automatisch vorbefüllte Layoverzeit. Nur dieser Wert darf bei
     * einer neuen Strecke aktualisiert werden; hat der Nutzer die Zahl selbst
     * geändert, bleibt seine Eingabe stehen.
     */
    private var autoLayoverHours: String? = null

    /**
     * Ob die Layover-Kachel gerade im angeklickten Zustand gezeigt wird (Wort
     * oben links in der Kerbe). null bis zum ersten Aufbau.
     */
    private var layoverActiveShown: Boolean? = null

    /** Ausgangs-Schriftgroesse und -farbe des mittigen Wortes "Layover". */
    private var layoverWordSize = 0f
    private var layoverWordColor = 0

    private var layoverHeaderAnimator: ValueAnimator? = null

    /**
     * Alle bisher eingetragenen Reisebuddy-Namen (häufigste zuerst), Grundlage
     * der Live-Vorschläge beim Eintippen im Reisebuddy-Feld. Wird einmalig
     * pro Seitenaufbau im Hintergrund geladen ([loadKnownBuddyNames]).
     */
    private var knownBuddyNames: List<String> = emptyList()

    /** Ob [knownBuddyNames] schon geladen ist. */
    private var buddyNamesLoaded = false

    /** Das gerade offene Vorschlags-Popup über einem Reisebuddy-Feld. */
    private var buddySuggestionsPopup: PopupWindow? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentAddEntryBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Unter dem Formular bleibt scrollbarer Leerraum für die schwebende
        // Navigationsleiste, damit die untersten Felder nicht hinter ihr
        // verschwinden.
        binding.scrollView.addBottomNavPadding()

        editingEntryId = requireArguments().getLong("entryId", -1L)

        // Beim Bearbeiten steht der Fokus sofort im Feld "Fluggesellschaft" und
        // wuerde aus eigener Kraft die Tastatur oeffnen. Deshalb ist die
        // Tastatur beim Oeffnen der Seite versteckt - sie erscheint erst
        // wieder, wenn der Nutzer aktiv in ein Feld tippt.
        val window = requireActivity().window
        previousSoftInputMode = window.attributes.softInputMode
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        binding.btnBack.setOnClickListener {
            confirmDiscard()
        }

        setupFlightTypeTiles()
        setupDeadheadTiles()
        setupClassTiles()
        setupAirportTiles()
        setupTileExamples()
        setupFlightCombinedWatcher()
        setupAircraftTypeFields()
        setupRegistrationUppercase()
        setupAirportAutoAdvance()
        setupFlightTimeConversion()
        setupDateField()
        setupReturnToggle()
        updateReturnUi()
        setupProgressiveReveal()
        setupOptionalSection()
        setupTravelBuddyChips()
        setupLayoverHours()
        setupSaveAndDiscard()
        setupCursorAtEndOnTap()

        updateLayoverVisibility()

        val editingEntry = LogbookRepository.getEntry(editingEntryId)
        if (editingEntry != null) {
            binding.tvEntryTitle.text = getString(R.string.edit_entry_title)
            prefill(editingEntry)
        } else {
            // Import per Bordkarten-QR-Code: Rohtext dekodieren und das
            // Formular damit vorbefüllen - speichern bleibt ein normaler
            // „Neuer Flug"-Vorgang.
            arguments?.getString("prefillQr")?.let { qr ->
                QrPayload.decode(qr)?.let { prefill(it) }
            }
        }
        refreshVisibility()

        // Der Soft-Input-Modus wirkt nur beim ersten Anzeigen des Fensters:
        // Die Tastatur der vorherigen Seite bliebe sonst stehen und landete
        // beim Vorbefuellen in einem der Felder. Einmalig loesen - sie
        // erscheint erst wieder, wenn der Nutzer selbst in ein Feld tippt.
        view.post {
            view.findFocus()?.clearFocus()
            hideKeyboard()
        }
    }

    private fun setupFlightTypeTiles() {
        reorderFlightTypeTiles()
        val tiles = orderedFlightTypeTiles()
        val labels = orderedFlightTypeLabels()

        SelectionHighlight.configure(binding.flightGroupFrame, binding.flightHighlight, tiles)

        tiles.forEachIndexed { visualIndex, tile ->
            tile.setOnClickListener { selectFlightType(flightTypeVisualOrder()[visualIndex]) }
        }
        updateFlightLabels(labels, null)
    }

    /**
     * Semantische Indizes -> Kacheln. Die sichtbare Reihenfolge kann je nach Rolle
     * abweichen: Die Besatzung waehlt On Duty, Deadhead, Dienstreise, Privat, der
     * Fluggast nur Privat und Dienstreise. Kacheln/Labels werden deshalb immer
     * ueber diese Zuordnung aufgeloest.
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
        if (role.isCrew) {
            intArrayOf(ON_DUTY_INDEX, DEADHEAD_INDEX, DUTY_TRAVEL_INDEX, PRIVATE_INDEX)
        } else {
            intArrayOf(PRIVATE_INDEX, DUTY_TRAVEL_INDEX)
        }

    private fun orderedFlightTypeTiles(): List<View> =
        flightTypeVisualOrder().map { flightTypeTilesByIndex()[it]!! }

    private fun orderedFlightTypeLabels(): List<TextView> =
        flightTypeVisualOrder().map { flightTypeLabelsByIndex()[it]!! }

    private fun reorderFlightTypeTiles() {
        val container = binding.flightTypeContainer
        val order = flightTypeVisualOrder()
        // Dem Fluggast fehlen On Duty und Deadhead ganz: Ihre Kacheln bleiben
        // im Layout, werden aber ausgeblendet und belegen keinen Platz mehr.
        flightTypeTilesByIndex().forEach { (index, tile) ->
            tile.visibility = if (order.contains(index)) View.VISIBLE else View.GONE
        }
        val ordered = orderedFlightTypeTiles()
        for (tile in ordered) container.removeView(tile)
        for (tile in ordered) container.addView(tile)
    }

    private fun setupDeadheadTiles() {
        val tiles = deadheadTiles()
        val labels = deadheadLabels()

        SelectionHighlight.configure(binding.deadheadGroupFrame, binding.deadheadHighlight, tiles)

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
        SelectionHighlight.move(
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
        val isDeadhead = role.isCrew && selectedFlightTypeIndex == DEADHEAD_INDEX
        binding.deadheadSection.visibility = if (isDeadhead) View.VISIBLE else View.GONE
        if (isDeadhead && selectedDeadheadIndex == null) {
            selectDeadheadType(0)
        }
    }

    /**
     * Das Feld "Funktion" erscheint nur bei den Reisearten On Duty und Deadhead
     * und wird automatisch mit der in den Einstellungen gewählten Crew-Funktion
     * befüllt (sofern es noch leer ist). Bei aktivem Rückflug hat jede Richtung
     * ihr eigenes Feld, weil daraus auch zwei Einträge werden.
     */
    private fun updateFunctionVisibility() {
        val show = role.showsFunction && (
            selectedFlightTypeIndex == ON_DUTY_INDEX ||
                selectedFlightTypeIndex == DEADHEAD_INDEX
            )
        if (returnActive) {
            binding.tileHinflugFunction.visibility = if (show) View.VISIBLE else View.GONE
            binding.tileRueckflugFunction.visibility = if (show) View.VISIBLE else View.GONE
            if (show && !prefilling) {
                fillFunctionFromSettings(binding.etHinflugFunction)
                fillFunctionFromSettings(binding.etRueckflugFunction)
            }
            return
        }
        binding.functionSection.visibility = if (show) View.VISIBLE else View.GONE
        if (show && !prefilling) fillFunctionFromSettings(binding.etFunction)
    }

    private fun fillFunctionFromSettings(field: EditText) {
        if (field.text.isNullOrBlank()) {
            field.setText(Settings.selectedCrewFunctionLabel(requireContext()))
        }
    }

    private fun selectFlightType(index: Int) {
        selectedFlightTypeIndex = index
        val tiles = orderedFlightTypeTiles()
        val labels = orderedFlightTypeLabels()
        val visualIndex = flightTypeVisualOrder().indexOf(index)
        SelectionHighlight.move(
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
        val isPrivate = selectedFlightTypeIndex == PRIVATE_INDEX
        val visible = role.showsLayover && !isPrivate
        // Die Layover-Kachel (graues Panel mit Auswahlfeld) folgt der Rollen-
        // Sichtbarkeit; ob das Auswahlfeld darin mittig steht oder angeklickt
        // in der Kerbe oben links, regelt updateLayoverHoursVisibility().
        binding.layoverPanel.visibility = if (visible) View.VISIBLE else View.GONE
        updateLayoverHoursVisibility()
    }

    /**
     * Wie lange ein Layover gedauert hat, weiss nur der Nutzer - und es
     * interessiert nur, wenn der Eintrag ueberhaupt ein Layover ist. Das Feld
     * erscheint deshalb erst, sobald oben bei "Ankunft" das Kästchen
     * "Layover" angeklickt ist, und verschwindet wieder, wenn es abgehaakt
     * wird. Der bei "Flugdistanz" und "Flugzeit" benutzte Weg ueber
     * [refreshVisibility] passt hier nicht, weil die Sichtbarkeit nicht am
     * Textfeld haengt.
     */
    private fun setupLayoverHours() {
        binding.cbLayover.setOnCheckedChangeListener { _, _ ->
            updateLayoverHoursVisibility()
        }
        // Tippen auf die Auswahlfeld-Zeile (neben Hacken und Wort) klappt das
        // Kaestchen um; im angeklickten Zustand (Zeile weg) uebernimmt der
        // Header den Tipp und klappt zurueck. Das kleine X oben rechts entfernt
        // das Layover wieder, falls man sich verklickt hat.
        binding.layoverCenterRow.setOnClickListener { binding.cbLayover.toggle() }
        binding.layoverHeader.setOnClickListener { binding.cbLayover.toggle() }
        binding.btnLayoverClear.setOnClickListener { binding.cbLayover.isChecked = false }
        setupLayoverHoursUnit(binding.etLayoverHours, binding.tvLayoverHoursUnit)
        setupLayoverHoursUnit(binding.etHinflugLayoverHours, binding.tvHinflugLayoverHoursUnit)
        setupLayoverHoursQuickSelect()
    }

    /**
     * Die vier Vorschlags-Kacheln (14/24/48/72 h) unter dem Layoverzeit-Feld:
     * Ein Tipp trägt die Zahl in das Feld ein. Das Feld bleibt danach händisch
     * bearbeitbar - es wird nicht erneut vorbefüllt, sobald es Text trägt.
     */
    private fun setupLayoverHoursQuickSelect() {
        listOf(
            binding.tileLayoverHours14 to 14,
            binding.tileLayoverHours24 to 24,
            binding.tileLayoverHours48 to 48,
            binding.tileLayoverHours72 to 72
        ).forEach { (tile, hours) ->
            tile.contentDescription = getString(R.string.layover_hours_quick_desc, hours)
            tile.setOnClickListener {
                binding.etLayoverHours.setText(hours.toString())
            }
        }
    }

    private fun setupLayoverHoursUnit(input: EditText, unit: View) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                unit.visibility =
                    if (s.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        })
    }

    /**
     * Bei aktivem Rückflug wandert die Layoverlaenge in die Spalte "Hinflug" -
     * das Kästchen "Layover" steht beim Ankunftsfeld des Hinflugs.
     */
    private fun updateLayoverHoursVisibility() {
        // Ob die Kachel dem Nutzer angeboten wird, haengt an der Panel-Sichtbarkeit
        // (Rolle), nicht am Kaestchen selbst - das ist im angeklickten Zustand
        // ja gerade ausgeblendet.
        val layoverVisible = binding.layoverPanel.visibility == View.VISIBLE
        val active = layoverVisible && binding.cbLayover.isChecked
        // Ohne Rueckflug steht das Laenge-Feld in der Layover-Kachel unter der
        // Beschriftung, mit Rueckflug in der Spalte des Hinflugs.
        binding.layoverHoursSection.visibility =
            if (active && !returnActive) View.VISIBLE else View.GONE
        // Die vier Vorschlags-Kacheln (14/24/48/72 h) stehen darunter in der
        // Kachel und oeffnen sich zusammen mit dem Laenge-Feld.
        binding.layoverHoursQuickSection.visibility =
            if (active && !returnActive) View.VISIBLE else View.GONE
        binding.tileHinflugLayoverHours.visibility =
            if (active && returnActive) View.VISIBLE else View.GONE
        // Ist das Layover-Kaestchen an, verbindet ein schmaler Strich (oben und
        // unten breiter, in der Kachel-Hintergrundfarbe) die Mitte des To-Felds
        // mit der Mitte des Layover-Panels. Der Strich fuellt den 8-dp-Spalt,
        // deshalb faellt der obere Panel-Abstand dann weg.
        binding.layoverConnector.visibility = if (active) View.VISIBLE else View.GONE
        val panelParams = binding.layoverPanel.layoutParams as? ViewGroup.MarginLayoutParams
        if (panelParams != null) {
            val desiredTop = if (active) 0 else (8 * resources.displayMetrics.density).roundToInt()
            if (panelParams.topMargin != desiredTop) {
                panelParams.topMargin = desiredTop
                binding.layoverPanel.layoutParams = panelParams
            }
        }
        if (active) {
            prefillLayoverHours()
        }
        // Das kleine X oben rechts steht nur, wenn das Layover angeklickt ist.
        binding.btnLayoverClear.visibility = if (active) View.VISIBLE else View.GONE
        // Wechsel zwischen Auswahlfeld (mittig) und angeklicktem Zustand (Wort
        // oben links in der Kerbe); beim ersten Aufbau und beim Vorbefuellen
        // ohne Animation direkt in den Endzustand.
        if (active != layoverActiveShown) {
            val first = layoverActiveShown == null
            layoverActiveShown = active
            applyLayoverHeaderState(active, animate = !prefilling && !first)
        }
        // Nur loeschen, wenn das Kaestchen sichtbar und nicht angeklickt ist.
        // Ein ausgeblendetes Feld (Fluggast) bewahrt seinen Wert, damit ein
        // vorhandener Eintrag beim Speichern unveraendert bleibt.
        if (layoverVisible && !binding.cbLayover.isChecked) {
            binding.etLayoverHours.text?.clear()
            binding.etHinflugLayoverHours.text?.clear()
            autoLayoverHours = null
        }
    }

    /** Mitte von [view] waagerecht, umgerechnet auf den Vorfahren [ancestor]. */
    private fun centerXIn(view: View, ancestor: View): Float {
        var x = view.left + view.width / 2f
        var v = view.parent as? View
        while (v != null && v !== ancestor) {
            x += v.left
            v = v.parent as? View
        }
        return x
    }

    /** Mitte von [view] senkrecht, umgerechnet auf den Vorfahren [ancestor]. */
    private fun centerYIn(view: View, ancestor: View): Float {
        var y = view.top + view.height / 2f
        var v = view.parent as? View
        while (v != null && v !== ancestor) {
            y += v.top
            v = v.parent as? View
        }
        return y
    }

    /**
     * Schaltet die Layover-Kachel zwischen Auswahlfeld (mittig in der Kachel)
     * und angeklicktem Zustand um. Beim Anklicken wandert das Wort "Layover"
     * animiert von der Mitte nach oben links in den Rahmen der Kachel - Position,
     * Schriftgroesse und Farbe laufen dabei zusammen wie bei den uebrigen
     * Feldern (InputTile) - und das Kaestchen blendet parallel aus. Beim
     * Abwählen klappt alles ohne Animation zurueck in die Mitte.
     */
    private fun applyLayoverHeaderState(active: Boolean, animate: Boolean) {
        val row = binding.layoverCenterRow
        val word = binding.tvLayoverLabel
        val notch = binding.tvLayoverNotch
        val check = binding.cbLayover
        if (layoverWordSize == 0f) {
            layoverWordSize = word.paint.textSize
            layoverWordColor = word.currentTextColor
        }
        layoverHeaderAnimator?.cancel()
        if (!active) {
            // Zurueck in die Mitte: Kaestchen sichtbar, Wort mittig und gross.
            notch.visibility = View.GONE
            notch.alpha = 1f
            row.visibility = View.VISIBLE
            check.visibility = View.VISIBLE
            check.alpha = 1f
            word.translationX = 0f
            word.translationY = 0f
            word.setTextSize(TypedValue.COMPLEX_UNIT_PX, layoverWordSize)
            word.setTextColor(layoverWordColor)
            return
        }
        if (!animate) {
            row.visibility = View.GONE
            check.visibility = View.GONE
            notch.visibility = View.VISIBLE
            notch.alpha = 1f
            return
        }
        // Kerbe schon sichtbar, aber transparent, damit sie vermessen wird und
        // ihre Zielposition bekannt ist.
        notch.visibility = View.VISIBLE
        notch.alpha = 0f
        binding.layoverHeader.doOnPreDraw {
            val header = binding.layoverHeader
            val wordCx = centerXIn(word, header)
            val wordCy = centerYIn(word, header)
            val targetCx = centerXIn(notch, header)
            val targetCy = centerYIn(notch, header)
            val dx = targetCx - wordCx
            val dy = targetCy - wordCy
            val fromSize = layoverWordSize
            val toSize = notch.paint.textSize
            val fromColor = layoverWordColor
            val toColor = notch.currentTextColor
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { step ->
                    val p = step.animatedValue as Float
                    word.translationX = dx * p
                    word.translationY = dy * p
                    word.setTextSize(TypedValue.COMPLEX_UNIT_PX, fromSize + (toSize - fromSize) * p)
                    word.setTextColor(ColorUtils.blendARGB(fromColor, toColor, p))
                    check.alpha = 1f - p
                }
                doOnEnd {
                    // Das Wort sitzt jetzt genau auf der Kerbe: nahtloser
                    // Uebergang auf die Kerbe-Beschriftung, Kaestchen weg.
                    row.visibility = View.GONE
                    check.visibility = View.GONE
                    check.alpha = 1f
                    notch.alpha = 1f
                }
            }
            layoverHeaderAnimator = animator
            animator.start()
        }
    }

    /**
     * Fuellt die Layoverzeit passend zur eingetragenen Strecke vor: Sonderziele
     * zuerst (MIA 24 h, PEK 48 h), sonst nach Entfernung - unter 2000 km 14 h,
     * bis 8000 km 24 h, darueber 48 h. Nur ein leeres Feld bzw. der zuletzt
     * automatisch eingetragene Wert wird geschrieben; eine eigene Eingabe des
     * Nutzers bleibt stehen und wird gespeichert.
     */
    private fun prefillLayoverHours() {
        if (prefilling) return
        if (!binding.cbLayover.isChecked) return
        val target = if (returnActive) binding.etHinflugLayoverHours else binding.etLayoverHours
        val current = target.text?.toString()
        if (!current.isNullOrEmpty() && current != autoLayoverHours) return
        val from = binding.etAirportFrom.text?.toString()?.trim().orEmpty()
        val to = binding.etAirportTo.text?.toString()?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty() || from == to) return
        val a = AirportData.location(requireContext(), from) ?: return
        val b = AirportData.location(requireContext(), to) ?: return
        val km = GeoMath.distanceKm(a, b).roundToInt()
        val hours = when {
            to.uppercase() == "MIA" -> 24
            to.uppercase() == "PEK" -> 48
            km < 2000 -> 14
            km <= 8000 -> 24
            else -> 48
        }
        target.setText(hours.toString())
        autoLayoverHours = hours.toString()
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
        if (prefill && !prefilling && binding.etFlightCombined.text.isNullOrBlank()) {
            val airline = Settings.getAirline(requireContext())
            if (airline.isNotBlank()) {
                binding.etFlightCombined.setText(airline)
            }
        }
    }

    private fun selectClass(index: Int) {
        selectedClassIndex = index
        val tiles = classTiles()
        val classColors = ClassColorSchemes.colorsFor(Settings.getClassScheme(requireContext()))
        SelectionHighlight.move(
            binding.classGroupFrame,
            binding.classHighlight,
            tiles,
            index,
            ContextCompat.getColor(requireContext(), classColors[index])
        )
        updateClassLabels(classLabels(), index)
        refreshVisibility()
    }

    /**
     * Tippt man auf die schon gewählte Reiseklasse, ist sie wieder abgewählt:
     * Die Reiseklasse ist freiwillig, und ein Versehen soll sich nicht durch die
     * ganze Formularbedienung ziehen.
     */
    private fun toggleClass(index: Int) {
        if (selectedClassIndex == index) clearClass() else selectClass(index)
    }

    private fun clearClass() {
        selectedClassIndex = null
        val highlight = binding.classHighlight
        highlight.animate().cancel()
        highlight.visibility = View.GONE
        highlight.background = null
        updateClassLabels(classLabels(), null)
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
        SelectionHighlight.configure(binding.classGroupFrame, binding.classHighlight, classTiles())
        // Ohne Reiseklasse gibt es nichts zu markieren, deshalb startet der
        // Rahmen ausgeblendet und bekommt erst beim Tippen eine Farbe.
        binding.classHighlight.visibility = View.GONE

        classTiles().forEachIndexed { index, tile ->
            tile.setOnClickListener { toggleClass(index) }
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

        // Sitzplatz eintragen (nur bei Privat mit Reiseklasse sichtbar).
        binding.etSeat.setText(entry.seat.orEmpty())

        // Mit Leerzeichen, damit das Kästchen wieder in beide Felder zerlegbar
        // bleibt - ohne Trenner ist die Grenze beim Speichern nicht erkennbar.
        binding.etFlightCombined.setText(FlightCombined.format(entry.airline, entry.flightNumber))
        binding.etDate.setText(entry.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))

        binding.airportSection.visibility = View.VISIBLE
        binding.etAirportFrom.setText(entry.fromAirport)
        binding.etAirportTo.setText(entry.toAirport)

        binding.etDistance.setText(entry.distanceKm?.toString().orEmpty())
        binding.etFlightTime.setText(entry.flightMinutes?.toString().orEmpty())
        binding.cbLayover.isChecked = entry.layover
        // Erst nach dem Kästchen setzen: Der Listener blendet das Feld aus und
        // leert es, solange kein Layover angeklickt ist.
        binding.etLayoverHours.setText(entry.layoverHours?.toString().orEmpty())

        binding.etAircraftType.setText(entry.aircraftType)
        binding.etRegistration.setText(entry.registration)

        binding.etFunction.setText(entry.function)
        for (name in TravelBuddyStats.parse(entry.travelBuddy)) {
            addTravelBuddyChip(binding.buddyChips, name)
        }
        binding.etComment.setText(entry.comment)

        val hasOptional = entry.layoverHours != null || listOf(
            entry.aircraftType,
            entry.registration,
            entry.comment,
            entry.function,
            entry.travelBuddy
        ).any { !it.isNullOrBlank() }
        if (hasOptional) toggleOptionalBody(expanded = true)
        updateAirportCode(
            entry.fromAirport, binding.etAirportFrom, binding.ivAirportCheckFrom,
        )
        updateAirportCode(
            entry.toAirport, binding.etAirportTo, binding.ivAirportCheckTo,
        )
        prefilling = false
    }

    /**
     * Die Beschriftung der Auswahlkacheln folgt dem Ton der Platzhalterzeile der
     * Eingabekacheln: Ungewählt steht sie genauso abgedunkelt, damit beide
     * Kachelarten im Formular gleich zurückhaltend wirken. Gewählt bleibt sie
     * weiß, weil sie dann auf dem farbigen Feld liegt und sich sonst nicht mehr
     * vom Untergrund abhebt.
     */
    private fun restingSelectionColor(): Int = InputTile.hintColor(binding.root)

    private fun updateFlightLabels(labels: List<TextView>, selectedIndex: Int?) {
        val resting = restingSelectionColor()
        labels.forEachIndexed { index, label ->
            label.setTextColor(if (index == selectedIndex) Color.WHITE else resting)
        }
    }

    private fun updateClassLabels(labels: List<TextView>, selectedIndex: Int?) {
        val resting = restingSelectionColor()
        labels.forEachIndexed { index, label ->
            label.setTextColor(if (index == selectedIndex) Color.WHITE else resting)
        }
        binding.ivClassJump.imageTintList = ColorStateList.valueOf(
            if (selectedIndex == CLASS_JUMP_INDEX) Color.WHITE else resting
        )
    }

    /**
     * Die Eingabekachel, in der ein Feld liegt. Beschriftung und Beispiel der
     * Kachel gehören nicht dem Feld, deshalb wird die Kachel von unten herauf
     * gesucht, auch über eine Gruppe hinweg wie bei Wert und Einheit. So
     * braucht keine Kachel eine eigene id im Layout.
     */
    private fun inputTile(field: View): InputTile? {
        var parent = field.parent
        while (parent is View) {
            if (parent is InputTile) return parent
            parent = parent.parent
        }
        return null
    }

    /**
     * Abflug und Ankunft stehen ohne Rahmen und ohne Beschriftung da: Die beiden
     * Codes tragen die Richtung schon allein, zusammen mit dem Pfeil dazwischen,
     * und ein Rahmen um drei Buchstaben herum wäre nur eine Kiste mehr. Die
     * Kacheln zeigen deshalb dauerhaft ihr Feld, eine leere weist ihren
     * Beispieltext darin aus.
     */
    private fun setupAirportTiles() {
        inputTile(binding.etAirportFrom)?.setBare(true)
        inputTile(binding.etAirportTo)?.setBare(true)
    }

    /**
     * Beispiele fuer die Platzhalterzeile der leeren Kacheln: Solange nichts
     * getippt ist, steht da die Beschriftung, bei einzelnen Feldern mit einem
     * Zusatz in Klammern. Angezeigt werden nur Bedienhinweise, keine Beispiel-
     * werte - die stehen stattdessen weiterhin als Hinweis im Feld. Das
     * Reisebuddy-Feld traegt den Hinweis auf die Bestaetigung mit Enter, weil
     * dort ein Name eingetragen wird, der gar nicht geraten werden kann. Die
     * beiden Kommentar bekommen nichts, weil es dort nichts zu zeigen gibt.
     */
    private fun setupTileExamples() {
        inputTile(binding.etTravelBuddy)?.setExample(getString(R.string.buddy_confirm_example))
        inputTile(binding.etHinflugTravelBuddy)?.setExample(getString(R.string.buddy_confirm_example))
        inputTile(binding.etRueckflugTravelBuddy)?.setExample(getString(R.string.buddy_confirm_example))
    }

    private fun setupFlightCombinedWatcher() {
        setupCombinedWatcher(binding.etFlightCombined) {
            prefillRegistration()
            prefillRouteFromHistory()
        }
        // Im Rückflugskästchen wird nur die Nummer gepflegt. Abflug und Ankunft
        // stehen weiterhin für den Hinflug und werden hier bewusst nicht
        // nachgeschlagen, sonst nähme die Rückflugsnummer die Strecke mit.
        setupCombinedWatcher(binding.etRueckflugFlight) {}
    }

    /**
     * Beide Kästchen - Hinflug und Rückflug - trennen Fluggesellschaft und
     * Flugnummer beim Tippen mit einem Leerzeichen und schreiben die ersten
     * beiden Zeichen gross. [afterNormalize] laeuft danach, also mit dem Text,
     * der auch gespeichert wird.
     */
    private fun setupCombinedWatcher(field: EditText, afterNormalize: () -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val normalized = FlightCombined.normalizeTyped(s.toString())
                if (normalized != s.toString()) {
                    s.replace(0, s.length, normalized)
                    field.setSelection(normalized.length)
                }
                val (capitalized, caret) = FlightCombined.capitalizeTyped(s.toString())
                if (capitalized != s.toString()) {
                    s.replace(0, s.length, capitalized)
                    field.setSelection(caret)
                }
                afterNormalize()
            }
        })
    }

/**
 * Abflug und Ankunft gehören immer zum Hinflug: Sie werden aus der Nummer im
 * linken Kästchen nachgeschlagen, nie aus der des Rückflugs. Die Strecke folgt
 * der Nummer, solange man tippt, und zeigt immer nur den exakten Treffer dazu –
 * trifft sie später nicht mehr, verschwindet der Vorschlag wieder. Eigene
 * Eingaben werden nicht überschrieben.
 */
private fun prefillRouteFromHistory() {
    if (editingEntryId >= 0) return
    val (airline, flightNumber) = combinedFlight()
    val action = routePrefillAction(
        LogbookRepository.getFlownEntries(),
        airline,
        flightNumber,
        currentRoute(),
        suggestedRoute,
    )
    when (action) {
        RoutePrefillAction.Keep -> return
        RoutePrefillAction.Clear -> clearSuggestedRoute()
        is RoutePrefillAction.Fill -> fillSuggestedRoute(action.route)
    }
}

/** Abflug und Ankunft, wie sie gerade stehen – leer, wenn beide leer sind. */
private fun currentRoute(): Route? {
    val from = binding.etAirportFrom.text?.toString()?.trim().orEmpty()
    val to = binding.etAirportTo.text?.toString()?.trim().orEmpty()
    if (from.isEmpty() && to.isEmpty()) return null
    return Route(from, to)
}

/**
 * Setzt die gefundene Strecke und merkt sie sich als eigenen Vorschlag.
 * Distanz und Flugzeit gehörten zur bisherigen Strecke und werden vor dem
 * Ersetzen entfernt – für die neue Strecke spielt `autoFillRouteData` sie
 * wieder ein.
 */
private fun fillSuggestedRoute(route: Route) {
    if (currentRoute() != null) {
        binding.etDistance.setText("")
        binding.etFlightTime.setText("")
    }
    writingSuggestedRoute = true
    binding.etAirportFrom.setText(route.from)
    binding.etAirportTo.setText(route.to)
    writingSuggestedRoute = false
    suggestedRoute = route
}

/**
 * Nimmt den eigenen Vorschlag zurück, weil die Flugnummer nicht mehr passt.
 * Distanz und Flugzeit gehören zur Strecke und verschwinden mit ihr.
 */
private fun clearSuggestedRoute() {
    suggestedRoute = null
    writingSuggestedRoute = true
    binding.etAirportFrom.setText("")
    binding.etAirportTo.setText("")
    binding.etDistance.setText("")
    binding.etFlightTime.setText("")
    writingSuggestedRoute = false
}

    private fun setupAircraftTypeFields() {
        setupAircraftTypeField(binding.etAircraftType, binding.etRegistration)
        setupAircraftTypeField(binding.etHinflugAircraft, binding.etHinflugRegistration)
        setupAircraftTypeField(binding.etRueckflugAircraft, binding.etRueckflugRegistration)
    }

    /**
     * Registrierung und Sitzplatz werden beim Tippen grossgeschrieben, damit
     * "13a" im Feld sofort als "13A" steht (das Layout fordert das schon per
     * inputType, manche Tastaturen ignorieren die Flagge aber).
     */
    private fun setupRegistrationUppercase() {
        setupUppercaseWatcher(binding.etRegistration)
        setupUppercaseWatcher(binding.etHinflugRegistration)
        setupUppercaseWatcher(binding.etRueckflugRegistration)
        setupUppercaseWatcher(binding.etSeat)
    }

    /**
     * Tippt man in ein Feld des Formulars, steht der Cursor immer hinten
     * hinter dem bisherigen Text - beim Bearbeiten eines Eintrags stehen die
     * Felder schon voll, und ein Klick mitten hinein soll nicht mitten im
     * Text landen.
     *
     * Das darf nicht ueber einen OnClickListener am Feld passieren: Die Kachel
     * prueft an ihrem eigenen Klick `field.performClick()` und bricht frueh ab,
     * sobald das Feld einen Listener traegt (so oeffnet das Datum seinen
     * Kalender). Wuerde jedes Feld einen Listener bekommen, kaeme der Fokus
     * bei leeren Feldern (Flugnummer, Sitzplatz) nie mehr an. Deshalb lauert
     * hier ein Touch-Listener: Beim Loslassen eines einfachen Tipps (ohne
     * bestehende Markierung, damit Ziehen zum Markieren von Text erhalten
     * bleibt) wird der Cursor gepostet ans Feldende gesetzt - nach der
     * Touch-Behandlung, damit es gegen die Cursorposition an der Tippstelle
     * gewinnt. Die Datumsfelder sind nicht in der Liste, sie behandelt
     * [setupDateEditText] selbst (ihr Klick oeffnet den Kalender).
     */
    private fun setupCursorAtEndOnTap() {
        listOf(
            binding.etSeat,
            binding.etFlightCombined,
            binding.etRueckflugFlight,
            binding.etAirportFrom,
            binding.etAirportTo,
            binding.etDistance,
            binding.etFlightTime,
            binding.etLayoverHours,
            binding.etHinflugLayoverHours,
            binding.etAircraftType,
            binding.etRegistration,
            binding.etHinflugAircraft,
            binding.etHinflugRegistration,
            binding.etRueckflugAircraft,
            binding.etRueckflugRegistration,
            binding.etFunction,
            binding.etHinflugFunction,
            binding.etRueckflugFunction,
            binding.etTravelBuddy,
            binding.etHinflugTravelBuddy,
            binding.etRueckflugTravelBuddy,
            binding.etComment,
            binding.etHinflugComment,
            binding.etRueckflugComment
        ).forEach { field ->
            field.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val text = field.text
                    val start = text?.let(Selection::getSelectionStart) ?: -1
                    val end = text?.let(Selection::getSelectionEnd) ?: -1
                    if (start == end) {
                        field.post { field.setSelection(field.text?.length ?: 0) }
                    }
                }
                false
            }
        }
    }

    private fun setupUppercaseWatcher(editText: EditText) {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val upper = s.toString().uppercase()
                if (upper != s.toString()) {
                    s.replace(0, s.length, upper)
                    // Der Cursor bleibt hinten im Feld.
                    editText.setSelection(upper.length)
                }
            }
        })
    }

    private fun setupAircraftTypeField(aircraft: EditText, registration: EditText) {
        aircraft.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            // Nur beim Tippen (Einfuegen) wird ergaenzt; beim Loeschen kaempft
            // die Ergaenzung sonst gegen den Nutzer ("A350-900" auf "A350-9"
            // zurueckloeschen wuerde sofort wieder zu "A350-900" auffuellen).
            private var deleting = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                deleting = count > after
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (isFormatting) return
                val completion = if (deleting) null else aircraftCompletion(s.toString())
                if (completion != null) {
                    val (text, isFinal) = completion
                    isFormatting = true
                    s.replace(0, s.length, text)
                    isFormatting = false
                    // Der Cursor steht danach immer hinten, bereit fuer die
                    // naechste Ziffer (z. B. die Variante hinter dem Strich).
                    aircraft.setSelection(text.length)
                    prefillRegistration(aircraft, registration)
                    // Der Sprung zur Registrierung ist ein Bedienhinweis beim
                    // Tippen und gilt nur, wenn der Typ fertig ist - bei
                    // "A350-" fehlt die Variante noch. Beim Vorbefuellen eines
                    // gespeicherten Eintrags steht der Fokus woanders und die
                    // Tastatur bleibt zu.
                    if (isFinal && aircraft.hasFocus()) {
                        focusAndShowKeyboard(registration)
                    }
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

    /**
     * Ergaenzung fuer einen getippten Anfang als Paar aus Zieltext und "fertig":
     * Erst greift die Variante hinter dem Strich ("A350-9" -> "A350-900",
     * "A350-1" -> "A350-1000"), dann die Familie ("A35" -> "A350-"). Bei "A350-"
     * ist der Typ noch nicht fertig - die Varianten-Ziffer fehlt noch -, deshalb
     * darf der Fokus dann noch nicht zur Registrierung springen.
     */
    private fun aircraftCompletion(input: String): Pair<String, Boolean>? {
        val prefix = input.trim().uppercase()
        if (prefix.length < 3) return null
        AIRCRAFT_VARIANT_AUTOCOMPLETE[prefix]?.let { return it to true }
        return AIRCRAFT_AUTOCOMPLETE[prefix]
    }

    /**
     * Normalisiert den Flugzeugtyp so, dass nur der erste Buchstabe sicher
     * groß steht; alles danach bleibt wie getippt (klein oder groß), z. B.
     * "A320", "B737", "ATR72" oder "Atr72".
     */
    private fun formatAircraftType(input: String): String {
        if (input.isEmpty()) return input
        return input.first().uppercaseChar() + input.substring(1)
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

    private fun airlineIsLh(): Boolean {
        val (airline, _) = combinedFlight()
        return airline.equals("LH", ignoreCase = true)
    }

    private fun setupAirportAutoAdvance() {
        // Drei Zeichen, oder vier, wenn der Code mit X beginnt (geschlossener
        // Flughafen wie "XMUC" für München-Riem). Der Filter ersetzt die reine
        // Längengrenze aus dem Layout, weil die vom ersten Zeichen abhängt.
        val lengthFilter = airportCodeLengthFilter()
        binding.etAirportFrom.filters = arrayOf(lengthFilter)
        binding.etAirportTo.filters = arrayOf(lengthFilter)

        binding.etAirportFrom.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                // Selbst getippte Strecke: Der Historievorschlag darf sie dann
                // weder ersetzen noch wieder entfernen.
                if (!writingSuggestedRoute) suggestedRoute = null
                val upper = s.toString().uppercase()
                if (upper != s.toString()) {
                    s.replace(0, s.length, upper)
                    return
                }
                updateAirportCode(
                    s.toString(), binding.etAirportFrom, binding.ivAirportCheckFrom,
                )
                // Nur beim eigenen Tippen wechselt das Feld weiter: Wird der
                // Code dagegen aus der Routenvorschlag-Liste geschrieben, gehoert
                // der Fokus noch dem Kästchen der Flugnummer und die Tastatur
                // darf weder springen noch aufgehen.
                if (isAirportCodeComplete(s.toString()) && binding.etAirportFrom.hasFocus()) {
                    focusAndShowKeyboard(binding.etAirportTo)
                }
                autoFillRouteData()
            }
        })

        binding.etAirportTo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                // Siehe Abflugsfeld: Eigenes Tippen macht den Vorschlag ungültig.
                if (!writingSuggestedRoute) suggestedRoute = null
                val upper = s.toString().uppercase()
                if (upper != s.toString()) {
                    s.replace(0, s.length, upper)
                    return
                }
                updateAirportCode(
                    s.toString(), binding.etAirportTo, binding.ivAirportCheckTo,
                )
                autoFillRouteData()
            }
        })
    }

    /**
     * Prüft einen Flughafencode und blendet den Haken ein. Er wird danach an
     * seine Stelle am Code gesetzt, siehe [positionAirportOverlays].
     */
    private fun updateAirportCode(
        code: String,
        editText: EditText,
        check: ImageView,
    ) {
        validateAirport(code, check)
        positionAirportOverlays(editText, check)
    }

    /**
     * Setzt den Haken an seine Stelle am Code: Direkt hinter die drei Buchstaben
     * auf deren Höhe, weil er neben ihnen stehen soll und nicht an einer festen
     * Stelle der Kachel. Drei Buchstaben in doppelter Schrift sind breit genug,
     * um daneben Platz zu lassen.
     */
    private fun positionAirportOverlays(editText: EditText, check: ImageView) {
        editText.doOnLayout { placeCheckBehindCode(editText, check) }
    }

    /**
     * Setzt den Haken direkt hinter den Code, weil er neben den drei Buchstaben
     * stehen soll und nicht an einer festen Stelle der Kachel: Drei Buchstaben in
     * doppelter Schrift sind breit genug, um daneben Platz zu lassen.
     */
    private fun placeCheckBehindCode(editText: EditText, check: ImageView) {
        val code = codeBoxInTile(editText) ?: return
        val density = resources.displayMetrics.density
        check.translationX = code.right + 5f * density
        check.translationY = code.centerY() - 8f * density
    }

    /**
     * Das Rechteck der drei Buchstaben innerhalb der Kachel. Der Code steht
     * waagerecht mittig im Feld und senkrecht mittig in dem Teil des Feldes,
     * der unter der Beschriftung liegt.
     *
     * Bezugspunkt ist die Kachel selbst und nicht der Vater des Feldes - je nach
     * Kachel ist das einmal die Kachel und einmal eine Gruppe darum, deren
     * Innenabstand sonst mitgezählt würde.
     */
    private fun codeBoxInTile(editText: EditText): Rect? {
        val tile = inputTile(editText) ?: return null
        var left = 0
        var top = 0
        var view: View? = editText
        while (view != null && view !== tile) {
            left += view.left
            top += view.top
            view = view.parent as? View
        }
        // Die Textbreite kommt aus dem Zeilenumbruch, weil er die Buchstaben
        // wirklich kennt. Deren Anfang nicht: Der Umbruch eines Feldes traegt
        // keine brauchbare Breite, solange es unvermessen ist, und seine
        // mittige Zeile liegt dann um die halbe Million Pixel daneben. Der
        // Abstand zum Rand wird deshalb aus dem Feld selbst gerechnet.
        val line = editText.layout?.takeIf { it.lineCount > 0 }
        val textWidth = line?.getLineWidth(0)
            ?: editText.paint.measureText(editText.text.toString())
        val innerWidth =
            (editText.width - editText.totalPaddingLeft - editText.totalPaddingRight)
                .coerceAtLeast(0)
        val slack = (innerWidth - textWidth).coerceAtLeast(0f)
        val horizontal = editText.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        val inset = when (horizontal) {
            Gravity.CENTER, Gravity.CENTER_HORIZONTAL -> slack / 2f
            Gravity.END, Gravity.RIGHT -> slack
            else -> 0f
        }
        val textLeft = left + editText.totalPaddingLeft + inset
        // Senkrecht zaehlt die Zeile selbst, nicht das Feld: Unter der Kerbe
        // fuellt das Feld den ganzen Rest der Kachel, der Code steht aber nur
        // in der Mitte davon. Sonst hinge die Flagge am oberen Rand des Feldes
        // statt ueber den Buchstaben.
        val lineHeight = (line?.height ?: editText.height).toFloat()
        val innerHeight =
            (editText.height - editText.totalPaddingTop - editText.totalPaddingBottom)
                .coerceAtLeast(0)
        val slackHeight = (innerHeight - lineHeight).coerceAtLeast(0f)
        val vertical = editText.gravity and Gravity.VERTICAL_GRAVITY_MASK
        val insetHeight = when (vertical) {
            Gravity.CENTER, Gravity.CENTER_VERTICAL -> slackHeight / 2f
            Gravity.BOTTOM -> slackHeight
            else -> 0f
        }
        val textTop = (top + editText.totalPaddingTop + insetHeight).toInt()
        return Rect(
            textLeft.toInt(),
            textTop,
            (textLeft + textWidth).toInt(),
            textTop + lineHeight.toInt(),
        )
    }

    /**
     * Längengrenze für ein Flughafenfeld: drei Zeichen, oder vier, wenn das
     * erste Zeichen "X" ist. So passt "X"+Dreilettercode (geschlossener
     * Flughafen) hinein, ohne dass ein offener Code vier Stellen bekommen kann.
     * Ein echter IATA-Code hat immer genau drei Zeichen, deshalb ist das
     * eindeutig. Gibt "" zurück, um eine zu lange Eingabe zu verwerfen.
     */
    private fun airportCodeLengthFilter(): InputFilter = InputFilter { source, start, end, dest, dstart, dend ->
        val added = source.subSequence(start, end).toString()
        val combined = dest.subSequence(0, dstart).toString() + added +
            dest.subSequence(dend, dest.length).toString()
        val limit = if (combined.startsWith("X", ignoreCase = true)) 4 else 3
        if (combined.length <= limit) null else ""
    }

    /**
     * Ist der Flughafencode fertig eingetippt, sodass der Fokus weiterspringen
     * darf? Drei Zeichen sind fertig; beginnt der Code mit "X", kann noch ein
     * viertes folgen – es sei denn, die drei Zeichen sind bereits ein echter
     * offener Flughafen (z. B. "XAP").
     */
    private fun isAirportCodeComplete(code: String): Boolean = when {
        code.length == 4 -> code[0].uppercaseChar() == 'X'
        code.length == 3 -> code[0].uppercaseChar() != 'X' ||
            AirportData.location(requireContext(), code) != null
        else -> false
    }

    private fun validateAirport(code: String, check: ImageView) {
        // Gültig ist ein bekannter offener Code (drei Zeichen) oder ein
        // bekannter geschlossener Code ("X" + Dreilettercode).
        val exists = (code.length == 3 || AirportData.isClosedCode(code)) &&
            AirportData.location(requireContext(), code) != null
        if (exists) {
            val wasVisible = check.visibility == View.VISIBLE
            if (!wasVisible) {
                check.visibility = View.VISIBLE
                check.alpha = 0f
                check.scaleX = 0.3f
                check.scaleY = 0.3f
                check.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }
        } else if (check.visibility != View.GONE) {
            check.animate().cancel()
            check.visibility = View.GONE
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
        val fromComplete = from.length == 3 || AirportData.isClosedCode(from)
        val toComplete = to.length == 3 || AirportData.isClosedCode(to)
        if (!fromComplete || !toComplete || from == to) return
        val a = AirportData.location(requireContext(), from) ?: return
        val b = AirportData.location(requireContext(), to) ?: return
        val km = GeoMath.distanceKm(a, b)
        binding.etDistance.setText(km.roundToInt().toString())
        binding.etFlightTime.setText(GeoMath.flightMinutes(km).toString())
        // Die Layoverzeit wird passend zur Strecke vorbefuellt (sofern das
        // Layover-Kaestchen an ist und das Feld leer bzw. noch automatisch).
        prefillLayoverHours()
    }

    private fun setupProgressiveReveal() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                refreshVisibility()
            }
        }
        binding.etFlightCombined.addTextChangedListener(watcher)
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

    /**
     * Reisebuddies werden Eingabe-für-Eingabe gesammelt: Ein Name wird getippt,
     * mit Enter bestätigt und erscheint als Chip mit grünem Haken. Ein neuer
     * Name beginnt im Feld dahinter, die Tastatur bleibt dabei geöffnet. Bei
     * aktivem Rückflug hat jede Richtung ihr eigenes Feld, deshalb wird die
     * Sammlung für Chip-Container und Eingabefeld parametrisiert.
     */
    private fun setupTravelBuddyChips() {
        setupTravelBuddyChips(binding.buddyChips, binding.etTravelBuddy)
        setupTravelBuddyChips(binding.buddyChipsHinflug, binding.etHinflugTravelBuddy)
        setupTravelBuddyChips(binding.buddyChipsRueckflug, binding.etRueckflugTravelBuddy)
        // Die Chips stehen unter der Kerbe, nicht im Eingabefeld: Ohne diese
        // Meldung hält die Kachel den leeren Rückstand im Feld für "nichts
        // eingetragen" und blendet die bereits gespeicherten Buddies aus, bis
        // man hineintippt.
        inputTile(binding.etTravelBuddy)?.setExtraContentCheck { binding.buddyChips.childCount > 0 }
        inputTile(binding.etHinflugTravelBuddy)?.setExtraContentCheck { binding.buddyChipsHinflug.childCount > 0 }
        inputTile(binding.etRueckflugTravelBuddy)?.setExtraContentCheck { binding.buddyChipsRueckflug.childCount > 0 }
        loadKnownBuddyNames()
    }

    private fun setupTravelBuddyChips(chips: FlowLayout, input: EditText) {
        input.addTextChangedListener(object : TextWatcher {
            private var isCommitting = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (isCommitting) return
                val newline = s.indexOf('\n')
                if (newline >= 0) {
                    isCommitting = true
                    val name = s.substring(0, newline).trim()
                    // Nur der Name vor dem Zeilenumbruch wird übernommen, der Rest
                    // bleibt im Feld stehen (z. B. beim Einfügen mehrerer Namen).
                    s.delete(0, newline + 1)
                    isCommitting = false
                    if (name.isNotEmpty()) addTravelBuddyChip(chips, name)
                }
                // Live-Vorschläge: bereits eingetragene Namen, die zum
                // getippten Text passen, erscheinen als Popup über dem Feld.
                updateBuddySuggestions(chips, input, s.toString())
            }
        })
        // Verlässt das Feld den Fokus (z. B. Tipp woandershin), klappt das
        // Vorschlags-Popup wieder ein.
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) dismissBuddySuggestions()
        }
    }

    /**
     * Lädt alle bisher eingetragenen Reisebuddy-Namen im Hintergrund
     * (häufigste zuerst, siehe [TravelBuddyStats.build]). Danach werden die
     * Vorschläge für das Feld mit Fokus nachgereicht, falls schon getippt
     * wurde, bevor die Namen da waren.
     */
    private fun loadKnownBuddyNames() {
        val app = requireContext().applicationContext
        Thread {
            val names = TravelBuddyStats.build(LogbookRepository.getEntries())
                .map { it.name }
            view?.post {
                if (_binding == null) return@post
                knownBuddyNames = names
                buddyNamesLoaded = true
                val focused = listOf(
                    binding.etTravelBuddy to binding.buddyChips,
                    binding.etHinflugTravelBuddy to binding.buddyChipsHinflug,
                    binding.etRueckflugTravelBuddy to binding.buddyChipsRueckflug
                ).firstOrNull { (input, _) -> input.hasFocus() }
                if (focused != null) {
                    updateBuddySuggestions(
                        focused.second, focused.first,
                        focused.first.text?.toString().orEmpty()
                    )
                }
            }
        }.start()
    }

    /**
     * Prüft live, ob der getippte Name bereits in der Historie vorkommt, und
     * blendet passende Namen als Popup **über** dem Feld ein. Der getippte
     * Name ist der Text nach dem letzten Zeilenumbruch; Namen, die in diesem
     * Feld schon als Chip stehen, werden nicht vorgeschlagen.
     */
    private fun updateBuddySuggestions(chips: FlowLayout, input: EditText, text: String) {
        val query = text.substringAfterLast('\n').trim()
        if (query.isEmpty() || !buddyNamesLoaded || !input.hasFocus()) {
            dismissBuddySuggestions()
            return
        }
        val already = HashSet<String>()
        for (i in 0 until chips.childCount) {
            already += (chips.getChildAt(i) as TextView).text.toString().lowercase()
        }
        val matches = knownBuddyNames
            .filter {
                it.lowercase().contains(query.lowercase()) &&
                    !already.contains(it.lowercase())
            }
            .take(MAX_BUDDY_SUGGESTIONS)
        if (matches.isEmpty()) {
            dismissBuddySuggestions()
            return
        }
        showBuddySuggestions(chips, input, matches)
    }

    /**
     * Zeigt die Vorschläge [matches] als Popup über dem Eingabefeld. Ein Tipp
     * auf einen Namen übernimmt ihn als Chip und leert das Feld - wie Enter.
     */
    private fun showBuddySuggestions(chips: FlowLayout, input: EditText, matches: List<String>) {
        dismissBuddySuggestions()
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
        }
        val popupBg = MaterialColors.getColor(
            container,
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
            Color.WHITE
        )
        val popupStroke = MaterialColors.getColor(
            container,
            com.google.android.material.R.attr.colorOutlineVariant,
            Color.GRAY
        )
        container.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(popupBg)
            setStroke(dp(1), popupStroke)
        }
        matches.forEach { name ->
            val row = TextView(context).apply {
                text = name
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
                )
                setTextColor(
                    MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK
                    )
                )
                val padH = dp(16)
                val padV = dp(10)
                setPadding(padH, padV, padH, padV)
                val ripple = TypedValue()
                context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, ripple, true
                )
                background = ContextCompat.getDrawable(context, ripple.resourceId)
                setOnClickListener {
                    addTravelBuddyChip(chips, name)
                    input.text?.clear()
                    dismissBuddySuggestions()
                }
            }
            container.addView(row)
        }
        val width = input.width.coerceAtLeast(dp(200))
        // Inhalt messen, damit das Popup exakt oberhalb des Feldes endet.
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight = container.measuredHeight
        val popup = PopupWindow(
            container, width, ViewGroup.LayoutParams.WRAP_CONTENT, false
        ).apply {
            // Nicht fokussierbar: Die Tastatur bleibt offen und das Tippen
            // läuft weiter, während das Popup offen ist.
            isFocusable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            setOnDismissListener { buddySuggestionsPopup = null }
        }
        // Popup direkt über dem Feld: untere Kante auf die Oberkante des Feldes
        // (4 dp Luft), links bündig. Absolute Bildschirm-Koordinaten über
        // NO_GRAVITY; passt oben kein Platz, klappt es unter das Feld auf.
        val location = IntArray(2)
        input.getLocationOnScreen(location)
        val gap = dp(4)
        val yAbove = location[1] - popupHeight - gap
        val y = if (yAbove >= 0) {
            yAbove
        } else {
            location[1] + input.height + gap
        }
        popup.showAtLocation(input, Gravity.NO_GRAVITY, location[0], y)
        buddySuggestionsPopup = popup
    }

    /** Klappt das Vorschlags-Popup wieder ein, falls es offen ist. */
    private fun dismissBuddySuggestions() {
        buddySuggestionsPopup?.dismiss()
        buddySuggestionsPopup = null
    }

    private fun addTravelBuddyChip(chips: FlowLayout, name: String) {
        val chip = ItemBuddyChipBinding.inflate(layoutInflater).root
        chip.text = name
        chip.contentDescription = getString(R.string.buddy_chip_desc, name)
        chip.setOnClickListener { removeTravelBuddyChip(chips, chip) }
        chip.alpha = 0f
        chip.animate().alpha(1f).setDuration(180).start()
        chips.addView(chip)
        chips.visibility = View.VISIBLE
        // Der Chip ist Inhalt der Kachel, nicht des Feldes: Die Kachel muss
        // neu bewertet werden, sonst bleibt sie im Platzhalterzustand.
        inputTile(chips)?.refreshState()
    }

    private fun removeTravelBuddyChip(chips: FlowLayout, chip: View) {
        chips.removeView(chip)
        chips.visibility =
            if (chips.childCount == 0) View.GONE else View.VISIBLE
        // Ohne den letzten Chip und ohne Text im Feld fällt die Kachel in ihren
        // Platzhalterzustand zurück - aber erst nach der Neubewertung.
        inputTile(chips)?.refreshState()
    }

    private fun clearTravelBuddyChips(chips: FlowLayout, input: EditText) {
        chips.removeAllViews()
        chips.visibility = View.GONE
        input.text?.clear()
        inputTile(chips)?.refreshState()
    }

    /**
     * Sammelt die bestätigten Chips und den noch nicht mit Enter bestätigten
     * Resttext im Feld. Die Namen werden kommagetrennt gespeichert und in
     * [TravelBuddyStats] wieder zerlegt.
     */
    private fun collectedTravelBuddyNames(chips: FlowLayout, input: EditText): List<String> {
        val names = mutableListOf<String>()
        for (i in 0 until chips.childCount) {
            val text = (chips.getChildAt(i) as TextView).text.toString().trim()
            if (text.isNotEmpty()) names += text
        }
        val typed = input.text?.toString().orEmpty()
        return TravelBuddyStats.parse(names.joinToString("\n") + "\n" + typed)
    }

    private fun setupReturnToggle() {
        binding.tvAddReturn.setOnClickListener { activateReturn() }
        binding.tvAddReturn.visibility = if (returnActive) View.GONE else View.VISIBLE
        binding.btnRemoveReturn.setOnClickListener { deactivateReturn() }
        updateRouteArrows()
    }

    /**
     * Der zweite Pfeil zwischen Abflug und Ankunft kommt mit dem Rückflug dazu:
     * Der Hinflug nach rechts rückt dadurch nach oben, darunter steht der
     * Rückflug nach links. Beide Pfeile werden mittig in der Spalte zwischen den
     * Kacheln ausgerichtet, deshalb muss hier nur die Sichtbarkeit stimmen.
     */
    private fun updateRouteArrows() {
        binding.ivRouteBack.visibility = if (returnActive) View.VISIBLE else View.GONE
    }

    private fun activateReturn() {
        if (returnActive) return
        returnActive = true
        val aircraft = binding.etAircraftType.text?.toString()?.trim().orEmpty()
        val registration = binding.etRegistration.text?.toString()?.trim().orEmpty()
        val function = binding.etFunction.text?.toString()?.trim().orEmpty()
        val comment = binding.etComment.text?.toString()?.trim().orEmpty()
        val (airline, flightNumber) = combinedFlight()
        binding.etHinflugAircraft.setText(aircraft)
        binding.etHinflugRegistration.setText(registration)
        binding.etHinflugFunction.setText(function)
        binding.etHinflugComment.setText(comment)
        binding.etRueckflugAircraft.setText(aircraft)
        binding.etRueckflugRegistration.setText(registration)
        binding.etRueckflugFunction.setText(function)
        binding.etRueckflugComment.setText(comment)
        // Reisebuddies und Layoverlaenge stehen bei einem Rückflug in beiden
        // Spalten, damit sie sich einzeln korrigieren lassen.
        val buddies = collectedTravelBuddyNames(binding.buddyChips, binding.etTravelBuddy)
        clearTravelBuddyChips(binding.buddyChips, binding.etTravelBuddy)
        val layoverHours = binding.etLayoverHours.text?.toString()?.trim().orEmpty()
        binding.etLayoverHours.text?.clear()
        for (name in buddies) {
            addTravelBuddyChip(binding.buddyChipsHinflug, name)
            addTravelBuddyChip(binding.buddyChipsRueckflug, name)
        }
        binding.etHinflugLayoverHours.setText(layoverHours)
        // "LH 400" wird zu "LH 401": Die Airline wandert mit, die Nummer geht
        // eins weiter, damit nicht zwei Eintraege dieselbe bekommen.
        binding.etRueckflugFlight.setText(
            FlightCombined.format(airline, FlightCombined.nextNumber(flightNumber))
        )

        val hinflugDate = binding.etDate.text?.toString()?.trim().orEmpty()
        if (hinflugDate.isNotEmpty()) {
            binding.etDateReturn.setText(hinflugDate)
        }
        // Abflug und Ankunft bleiben die des Hinflugs, die Rückflugsnummer hat
        // darauf keinen Einfluss. Waren sie noch leer, gehoeren sie jetzt
        // eindeutig zum Hinflug und werden aus seiner Nummer nachgeschlagen.
        prefillRouteFromHistory()
        // Nur die Rueckflugsnummer wird eingetragen. Der Datumsdialog bleibt zu,
        // das Datum holt sich der Nutzer selbst, sobald er das Feld antippt.
        updateReturnUi()
    }

    /**
     * Nimmt den Rückflug wieder aus dem Formular: Die Felder des Hinflugs
     * wandern zurück in den Einzelblock, die des Rückflugs werden geleert.
     */
    private fun deactivateReturn() {
        if (!returnActive) return
        returnActive = false
        binding.etAircraftType.setText(binding.etHinflugAircraft.text?.toString()?.trim().orEmpty())
        binding.etRegistration.setText(binding.etHinflugRegistration.text?.toString()?.trim().orEmpty())
        binding.etFunction.setText(binding.etHinflugFunction.text?.toString()?.trim().orEmpty())
        binding.etComment.setText(binding.etHinflugComment.text?.toString()?.trim().orEmpty())
        binding.etRueckflugFlight.setText("")
        val buddies = collectedTravelBuddyNames(binding.buddyChipsHinflug, binding.etHinflugTravelBuddy)
        clearTravelBuddyChips(binding.buddyChipsHinflug, binding.etHinflugTravelBuddy)
        clearTravelBuddyChips(binding.buddyChipsRueckflug, binding.etRueckflugTravelBuddy)
        val layoverHours = binding.etHinflugLayoverHours.text?.toString()?.trim().orEmpty()
        binding.etHinflugLayoverHours.text?.clear()
        binding.etLayoverHours.setText(layoverHours)
        for (name in buddies) addTravelBuddyChip(binding.buddyChips, name)
        updateReturnUi()
    }

    private fun updateReturnUi() {
        val editing = editingEntryId >= 0
        binding.returnSection.visibility = if (editing) View.GONE else View.VISIBLE
        binding.tvAddReturn.visibility = if (returnActive) View.GONE else View.VISIBLE
        binding.tileFlightReturn.visibility = if (returnActive) View.VISIBLE else View.GONE
        binding.tileDateReturn.visibility = if (returnActive) View.VISIBLE else View.GONE
        updateRouteArrows()
        // Erst mit dem Rückflug sind es zwei Flüge: Links steht dann der
        // Hinflug und daneben der Rückflug, beide mit eigener Flugnummer.
        // Die Beschriftung wird ueber die Kachel gesetzt und nicht direkt in
        // ihren TextView: Sie setzt ihren Text bei jedem Zustandswechsel neu
        // auf und kaeme dabei wieder auf das beim Anlegen gelesene "Datum"
        // zurueck, sobald das Feld geleert wird.
        inputTile(binding.etFlightCombined)?.setLabelText(
            getString(if (returnActive) R.string.hinflight_label else R.string.flight_number_label)
        )
        inputTile(binding.etDate)?.setLabelText(
            getString(if (returnActive) R.string.hinflight_label else R.string.date_label)
        )
        binding.optionalSingleBlock.visibility = if (returnActive) View.GONE else View.VISIBLE
        binding.optionalSplitBlock.visibility = if (returnActive) View.VISIBLE else View.GONE
        // Das Layoverzeit-Feld wechselt zwischen Ankunftskarte (ohne Rueckflug)
        // und Hinflug-Spalte (mit Rueckflug).
        updateLayoverHoursVisibility()
        // Die Funktion hat bei einem Rückflug in beiden Spalten ein eigenes
        // Feld, ihre Sichtbarkeit hängt also an beiden Blöcken.
        updateFunctionVisibility()
        binding.btnSaveFlight.setText(
            if (editing) R.string.btn_update_flight
            else if (returnActive) R.string.btn_save_flights
            else R.string.btn_save_flight
        )
        binding.btnDiscardFlight.setText(if (returnActive) R.string.btn_discard_flights else R.string.btn_discard_flight)
        refreshVisibility()
    }

    /**
     * Blendet die Abschnitte des Formulars. Pflicht ist nur die Reiseart in der
     * ersten Zeile, alles Weitere folgt daraus: Sobald eine Reiseart gewählt
     * ist, erscheinen die Reiseklassen und mit ihnen die Felder für
     * Fluggesellschaft, Flugnummer und Datum. Eine Reiseklasse muss nicht
     * gewählt werden.
     */
    private fun refreshVisibility() {
        val hasType = selectedFlightTypeIndex != null
        val (airline, flightNumber) = combinedFlight()
        val date = parseDate(binding.etDate.text?.toString()) != null
        val returnDate = !returnActive ||
            parseDate(binding.etDateReturn.text?.toString()) != null

        binding.classSection.visibility = if (hasType) View.VISIBLE else View.GONE
        binding.detailsSection.visibility = if (hasType) View.VISIBLE else View.GONE

        // Sitzplatz nur bei Privat mit ausgewählter Reiseklasse.
        val isPrivate = selectedFlightTypeIndex == PRIVATE_INDEX
        binding.seatSection.visibility =
            if (isPrivate && selectedClassIndex != null) View.VISIBLE else View.GONE

        val revealAirports = hasType &&
            airline.isNotEmpty() && flightNumber.isNotEmpty() && date
        binding.airportSection.visibility = if (revealAirports) View.VISIBLE else View.GONE
        binding.optionalSection.visibility = if (revealAirports) View.VISIBLE else View.GONE

        val from = binding.etAirportFrom.text?.toString()?.trim().orEmpty()
        val to = binding.etAirportTo.text?.toString()?.trim().orEmpty()
        val distance = binding.etDistance.text?.toString()?.trim().orEmpty()
        val flightTime = binding.etFlightTime.text?.toString()?.trim().orEmpty()

        binding.tvDistanceUnit.visibility = if (distance.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvFlightTimeMin.visibility = if (flightTime.isNotEmpty()) View.VISIBLE else View.GONE

        val allRequired = hasType && airline.isNotEmpty() && flightNumber.isNotEmpty() &&
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

        /** Maximal so viele Reisebuddy-Vorschläge zeigt das Popup über dem Feld. */
        private const val MAX_BUDDY_SUGGESTIONS = 5
        /**
         * Familie je Anfang: "A35" wird zu "A350-" (offen - die Varianten-Ziffer
         * wird nachtraglich ergaenzt, siehe [AIRCRAFT_VARIANT_AUTOCOMPLETE]),
         * "A38" direkt zu "A380-800". Der zweite Wert sagt, ob der Typ damit
         * fertig ist.
         */
        private val AIRCRAFT_AUTOCOMPLETE: Map<String, Pair<String, Boolean>> = mapOf(
            "A35" to ("A350-" to false),
            "A38" to ("A380-800" to true),
        )

        /** Variante hinter dem Strich: "A350-9" -> "A350-900", "A350-1" -> "A350-1000". */
        private val AIRCRAFT_VARIANT_AUTOCOMPLETE = mapOf(
            "A350-9" to "A350-900",
            "A350-1" to "A350-1000",
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

    /** Fluggesellschaft und Flugnummer, wie sie gerade im Kästchen stehen. */
    private fun combinedFlight(): Pair<String, String> =
        FlightCombined.split(binding.etFlightCombined.text?.toString().orEmpty())

    private fun saveFlight() {
        val missingLabels = mutableListOf<String>()

        // Die Reiseart ist die einzige Pflichtangabe. Ohne Reiseklasse wird der
        // Eintrag ohne gespeichert, das Feld ist in der Datenbank auch nullable.
        if (selectedFlightTypeIndex == null) missingLabels += getString(R.string.flight_type_label)

        val (airline, flightNumber) = combinedFlight()
        if (airline.isEmpty() || flightNumber.isEmpty()) {
            missingLabels += getString(R.string.flight_number_label)
        }

        val date = parseDate(binding.etDate.text?.toString())
        if (date == null) missingLabels += getString(R.string.date_label)

        val returnDate = if (returnActive) {
            parseDate(binding.etDateReturn.text?.toString())
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

        // Die Layoverlaenge gehoert zu einem Layover: ohne angeklicktes
        // Kästchen wird sie nicht gespeichert, auch wenn das Feld noch Text hat.
        // Bei aktivem Rückflug steht sie in der Spalte "Hinflug".
        val layoverHoursField =
            if (returnActive) binding.etHinflugLayoverHours else binding.etLayoverHours
        val layoverHoursText = layoverHoursField.text?.toString()?.trim().orEmpty()
        val layoverHours = layoverHoursText.toIntOrNull()
        if (layoverHoursText.isNotEmpty() && layoverHours == null) {
            missingLabels += getString(R.string.layover_hours_label)
        }

        if (missingLabels.isNotEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.save_flight_incomplete) + "\n" + missingLabels.joinToString(", "),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val confirmedDate = date ?: return

        // Das Kästchen des Rückflugs enthält wieder Airline und Nummer. Fehlt
        // dort etwas, übernimmt die Airline des Hinflugs.
        val (returnAirline, returnFlightNumber) =
            FlightCombined.split(binding.etRueckflugFlight.text?.toString().orEmpty())

        val aircraftType = (if (returnActive) binding.etHinflugAircraft else binding.etAircraftType)
            .text?.toString()?.trim()?.ifEmpty { null }
        val registration = (if (returnActive) binding.etHinflugRegistration else binding.etRegistration)
            .text?.toString()?.trim()?.uppercase()?.ifEmpty { null }
        val comment = (if (returnActive) binding.etHinflugComment else binding.etComment)
            .text?.toString()?.trim()?.ifEmpty { null }
        // Die Funktion gehoert zu der Richtung, aus der der Eintrag entsteht:
        // Bei einem Rueckflug hat der Hinflug sein eigenes Feld.
        val function = (if (returnActive) binding.etHinflugFunction else binding.etFunction)
            .text?.toString()?.trim()?.ifEmpty { null }
        // Reisebuddies werden im Formular als Chips gesammelt und hier als
        // kommagetrennte Liste gespeichert; TravelBuddyStats zerlegt sie später.
        // Bei aktivem Rückflug hat jede Richtung ihr eigenes Feld.
        val travelBuddy = collectedTravelBuddyNames(
            if (returnActive) binding.buddyChipsHinflug else binding.buddyChips,
            if (returnActive) binding.etHinflugTravelBuddy else binding.etTravelBuddy
        )
            .joinToString(", ")
            .ifEmpty { null }
        val returnTravelBuddy = collectedTravelBuddyNames(
            binding.buddyChipsRueckflug, binding.etRueckflugTravelBuddy
        )
            .joinToString(", ")
            .ifEmpty { null }

        val entry = LogbookEntry(
            id = editingEntryId.takeIf { it >= 0 },
            date = confirmedDate,
            flightType = selectedFlightType(),
            classType = selectedClassType(),
            seat = binding.etSeat.text?.toString()?.trim()?.uppercase()?.ifEmpty { null },
            fromAirport = from,
            toAirport = to,
            airline = airline.uppercase(),
            flightNumber = flightNumber,
            aircraftType = aircraftType,
            registration = registration,
            distanceKm = distanceKm,
            flightMinutes = flightMinutes,
            layover = binding.cbLayover.isChecked,
            layoverHours = if (binding.cbLayover.isChecked) layoverHours else null,
            fromCountry = AirportData.country(requireContext(), from),
            toCountry = AirportData.country(requireContext(), to),
            function = function,
            travelBuddy = travelBuddy,
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
                airline = returnAirline.uppercase().ifEmpty { entry.airline },
                flightNumber = returnFlightNumber.ifEmpty { FlightCombined.nextNumber(entry.flightNumber) },
                aircraftType = binding.etRueckflugAircraft.text?.toString()?.trim()?.ifEmpty { null },
                registration = binding.etRueckflugRegistration.text?.toString()?.trim()?.uppercase()?.ifEmpty { null },
                distanceKm = entry.distanceKm,
                flightMinutes = entry.flightMinutes,
                layover = false,
                layoverHours = null,
                fromCountry = entry.toCountry,
                toCountry = entry.fromCountry,
                function = binding.etRueckflugFunction.text?.toString()?.trim()?.ifEmpty { null },
                travelBuddy = returnTravelBuddy,
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
        val errorColor = MaterialColors.getColor(
            requireView(),
            com.google.android.material.R.attr.colorError
        )
        val notSavedText = getString(R.string.discard_not_saved)
        val messageText = getString(R.string.discard_message, notSavedText)
        val notSavedStart = messageText.indexOf(notSavedText)
        val message = SpannableString(messageText).apply {
            if (notSavedStart >= 0) {
                setSpan(
                    ForegroundColorSpan(errorColor),
                    notSavedStart,
                    notSavedStart + notSavedText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.discard_title)
            .setMessage(message)
            .setPositiveButton(R.string.discard_confirm) { _, _ ->
                findNavController().navigateUp()
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(errorColor)
        }
        dialog.show()
    }

    /**
     * Das Datumfenster des Rückflugs öffnet im Monat des Hinflugs: Der Nutzer
     * fliegt hin und zurück, deshalb steht er beim Rückflug im selben Monat -
     * auch wenn er den Hinflug gerade in einen anderen Monat gesetzt hat. Der
     * Tag bleibt der, den der Rückflug schon hat.
     */
    private fun returnDatePickerStart(own: LocalDate?): LocalDate {
        val outbound = parseDate(binding.etDate.text?.toString())
            ?: return own ?: LocalDate.now()
        val month = YearMonth.of(outbound.year, outbound.monthValue)
        val day = (own?.dayOfMonth ?: outbound.dayOfMonth)
            .coerceIn(1, month.lengthOfMonth())
        return month.atDay(day)
    }

    private fun parseDate(text: String?): LocalDate? = try {
        LocalDate.parse(text?.trim().orEmpty(), DATE_FORMAT)
    } catch (e: Exception) {
        null
    }

    private fun setupDateField() {
        setupDateEditText(binding.etDate)
        setupDateEditText(binding.etDateReturn)
    }

    /**
     * Datum und Rueckflugdatum oeffnen beim Antippen ihren Kalender, ohne
     * selbst zu einer Tastatur zu fuehren - der Kalender ist die einzige
     * Eingabe dafuer. Der Fokus zaehlt zusaetzlich mit, falls das Feld auf
     * anderem Weg den Fokus bekommt; der Listener wird ueber die Kachel
     * angemeldet, weil ein eigener am Feld den der Kachel ersetzen wuerde und
     * sie nicht mehr mitbekaeme, dass getippt wird.
     */
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

        editText.setOnClickListener {
            // Wie in allen anderen Feldern: Cursor hinten, siehe
            // [setupCursorAtEndOnTap].
            editText.setSelection(editText.text?.length ?: 0)
            showDatePicker(editText)
        }
        inputTile(editText)?.setOnInputFocusChangeListener { hasFocus ->
            if (hasFocus) showDatePicker(editText)
        }
    }

    private fun showDatePicker(target: EditText) {
        val typed = parseDate(target.text?.toString())
        val initial = if (target === binding.etDateReturn) {
            returnDatePickerStart(typed)
        } else {
            typed ?: LocalDate.now()
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroyView() {
        super.onDestroyView()
        dismissBuddySuggestions()
        activity?.window?.setSoftInputMode(previousSoftInputMode)
        _binding = null
    }
}