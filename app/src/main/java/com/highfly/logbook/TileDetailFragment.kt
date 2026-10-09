package com.highfly.logbook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentTileDetailBinding
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

class TileDetailFragment : Fragment() {

    private var _binding: FragmentTileDetailBinding? = null
    private val binding get() = _binding!!

    private var tileId: String = "flights"

    /** Nach was die Layover-Balken geordnet sind, Standard: Anzahl. */
    private var layoverSort: ChartData.LayoverSort = ChartData.LayoverSort.ANZAHL

    /** Reiseart, nach der die Kachel "Reiseklasse" ihre Klassen zeigt. */
    private var classTravelType: String = ClassTravelType.DEFAULT

    /** Suchtext der Layover-Kachel: Dreilettercode oder Stadt. */
    private var layoverQuery: String = ""

    /** Ob das Suchfeld der Layover-Kachel ausgeklappt ist. */
    private var layoverSearchOpen = false

    /** Alle Layover-Balken vor der Suche, damit die Eingabe nur filtern muss. */
    private var layoverAllBars: List<ChartData.Bar> = emptyList()

    /** Laufende Aufklapp-/Einklappanimation des Layover-Suchfelds. */
    private var searchAnimator: ValueAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTileDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tileId = requireArguments().getString("tileId") ?: "flights"
        // Bei einer Neuerstellung der Ansicht bleibt die Fragment-Instanz
        // erhalten, der Zustands-Bundle ist dann null. Der bereits gewählte
        // Wert darf in diesem Fall nicht auf den Standard zurückspringen.
        savedInstanceState?.getString(KEY_LAYOVER_SORT)?.let { name ->
            layoverSort = ChartData.LayoverSort.entries
                .firstOrNull { it.name == name } ?: layoverSort
        }
        savedInstanceState?.getString(KEY_LAYOVER_QUERY)?.let { layoverQuery = it }
        layoverSearchOpen = savedInstanceState?.getBoolean(KEY_LAYOVER_SEARCH_OPEN) ?: false

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        val tile = DashboardPrefs.tileById(tileId)
        binding.tvDetailTitle.text = requireContext().getString(tile.nameRes)
        binding.tvDetailSubtitle.text = getString(
            R.string.tile_detail_period,
            PeriodOptions.label(
                requireContext(),
                Settings.getDefaultPeriodKey(requireContext())
            )
        )

