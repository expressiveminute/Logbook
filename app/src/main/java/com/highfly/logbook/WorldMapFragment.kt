package com.highfly.logbook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.FragmentWorldMapBinding
import org.osmdroid.config.Configuration as OsmdroidConfig
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.hypot
import kotlin.math.ln

class WorldMapFragment : LogbookFragment() {

    companion object {
        private const val TAG = "WorldMapFragment"

        /** Obergrenze der Kartenstufe, siehe [configureMap]. */
        const val MAX_ZOOM = 10.0

        /** Zoomstufen über der Standardansicht, mit denen die Karte aufgeht. */
        const val START_ZOOM_STEPS = 2.0

        /**
         * Ab hier stehen die Hauptstädte auf der Karte, ab [GROESSTE_STADT_ZOOM]
         * die jeweils grösste Stadt. Alles darunter bleibt weg.
         */
        const val HAUPTSTADT_ZOOM = 2.8
        const val GROESSTE_STADT_ZOOM = 4.0

        /**
         * Stufe, mit der die Karte aufgeht: [START_ZOOM_STEPS] Stufen näher
         * ran als die Standardansicht, aber nie über [MAX_ZOOM] hinaus.
         */
        @JvmStatic
        fun startZoom(standardZoom: Double): Double =
            (standardZoom + START_ZOOM_STEPS).coerceAtMost(MAX_ZOOM)

        /** Takt der Layover-Animation: stößt das Neuzeichnen der Karte an. */
        private const val LAYOVER_FRAME_MS = 33L
    }

    private var _binding: FragmentWorldMapBinding? = null
    private val binding get() = _binding!!

    private var mapView: MapView? = null
    private var loadThread: Thread? = null

    private var routeColor = 0
    private var labelColor = 0
    private var landOverlay: OfflineLandOverlay? = null
    private var borderOverlay: OfflineBorderOverlay? = null

    private var selectedIata: String? = null
    private var activeMapFilter = MapFilter()
    private var entriesData: List<LogbookEntry> = emptyList()
    private var routesData: List<RouteLine>? = null
    private var airportsData: Map<String, GeoPoint>? = null
    private var citiesData: List<MapCity>? = null
    private var countriesData: List<CountryLabelOverlay.Country>? = null

    /** Plan der Layover-Animation, null wenn sie nicht läuft. */
    private var layoverPlan: LayoverAnimation.Plan? = null

    /** Overlay, das Flugzeuge und Spuren der Layover-Animation zeichnet. */
    private var layoverOverlay: LayoverOverlay? = null

    /** Ob der Takt der Layover-Animation gerade läuft. */
    private var layoverTickerActive = false

    /** Zeitpunkt der Pause, damit die Animation danach nahtlos weiterläuft. */
    private var layoverPausedAtMs: Long? = null

    private class RouteLine(
        val from: String,
        val to: String,
        val points: List<GeoPoint>,
        val count: Int
    )

