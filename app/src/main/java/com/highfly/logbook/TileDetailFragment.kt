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

    /** Nach was Hangar (Flugzeugtypen und Registrierungen) geordnet wird. */
    private var hangarSort: ChartData.HangarSort = ChartData.HangarSort.ANZAHL

    /** Reiseart, nach der die Kachel "Reiseklasse" ihre Klassen zeigt. */
    private var classTravelType: String = ClassTravelType.DEFAULT

    /** Wessen Balken das Suchfeld oben im Kopf gerade filtert. */
    private enum class SearchMode { LAYOVER, AIRPORT }

    private var searchMode = SearchMode.LAYOVER

    /** Suchtext der Layover- und der Flughäfen-Kachel: Dreilettercode oder Stadt. */
    private var searchQuery: String = ""

    /** Ob das Suchfeld oben im Kopf ausgeklappt ist. */
    private var searchOpen = false

    /** Alle Layover-Balken vor der Suche, damit die Eingabe nur filtern muss. */
    private var layoverAllBars: List<ChartData.Bar> = emptyList()

    /** Alle Flughäfen-Balken vor der Suche, damit die Eingabe nur filtern muss. */
    private var airportAllBars: List<ChartData.Bar> = emptyList()

    /** Laufende Aufklapp-/Einklappanimation des Suchfelds. */
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
        savedInstanceState?.getString(KEY_SEARCH_QUERY)?.let { searchQuery = it }
        searchOpen = savedInstanceState?.getBoolean(KEY_SEARCH_OPEN) ?: false
        savedInstanceState?.getString(KEY_HANGAR_SORT)?.let { name ->
            hangarSort = ChartData.HangarSort.entries
                .firstOrNull { it.name == name } ?: hangarSort
        }

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
                renderAirports()
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
        setupTileSearch(SearchMode.LAYOVER)
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
        val query = searchQuery.trim()
        val matching = if (query.isEmpty()) {
            layoverAllBars
        } else {
            layoverAllBars.filter { airportMatches(it.label, query) }
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
     * Lupe oben im Kopf, rechts im Header: Ein Tipp klappt links daneben das
     * Suchfeld aus, ein zweiter wieder ein und hebt den Filter auf. Gesucht
     * wird nach dem Flughafencode und nach der Stadt - je nach Kachel filtert
     * die Eingabe die Layover- oder die Flughäfen-Balken.
     */
    private fun setupTileSearch(mode: SearchMode) {
        searchMode = mode
        binding.ivSearch.visibility = View.VISIBLE
        binding.ivSearch.contentDescription = getString(
            if (mode == SearchMode.LAYOVER) R.string.layover_search_desc
            else R.string.airport_search_desc
        )
        binding.etSearch.setHint(
            if (mode == SearchMode.LAYOVER) R.string.layover_search_hint
            else R.string.airport_search_hint
        )
        // Text vor dem Beobachter setzen: die wiederhergestellte Suche soll
        // nicht schon hier, sondern erst beim Zeichnen der Balken greifen.
        binding.etSearch.setText(searchQuery)
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                applySearchFilter()
            }
        })
        binding.ivSearch.setOnClickListener {
            toggleSearch(!searchOpen)
        }
        if (searchOpen) {
            binding.etSearch.visibility = View.VISIBLE
            binding.etSearch.alpha = 1f
            setSearchProgress(1f)
        }
    }

    /** Filtert je nach Kachel die Layover- oder die Flughäfen-Balken. */
    private fun applySearchFilter() {
        when (searchMode) {
            SearchMode.LAYOVER -> applyLayoverFilter()
            SearchMode.AIRPORT -> applyAirportFilter()
        }
    }

    /**
     * Trifft die Suche den Flughafen - ueber den Code, die Stadt oder die
     * deutsche Schreibweise der Stadt (z. B. "Muenchen" fuer MUC).
     */
    private fun airportMatches(code: String, query: String): Boolean {
        if (code.contains(query, ignoreCase = true)) return true
        val info = AirportNames.get(requireContext(), code) ?: return false
        if (info.city?.contains(query, ignoreCase = true) == true) return true
        return info.cityDe?.contains(query, ignoreCase = true) == true
    }

    /**
     * Klappt das Suchfeld aus oder ein. Beim Oeffnen waechst es aus der Lupe
     * heraus nach links ueber den Titel, beim Schliessen laeuft es rueckwaerts
     * und leert die Suche erst am Ende - so springt die Liste nicht schon
     * waehrend der Animation auf den vollen Bestand zurueck.
     */
    private fun toggleSearch(open: Boolean) {
        searchAnimator?.cancel()
        searchOpen = open
        if (open) {
            binding.etSearch.visibility = View.VISIBLE
            binding.etSearch.requestFocus()
            binding.etSearch.post {
                if (_binding != null) showSearchKeyboard()
            }
        } else {
            hideSearchKeyboard()
        }
        searchAnimator = ValueAnimator.ofFloat(if (open) 0f else 1f, if (open) 1f else 0f)
            .apply {
                duration = SEARCH_ANIMATION_MS
                addUpdateListener { animation ->
                    setSearchProgress(animation.animatedValue as Float)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!open) {
                            binding.etSearch.visibility = View.GONE
                            // Leeren loest den Beobachter aus und zeigt wieder
                            // alle Balken.
                            binding.etSearch.setText("")
                        }
                    }
                })
                start()
            }
    }

    /**
     * Oeffnungsgrad des Suchfelds als gegenlaeufige Gewichte: Bei 0 traegt der
     * Titel den Rest und das Feld bleibt ohne Breite, bei 1 fuellt das Feld
     * den Platz bis zur Lupe und der Titel verschwindet. Die Lupe rechts
     * bleibt dabei stehen.
     */
    private fun setSearchProgress(progress: Float) {
        val field = binding.etSearch
        if (progress > 0f) field.visibility = View.VISIBLE
        field.alpha = progress
        val fieldParams = field.layoutParams as LinearLayout.LayoutParams
        fieldParams.weight = progress
        field.requestLayout()
        val titleParams = binding.titleGroup.layoutParams as LinearLayout.LayoutParams
        titleParams.weight = 1f - progress
        binding.titleGroup.requestLayout()
    }

    private fun showSearchKeyboard() {
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearchKeyboard() {
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
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

    /**
     * Kachel "Flughäfen": Kompass mit den Extrem-Flughäfen, Höhen-Grafik mit
     * dem niedrigsten und höchsten Flughafen, darunter die Flughäfen als
     * Balkenliste von oben nach unten mit Medaillen wie die anderen Kacheln.
     * Das Diagramm der Flugrouten liegt in der eigenen Kachel "flightroutes".
     * Ein Tipp auf einen Balken oder auf einen Code im Kompass beziehungsweise
     * in der Höhen-Grafik öffnet die Detailseite des Flughafens.
     */
    private fun renderAirports() {
        val entries = ChartData.periodFiltered(requireContext())
        binding.compassView.setOnAirportClickListener { openAirportDetail(it) }
        binding.altitudeView.setOnAirportClickListener { openAirportDetail(it) }
        setupAirportCompass(entries)
        setupAirportAltitude(entries)
        airportAllBars = ChartData.airportBars(requireContext())
        setupTileSearch(SearchMode.AIRPORT)
        applyAirportFilter()
    }

    /**
     * Zeichnet die Flughäfen-Liste erneut. Ohne Sucheingabe stehen die
     * angeflogenen Flughäfen mit ihrer Zahl an Starts und Landungen. Mit
     * Sucheingabe wird der gesamte gespeicherte Flughafenbestand durchsucht -
     * nach Stadt, IATA- und ICAO-Code - sodass auch nie angeflogene Flughäfen
     * erscheinen (dann mit dem Wert 0). Ein Tipp auf eine Zeile öffnet die
     * Detailseite des Flughafens. Trifft die Eingabe nichts, bleibt nur der
     * Hinweis und die Liste verschwindet.
     */
    private fun applyAirportFilter() {
        val query = searchQuery.trim()
        val bars = if (query.isEmpty()) {
            airportAllBars
        } else {
            val counts = airportAllBars.associate { it.label.uppercase() to it.count }
            AirportDirectory.search(requireContext(), query, AIRPORT_SEARCH_LIMIT).map {
                ChartData.Bar(it.iata, counts[it.iata.uppercase()] ?: 0)
            }
        }

        binding.tvEmpty.setText(
            if (query.isEmpty()) R.string.chart_empty else R.string.airport_search_empty
        )
        if (bars.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.barChart.visibility = View.GONE
            binding.barChart.setItems(emptyList())
            return
        }
        binding.tvEmpty.visibility = View.GONE
        binding.barChart.visibility = View.VISIBLE
        // Medaillen für die ersten drei Plätze, Wert neben dem Balken - wie
        // die anderen Balkenkacheln des Dashboards.
        binding.barChart.setShowRanks(true)
        binding.barChart.setValueInsideBar(false)
        binding.barChart.setOnItemClickListener { index ->
            openAirportDetail(bars.getOrNull(index)?.label)
        }
        binding.barChart.setItems(
            bars.map { BarChartView.Item(it.label, it.count, it.subLabel, it.countLabel) }
        )
    }

    /** Detailseite eines Flughafens, wie sie ein Klick in der Kachel öffnet. */
    private fun openAirportDetail(label: String?) {
        val code = label?.trim()?.uppercase()
        if (code.isNullOrBlank()) return
        findNavController().navigate(
            R.id.action_tile_detail_to_airport_detail,
            bundleOf(AirportDetailFragment.ARG_CODE to code)
        )
    }

    /** Ein berührter Flughafen mit Koordinate, Höhe und Kurzname. */
    private data class AirportSite(
        val code: String,
        val lat: Double,
        val lon: Double,
        val meters: Int?,
        val label: String
    )

    /**
     * Alle Flughäfen der Flüge des Zeitraums (Abflug und Ziel), mit Standort
     * aus [AirportData] und Stadt/Name aus [AirportNames]. Flughäfen ohne
     * bekannte Koordinate fallen heraus.
     */
    private fun airportSites(entries: List<LogbookEntry>): List<AirportSite> {
        val context = requireContext()
        return entries.flatMap { listOf(it.fromAirport, it.toAirport) }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { raw ->
                val code = raw.uppercase()
                val location = AirportData.location(context, code)
                    ?: return@mapNotNull null
                val info = AirportNames.get(context, code)
                val label = info?.cityDe ?: info?.city ?: info?.name ?: code
                AirportSite(
                    code,
                    location.lat,
                    location.lon,
                    AirportData.elevationMeters(context, code),
                    label
                )
            }
    }

    /**
     * Kompass befüllen: Mitte der häufigste Abflughafen (meiste Starts),
     * Norden der nördlichste, Süden der südlichste, Westen der westlichste,
     * Osten der östlichste Flughafen des Datensatzes. Derselbe Flughafen darf
     * mehrere Positionen belegen, dann wiederholt sich sein Code.
     */
    private fun setupAirportCompass(entries: List<LogbookEntry>) {
        val context = requireContext()
        val sites = airportSites(entries)
        val fromCounts = entries.mapNotNull { it.fromAirport.takeIf(String::isNotBlank) }
            .groupingBy { it.uppercase() }
            .eachCount()
        val topFrom = fromCounts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
            )
            .firstOrNull()
            ?.key
        val centerInfo = topFrom?.let { top ->
            val info = AirportNames.get(context, top)
            CompassView.Airport(top, info?.cityDe ?: info?.city ?: info?.name ?: top)
        }
        if (centerInfo == null || sites.isEmpty()) {
            binding.compassView.visibility = View.GONE
            return
        }
        val north = sites.maxWithOrNull(compareBy<AirportSite> { it.lat }.thenBy { it.code })
        val south = sites.minWithOrNull(compareBy<AirportSite> { it.lat }.thenBy { it.code })
        val west = sites.minWithOrNull(compareBy<AirportSite> { it.lon }.thenBy { it.code })
        val east = sites.maxWithOrNull(compareBy<AirportSite> { it.lon }.thenBy { it.code })
        binding.compassView.visibility = View.VISIBLE
        binding.compassView.setAirports(
            center = centerInfo,
            north = north?.let { CompassView.Airport(it.code, it.label) },
            south = south?.let { CompassView.Airport(it.code, it.label) },
            west = west?.let { CompassView.Airport(it.code, it.label) },
            east = east?.let { CompassView.Airport(it.code, it.label) }
        )
    }

    /**
     * Höhen-Grafik befüllen: unten der niedrigstgelegene Flughafen (Höhe über
     * N.N.), oben auf der Bergspitze der höchstgelegene. Ohne mindestens zwei
     * bekannte Höhen bleibt die Grafik verborgen, damit sie nicht leer läuft.
     */
    private fun setupAirportAltitude(entries: List<LogbookEntry>) {
        try {
            val context = requireContext()
            val allSites = airportSites(entries)
            val sites = allSites.filter { it.meters != null }
            val byMeters = compareBy<AirportSite> { it.meters ?: 0 }.thenBy { it.code }
            val lowest = sites.minWithOrNull(byMeters)
            val highest = sites.maxWithOrNull(byMeters)
            if (lowest == null || highest == null || lowest.code == highest.code) {
                binding.altitudeView.visibility = View.GONE
                return
            }
            binding.altitudeView.visibility = View.VISIBLE
            binding.altitudeView.setAltitudeAirports(
                AirportAltitudeView.Airport(lowest.code, lowest.label, lowest.meters),
                AirportAltitudeView.Airport(highest.code, highest.label, highest.meters)
            )
        } catch (e: Exception) {
        }
    }

    private fun renderAircraftRegistrations() {
        setupHangarSort()
        renderHangar()
    }

    /**
     * Sortierung der Hangar-Seite als diskreter Text oben rechts, wie die
     * Layover-Sortierung. Standard ist "Anzahl"; "Entfernung" ordnet
     * Flugzeugtypen und Registrierungen nach den geflogenen Kilometern. Ein
     * Wechsel zeichnet beide Diagramme neu.
     */
    private fun setupHangarSort() {
        binding.aircraftSortContainer.visibility = View.VISIBLE
        binding.tvAircraftSort.text = hangarSortLabel()
        binding.tvAircraftSort.setOnClickListener { anchor ->
            val options = ChartData.HangarSort.entries
            FilterPopupMenu.showOptions(
                requireContext(),
                anchor,
                options.map { it.name },
                options.map { hangarSortLabel(it) },
                hangarSort.name
            ) { key ->
                val selected = ChartData.HangarSort.entries.firstOrNull { it.name == key }
                if (selected != null && selected != hangarSort) {
                    hangarSort = selected
                    binding.tvAircraftSort.text = hangarSortLabel()
                    renderHangar()
                }
            }
        }
    }

    private fun hangarSortLabel(sort: ChartData.HangarSort = hangarSort): String = when (sort) {
        ChartData.HangarSort.ANZAHL -> getString(R.string.hangar_sort_count)
        ChartData.HangarSort.ENTFERNUNG -> getString(R.string.hangar_sort_distance)
    }

    private fun renderHangar() {
        val types = ChartData.aircraftBars(requireContext(), hangarSort)
        renderScrollableColumnChart(
            types,
            binding.aircraftChart,
            binding.barAircraft,
            binding.barAircraftAxis,
            binding.tvAircraftTitle
        )
        val registrations = ChartData.hangarBars(requireContext(), hangarSort) { it.registration }
        renderBars(registrations)
        binding.tvRegistrationsTitle.visibility =
            if (registrations.isEmpty()) View.GONE else View.VISIBLE
        binding.tvEmpty.visibility =
            if (types.isEmpty() && registrations.isEmpty()) View.VISIBLE else View.GONE
        // Ohne etwas zum Sortieren verschwindet der Filter aus dem Header wie
        // die Sortierzeile bei der Layover-Kachel; ueber dem Leertext haette er
        // nichts zu ordnen.
        binding.aircraftSortContainer.visibility =
            if (types.isEmpty() && registrations.isEmpty()) View.GONE else View.VISIBLE
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
        val chartItems = bars.map {
            MonthBarChartView.Item(it.label, it.count, it.countLabel)
        }

        chart.setItems(chartItems)
        // Die Achsenmaske waechst mit ihrer Beschriftung: Die Kilometerwerte
        // der Hangar-Kachel sind vielstellig und passen nicht in die schmale
        // Standardbreite. Das Diagramm bekommt denselben linken Rand, damit
        // die ueberlagerte Achse und die gezeichnete Achse deckungsgleich
        // bleiben.
        val axisWidth = maxOf(
            (AXIS_WIDTH_DP * resources.displayMetrics.density).toInt(),
            chart.axisLabelWidthPx()
        )
        val axisParams = axis.layoutParams
        if (axisParams.width != axisWidth) {
            axisParams.width = axisWidth
            axis.layoutParams = axisParams
        }
        chart.setLeadingSpace(axisWidth)
        chart.setPlotHeight(chartHeight)

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
     * Merkt die Layover-Sortierung und die Suche ueber eine Neuerstellung der
     * Seite hinweg.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_LAYOVER_SORT, layoverSort.name)
        outState.putString(KEY_SEARCH_QUERY, searchQuery)
        outState.putBoolean(KEY_SEARCH_OPEN, searchOpen)
        outState.putString(KEY_HANGAR_SORT, hangarSort.name)
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

        /** Schmale Mindestbreite der festen y-Achsenmaske der Spaltendiagramme. */
        const val AXIS_WIDTH_DP = 28

        /**
         * Obergrenze der Suchtreffer in der Flughäfen-Kachel. Der Bestand
         * umfasst knapp neuntausend Flughäfen; ohne Grenze würde eine kurze
         * Eingabe die Liste endlos lang machen.
         */
        const val AIRPORT_SEARCH_LIMIT = 40

        /** Grenzen der gemeinsamen Schriftgrösse der beiden Code-Kacheln. */
        const val LAYOVER_CODE_MIN_SP = 14
        const val LAYOVER_CODE_MAX_SP = 28

        /** Schlüssel für die gemerkte Layover-Sortierung im Zustand. */
        const val KEY_LAYOVER_SORT = "layoverSort"

        /** Schlüssel für die gemerkte Suche im Zustand. */
        const val KEY_SEARCH_QUERY = "searchQuery"

        /** Schlüssel dafür, ob das Suchfeld oben im Kopf ausgeklappt ist. */
        const val KEY_SEARCH_OPEN = "searchOpen"

        /** Schlüssel für die gemerkte Hangar-Sortierung im Zustand. */
        const val KEY_HANGAR_SORT = "hangarSort"

        /** Dauer der Aufklapp-/Einklappanimation des Suchfelds. */
        const val SEARCH_ANIMATION_MS = 220L

        val DATE_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}