        when {
            tileId == "class" -> {
                setupClassTravelType()
                renderClassPie()
            }
            tileId == "traveltype" -> {
                renderPie(ChartData.travelTypeSlices(requireContext()))
            }
            tileId == "function" -> {
                renderPie(ChartData.functionSlices(requireContext()))
            }
            tileId == "routes" -> {
                renderRoutes()
            }
            tileId == "aircraftreg" -> {
                renderAircraftRegistrations()
            }
            ChartData.isBarChart(tileId) -> {
                if (tileId == "countries") {
                    renderContinents()
                }
                if (tileId == "layover") {
                    renderLayover()
                } else {
                    renderBars(ChartData.barChart(requireContext(), tileId))
                }
            }
            else -> {
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun renderContinents() {
        val slices = ChartData.continentSlices(requireContext())
        binding.tvContinentTitle.visibility = View.VISIBLE
        binding.pieContinent.visibility = View.VISIBLE
        binding.pieContinent.setSlices(
            slices.map {
                PieChartView.Slice(
                    it.label,
                    it.value,
                    ContextCompat.getColor(requireContext(), it.colorRes)
                )
            }
        )
    }

    /**
     * Layover-Kachel vollstaendig: die beiden Extrem-Kacheln, die Sortierung
     * darunter und die Balken des gewaehlten Modus. Nur die Balken haengen von
     * der Auswahl ab, die Kacheln oben bleiben, egal was gewaehlt ist.
     */
    private fun renderLayover() {
        setupLayoverOverviewButton()
        setupLayoverZeroSection()
        renderLayoverExtremes()
        renderLayoverSort()
        setupLayoverSearch()
        renderLayoverBars()
    }

    /**
     * Der Sterne-Knopf oben rechts ist nur ein Platzhalter: Er ist bereits
     * sichtbar und anklickbar, die Übersichtsseite dahinter entsteht später.
     */
    private fun setupLayoverOverviewButton() {
        binding.btnLayoverOverview.visibility = View.VISIBLE
        binding.btnLayoverOverview.setOnClickListener {
            // TODO: hier später die zusammenfassende Layover-Übersicht öffnen.
        }
    }

    /**
     * Sortierung als diskreter Text rechts neben seiner Beschriftung, wie die
     * Filterwoerter ueber den Kacheln des Dashboards. Standard ist "Anzahl".
     * Ein Tipp oeffnet das Menue, ein Wechsel zeichnet allein die Balken
     * darunter neu - die Auswahl selbst bleibt bis zum Verlassen der Seite.
     */
    private fun renderLayoverSort() {
        val options = listOf(
            ChartData.LayoverSort.ANZAHL to getString(R.string.layover_sort_count),
            ChartData.LayoverSort.DAUER to getString(R.string.layover_sort_duration)
        )
        binding.tvLayoverSort.text =
            options.firstOrNull { it.first == layoverSort }?.second
                ?: options.first().second
        binding.tvLayoverSort.setOnClickListener { anchor ->
            FilterPopupMenu.showOptions(
                requireContext(),
                anchor,
                options.map { it.first.name },
                options.map { it.second },
                layoverSort.name
            ) { key ->
                val selected =
                    ChartData.LayoverSort.entries.firstOrNull { it.name == key }
                if (selected != null && selected != layoverSort) {
                    layoverSort = selected
                    renderLayoverSort()
                    renderLayoverBars()
                }
            }
        }
    }

    /**
     * Balken der Layover-Kachel im gewaehlten Modus: nach Zahl der Layover
     * oder nach der gesamten Layoverzeit in Tagen. Die Auswahlzeile bleibt
     * sichtbar, solange es etwas zu sortieren gibt.
     *
     * Klick auf Medaille, Code oder Balken oeffnet in beiden Modi dieselbe
     * Detailseite des Ziel-Flughafens.
     */
    private fun renderLayoverBars() {
        layoverAllBars = ChartData.barChart(requireContext(), "layover", layoverSort)
        binding.layoverSortRow.visibility =
            if (layoverAllBars.isEmpty()) View.GONE else View.VISIBLE
        applyLayoverFilter()
    }

    /**
     * Zeichnet die Layover-Balken erneut, aber nur mit den Flughaefen, die zur
     * Sucheingabe passen. Ohne Eingabe bleiben alle stehen. Flughaefen ohne
     * erfasste Dauer (nur im Dauer-Modus moeglich) stehen nicht in der
     * Rangliste, sondern in der einklappbaren Gruppe darunter - das gilt auch
     * fuer die gefilterte Auswahl.
     */
    private fun applyLayoverFilter() {
        val query = layoverQuery.trim()
        val matching = if (query.isEmpty()) {
            layoverAllBars
        } else {
            layoverAllBars.filter { layoverMatches(it.label, query) }
        }
        val (bars, zeroBars) = ChartData.splitLayoverZeroDuration(matching)

        binding.barChart.setOnItemClickListener { index ->
            openLayoverDetail(bars.getOrNull(index)?.label)
        }
        // Die Layover-Kachel zeigt in beiden Sortierungen gleich: der Wert im
        // Balken, "Zuletzt: …" fest am rechten Rand.
        binding.barChart.setValueInsideBar(true)
        binding.tvEmpty.setText(
            if (query.isEmpty()) R.string.chart_empty else R.string.layover_search_empty
        )
        renderBars(bars)
        // Gibt es nur noch die Null-Gruppe, gehört der Leertext nicht über sie -
        // die Daten stehen ja darunter.
        if (zeroBars.isNotEmpty()) binding.tvEmpty.visibility = View.GONE
        renderLayoverZeroBars(zeroBars)
        // Trifft die Suche nichts, bleibt nur der Hinweis stehen; ein leeres
        // Diagramm darueber haette keinen Inhalt.
        if (matching.isEmpty() && query.isNotEmpty()) {
            binding.barChart.visibility = View.GONE
        }
    }

    /**
     * Lupe links auf der Sortierzeile: Ein Tipp klappt rechts daneben das
     * Suchfeld aus, ein zweiter wieder ein und hebt den Filter auf. Gesucht
     * wird nach dem Flughafencode und nach der Stadt.
     */
    private fun setupLayoverSearch() {
        // Text vor dem Beobachter setzen: die wiederhergestellte Suche soll
        // nicht schon hier, sondern erst beim Zeichnen der Balken greifen.
        binding.etLayoverSearch.setText(layoverQuery)
        binding.etLayoverSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                layoverQuery = s?.toString().orEmpty()
                applyLayoverFilter()
            }
        })
        binding.ivLayoverSearch.setOnClickListener {
            toggleLayoverSearch(!layoverSearchOpen)
        }
        if (layoverSearchOpen) {
            binding.etLayoverSearch.visibility = View.VISIBLE
            binding.etLayoverSearch.alpha = 1f
            setLayoverSearchProgress(1f)
        }
    }

    /**
     * Trifft die Suche den Flughafen - ueber den Code, die Stadt oder die
     * deutsche Schreibweise der Stadt (z. B. "Muenchen" fuer MUC).
     */
    private fun layoverMatches(code: String, query: String): Boolean {
        if (code.contains(query, ignoreCase = true)) return true
        val info = AirportNames.get(requireContext(), code) ?: return false
        if (info.city?.contains(query, ignoreCase = true) == true) return true
        return info.cityDe?.contains(query, ignoreCase = true) == true
    }

    /**
     * Klappt das Suchfeld aus oder ein. Beim Oeffnen waechst es aus der Lupe
     * heraus nach rechts, beim Schliessen laeuft es rueckwaerts und leert die
     * Suche erst am Ende - so springt die Liste nicht schon waehrend der
     * Animation auf den vollen Bestand zurueck.
     */
    private fun toggleLayoverSearch(open: Boolean) {
        searchAnimator?.cancel()
        layoverSearchOpen = open
        if (open) {
            binding.etLayoverSearch.visibility = View.VISIBLE
            binding.etLayoverSearch.requestFocus()
            binding.etLayoverSearch.post {
                if (_binding != null) showLayoverKeyboard()
            }
        } else {
            hideLayoverKeyboard()
        }
        searchAnimator = ValueAnimator.ofFloat(if (open) 0f else 1f, if (open) 1f else 0f)
            .apply {
                duration = SEARCH_ANIMATION_MS
                addUpdateListener { animation ->
                    setLayoverSearchProgress(animation.animatedValue as Float)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!open) {
                            binding.etLayoverSearch.visibility = View.GONE
                            // Leeren loest den Beobachter aus und zeigt wieder
                            // alle Balken.
                            binding.etLayoverSearch.setText("")
                        }
                    }
                })
                start()
            }
    }

    /**
     * Oeffnungsgrad des Suchfelds als gegenlaeufige Gewichte: Bei 0 traegt der
     * Leerraum den Rest und das Feld bleibt ohne Breite, bei 1 fuellt das Feld
     * den Platz zwischen Lupe und Sortierauswahl. Die Auswahl am rechten Rand
     * bleibt dabei stehen.
     */
    private fun setLayoverSearchProgress(progress: Float) {
        val field = binding.etLayoverSearch
        if (progress > 0f) field.visibility = View.VISIBLE
        field.alpha = progress
        val fieldParams = field.layoutParams as LinearLayout.LayoutParams
        fieldParams.weight = progress
        field.requestLayout()
        val spacerParams = binding.layoverSortSpacer.layoutParams as LinearLayout.LayoutParams
        spacerParams.weight = 1f - progress
        binding.layoverSortSpacer.requestLayout()
    }

    private fun showLayoverKeyboard() {
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(binding.etLayoverSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideLayoverKeyboard() {
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.etLayoverSearch.windowToken, 0)
    }

    /**
     * Die Flughäfen ohne erfasste Dauer als einklappbare Gruppe unter dem
     * Diagramm. Der Kopf nennt ihre Anzahl; die Balken darunter sehen aus wie
     * die der Rangliste, nur ohne Rangzeichen - eine Reihenfolge nach Dauer
     * gibt es hier ja nicht. Ohne solche Flughäfen bleibt die Gruppe weg.
     */
    private fun renderLayoverZeroBars(bars: List<ChartData.Bar>) {
        binding.layoverZeroSection.visibility =
            if (bars.isEmpty()) View.GONE else View.VISIBLE
        if (bars.isEmpty()) return
        binding.tvLayoverZeroHeader.text =
            getString(R.string.layover_zero_header, bars.size)
        binding.barChartZero.setValueInsideBar(true)
        binding.barChartZero.setShowRanks(false)
        binding.barChartZero.setOnItemClickListener { index ->
            openLayoverDetail(bars.getOrNull(index)?.label)
        }
        binding.barChartZero.setItems(
            bars.map {
                BarChartView.Item(it.label, it.count, it.subLabel, it.countLabel)
            }
        )
        applyLayoverZeroExpanded(expanded = false)
    }

    private fun setupLayoverZeroSection() {
        binding.layoverZeroHeader.setOnClickListener {
            applyLayoverZeroExpanded(binding.barChartZero.visibility != View.VISIBLE)
        }
    }

    /** Klappt die Null-Gruppe auf oder zu; der Pfeil dreht sich mit. */
    private fun applyLayoverZeroExpanded(expanded: Boolean) {
        binding.barChartZero.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.ivLayoverZeroArrow.animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(200)
            .start()
    }

    /**
     * Letztes und ältestes Layover des Zeitraums als zwei Kacheln ueber dem
     * Balkendiagramm. Ohne Layover im Zeitraum bleiben beide Kacheln
     * ausgeblendet, dann zeigt das Diagramm allein seinen Leertext.
     *
     * Beide Kacheln sind anklickbar und oeffnen dieselbe Detailseite wie ein
     * Klick auf den Code im Balkendiagramm darunter.
     */
    private fun renderLayoverExtremes() {
        val entries = ChartData.periodFiltered(requireContext())
        val newest = ChartData.newestLayover(entries) ?: return
        val oldest = ChartData.oldestLayover(entries) ?: return
        val newestDate = newest.date.format(DATE_LABEL_FORMAT)
        val oldestDate = oldest.date.format(DATE_LABEL_FORMAT)
        binding.tvLayoverNewest.text = newest.airport
        binding.tvLayoverNewestDate.text = newestDate
        binding.tvLayoverOldest.text = oldest.airport
        binding.tvLayoverOldestDate.text = oldestDate
        binding.layoverExtremes.visibility = View.VISIBLE
        // Die Kacheln nennen Flughafen und Datum für Talkback, sonst blieben die
        // Kinder stumm, weil die Kachel selbst den Namen trägt.
        binding.cardLayoverNewest.contentDescription = getString(
            R.string.layover_extreme_newest_desc, newest.airport, newestDate
        )
        binding.cardLayoverOldest.contentDescription = getString(
            R.string.layover_extreme_oldest_desc, oldest.airport, oldestDate
        )
        binding.cardLayoverNewest.setOnClickListener {
            openLayoverDetail(newest.airport)
        }
        binding.cardLayoverOldest.setOnClickListener {
            openLayoverDetail(oldest.airport)
        }
        fitLayoverTileCodes()
    }

    /**
     * Beide Kacheln zeigen ihren Code in derselben Schriftgrösse.
     *
     * Die Kacheln sind gleich breit, ihre Codes aber nicht gleich lang. Ohne
     * diese Angleichung müsste nur der längere Code schrumpfen und die Kacheln
     * unterschieden sich schon im Wortbild. Gesucht ist deshalb die grösste
     * Grösse, in der beide Codes nebeneinander passen. Das Zuschlagen auf die
     * gemessene Breite braucht den ersten Layout-Durchlauf.
     */
    private fun fitLayoverTileCodes() {
        binding.layoverExtremes.doOnLayout {
            fitSharedCodeSize(binding.tvLayoverNewest, binding.tvLayoverOldest)
        }
    }

    private fun fitSharedCodeSize(vararg codes: TextView) {
        // Pixel je sp: so wandelt TextView seine Schriftgrösse in Pixel, und
        // damit lässt sich die gemessene Breite wieder in sp zurückrechnen.
        val pixelPerSp = resources.displayMetrics.density *
            resources.configuration.fontScale
        var needed = Float.MAX_VALUE
        codes.forEach { code ->
            val available = code.width - code.paddingLeft - code.paddingRight
            val measured = code.paint.measureText(code.text, 0, code.text.length)
            if (available <= 0 || measured <= 0f) return@forEach
            needed = min(needed, code.textSize * available / measured)
        }
        if (needed == Float.MAX_VALUE) return
        val sizeSp = floor(needed / pixelPerSp).toInt()
            .coerceIn(LAYOVER_CODE_MIN_SP, LAYOVER_CODE_MAX_SP)
        codes.forEach { code ->
            code.setAutoSizeTextTypeUniformWithConfiguration(
                LAYOVER_CODE_MIN_SP, sizeSp, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
    }

    /** Detailseite eines Layover-Flughafens, wie sie ein Klick im Diagramm öffnet. */
    private fun openLayoverDetail(label: String?) {
        val code = label?.trim()?.uppercase()
        if (code.isNullOrBlank()) return
        findNavController().navigate(
            R.id.action_tile_detail_to_layover_detail,
            bundleOf(LayoverDetailFragment.ARG_AIRPORT_CODE to code)
        )
    }

    private fun renderRoutes() {
        renderScrollableColumnChart(
            ChartData.airportBars(requireContext()),
            binding.airportsChart,
            binding.barAirports,
            binding.barAirportsAxis,
            null
        )
        renderBars(ChartData.barChart(requireContext(), "routes"))
    }

    private fun renderAircraftRegistrations() {
        val types = ChartData.aircraftBars(requireContext())
        renderScrollableColumnChart(
            types,
            binding.aircraftChart,
            binding.barAircraft,
            binding.barAircraftAxis,
            binding.tvAircraftTitle
        )
        val registrations = ChartData.barChart(requireContext(), "aircraftreg")
        renderBars(registrations)
        binding.tvRegistrationsTitle.visibility =
            if (registrations.isEmpty()) View.GONE else View.VISIBLE
        binding.tvEmpty.visibility =
            if (types.isEmpty() && registrations.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderScrollableColumnChart(
        bars: List<ChartData.Bar>,
        container: View,
        chart: MonthBarChartView,
        axis: MonthBarChartView,
        title: View?
    ) {
        if (bars.isEmpty()) return
        val chartHeight =
            (resources.displayMetrics.heightPixels * COLUMN_CHART_HEIGHT_FACTOR).roundToInt()
        val chartItems = bars.map { MonthBarChartView.Item(it.label, it.count) }

        chart.setLeadingSpace(axis.layoutParams.width)
        chart.setPlotHeight(chartHeight)
        chart.setItems(chartItems)

        axis.setContentVisible(false)
        axis.setPlotHeight(chartHeight)
        axis.setItems(chartItems)

        title?.visibility = View.VISIBLE
        container.visibility = View.VISIBLE
    }

    private fun renderBars(bars: List<ChartData.Bar>) {
        binding.pieChart.visibility = View.GONE
        binding.tvEmpty.visibility = if (bars.isEmpty()) View.VISIBLE else View.GONE
        binding.barChart.visibility = View.VISIBLE
        binding.barChart.setItems(
            bars.map {
                BarChartView.Item(it.label, it.count, it.subLabel, it.countLabel)
            }
        )
    }

    private fun renderPie(slices: List<ChartData.Slice>) {
        binding.barChart.visibility = View.GONE
        binding.pieChart.visibility = View.VISIBLE
        binding.tvEmpty.visibility = if (slices.isEmpty()) View.VISIBLE else View.GONE
        binding.pieChart.setSlices(
            slices.map {
                PieChartView.Slice(
                    it.label,
                    it.value,
                    ContextCompat.getColor(requireContext(), it.colorRes)
                )
            }
        )
    }

    /**
     * Reiseart-Auswahl der Kachel "Reiseklasse" - dieselbe Zeile wie im
     * Formular "Neuer Flug". Anders als dort ist immer eine Reiseart gewaehlt:
     * Die Kachel zeigt nie alle Klassen auf einmal, Standard ist "Privat".
     */
    private fun setupClassTravelType() {
        classTravelType = Settings.getClassTravelType(requireContext())
        binding.classTravelTypeSection.visibility = View.VISIBLE

        val topTiles = classTopTiles()
        SelectionHighlight.configure(
            binding.classFlightGroupFrame, binding.classFlightHighlight, topTiles
        )
        topTiles.forEachIndexed { index, tile ->
            tile.setOnClickListener { selectClassTopType(index) }
        }

        val detailTiles = classDeadheadTiles()
        SelectionHighlight.configure(
            binding.classDeadheadGroupFrame, binding.classDeadheadHighlight, detailTiles
        )
        detailTiles.forEachIndexed { index, tile ->
            tile.setOnClickListener { selectClassDeadheadType(index) }
        }

        applyClassTravelType()
    }

    /** Markiert die gewaehlte Reiseart und blendet die Deadhead-Zeile passend ein. */
    private fun applyClassTravelType() {
        val topIndex = ClassTravelType.topIndexOf(classTravelType)
        SelectionHighlight.move(
            binding.classFlightGroupFrame,
            binding.classFlightHighlight,
            classTopTiles(),
            topIndex,
            ContextCompat.getColor(requireContext(), classTopColorRes(topIndex))
        )
        updateClassTravelTypeLabels(classTopLabels(), topIndex)

        val detailIndex = ClassTravelType.detailIndexOf(classTravelType)
        binding.classDeadheadSection.visibility =
            if (detailIndex >= 0) View.VISIBLE else View.GONE
        if (detailIndex >= 0) {
            SelectionHighlight.move(
                binding.classDeadheadGroupFrame,
                binding.classDeadheadHighlight,
                classDeadheadTiles(),
                detailIndex,
                ContextCompat.getColor(requireContext(), R.color.type_deadhead_bg)
            )
        }
        updateClassTravelTypeLabels(classDeadheadLabels(), detailIndex.takeIf { it >= 0 })
    }

    /**
     * Wechselt die oberste Reiseart. Bei "Deadhead" bleibt eine zuvor gewaehlte
     * Unterart (Ferry, Ground Transfer) erhalten, sonst beginnt es mit Deadhead.
     */
    private fun selectClassTopType(index: Int) {
        val top = ClassTravelType.TOP_LEVEL.getOrNull(index) ?: return
        classTravelType = if (top == ChartData.DEADHEAD_CANONICAL) {
            classTravelType.takeIf { it in ClassTravelType.DEADHEAD_DETAIL }
                ?: ChartData.DEADHEAD_CANONICAL
        } else {
            top
        }
        persistClassTravelType()
    }

    private fun selectClassDeadheadType(index: Int) {
        classTravelType = ClassTravelType.DEADHEAD_DETAIL.getOrNull(index) ?: return
        persistClassTravelType()
    }

    private fun persistClassTravelType() {
        Settings.setClassTravelType(requireContext(), classTravelType)
        applyClassTravelType()
        renderClassPie()
    }

    private fun classTopTiles() = listOf(
        binding.tileClassTypePrivate,
        binding.tileClassTypeOnDuty,
        binding.tileClassTypeDeadhead,
        binding.tileClassTypeDutyTravel,
    )

    private fun classTopLabels() = listOf(
        binding.labelClassTypePrivate,
        binding.labelClassTypeOnDuty,
        binding.labelClassTypeDeadhead,
        binding.labelClassTypeDutyTravel,
    )

    private fun classDeadheadTiles() = listOf(
        binding.tileClassDeadheadDeadhead,
        binding.tileClassDeadheadFerry,
        binding.tileClassDeadheadGroundTransfer,
    )

    private fun classDeadheadLabels() = listOf(
        binding.labelClassDeadheadDeadhead,
        binding.labelClassDeadheadFerry,
        binding.labelClassDeadheadGroundTransfer,
    )

    private fun classTopColorRes(index: Int): Int = when (index) {
        0 -> R.color.type_private_bg
        1 -> R.color.type_on_duty_bg
        2 -> R.color.type_deadhead_bg
        else -> R.color.type_duty_travel_bg
    }

    private fun updateClassTravelTypeLabels(labels: List<TextView>, selectedIndex: Int?) {
        val resting = InputTile.hintColor(binding.root)
        labels.forEachIndexed { index, label ->
            label.setTextColor(if (index == selectedIndex) Color.WHITE else resting)
        }
    }

    private fun renderClassPie() {
        renderPie(
            ChartData.classSlices(
                requireContext(),
                null,
                ChartData.periodFiltered(requireContext()),
                classTravelType
            )
        )
    }

    /**
     * Merkt die Layover-Sortierung und die Layover-Suche ueber eine
     * Neuerstellung der Seite hinweg.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_LAYOVER_SORT, layoverSort.name)
        outState.putString(KEY_LAYOVER_QUERY, layoverQuery)
        outState.putBoolean(KEY_LAYOVER_SEARCH_OPEN, layoverSearchOpen)
    }

    override fun onDestroyView() {
        // Zuerst die Zuhoerer entfernen: das Abbrechen ruft sonst noch einmal
        // in die Ansicht hinein, die gleich danach freigegeben wird.
        searchAnimator?.removeAllListeners()
        searchAnimator?.removeAllUpdateListeners()
        searchAnimator?.cancel()
        searchAnimator = null
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Anteil der Bildschirmhoehe, den die Diagrammflaeche einnimmt. */
        const val COLUMN_CHART_HEIGHT_FACTOR = 0.33f

        /** Grenzen der gemeinsamen Schriftgrösse der beiden Code-Kacheln. */
        const val LAYOVER_CODE_MIN_SP = 14
        const val LAYOVER_CODE_MAX_SP = 28

        /** Schlüssel für die gemerkte Layover-Sortierung im Zustand. */
        const val KEY_LAYOVER_SORT = "layoverSort"

        /** Schlüssel für die gemerkte Layover-Suche im Zustand. */
        const val KEY_LAYOVER_QUERY = "layoverQuery"

        /** Schlüssel dafür, ob das Layover-Suchfeld ausgeklappt ist. */
        const val KEY_LAYOVER_SEARCH_OPEN = "layoverSearchOpen"

        /** Dauer der Aufklapp-/Einklappanimation des Suchfelds. */
        const val SEARCH_ANIMATION_MS = 220L

        val DATE_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}