    private data class MapFilter(
        val flightType: String = "",
        val classType: String = "",
        val aircraftType: String = "",
        val layover: String = "",
        val travelBuddy: String = "",
        val airline: String = ""
    ) {
        fun isActive(): Boolean =
            listOf(flightType, classType, aircraftType, layover, travelBuddy, airline)
                .any { it.isNotBlank() }

        /**
         * [resolveAirline] löst einen gespeicherten Airline-Text auf den
         * kanonischen Schlüssel auf (siehe [AirlineStats.keyOf]), damit "LH"
         * und "DLH" denselben Filter treffen.
         */
        fun matches(entry: LogbookEntry, resolveAirline: (String?) -> String?): Boolean {
            if (flightType.isNotBlank() &&
                entry.flightType?.lowercase()?.contains(flightType.lowercase()) != true
            ) return false
            if (classType.isNotBlank() &&
                entry.classType?.lowercase()?.contains(classType.lowercase()) != true
            ) return false
            if (aircraftType.isNotBlank() &&
                entry.aircraftType?.lowercase()?.contains(aircraftType.lowercase()) != true
            ) return false
            if (layover == "yes" && !entry.layover) return false
            if (layover == "no" && entry.layover) return false
            if (!TravelBuddyStats.matches(entry.travelBuddy, travelBuddy)) return false
            if (airline.isNotBlank() &&
                resolveAirline(entry.airline) != resolveAirline(airline)
            ) return false
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = requireContext()
        val config = OsmdroidConfig.getInstance()
        config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        config.userAgentValue = context.packageName
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorldMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets selbst anwenden: MainActivity setzt kein contentHost-Padding
        // fuer die Weltkarte (sie hat keine Navigationsleiste), das Fragment
        // positioniert die schwebenden Elemente direkt an den Systemleisten.
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val margin = (8 * resources.displayMetrics.density).toInt()
            // Obere Buttons unterhalb der Statusleiste.
            listOf(binding.btnBack, binding.btnFilter, binding.btnFilterReset).forEach { btn ->
                (btn.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = bars.top + margin
                btn.requestLayout()
            }
            // Untere Elemente (Stats-Karte, Zoom-Knöpfe) oberhalb der
            // Systemleiste; die Weltkarte hat keine schwebende Nav-Leiste.
            val bottomMargin = (16 * resources.displayMetrics.density).toInt()
            (binding.statsCard.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.bottomMargin = bars.bottom + bottomMargin
            (binding.mapControls.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.bottomMargin = bars.bottom + (4 * resources.displayMetrics.density).toInt()
            insets
        }

        // Filter aus Navigationsargumenten lesen
        val filterTravelBuddy = arguments?.getString("filterTravelBuddy")?.trim().orEmpty()
        if (filterTravelBuddy.isNotBlank()) {
            activeMapFilter = activeMapFilter.copy(travelBuddy = filterTravelBuddy)
        }
        val filterAirline = arguments?.getString("filterAirline")?.trim().orEmpty()
        if (filterAirline.isNotBlank()) {
            activeMapFilter = activeMapFilter.copy(airline = filterAirline)
        }

        // Zurueck-Pfeil oben links: navigiert zurueck (z. B. zum Dashboard).
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnZoomIn.setOnClickListener { mapView?.controller?.zoomIn() }
        binding.btnZoomOut.setOnClickListener { mapView?.controller?.zoomOut() }
        binding.btnReset.setOnClickListener { mapView?.let { setStandardView(it) } }
        binding.btnRotate.setOnClickListener { toggleMapOrientation() }
        binding.btnFilter.setOnClickListener { showFilterDialog() }
        // Der Zurueck-Pfeil neben dem Filter setzt den Kartenfilter zurueck;
        // er ist nur sichtbar, solange ein Filter aktiv ist.
        binding.btnFilterReset.setOnClickListener {
            activeMapFilter = MapFilter()
            applyFilters()
        }
        // Blendet die Flugspuren der Layover-Animation erneut ein, nachdem
        // sie ausgeblendet wurden.
        binding.btnLayoverTrails.setOnClickListener { replayLayoverAnimation() }
        binding.statsCard.setOnClickListener {
            val iata = selectedIata ?: return@setOnClickListener
            findNavController().navigate(
                R.id.action_world_map_to_entries,
                Bundle().apply { putString("airportFilter", iata) }
            )
        }

        routeColor = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorPrimary
        )
        labelColor = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorOnSurfaceVariant
        )

        mapView = binding.mapView
        configureMap()
        loadAndRender()

        // Apply the configured map orientation (default landscape); restore
        // portrait when leaving.
        applyOrientationSetting()
    }

    private fun applyOrientationSetting() {
        val portrait =
            Settings.getWorldMapOrientation(requireContext()) == Settings.WORLD_MAP_PORTRAIT
        requireActivity().requestedOrientation =
            if (portrait) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        updateOrientationIcon()
    }

    /**
     * Shows the format the map would switch to on the next tap: a portrait
     * phone while in landscape, a landscape phone while in portrait.
     */
    private fun updateOrientationIcon() {
        val targetPortrait =
            Settings.getWorldMapOrientation(requireContext()) == Settings.WORLD_MAP_LANDSCAPE
        binding.btnRotateIcon.setImageResource(
            if (targetPortrait) R.drawable.ic_phone_portrait else R.drawable.ic_phone_landscape
        )
    }

    private fun toggleMapOrientation() {
        val current = Settings.getWorldMapOrientation(requireContext())
        val next =
            if (current == Settings.WORLD_MAP_PORTRAIT) Settings.WORLD_MAP_LANDSCAPE
            else Settings.WORLD_MAP_PORTRAIT
        Settings.setWorldMapOrientation(requireContext(), next)
        applyOrientationSetting()
    }

    private fun configureMap() {
        val mv = mapView ?: return

        // Fully offline: never fetch tiles from the network.
        mv.setUseDataConnection(false)
        mv.overlayManager.tilesOverlay.isEnabled = false
        mv.overlayManager.tilesOverlay.setLoadingBackgroundColor(Color.TRANSPARENT)
        mv.overlayManager.tilesOverlay.setLoadingLineColor(Color.TRANSPARENT)
        mv.tileProvider.clearTileCache()

        mv.setMultiTouchControls(true)
        mv.setBuiltInZoomControls(false)
        mv.setVerticalMapRepetitionEnabled(false)
        mv.setHorizontalMapRepetitionEnabled(false)

        // Confine panning to the single world view: horizontally the world is
        // shown exactly once (no panning past the left/right edges of the
        // standard view), vertically only as far as the Mercator poles.
        mv.setScrollableAreaLimitDouble(
            org.osmdroid.util.BoundingBox(85.05, 180.0, -85.05, -180.0)
        )
        mv.minZoomLevel = 1.0
        mv.maxZoomLevel = MAX_ZOOM
        mv.controller.setZoom(2.0)
        mv.controller.setCenter(GeoPoint(25.0, 0.0))

        val isDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        val overlay = OfflineLandOverlay(requireContext())
        val ocean = if (isDark) Color.rgb(20, 30, 40) else Color.rgb(174, 202, 226)
        val land = if (isDark) Color.rgb(52, 65, 76) else Color.rgb(222, 226, 220)
        val coast = if (isDark) Color.rgb(75, 90, 102) else Color.rgb(150, 160, 152)
        overlay.setColors(ocean, land, coast)
        landOverlay = overlay

        val borders = OfflineBorderOverlay(requireContext())
        val borderColor = if (isDark) Color.rgb(120, 132, 144) else Color.rgb(128, 140, 130)
        borders.setColor(borderColor)
        borderOverlay = borders
    }

    private fun loadAndRender() {
        val context = requireContext()
        val fragmentView = view
        binding.loadingOverlay.visibility = View.VISIBLE

        val thread = Thread {
            try {
                landOverlay?.load()
                borderOverlay?.load()

                val airports = linkedMapOf<String, GeoPoint>()
                val routeCounts = linkedMapOf<Pair<String, String>, Int>()
                val allEntries = DashboardStats.dashboardEntries(context)
                // Filter die Einträge basierend auf activeMapFilter
                val resolveAirline = airlineKeyResolver(context)
                val entries = allEntries.filter { activeMapFilter.matches(it, resolveAirline) }

                for (entry in entries) {
                    val fromIata = entry.fromAirport.uppercase()
                    val toIata = entry.toAirport.uppercase()
                    val from = AirportData.location(context, fromIata)
                    val to = AirportData.location(context, toIata)
                    if (from != null && to != null) {
                        airports.putIfAbsent(fromIata, GeoPoint(from.lat, from.lon))
                        airports.putIfAbsent(toIata, GeoPoint(to.lat, to.lon))
                        val key = fromIata to toIata
                        routeCounts[key] = (routeCounts[key] ?: 0) + 1
                    }
                }

                val routes = routeCounts.map { (key, count) ->
                    val from = airports.getValue(key.first)
                    val to = airports.getValue(key.second)
                    RouteLine(
                        from = key.first,
                        to = key.second,
                        points = unwrapLongitudes(
                            GeoMath.greatCircleArc(
                                AirportData.GeoLocation(from.latitude, from.longitude),
                                AirportData.GeoLocation(to.latitude, to.longitude)
                            )
                        ),
                        count = count
                    )
                }

                val cities = loadCities(context)
                val countries = loadCountries(context)

                fragmentView?.post {
                    render(routes, airports, cities, countries, entries)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Laden der Weltkarte", e)
                fragmentView?.post { finishLoading() }
            }
        }
        loadThread = thread
        thread.start()
    }

    private fun render(
        routes: List<RouteLine>,
        airports: Map<String, GeoPoint>,
        cities: List<MapCity>,
        countries: List<CountryLabelOverlay.Country>,
        entries: List<LogbookEntry>
    ) {
        if (_binding == null) return
        val mv = mapView ?: return
        routesData = routes
        airportsData = airports
        entriesData = entries
        citiesData = cities
        countriesData = countries

        buildOverlays(mv)
        updateFilterButton()
        applyStartView(mv)
        mv.invalidate()
        finishLoading()
    }

    /**
     * (Re-)builds the map overlays. Keeps the current viewport, so it can be
     * called again when an airport filter is applied or removed.
     */
    private fun buildOverlays(mv: MapView) {
        if (_binding == null) return
        val density = resources.displayMetrics.density
        mv.overlays.clear()

        landOverlay?.let { overlay ->
            if (overlay.isLoaded) {
                mv.overlays.add(overlay)
            }
        }

        borderOverlay?.let { overlay ->
            if (overlay.isLoaded) {
                mv.overlays.add(overlay)
            }
        }

        val countries = countriesData ?: emptyList()
        val cities = citiesData ?: emptyList()

        // Routes recompute whenever an active filter is set, so only the
        // matching flights are drawn.
        val (airports, allRoutes) = if (entriesData.isNotEmpty()) {
            computeRoutes(entriesData, activeMapFilter)
        } else {
            (airportsData ?: emptyMap()) to (routesData ?: emptyList())
        }

        val collision = LabelCollision()
        mv.overlays.add(CountryLabelOverlay(countries, routeColor, collision))
        mv.overlays.add(
            CitiesOverlay(
                cities,
                labelColor,
                collision,
                Settings.getCityLabelLanguage(requireContext())
            )
        )

        val selected = selectedIata
        // MapSelection entscheidet, welche Verbindungen zur Auswahl gehoeren und
        // welche Flughäfen damit verbunden sind - fuer Abfluege und Ankuenfte
        // gleichermassen, siehe dort.
        val filtered = allRoutes.filter { MapSelection.touches(it.from to it.to, selected) }
        val reached = MapSelection.connected(allRoutes.map { it.from to it.to }, selected)
        val connections = connectionCounts(allRoutes)

        // Die Layover-Animation planen, bevor die Linien gezeichnet werden.
        // Mit Layover = „Ja" bestehen die gefilterten Linien nur aus
        // Layover-Strecken - auch Rückflüge und Anschlussflüge, die nicht am
        // Hub starten. Die animierten Spuren ersetzen sie alle: Eine statische
        // Linie wäre schon vor der Animation sichtbar und ginge nach ihr nicht
        // weg; am Ende bleiben nur die Dreilettercodes der Ziele sichtbar.
        val layoverActive = updateLayoverAnimation() != null

        filtered.forEach { route ->
            if (layoverActive) return@forEach
            val polyline = Polyline().apply {
                setPoints(route.points)
                outlinePaint.color = routeColor
                // Die Linien behalten immer dieselbe Staerke - auch mit
                // Auswahl, die nur entscheidet, welche Strecken ueberhaupt
                // gezeichnet werden. Frueher wurden die Linien bei Auswahl
                // duenner, das nahm der sichtbaren Verbindung ihre Wirkung.
                outlinePaint.strokeWidth = 0.9f * density
                outlinePaint.alpha = 255
                outlinePaint.style = Paint.Style.STROKE
            }
            mv.overlays.add(polyline)
        }

        // Die Spuren zeichnen vor den Flughafen-Badges, damit die
        // Dreilettercodes auch über den Spuren lesbar bleiben.
        layoverOverlay?.let { mv.overlays.add(it) }

        mv.overlays.add(
            AirportOverlay(
                airports,
                connections,
                routeColor,
                labelColor,
                selected,
                reached,
                onAirportTapped = { onAirportTap(it) }
            )
        )

        val visibleRoutes = filtered
        val hasActiveFilter = selected != null || activeMapFilter.isActive()

        binding.tvEmpty.visibility = View.GONE
        binding.statsCard.visibility = View.GONE
        binding.tvShowEntries.visibility = View.GONE
        if (visibleRoutes.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.setText(
                if (hasActiveFilter) R.string.entries_no_results else R.string.world_map_empty
            )
        } else {
            binding.statsCard.visibility = View.VISIBLE
            val totalFlights = visibleRoutes.sumOf { it.count }
            binding.tvStats.text = if (selected == null) {
                getString(
                    R.string.world_map_stats,
                    totalFlights,
                    visibleRoutes.size,
                    airports.size
                )
            } else {
                getString(
                    R.string.world_map_filter_stats,
                    selected,
                    totalFlights,
                    visibleRoutes.size
                )
            }
            if (selected != null) {
                binding.tvShowEntries.visibility = View.VISIBLE
            }
        }
    }

    private fun computeRoutes(
        entries: List<LogbookEntry>,
        filter: MapFilter
    ): Pair<Map<String, GeoPoint>, List<RouteLine>> {
        val context = requireContext()
        val airports = linkedMapOf<String, GeoPoint>()
        val routeCounts = linkedMapOf<Pair<String, String>, Int>()

        val resolveAirline = airlineKeyResolver(context)
        for (entry in entries) {
            if (!filter.matches(entry, resolveAirline)) continue
            val fromIata = entry.fromAirport.uppercase()
            val toIata = entry.toAirport.uppercase()
            val from = AirportData.location(context, fromIata)
            val to = AirportData.location(context, toIata)
            if (from != null && to != null) {
                airports.putIfAbsent(fromIata, GeoPoint(from.lat, from.lon))
                airports.putIfAbsent(toIata, GeoPoint(to.lat, to.lon))
                val key = fromIata to toIata
                routeCounts[key] = (routeCounts[key] ?: 0) + 1
            }
        }

        val routes = routeCounts.map { (key, count) ->
            val from = airports.getValue(key.first)
            val to = airports.getValue(key.second)
            RouteLine(
                from = key.first,
                to = key.second,
                points = unwrapLongitudes(
                    GeoMath.greatCircleArc(
                        AirportData.GeoLocation(from.latitude, from.longitude),
                        AirportData.GeoLocation(to.latitude, to.longitude)
                    )
                ),
                count = count
            )
        }
        return airports to routes
    }

    /**
     * Zahl der verschiedenen Ziele je Flughafen. Ein Flughafen mit vielen
     * Verbindungen bekommt damit einen höheren Wert und wird beim Zeichnen
     * zuletzt gelegt, liegt also über den weniger verbundenen.
     */
    private fun connectionCounts(routes: List<RouteLine>): Map<String, Int> {
        val neighbors = HashMap<String, MutableSet<String>>()
        for (route in routes) {
            neighbors.getOrPut(route.from) { HashSet() }.add(route.to)
            neighbors.getOrPut(route.to) { HashSet() }.add(route.from)
        }
        return neighbors.mapValues { it.value.size }
    }

    private fun onAirportTap(iata: String) {
        selectedIata = if (selectedIata == iata) null else iata
        val mv = mapView ?: return
        buildOverlays(mv)
        mv.invalidate()
    }

    private fun showFilterDialog() {
        val sheetView = layoutInflater.inflate(R.layout.sheet_map_filter, null)
        val filterTravelType = sheetView.findViewById<AutoCompleteTextView>(R.id.filter_travel_type)
        val filterClass = sheetView.findViewById<AutoCompleteTextView>(R.id.filter_class)
        val filterAircraftType = sheetView.findViewById<AutoCompleteTextView>(R.id.filter_aircraft_type)
        val filterAirline = sheetView.findViewById<AutoCompleteTextView>(R.id.filter_airline)
        val filterLayover = sheetView.findViewById<AutoCompleteTextView>(R.id.filter_layover)
        val filterTravelBuddy = sheetView.findViewById<AutoCompleteTextView>(R.id.filter_travel_buddy)
        val filterCityLanguage = sheetView.findViewById<AutoCompleteTextView>(R.id.filter_city_language)

        val role = Settings.getRole(requireContext())
        // Der Fluggast kennt kein Layover - weder bei der Eingabe noch im Filter.
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
        // Die Vorschläge kommen aus den Airlines der Einträge; "LH" und "DLH"
        // treffen über die Katalog-Auflösung denselben Filter.
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
        // Die Vorschlaege kommen aus den Reisebuddies der Eintraege, nicht aus
        // dem Freitextfeld: Ein Eintrag traegt mehrere Namen, die Liste zeigt
        // jeden davon einzeln.
        filterTravelBuddy.setAdapter(buddyAdapter())

        highlightFilterValue(filterTravelType)
        highlightFilterValue(filterClass)
        highlightFilterValue(filterAircraftType)
        highlightFilterValue(filterAirline)
        highlightFilterValue(filterLayover)
        highlightFilterValue(filterTravelBuddy)
        // The city language always holds a label; only a non-default choice
        // counts as a value the user actively picked.
        highlightFilterValue(filterCityLanguage) {
            it != getString(R.string.world_map_city_language_native)
        }

        filterTravelType.setText(activeMapFilter.flightType, false)
        filterClass.setText(activeMapFilter.classType, false)
        filterAircraftType.setText(activeMapFilter.aircraftType, false)
        filterAirline.setText(activeMapFilter.airline, false)
        filterLayover.setText(when (activeMapFilter.layover) {
            "yes" -> getString(R.string.world_map_filter_layover_yes)
            "no" -> getString(R.string.world_map_filter_layover_no)
            else -> ""
        }, false)
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
                if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                    0.9f
                else
                    0.5f
            sheet?.layoutParams?.width = (resources.displayMetrics.widthPixels * widthFraction).toInt()
            sheet?.requestLayout()
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private fun applyFilters() {
        val mv = mapView ?: return
        buildOverlays(mv)
        updateFilterButton()
        mv.invalidate()
    }

    /** Takt der Layover-Animation: stößt das Neuzeichnen der Karte an. */
    private val layoverTicker = object : Runnable {
        override fun run() {
            val mv = mapView ?: return
            val overlay = layoverOverlay
            val plan = layoverPlan
            if (overlay == null || plan == null) {
                layoverTickerActive = false
                return
            }
            mv.invalidate()
            if (LayoverAnimation.isFinished(SystemClock.uptimeMillis() - overlay.startTimeMs, plan)) {
                // Ein letztes Bild, dann Schluss: Die Spuren sind zu diesem
                // Zeitpunkt bereits ausgeblendet, nur die Dreilettercodes
                // bleiben sichtbar.
                layoverTickerActive = false
                return
            }
            mv.postDelayed(this, LAYOVER_FRAME_MS)
        }
    }

    private fun startLayoverTicker() {
        val mv = mapView ?: return
        if (layoverTickerActive || layoverPlan == null || layoverPausedAtMs != null) return
        layoverTickerActive = true
        mv.postDelayed(layoverTicker, LAYOVER_FRAME_MS)
    }

    private fun stopLayoverTicker() {
        layoverTickerActive = false
        mapView?.removeCallbacks(layoverTicker)
    }

    /** Pausiert die Animation; onResume schiebt die Pause wieder heraus. */
    private fun pauseLayoverAnimation() {
        if (layoverPausedAtMs == null && layoverTickerActive) {
            layoverPausedAtMs = SystemClock.uptimeMillis()
        }
        stopLayoverTicker()
    }

    private fun resumeLayoverAnimation() {
        val pausedAt = layoverPausedAtMs ?: return
        layoverPausedAtMs = null
        // Die Pause wird der Animation abgezogen, das Tempo bleibt gleich.
        layoverOverlay?.let { it.startTimeMs += SystemClock.uptimeMillis() - pausedAt }
        startLayoverTicker()
    }

    /** Startet die Animation erneut, wenn der Nutzer die Spuren sehen will. */
    private fun replayLayoverAnimation() {
        val overlay = layoverOverlay ?: return
        overlay.startTimeMs = SystemClock.uptimeMillis()
        layoverPausedAtMs = null
        startLayoverTicker()
        mapView?.invalidate()
    }

    /** Ob Systemanimationen laufen dürfen (Einstellung „Animationsdauer"). */
    private fun animationsEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(
            requireContext().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f

    /**
     * Plant die Layover-Animation, wenn der Layover-Filter auf „Ja" steht,
     * und legt das Overlay dafür an bzw. behält das laufende bei. Ohne
     * Layover-Filter oder mit abgeschalteten Systemanimationen bleibt der
     * Plan leer und die statischen Linien zeichnen wie gehabt. Zurückgegeben
     * wird der Plan, damit buildOverlays die Linien ab dem Hub aussparen kann.
     */
    private fun updateLayoverAnimation(): LayoverAnimation.Plan? {
        if (_binding == null) return null
        val plan = if (animationsEnabled() && activeMapFilter.layover == "yes") {
            val context = requireContext()
            val resolveAirline = airlineKeyResolver(context)
            val layoverEntries =
                entriesData.filter { activeMapFilter.matches(it, resolveAirline) }
            LayoverAnimation.plan(layoverEntries) { AirportData.location(context, it) }
        } else {
            null
        }
        layoverPlan = plan
        if (plan == null) {
            layoverOverlay = null
            stopLayoverTicker()
            binding.btnLayoverTrails.visibility = View.GONE
            return null
        }
        var overlay = layoverOverlay
        if (overlay == null || overlay.plan != plan) {
            overlay = LayoverOverlay(requireContext(), plan, routeColor)
            layoverOverlay = overlay
        }
        binding.btnLayoverTrails.visibility = View.VISIBLE
        startLayoverTicker()
        return plan
    }

    /**
     * Löst einen gespeicherten Airline-Text auf den kanonischen Schlüssel auf
     * (siehe [AirlineStats.keyOf]): "LH" und "DLH" ergeben denselben Schlüssel,
     * damit der Airline-Filter beide Schreibweisen trifft.
     */
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

    /**
     * Paints the text of a filter field in the accent colour while it holds a
     * value the user entered, so active criteria stand out from empty fields.
     */
    private fun highlightFilterValue(
        field: AutoCompleteTextView,
        activeWhen: (String) -> Boolean = { it.isNotBlank() }
    ) {
        val accent = MaterialColors.getColor(
            field, com.google.android.material.R.attr.colorPrimary
        )
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

    /** Die Reisebuddies der Eintraege als Vorschlaege, jeder Name einmal. */
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
        val accent = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorPrimary
        )
        val surface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh
        )
        binding.btnFilter.backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (active) accent else surface)
        binding.btnFilterIcon.imageTintList =
            android.content.res.ColorStateList.valueOf(
                if (active) Color.WHITE else MaterialColors.getColor(
                    binding.root, com.google.android.material.R.attr.colorOnSurface
                )
            )
        // Der Zurueck-Pfeil erscheint nur, solange ein Filter aktiv ist; das
        // Zuruecksetzen selbst sitzt als Knopf auf der Karte (btn_filter_reset).
        binding.btnFilterReset.visibility = if (active) View.VISIBLE else View.GONE
    }

    /**
     * Locks the map to the standard world view: the full world spans the
     * screen width once (no horizontal repetition), Africa is centered and
     * Hawaii appears on the left edge. Panning is disabled by PanLockedMapView
     * and zooming out below this level is not allowed.
     */
    private fun setStandardView(mv: MapView) {
        val zoom = standardZoom(mv)
        mv.minZoomLevel = zoom
        mv.controller.setZoom(zoom)
        mv.controller.setCenter(GeoPoint(20.0, 0.0))
    }

    private fun standardZoom(mv: MapView): Double {
        val width = if (mv.width > 0) mv.width else resources.displayMetrics.widthPixels
        return (ln(width.toDouble() / 256.0) / ln(2.0)).coerceIn(1.0, MAX_ZOOM)
    }

    /**
     * Sets the view the map opens with: a few zoom steps in from the standard
     * world view, with Europe centered. Far enough in that country names and
     * capitals are already legible - the whole world in one screen is a
     * preview, not a map to read.
     */
    private fun applyStartView(mv: MapView) {
        mv.minZoomLevel = standardZoom(mv)
        mv.controller.setZoom(startZoom(standardZoom(mv)))
        mv.controller.setCenter(GeoPoint(48.0, 12.0))
    }

    private fun unwrapLongitudes(points: List<GeoPoint>): List<GeoPoint> {
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            var lon = cur.longitude
            while (lon - prev.longitude > 180) lon -= 360
            while (lon - prev.longitude < -180) lon += 360
            cur.longitude = lon
        }
        return points
    }

