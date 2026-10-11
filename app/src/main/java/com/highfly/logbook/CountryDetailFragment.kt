package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentCountryDetailBinding
import java.util.Locale

/**
 * Länderseite des Dashboards: Karte des Landes mit allen angeflogenen
 * Flughäfen, den Verbindungen innerhalb des Landes und den Auslandsflügen als
 * kurzen, verblassenden Linien am Flughafen des Landes.
 */
class CountryDetailFragment : Fragment() {

    private var _binding: FragmentCountryDetailBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    /**
     * Letztes Auswertungsergebnis, aus dem der Detailtext des angeklickten
     * Flughafens gebaut wird.
     */
    private var lastResult: CountryDetailModel.Result? = null

    /** Das Land als ISO-2, aus den Argumenten gelesen. */
    private val iso2: String?
        get() = arguments?.getString(ARG_ISO2)?.trim()?.uppercase()

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
        _binding = FragmentCountryDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCountryDetailBack.setOnClickListener { findNavController().navigateUp() }
        binding.fabCountryFocus.setOnClickListener { binding.countryMap.zoomToFocus() }
        // Ein Klick auf ein Flughafen-Badge blendet unten den Detailtext ein.
        binding.countryMap.onAirportSelectionChanged = { code -> showAirportDetail(code) }

