package com.highfly.logbook

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.highfly.logbook.databinding.FragmentGlobeBinding

/**
 * Seite „Weltkugel" (Klick auf die Dashboard-Kachel): große bedienbare Kugel
 * ([GlobeView]) mit allen Flugverbindungen des Logbuchs - gerendert wie die
 * Weltkarte mit Dreilettercodes, gleichem Filter ([sheet_map_filter.xml],
 * [MapFilter]) und gleichen Knöpfen (Zurück, Filter, Zoom, Reset), dazu der
 * Maßstab unten rechts ([GlobeScaleBarView]) und die Layover-Animation
 * ([LayoverAnimation], wie auf der Weltkarte mit Spuren-Knopf).
 *
 * Im Unterschied zur Weltkarte (osmdroid) zeichnet die Kugel alles selbst auf
 * einem Canvas und baut die Pfade auf einem Hintergrund-Thread neu - Drehen
 * und Zoomen laufen dadurch flüssig. Ab [GlobeView.ZOOM_CITIES] erscheinen
 * zusätzlich Städtebeschriftungen. Laden darf kurz dauern: bis die Daten da
 * sind, liegt ein Ladebildschirm über der Kugel.
 */
class GlobeFragment : LogbookFragment() {

    private var _binding: FragmentGlobeBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    /** Aktiver Kartenfilter, siehe [MapFilter]. */
    private var activeMapFilter = MapFilter()

    /** Einträge nach Filter, Grundlage der Routen. */
    private var entriesData: List<LogbookEntry> = emptyList()

    /** Alle Routen nach Filter (ungefiltert nach Auswahl). */
    private var allRoutes: List<GlobeRoutes.Arc> = emptyList()

    /** Alle Flughäfen nach Filter. */
    private var airportsData: Map<String, GlobeRoutes.Airport> = emptyMap()

    /** Vernetzungsgrad je Flughafen (Badge-Reihenfolge auf der Kugel). */
    private var connectionsData: Map<String, Int> = emptyMap()

    /** Länder und Städte für die Kugel. */
    private var countriesData: List<CountryShapes.Country> = emptyList()
    private var citiesData: List<MapCity> = emptyList()

    /** Ausgewählter Flughafen (Tipp auf einen Punkt), null = alle. */
    private var selectedIata: String? = null

    /** Unteres Systemleisten-Inset in px, zuletzt in [applyInsets] gesetzt. */
    private var systemBarsBottomPx = 0