    private fun loadCities(context: Context): List<MapCity> = MapCities.load(context)

    private fun loadCountries(context: Context): List<CountryLabelOverlay.Country> {
        return try {
            CountryLabelOverlay.load(context)
        } catch (e: Exception) {
            Log.e(TAG, "Ländernamen konnten nicht geladen werden", e)
            emptyList()
        }
    }

    private fun finishLoading() {
        // Das Ueberblenden darf nicht mehr auf [binding] zugreifen: Die
        // Karten-Orientierung dreht das Fenster waehrend der 300-ms-Animation,
        // dadurch wird die Ansicht zerstoert und [_binding] auf null gesetzt.
        // Der End-Action lief dann auf null und liess die App abstuerzen.
        // Die lokal gehaltene Ansicht unschaerfbar zu machen ist harmlos.
        val current = _binding ?: return
        current.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { current.loadingOverlay.visibility = View.GONE }
            .start()
    }

    private class CitiesOverlay(
        private val cities: List<MapCity>,
        private val labelColor: Int,
        private val collision: LabelCollision,
        private val language: String
    ) : Overlay() {

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            if (cities.isEmpty()) return
            val density = mapView.context.resources.displayMetrics.density
            val zoom = mapView.zoomLevelDouble

            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = labelColor
                alpha = 150
            }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = labelColor
                textSize = 9f * density
                alpha = 200
            }
            val fm = label.fontMetrics
            val textHeight = fm.descent - fm.ascent
            val pad = 3f * density
            val staggerStep = textHeight + 4f * density
            val labelGap = 4f * density

            // City dots only appear together with the city name, so that
            // zoomed-out views do not get cluttered with plain dots.
            cities.forEach { city ->
                // Nur Hauptstaedte und die groessten Staedte. Darunter stehen
                // in [MapCities] ueber 2900 Orte, die zusammen eine Wolke
                // ergeben, aus der sich nichts mehr herausliest - die
                // Einzelergebnisse stehen ohnehin in [CityPopupSheet].
                val minLabelZoom = if (city.isCapital) {
                    HAUPTSTADT_ZOOM
                } else if (city.rank == 0) {
                    GROESSTE_STADT_ZOOM
                } else {
                    return@forEach
                }
                if (zoom < minLabelZoom) return@forEach

                val px = mapView.projection.toPixels(
                    GeoPoint(city.lat, city.lon), null
                )
                val sx = px.x.toFloat()
                val sy = px.y.toFloat()
                if (sx < -30 || sx > mapView.width + 30) return@forEach
                if (sy < -30 || sy > mapView.height + 30) return@forEach

                val displayName = city.displayName(language)
                val textWidth = label.measureText(displayName)
                val placed = collision.place(
                    sx + labelGap,
                    sy - labelGap,
                    textWidth,
                    textHeight,
                    pad,
                    baselineShiftY = staggerStep,
                    maxShiftLines = 3,
                    mirrorTextLeft = sx - labelGap - textWidth,
                    screenWidth = mapView.width.toFloat(),
                    screenHeight = mapView.height.toFloat()
                ) ?: return@forEach

                val radius = (if (city.isCapital) 2.4f else 2.5f) * density
                canvas.drawCircle(sx, sy, radius, dot)
                label.isUnderlineText = city.isCapital
                canvas.drawText(displayName, placed[0], placed[1], label)
                label.isUnderlineText = false
            }
        }
    }

    /**
     * Die Flughäfen als Badges mit dem IATA-Code.
     *
     * Ist ein Flughafen angeklickt, teilt sich die Darstellung in zwei Gruppen:
     * Die von ihm aus angeflogenen Flughäfen stehen in Akzentfarbe und werden
     * betont gezeichnet, alle anderen treten zurück. Die betonten Badges kommen
     * zuletzt, damit sie auch dann noch lesbar sind, wenn die Karte an der
     * Stelle schon voller Badges hat.
     */
    private class AirportOverlay(
        private val airports: Map<String, GeoPoint>,
        private val connectionCounts: Map<String, Int>,
        private val dotColor: Int,
        private val mutedColor: Int,
        private val selectedIata: String?,
        private val reached: Set<String>,
        private val onAirportTapped: (String) -> Unit
    ) : Overlay() {

        override fun onSingleTapConfirmed(event: android.view.MotionEvent, mapView: MapView): Boolean {
            if (airports.isEmpty()) return false
            val density = mapView.context.resources.displayMetrics.density
            val hit = 40f * density
            var best: String? = null
            var bestDist = Float.MAX_VALUE
            for ((iata, point) in airports) {
                val p = mapView.projection.toPixels(point, null)
                val dx = event.x - p.x
                val dy = event.y - p.y
                val dist = hypot(dx, dy)
                if (dist <= hit && dist < bestDist) {
                    best = iata
                    bestDist = dist
                }
            }
            if (best != null) {
                onAirportTapped(best)
                return true
            }
            if (selectedIata != null) {
                onAirportTapped(selectedIata)
                return true
            }
            return false
        }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            if (airports.isEmpty()) return

            // Ohne Auswahl bleibt alles in Akzentfarbe, wie es vorher war. Erst
            // mit Auswahl trennt sich die Karte in betonte und zurücktretende
            // Flughäfen.
            if (selectedIata == null) {
                drawBadges(canvas, mapView, airports.keys, dotColor, bold = false)
                return
            }

            val highlighted = airports.keys.filter { it in reached }
            val muted = airports.keys.filter { it != selectedIata && it !in reached }

            // Die zurücktretenden Badges zuerst, danach die betonten. Weil die
            // Kacheln ueberlappen, bleiben die wichtigen nur so lesbar, wenn sie
            // als letzte gezeichnet werden.
            drawBadges(canvas, mapView, muted, mutedColor, bold = false)
            drawBadges(canvas, mapView, highlighted, dotColor, bold = true)
            drawBadges(canvas, mapView, listOfNotNull(selectedIata), dotColor, bold = true)
        }

        /**
         * Zeichnet die Badges einer Gruppe. [bold] betont die Gruppe mit einem
         * dickeren Ring, damit die Ziele vom angeklickten Flughafen auch dann
         * auffallen, wenn ringsum weitere Badges liegen.
         */
        private fun drawBadges(
            canvas: Canvas,
            mapView: MapView,
            iatas: Collection<String>,
            badgeColor: Int,
            bold: Boolean
        ) {
            if (iatas.isEmpty()) return
            val density = mapView.context.resources.displayMetrics.density

            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = readableOn(badgeColor)
                textSize = 8.5f * density
                isFakeBoldText = bold
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = badgeColor
                alpha = if (bold) 255 else 200
            }
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
                alpha = if (bold) 255 else 170
            }
            val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = badgeColor
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
            }

            val fm = label.fontMetrics
            val textHeight = fm.descent - fm.ascent
            val padX = 3f * density
            val padY = 1.5f * density

            // Weniger verbundene Flughäfen zuerst, stark vernetzte zuletzt -
            // so liegen letztere obenauf, wenn sich Badges überlappen.
            iatas.sortedBy { connectionCounts[it] ?: 0 }.forEach { iata ->
                val point = airports[iata] ?: return@forEach
                val px = mapView.projection.toPixels(point, null)
                val sx = px.x.toFloat()
                val sy = px.y.toFloat()

                val badgeWidth = label.measureText(iata) + 2 * padX
                val badgeHeight = textHeight + 2 * padY
                val left = sx - badgeWidth / 2
                val top = sy - badgeHeight / 2
                val textWidth = label.measureText(iata)

                if (iata == selectedIata) {
                    val extra = 3f * density
                    canvas.drawRoundRect(
                        left - extra,
                        top - extra,
                        left + badgeWidth + extra,
                        top + badgeHeight + extra,
                        badgeHeight / 2 + extra,
                        badgeHeight / 2 + extra,
                        highlight
                    )
                }
                canvas.drawRoundRect(
                    left, top, left + badgeWidth, top + badgeHeight,
                    badgeHeight / 2, badgeHeight / 2, fill
                )
                canvas.drawRoundRect(
                    left, top, left + badgeWidth, top + badgeHeight,
                    badgeHeight / 2, badgeHeight / 2, ring
                )
                canvas.drawText(iata, sx - textWidth / 2, sy - (fm.descent + fm.ascent) / 2, label)
            }
        }

        private fun readableOn(bg: Int): Int {
            val r = Color.red(bg) / 255f
            val g = Color.green(bg) / 255f
            val b = Color.blue(bg) / 255f
            val luminance = 0.299f * r + 0.587f * g + 0.114f * b
            return if (luminance > 0.5f) Color.rgb(43, 43, 43) else Color.WHITE
        }
    }

    /**
     * Zeichnet die Layover-Animation der Weltkarte: Vom Hub starten
     * Flugzeuge in Richtung jedes Layover-Ziels und ziehen eine nach vorn
     * kraeftiger werdende Spur hinter sich her. Nach der Ankunft des letzten
     * Flugzeugs stehen die Spuren noch zwei Sekunden und blenden dann aus.
     * Die Dreilettercodes zeichnet weiterhin [AirportOverlay].
     */
    private class LayoverOverlay(
        context: Context,
        val plan: LayoverAnimation.Plan,
        color: Int
    ) : Overlay() {

        private val density = context.resources.displayMetrics.density
        private val plane: Drawable? =
            ContextCompat.getDrawable(context, R.drawable.ic_flight)?.mutate()
        private val planeSize = RouteMiniMapView.PLANE_SIZE_DP * density
        private val planeBounds = Rect()
        private val reusePoint = Point()
        private val trailPath = Path()
        private val headOut = FloatArray(3)
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val trailColor = Color.argb(
            235, Color.red(color), Color.green(color), Color.blue(color)
        )

        /** Projektionspuffer des aktuellen Streckenzugs, wächst bei Bedarf. */
        private var scratch = FloatArray(0)

        /** Startzeit der Animation; wird bei Pause und Replay verschoben. */
        var startTimeMs: Long = SystemClock.uptimeMillis()

        init {
            plane?.setTint(color)
        }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val elapsed = SystemClock.uptimeMillis() - startTimeMs
            val alpha = LayoverAnimation.trailAlpha(elapsed, plan)
            if (alpha <= 0) return
            val projection = mapView.projection
            for (route in plan.routes) {
                drawRoute(canvas, projection, route, elapsed, alpha)
            }
        }

        private fun drawRoute(
            canvas: Canvas,
            projection: org.osmdroid.views.Projection,
            route: LayoverAnimation.Route,
            elapsedMs: Long,
            alpha: Int
        ) {
            val count = route.points.size
            if (count < 2) return
            val needed = count * 2
            if (scratch.size < needed) scratch = FloatArray(needed)
            val projected = scratch
            var i = 0
            for (point in route.points) {
                projection.toPixels(GeoPoint(point.lat, point.lon), reusePoint)
                projected[i++] = reusePoint.x.toFloat()
                projected[i++] = reusePoint.y.toFloat()
            }
            val total = RouteDeparture.length(projected, count)
            if (total <= 0f) return
            val fraction = LayoverAnimation.planeFraction(elapsedMs, route)
            val head = fraction * total
            if (!RouteDeparture.pointAt(projected, count, head, headOut)) return
            drawTrail(canvas, projected, count, head, alpha)
            // Das Flugzeug ist nur unterwegs zu sehen; am Ziel bleibt die Spur.
            if (fraction < 1f) {
                drawPlane(canvas, headOut[0], headOut[1], headOut[2])
            }
        }

        /**
         * Die Spur vom Hub bis zur Spitze: ein Verlauf von durchsichtig am
         * Hub zur vollen Akzentfarbe vorn, mit der Deckkraft [alpha] der
         * aktuellen Phase.
         */
        private fun drawTrail(
            canvas: Canvas,
            points: FloatArray,
            count: Int,
            head: Float,
            alpha: Int
        ) {
            if (head <= 0.5f) return
            trailPath.reset()
            var covered = 0f
            var moved = false
            var i = 1
            while (i < count && covered <= head) {
                val ax = points[(i - 1) * 2]
                val ay = points[(i - 1) * 2 + 1]
                val dx = points[i * 2] - ax
                val dy = points[i * 2 + 1] - ay
                val segment = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (segment > 0f) {
                    val next = covered + segment
                    val t = ((head - covered) / segment).coerceIn(0f, 1f)
                    val x = ax + dx * t
                    val y = ay + dy * t
                    if (moved) {
                        trailPath.lineTo(x, y)
                    } else {
                        trailPath.moveTo(ax, ay)
                        trailPath.lineTo(x, y)
                        moved = true
                    }
                    covered = next
                }
                i++
            }
            if (!moved) return
            trailPaint.shader = LinearGradient(
                points[0], points[1],
                headOut[0], headOut[1],
                Color.TRANSPARENT, trailColor, Shader.TileMode.CLAMP
            )
            trailPaint.alpha = alpha
            canvas.drawPath(trailPath, trailPaint)
            trailPaint.shader = null
        }

        /** Das Flugzeugsymbol an der Spitze der Spur, in Fahrtrichtung gedreht. */
        private fun drawPlane(canvas: Canvas, x: Float, y: Float, rotation: Float) {
            val symbol = plane ?: return
            val half = planeSize / 2f
            canvas.save()
            canvas.rotate(rotation, x, y)
            planeBounds.set(
                (x - half).toInt(), (y - half).toInt(),
                (x + half).toInt(), (y + half).toInt()
            )
            symbol.bounds = planeBounds
            symbol.draw(canvas)
            canvas.restore()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        resumeLayoverAnimation()
    }

    override fun onPause() {
        super.onPause()
        pauseLayoverAnimation()
        mapView?.onPause()
    }

    override fun onDestroyView() {
        stopLayoverTicker()
        layoverOverlay = null
        layoverPlan = null
        layoverPausedAtMs = null
        loadThread?.interrupt()
        loadThread = null
        mapView?.onDetach()
        mapView = null
        if (activity?.isChangingConfigurations == false) {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onDestroyView()
        _binding = null
    }
}