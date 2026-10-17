package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentAirportDetailBinding
import com.highfly.logbook.databinding.ItemAirportFactBinding
import java.util.Locale
import kotlin.math.abs

/**
 * Detailseite zu einem Flughafen der Kachel "Flughäfen": Kopfbereich mit dem
 * Dreilettercode, Name und Ort, darunter die wichtigsten Kenndaten (IATA,
 * ICAO, Höhe über N.N. und Koordinaten). Dann folgt – wenn vorhanden – das
 * mitgelieferte Satellitenbild des Flughafens und darunter eine Karte nach dem
 * Vorbild der Länderseite. Die Karte zeigt den Flughafen im Mittelpunkt und
 * alle Flugverbindungen als Großkreisbögen; unter der Karte steht, wie viele
 * Verbindungen es ab diesem Flughafen gibt.
 */
class AirportDetailFragment : Fragment() {

    private var _binding: FragmentAirportDetailBinding? = null
    private val binding get() = _binding!!

    private var code: String = ""

    /** Verwirft das Ergebnis eines bereits verlassenen Ladevorgangs. */
    private var loadGeneration = 0

    /** Dasselbe für den Ladevorgang des Satellitenbildes. */
    private var satelliteGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = requireContext()
        val config = org.osmdroid.config.Configuration.getInstance()
        config.load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
        config.userAgentValue = context.packageName
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAirportDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        code = requireArguments().getString(ARG_CODE)?.trim()?.uppercase() ?: ""

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.fabAirportZoomOut.setOnClickListener {
            binding.airportMap.zoomToRoutes()
        }
        binding.tvDetailSubtitle.text = getString(
            R.string.tile_detail_period_flight_type,
            PeriodOptions.label(
                requireContext(),
                Settings.getDefaultPeriodKey(requireContext())
            ),
            FlightTypeOptions.label(
                requireContext(),
                Settings.getFlightTypeFilterKey(requireContext())
            )
        )