    /** Hinweis-Popup am Spuren-Knopf, null wenn keines offen ist. */
    private var layoverHintPopup: PopupWindow? = null
    private val layoverHintDismiss = Runnable { dismissLayoverHint() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGlobeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets selbst anwenden wie die Weltkarte: MainActivity konsumiert sie
        // auf der Wurzelansicht, deshalb zusätzlich direkt über die
        // Root-Window-Insets lesen, sobald das Layout steht.
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            applyInsets(insets.getInsets(WindowInsetsCompat.Type.systemBars()))
            insets
        }
        view.doOnLayout {
            ViewCompat.getRootWindowInsets(view)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())
                ?.let { applyInsets(it) }
        }
        // Spuren-Knopf auf Höhe des Plus-Knopfes ausrichten, Stats-Karte
        // vertikal mittig zu den oberen Knöpfen zentrieren. Nach dem Zerstören
        // der Ansicht darf kein Listener mehr auf binding zugreifen.
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (_binding == null) return@addOnLayoutChangeListener
            alignLayoverButtonToZoomIn()
            alignStatsCardToButtons()
        }

        // Filter aus Navigationsargumenten: der 2D/3D-Wechsel übergibt den
        // kompletten Kartenfilter, Deep-Links (z. B. Airline-Detailseite) die
        // Einzelfelder.
        activeMapFilter = MapFilter.fromBundle(arguments)
        val filterTravelBuddy = arguments?.getString("filterTravelBuddy")?.trim().orEmpty()
        if (filterTravelBuddy.isNotBlank()) {
            activeMapFilter = activeMapFilter.copy(travelBuddy = filterTravelBuddy)
        }
        val filterAirline = arguments?.getString("filterAirline")?.trim().orEmpty()
        if (filterAirline.isNotBlank()) {
            activeMapFilter = activeMapFilter.copy(airline = filterAirline)
        }

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnZoomIn.setOnClickListener { binding.globeView.zoomBy(ZOOM_STEP) }
        binding.btnZoomOut.setOnClickListener { binding.globeView.zoomBy(1f / ZOOM_STEP) }
        binding.btnReset.setOnClickListener { binding.globeView.resetToWorld() }
        // „2D“-Knopf unter dem Reset: wechselt zur 2D-Karte, immer im
        // Querformat; der aktive Filter wird mitgegeben.
        binding.btnToggle2d.setOnClickListener {
            val bundle = Bundle().apply {
                putBoolean(MapFilter.ARG_FORCE_LANDSCAPE, true)
                activeMapFilter.writeTo(this)
            }
            findNavController().navigate(R.id.action_globe_to_world_map, bundle)
        }
        binding.btnFilter.setOnClickListener { showFilterDialog() }
        binding.btnFilterReset.setOnClickListener {
            activeMapFilter = MapFilter()
            applyFilters()
        }
        binding.btnLayoverTrails.setOnClickListener {
            binding.globeView.showLayoverTrails()
            dismissLayoverHint()
        }
        binding.statsCard.setOnClickListener {
            val iata = selectedIata ?: return@setOnClickListener
            findNavController().navigate(
                R.id.action_globe_to_entries,
                Bundle().apply { putString("airportFilter", iata) }
            )
        }

        val accent = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)
        binding.globeView.setColors(accent)
        binding.globeView.interactive = true
        binding.globeView.onAirportTap = { onAirportTap(it) }
        binding.globeView.onLayoverFinished = { showLayoverTrailsButton() }
        // Null-sicher: Das Lambda feuert aus dem Aufbau-Thread der Kugel und
        // darf nach dem Zerstören der Ansicht (z. B. Orientierungs-Recreate
        // beim 2D/3D-Wechsel) nicht mehr auf binding zugreifen.
        binding.globeView.onStateChanged = {
            _binding?.let { b -> b.scaleBar.update(b.globeView.metersPerPixelAtCenter()) }
        }

        loadAndRender()
    }

    override fun onResume() {
        super.onResume()
        _binding?.globeView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        _binding?.globeView?.onPause()
    }

    override fun onDestroyView() {
        dismissLayoverHint()
        _binding?.globeView?.stopLayover()
        super.onDestroyView()
        _binding = null
    }

    /**
     * Positioniert die schwebenden Elemente an den Systemleisten: die oberen
     * Knöpfe unterhalb der Statusleiste, die unteren (Zoom-Knöpfe, Maßstab,
     * Stats-Karte) oberhalb der Systemleiste. Nur bei tatsächlicher Änderung
     * umlegen, damit daraus keine Layoutschleife entsteht.
     */
    private fun applyInsets(bars: androidx.core.graphics.Insets) {
        val b = _binding ?: return
        val top = bars.top
        val bottom = bars.bottom
        if (bottom == systemBarsBottomPx && top == lastStatusBarTopPx) return
        systemBarsBottomPx = bottom
        lastStatusBarTopPx = top
        val density = resources.displayMetrics.density
        fun setTopMargin(view: View, extraDp: Int) {
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val target = top + (extraDp * density).toInt()
            if (lp.topMargin != target) {
                lp.topMargin = target
                view.layoutParams = lp
            }
        }
        fun setBottomMargin(view: View, extraDp: Int) {
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val target = bottom + (extraDp * density).toInt()
            if (lp.bottomMargin != target) {
                lp.bottomMargin = target
                view.layoutParams = lp
            }
        }
        setTopMargin(b.btnBack, 20)
        setTopMargin(b.filterButtons, 20)
        // Zoom-Knopfspalte rechts und Maßstab links beide direkt über der
        // Systemleiste - der Maßstab sitzt damit auf Höhe des untersten
        // rechten Knopfes.
        setBottomMargin(b.mapControls, 0)
        setBottomMargin(b.scaleBar, 0)
    }

    private var lastStatusBarTopPx = -1

    /** Richtet den Spuren-Knopf an der Oberkante des Plus-Knopfes aus. */
    private fun alignLayoverButtonToZoomIn() {
        val b = _binding ?: return
        val zoomIn = b.btnZoomIn
        val btn = b.btnLayoverTrails
        val lp = btn.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val target = zoomIn.top
        if (lp.topMargin != target) {
            lp.topMargin = target
            btn.layoutParams = lp
        }
    }

    /**
     * Zentriert die Stats-Karte oben vertikal mittig zu den 48-dp-Knöpfen
     * (Zurück links, Filter rechts): gleiche Oberkante wie die Knöpfe minus
     * halber Höhenunterschied.
     */
    private fun alignStatsCardToButtons() {
        val b = _binding ?: return
        val back = b.btnBack
        val card = b.statsCard
        if (back.height <= 0 || card.height <= 0) return
        val lp = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val target = back.top + (back.height - card.height) / 2
        if (lp.topMargin != target) {
            lp.topMargin = target
            card.layoutParams = lp
        }
    }

    /**
     * Lädt Länder, Städte und die gefilterten Einträge im Hintergrund und
     * befüllt die Kugel. Der Generationszähler verwirft veraltete Ergebnisse.
     */
    private fun loadAndRender() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        binding.loadingOverlay.visibility = View.VISIBLE
        Thread {
            val context = appContext
            val countries = CountryShapes.load(context).orEmpty()
            val cities = MapCities.load(context)
            val resolveAirline = airlineKeyResolver(context)
            val allEntries = DashboardStats.dashboardEntries(context)
            val entries = allEntries.filter { activeMapFilter.matches(it, resolveAirline) }
            val result = GlobeRoutes.compute(entries) { AirportData.location(context, it) }
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                countriesData = countries
                citiesData = cities
                entriesData = entries
                allRoutes = result.routes
                airportsData = result.airports
                connectionsData = result.connections
                render()
            }
        }.start()
    }

    /** Befüllt Kugel, Stats und Layover nach (Neu-)Laden oder Filterwechsel. */
    private fun render() {
        if (_binding == null) return
        updateLayover()
        applyContent()
        updateFilterButton()
        // Ladebildschirm weich ausblenden.
        val current = _binding ?: return
        current.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { current.loadingOverlay.visibility = View.GONE }
            .start()
    }

    /** Übergibt die Routen (ggf. auf die Auswahl gefiltert) an die Kugel. */
    private fun applyContent() {
        if (_binding == null) return
        val selected = selectedIata
        val visibleRoutes = if (selected == null) {
            allRoutes
        } else {
            allRoutes.filter { MapSelection.touches(it.from to it.to, selected) }
        }
        binding.globeView.setContent(
            GlobeView.Content(
                countries = countriesData,
                routes = visibleRoutes,
                airports = airportsData,
                connections = connectionsData,
                cities = citiesData
            )
        )
        // Stats-Karte wie auf der Weltkarte.
        val hasActiveFilter = selected != null || activeMapFilter.isActive()
        if (visibleRoutes.isEmpty()) {
            binding.statsCard.visibility = View.GONE
            binding.tvShowEntries.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.setText(
                if (hasActiveFilter) R.string.entries_no_results else R.string.world_map_empty
            )
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.statsCard.visibility = View.VISIBLE
            val totalFlights = visibleRoutes.sumOf { it.count }
            binding.tvStats.text = if (selected == null) {
                getString(R.string.world_map_stats, totalFlights, visibleRoutes.size, airportsData.size)
            } else {
                getString(R.string.world_map_filter_stats, selected, totalFlights, visibleRoutes.size)
            }
            binding.tvShowEntries.visibility = if (selected != null) View.VISIBLE else View.GONE
        }
        binding.scaleBar.update(binding.globeView.metersPerPixelAtCenter())
    }

    /** Wendet den aktiven Filter erneut an (nach Dialog oder Reset). */
    private fun applyFilters() {
        loadAndRender()
    }

    /** Tippen auf einen Flughafen: Auswahl umschalten und Routen filtern. */
    private fun onAirportTap(iata: String) {
        selectedIata = if (selectedIata == iata) null else iata
        applyContent()
    }

    // ---------------------------------------------------------------- Layover

    /**
     * Plant die Layover-Animation neu (bei Layover = „Ja"): Die statischen
     * Linien werden währenddessen in der Kugel ausgeblendet, die animierten
     * Spuren zeichnen stattdessen. Danach erscheint der Spuren-Knopf.
     */
    private fun updateLayover() {
        if (_binding == null) return
        val plan = if (animationsEnabled() && activeMapFilter.layover == "yes") {
            val context = requireContext()
            val resolveAirline = airlineKeyResolver(context)
            val layoverEntries = entriesData.filter { activeMapFilter.matches(it, resolveAirline) }
            LayoverAnimation.plan(layoverEntries) { AirportData.location(context, it) }
        } else {
            null
        }
        if (plan == null) {
            binding.globeView.stopLayover()
            hideLayoverTrailsButton()
        } else {
            binding.globeView.startLayover(plan)
        }
    }

    private fun animationsEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(
            requireContext().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f

    private fun showLayoverTrailsButton() {
        val btn = _binding?.btnLayoverTrails ?: return
        val wasHidden = btn.visibility != View.VISIBLE
        btn.visibility = View.VISIBLE
        if (wasHidden) btn.post { showLayoverTrailsHint() }
    }

    private fun hideLayoverTrailsButton() {
        _binding?.btnLayoverTrails?.visibility = View.GONE
        dismissLayoverHint()
    }

    /** Hinweis-Popup rechts neben dem Spuren-Knopf, blendet sich nach 3 s aus. */
    private fun showLayoverTrailsHint() {
        val btn = _binding?.btnLayoverTrails ?: return
        if (!isAdded || btn.visibility != View.VISIBLE) return
        dismissLayoverHint()
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val bubbleColor = MaterialColors.getColor(btn, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val bubble = TextView(context).apply {
            setText(R.string.world_map_layover_trails_hint)
            setTextColor(MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOnSurface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxWidth = dp(230)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                val r = dp(12).toFloat()
                setCornerRadii(floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f))
                setColor(bubbleColor)
            }
        }
        val pointer = ImageView(context).apply {
            setImageResource(R.drawable.ic_hint_pointer)
            setColorFilter(bubbleColor)
            val size = dp(12)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = -size / 2
            layoutParams = lp
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pointer)
            addView(bubble)
        }
        val popup = PopupWindow(
            row,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(4).toFloat()
        }
        row.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popup.showAsDropDown(btn, dp(8), -row.measuredHeight / 2 + btn.height / 2)
        layoverHintPopup = popup
        btn.postDelayed(layoverHintDismiss, LAYOVER_HINT_MS)
    }

    private fun dismissLayoverHint() {
        layoverHintPopup?.dismiss()
        layoverHintPopup = null
        _binding?.btnLayoverTrails?.removeCallbacks(layoverHintDismiss)
    }

    // ---------------------------------------------------------------- Filter-Dialog

    /** Löst einen Airline-Text auf den kanonischen Schlüssel auf ("LH" = "DLH"). */
    private fun airlineKeyResolver(context: Context): (String?) -> String? = { raw ->
        AirlineStats.keyOf(raw) { AirlineCatalog.resolve(context, it) }
    }

    private fun labelAdapter(vararg labels: String): ArrayAdapter<String> =
        ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels.toList())

    private fun cityLanguageLabel(): String = when (Settings.getCityLabelLanguage(requireContext())) {
        Settings.CITY_LANG_EN -> getString(R.string.world_map_city_language_en)
        Settings.CITY_LANG_DE -> getString(R.string.world_map_city_language_de)
        else -> getString(R.string.world_map_city_language_native)
    }

    private fun highlightFilterValue(
        field: MaterialAutoCompleteTextView,
        activeWhen: (String) -> Boolean = { it.isNotBlank() }
    ) {
        val accent = MaterialColors.getColor(field, com.google.android.material.R.attr.colorPrimary)
        val normal = field.currentTextColor
        fun refresh() {
            val text = field.text?.toString()?.trim().orEmpty()
            field.setTextColor(if (activeWhen(text)) accent else normal)
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()
    }

    private fun stringSuggestionsAdapter(value: (LogbookEntry) -> String?): ArrayAdapter<String> {
        val values = entriesData
            .mapNotNull(value)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        return ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, values)
    }

    private fun buddyAdapter(): ArrayAdapter<String> {
        val values = entriesData
            .flatMap { TravelBuddyStats.parse(it.travelBuddy) }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        return ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, values)
    }

    private fun updateFilterButton() {
        if (_binding == null) return
        val active = activeMapFilter.isActive()
        val accent = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val surface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        binding.btnFilter.backgroundTintList = ColorStateList.valueOf(if (active) accent else surface)
        binding.btnFilterIcon.imageTintList = ColorStateList.valueOf(
            if (active) Color.WHITE else MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorOnSurface
            )
        )
        binding.btnFilterReset.visibility = if (active) View.VISIBLE else View.GONE
    }

    /** Derselbe Filter-Dialog wie auf der Weltkarte (sheet_map_filter.xml). */
    private fun showFilterDialog() {
        val sheetView = layoutInflater.inflate(R.layout.sheet_map_filter, null)
        val filterTravelType = sheetView.findViewById<MaterialAutoCompleteTextView>(R.id.filter_travel_type)
        val filterClass = sheetView.findViewById<MaterialAutoCompleteTextView>(R.id.filter_class)
        val filterAircraftType = sheetView.findViewById<MaterialAutoCompleteTextView>(R.id.filter_aircraft_type)
        val filterAirline = sheetView.findViewById<MaterialAutoCompleteTextView>(R.id.filter_airline)
        val filterLayover = sheetView.findViewById<MaterialAutoCompleteTextView>(R.id.filter_layover)
        val filterTravelBuddy = sheetView.findViewById<MaterialAutoCompleteTextView>(R.id.filter_travel_buddy)
        val filterCityLanguage = sheetView.findViewById<MaterialAutoCompleteTextView>(R.id.filter_city_language)

        val role = Settings.getRole(requireContext())
        sheetView.findViewById<View>(R.id.filter_layover_field).visibility =
            if (role.showsLayover) View.VISIBLE else View.GONE

        filterTravelType.setAdapter(
            if (role.isCrew) {
                labelAdapter(
                    getString(R.string.flight_type_private),
                    getString(R.string.flight_type_on_duty),
                    getString(R.string.flight_type_deadhead),
                    getString(R.string.flight_type_ferry),
                    getString(R.string.flight_type_ground_transfer),
                    getString(R.string.flight_type_duty_travel)
                )
            } else {
                labelAdapter(
                    getString(R.string.flight_type_private),
                    getString(R.string.flight_type_duty_travel)
                )
            }
        )
        filterClass.setAdapter(
            labelAdapter(
                getString(R.string.class_economy),
                getString(R.string.class_premium_economy),
                getString(R.string.class_business),
                getString(R.string.class_first),
                getString(R.string.class_jump)
            )
        )
        filterAircraftType.setAdapter(stringSuggestionsAdapter { it.aircraftType })
        filterAirline.setAdapter(stringSuggestionsAdapter { it.airline })
        filterLayover.setAdapter(
            labelAdapter(
                getString(R.string.world_map_filter_layover_yes),
                getString(R.string.world_map_filter_layover_no)
            )
        )
        filterCityLanguage.setAdapter(
            labelAdapter(
                getString(R.string.world_map_city_language_native),
                getString(R.string.world_map_city_language_en),
                getString(R.string.world_map_city_language_de)
            )
        )
        filterTravelBuddy.setAdapter(buddyAdapter())

        highlightFilterValue(filterTravelType)
        highlightFilterValue(filterClass)
        highlightFilterValue(filterAircraftType)
        highlightFilterValue(filterAirline)
        highlightFilterValue(filterLayover)
        highlightFilterValue(filterTravelBuddy)
        highlightFilterValue(filterCityLanguage) {
            it != getString(R.string.world_map_city_language_native)
        }

        filterTravelType.setText(activeMapFilter.flightType, false)
        filterClass.setText(activeMapFilter.classType, false)
        filterAircraftType.setText(activeMapFilter.aircraftType, false)
        filterAirline.setText(activeMapFilter.airline, false)
        filterLayover.setText(
            when (activeMapFilter.layover) {
                "yes" -> getString(R.string.world_map_filter_layover_yes)
                "no" -> getString(R.string.world_map_filter_layover_no)
                else -> ""
            },
            false
        )
        filterTravelBuddy.setText(activeMapFilter.travelBuddy, false)
        filterCityLanguage.setText(cityLanguageLabel(), false)

        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(sheetView)

        sheetView.findViewById<View>(R.id.btn_filter_apply).setOnClickListener {
            activeMapFilter = MapFilter(
                flightType = filterTravelType.text?.toString()?.trim().orEmpty(),
                classType = filterClass.text?.toString()?.trim().orEmpty(),
                aircraftType = filterAircraftType.text?.toString()?.trim().orEmpty(),
                airline = filterAirline.text?.toString()?.trim().orEmpty(),
                layover = if (role.showsLayover) {
                    when (filterLayover.text?.toString()?.trim().orEmpty()) {
                        getString(R.string.world_map_filter_layover_yes) -> "yes"
                        getString(R.string.world_map_filter_layover_no) -> "no"
                        else -> ""
                    }
                } else {
                    ""
                },
                travelBuddy = filterTravelBuddy.text?.toString()?.trim().orEmpty()
            )
            Settings.setCityLabelLanguage(
                requireContext(),
                when (filterCityLanguage.text?.toString()?.trim().orEmpty()) {
                    getString(R.string.world_map_city_language_en) -> Settings.CITY_LANG_EN
                    getString(R.string.world_map_city_language_de) -> Settings.CITY_LANG_DE
                    else -> Settings.CITY_LANG_NATIVE
                }
            )
            applyFilters()
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            val widthFraction =
                if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                    0.9f
                } else {
                    0.5f
                }
            sheet?.layoutParams?.width = (resources.displayMetrics.widthPixels * widthFraction).toInt()
            sheet?.requestLayout()
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private companion object {
        /** Zoom-Schritt der Zoom-Knöpfe. */
        const val ZOOM_STEP = 1.6f

        /** Anzeigedauer des Hinweis-Popups am Spuren-Knopf. */
        const val LAYOVER_HINT_MS = 3000L
    }
}