        // Bis zum Laden steht der Code als Titel; Name und Flagge kommen mit
        // dem Ergebnis aus dem Hintergrund.
        binding.tvCountryDetailTitle.text = iso2 ?: ""
        load()
    }

    override fun onResume() {
        super.onResume()
        binding.countryMap.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.countryMap.onPause()
    }

    /**
     * Lädt Umrisse, Einträge und Flughafen-Daten im Hintergrund. Der
     * Generationszähler verwirft Ergebnisse einer bereits verlassenen Seite.
     */
    private fun load() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        val locale = resources.configuration.locales[0]
        val target = iso2 ?: return

        Thread {
            val shapes = CountryShapes.load(appContext)
            val entries = LogbookRepository.getFlownEntries()

            // Rang des Landes in der Länderliste, damit die Medaille in der
            // Kopfzeile zum Listenplatz passt.
            val rank = shapes?.let { list ->
                val items = VisitedCountries.build(
                    countries = list,
                    entries = entries,
                    german = useGermanNames(locale),
                    collator = java.text.Collator.getInstance(locale),
                    countryOf = { AirportData.country(appContext, it) }
                )
                items.firstOrNull { it.iso2 == target }?.let {
                    VisitedCountries.rankOf(it, items)
                } ?: 0
            } ?: 0

            // Gespeicherte Länder haben Vorrang, das Asset füllt nur die
            // Lücken: Die Zuordnung im Logbuch ist dieselbe wie auf den
            // Entries-Seiten.
            val stored = HashMap<String, String>()
            for (entry in entries) {
                entry.fromCountry?.takeIf { it.isNotBlank() }?.let {
                    stored.putIfAbsent(entry.fromAirport.trim().uppercase(), it)
                }
                entry.toCountry?.takeIf { it.isNotBlank() }?.let {
                    stored.putIfAbsent(entry.toAirport.trim().uppercase(), it)
                }
            }
            val countryOf = { code: String ->
                stored[code.uppercase()] ?: AirportData.country(appContext, code)
            }
            val location = { code: String -> AirportData.location(appContext, code) }

            val result = CountryDetailModel.analyze(entries, target, countryOf, location)
            val shape = shapes?.firstOrNull { it.iso2 == target }
            val focus = CountryDetailModel.focusBounds(result.airports)
            val domesticLines = result.domestic.mapNotNull { arcs(it, location) }
            val internationalLines = result.international.mapNotNull { arcs(it, location) }

            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                render(shape, result, domesticLines, internationalLines, focus, locale, rank)
            }
        }.start()
    }

    private fun arcs(
        route: CountryDetailModel.Route,
        location: (String) -> AirportData.GeoLocation?
    ): CountryRouteMapView.RouteLine? {
        val from = location(route.from) ?: return null
        val to = location(route.to) ?: return null
        return CountryRouteMapView.RouteLine(
            from = route.from,
            to = route.to,
            points = unwrapLongitudes(GeoMath.greatCircleArc(from, to)),
            count = route.count
        )
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

    private fun render(
        shape: CountryShapes.Country?,
        result: CountryDetailModel.Result,
        domestic: List<CountryRouteMapView.RouteLine>,
        international: List<CountryRouteMapView.RouteLine>,
        focus: CountryDetailModel.GeoBox?,
        locale: Locale,
        rank: Int
    ) {
        if (_binding == null) return
        val name = shape?.let { if (useGermanNames(locale)) it.nameDe else it.nameEn }
            ?.takeIf { it.isNotBlank() } ?: iso2
        binding.tvCountryDetailTitle.text = listOfNotNull(
            name,
            AirportData.flagEmoji(iso2.orEmpty())
        ).joinToString(" ")
        binding.tvCountryDetailSubtitle.text = listOfNotNull(
            iso2,
            iso2?.let { country ->
                Continents.continentOf(country)
                    ?.let { resources.getString(Continents.nameRes(it)) }
            }
        ).distinct().joinToString(" · ")

        // Medaille nur für die ersten drei Plätze; ab Platz vier bleibt das
        // Badge unsichtbar, weil die Kopfzeile sonst eine Platznummer trüge.
        val hasMedal = rank in 1..3
        binding.badgeCountryDetailRank.visibility = if (hasMedal) View.VISIBLE else View.GONE
        if (hasMedal) binding.badgeCountryDetailRank.setRank(rank)

        binding.tvCountryStats.text = getString(
            R.string.country_detail_stats,
            result.airports.size,
            result.flights
        )

        val hasData = result.airports.isNotEmpty() ||
            domestic.isNotEmpty() || international.isNotEmpty()
        binding.tvCountryEmpty.visibility = if (hasData) View.GONE else View.VISIBLE

        binding.countryMap.setData(shape, result.airports, domestic, international)
        binding.countryMap.setFocus(focus)
        binding.fabCountryFocus.visibility = if (focus != null) View.VISIBLE else View.GONE
        binding.countryMap.contentDescription = getString(
            R.string.country_detail_map_desc,
            name ?: iso2.orEmpty(),
            result.airports.size
        )
        // Frisch geladene Seite ohne Auswahl: der Detailtext ist verborgen.
        lastResult = result
        showAirportDetail(null)
    }

    /**
     * Zeigt unten den Detailtext zum angeklickten Flughafen: Zeile eins mit
     * Dreilettercode und Name, Zeile zwei mit der Zahl der Flüge und Zeile
     * drei mit den drei häufigsten Verbindungen, getrennt durch einen Punkt.
     * Null blendet den Text wieder aus.
     */
    private fun showAirportDetail(code: String?) {
        val view = _binding ?: return
        val result = lastResult ?: return
        val airport = code?.let { c -> result.airports.firstOrNull { it.iata == c } }
        if (airport == null) {
            view.tvCountryAirportDetail.visibility = View.GONE
            return
        }
        val lines = mutableListOf(
            listOfNotNull(airport.iata, airportName(airport.iata)).joinToString(" · ")
        )
        lines += resources.getQuantityString(
            R.plurals.visited_country_flights, airport.count, airport.count
        )
        val top = topConnections(result, airport.iata)
        if (top.isNotEmpty()) {
            lines += resources.getQuantityString(
                R.plurals.country_detail_top_routes,
                top.size,
                top.joinToString(" · ")
            )
        }
        view.tvCountryAirportDetail.text = lines.joinToString("\n")
        view.tvCountryAirportDetail.visibility = View.VISIBLE
    }

    /** Name des Flughafens, ersatzweise die Stadt. */
    private fun airportName(code: String): String? =
        AirportNames.get(requireContext(), code)?.let { it.name ?: it.city }

    /** Die drei häufigsten Verbindungen des Flughafens, absteigend nach Zahl. */
    private fun topConnections(result: CountryDetailModel.Result, code: String): List<String> =
        (result.domestic + result.international)
            .mapNotNull { route ->
                when {
                    route.from == code && route.to != code -> route.to to route.count
                    route.to == code && route.from != code -> route.from to route.count
                    else -> null
                }
            }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map { it.first }
            .distinct()
            .take(3)

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(locale: Locale): Boolean =
        locale.language.isEmpty() || locale.language == Settings.LANG_DE

    override fun onDestroyView() {
        loadGeneration++
        _binding?.countryMap?.onDetach()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Name des Navigationsarguments, siehe `nav_graph.xml`. */
        const val ARG_ISO2 = "iso2"
    }
}