        renderHeader()
        renderFacts()
        loadRoutes()
    }

    /**
     * Code mittig, darunter der Name, darunter "Stadt, Land" mit der Flagge
     * dahinter - derselbe Aufbau wie auf der Layover-Detailseite.
     */
    private fun renderHeader() {
        binding.tvAirportCode.text = code
        val info = AirportNames.get(requireContext(), code)
        val iso = AirportData.country(requireContext(), code)
        if (info?.name != null && info.name.isNotBlank()) {
            binding.tvAirportName.visibility = View.VISIBLE
            binding.tvAirportName.text = info.name
        }
        val location = listOfNotNull(
            info?.city?.takeIf { it.isNotBlank() },
            iso?.takeIf { it.isNotBlank() }?.let { countryName(it) }
        ).joinToString(", ")
        val line = listOfNotNull(
            location.takeIf { it.isNotBlank() },
            iso?.takeIf { it.isNotBlank() }?.let { AirportData.flagEmoji(it) }
        ).joinToString(" ")
        if (line.isNotBlank()) {
            binding.tvAirportCityCountry.visibility = View.VISIBLE
            binding.tvAirportCityCountry.text = line
        }
    }

    private fun countryName(iso: String): String? {
        val country = CountryShapes.load(requireContext())
            ?.firstOrNull { it.iso2 == iso }
            ?: return null
        return if (useGermanNames(Locale.getDefault())) country.nameDe else country.nameEn
    }

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(locale: Locale): Boolean =
        locale.language.isEmpty() || locale.language == Settings.LANG_DE

    /**
     * Kenndaten über der Karte: IATA-Code (derselbe wie oben), ICAO-Code,
     * Höhe über N.N. und Koordinaten. Zeilen, deren Wert fehlt (z. B. der
     * ICAO-Code eines geschlossenen Flughafens), bleiben weg.
     */
    private fun renderFacts() {
        val context = requireContext()
        addFact(R.string.airport_fact_iata, code)
        AirportData.icao(context, code)?.takeIf { it.isNotBlank() }?.let {
            addFact(R.string.airport_fact_icao, it)
        }
        AirportData.elevationMeters(context, code)?.let { meters ->
            addFact(
                R.string.airport_fact_elevation,
                AirportAltitudeView.altitudeCaption(context, meters)
            )
        }
        AirportData.location(context, code)?.let { location ->
            addFact(R.string.airport_fact_coordinates, coordinates(location))
        }
    }

    private fun addFact(labelRes: Int, value: String) {
        val row = ItemAirportFactBinding.inflate(
            layoutInflater, binding.factsContainer, false
        )
        row.tvFactLabel.setText(labelRes)
        row.tvFactValue.text = value
        binding.factsContainer.addView(row.root)
    }

    /** "48,354° N, 11,786° O"; die Richtungsbuchstaben kommen aus dem Kompass. */
    private fun coordinates(location: AirportData.GeoLocation): String {
        val lat = coordinate(location.lat, R.string.compass_north, R.string.compass_south)
        val lon = coordinate(location.lon, R.string.compass_east, R.string.compass_west)
        return "$lat, $lon"
    }

    private fun coordinate(value: Double, positiveRes: Int, negativeRes: Int): String {
        val direction = getString(if (value >= 0.0) positiveRes else negativeRes)
        return String.format(
            Settings.language(requireContext()), "%.3f° %s", abs(value), direction
        )
    }

    /**
     * Baut die Flugverbindungen im Hintergrund: Aus allen erfassten Flügen
     * werden die Strecken dieses Flughafens gesammelt, je Ziel die Häufigkeit
     * gezählt und als Großkreisbogen aufbereitet. Danach wird die Karte
     * gezeichnet.
     */
    private fun loadRoutes() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        val target = code
        if (target.isBlank()) return

        val self = AirportData.location(appContext, target)
        Thread {
            val entries = DashboardStats.dashboardEntries(appContext)
            val counts = LinkedHashMap<String, Int>()
            for (entry in entries) {
                val from = entry.fromAirport.trim().uppercase()
                val to = entry.toAirport.trim().uppercase()
                when (target) {
                    from -> if (to.isNotEmpty() && to != target) {
                        counts[to] = (counts[to] ?: 0) + 1
                    }
                    to -> if (from.isNotEmpty() && from != target) {
                        counts[from] = (counts[from] ?: 0) + 1
                    }
                }
            }

            val routes = mutableListOf<CountryRouteMapView.RouteLine>()
            val markers = mutableListOf<CountryDetailModel.AirportMarker>()
            if (self != null) {
                for ((other, count) in counts) {
                    val location = AirportData.location(appContext, other) ?: continue
                    markers += CountryDetailModel.AirportMarker(
                        iata = other,
                        lat = location.lat,
                        lon = location.lon,
                        count = count
                    )
                    routes += CountryRouteMapView.RouteLine(
                        from = target,
                        to = other,
                        points = unwrapLongitudes(GeoMath.greatCircleArc(self, location)),
                        count = count
                    )
                }
            }
            routes.sortWith(compareByDescending<CountryRouteMapView.RouteLine> { it.count }
                .thenBy { it.to })
            markers.sortWith(compareByDescending<CountryDetailModel.AirportMarker> { it.count }
                .thenBy { it.iata })

            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                renderConnections(self, markers, routes, counts.size)
            }
        }.start()
    }

    /** Hält den Bogen zusammenhängend, damit er nicht über die Weltkarte springt. */
    private fun unwrapLongitudes(
        points: List<org.osmdroid.util.GeoPoint>
    ): List<org.osmdroid.util.GeoPoint> {
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

    /**
     * Zeichnet die Karte wie auf der Länderseite: der Flughafen als
     * hervorgehobenes Badge, alle Verbindungen als durchgezogene Bögen und die
     * Ziele als Badges. Unter der Karte steht die Zahl der Verbindungen. Die
     * Karte startet dicht am Flughafen; der Knopf unten rechts holt alle
     * Strecken ins Bild.
     */
    private fun renderConnections(
        self: AirportData.GeoLocation?,
        markers: List<CountryDetailModel.AirportMarker>,
        routes: List<CountryRouteMapView.RouteLine>,
        connectionCount: Int
    ) {
        binding.tvAirportConnections.text = resources.getQuantityString(
            R.plurals.airport_detail_connections,
            connectionCount,
            connectionCount,
            code
        )
        binding.tvAirportConnections.visibility = View.VISIBLE

        val selfMarker = self?.let {
            CountryDetailModel.AirportMarker(
                iata = code,
                lat = it.lat,
                lon = it.lon,
                count = routes.size
            )
        }
        if (selfMarker == null) {
            binding.countryMapCard.visibility = View.GONE
            binding.fabAirportZoomOut.visibility = View.GONE
            return
        }
        val airports = listOf(selfMarker) + markers
        binding.countryMapCard.visibility = View.VISIBLE
        binding.airportMap.setData(
            country = null,
            airports = airports,
            domestic = routes,
            international = emptyList()
        )
        loadSatellite()
        // Der eigene Flughafen ist vorausgewählt: seine Verbindungen sind
        // betont und seine Ziele tragen Badges. Die Karte startet dicht an ihm.
        binding.airportMap.focusAirportClose(code)
        // Von ihm starten fortlaufend Flugzeuge in Richtung jeder Verbindung.
        binding.airportMap.playDepartureAnimation()
        binding.airportMap.contentDescription = getString(R.string.airport_map_desc, code)
        // Ohne Strecken gibt es nichts herauszuzoomen.
        binding.fabAirportZoomOut.visibility = if (routes.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Legt das mitgelieferte Satellitenbild (Sentinel-2 Cloudless, Top 100)
     * als eigene Kachel zwischen die Kenndaten und die Verbindungskarte, falls
     * es für diesen Flughafen existiert, und blendet sie sanft ein. Der
     * CC-BY-Nachweis steht in der Kachel unter dem Bild. Alles offline aus
     * `assets/`.
     */
    private fun loadSatellite() {
        val generation = ++satelliteGeneration
        val appContext = requireContext().applicationContext
        val target = code
        binding.airportSatelliteCard.visibility = View.GONE
        Thread {
            val image = AirportSatellite.load(appContext, target)
            view?.post {
                if (generation != satelliteGeneration || _binding == null) return@post
                if (image == null) return@post
                binding.airportSatelliteMap.setImage(image)
                binding.tvAirportSatelliteCredit.setText(AirportSatellite.creditRes())
                // Sanfter Übergang statt hartem Erscheinen.
                binding.airportSatelliteCard.alpha = 0f
                binding.airportSatelliteCard.visibility = View.VISIBLE
                binding.airportSatelliteCard.animate()
                    .alpha(1f)
                    .setDuration(SATELLITE_FADE_MS)
                    .start()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) binding.airportMap.onResume()
    }

    override fun onPause() {
        if (_binding != null) binding.airportMap.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        loadGeneration++
        satelliteGeneration++
        if (_binding != null) binding.airportMap.onDetach()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Name des Navigationsarguments, siehe `nav_graph.xml`. */
        const val ARG_CODE = "code"

        /** Dauer der Einblendung des Satellitenbildes in Millisekunden. */
        private const val SATELLITE_FADE_MS = 400L
    }
}
