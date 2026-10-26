package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentCo2OffsetBinding
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Seite "CO₂-Ausgleich": Karte des Abfluglands mit dem meistgenutzten
 * Flughafen, um den herum eine animierte Waldfläche die nötige Pflanzfläche
 * zeigt.
 *
 * Der Zeitraum lässt sich oben rechts einstellen (Standard: gesamter Zeitraum);
 * die Flugart kommt wie auf dem Dashboard aus den Einstellungen. Beide werden
 * unter dem Titel angezeigt, damit die Zahlen einordenbar sind. Die Auswahl des
 * Landes und des Flughafens steckt in [Co2OffsetModel], die Animation in
 * [Co2OffsetMapView].
 */
class Co2OffsetFragment : LogbookFragment() {

    private var _binding: FragmentCo2OffsetBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    /**
     * Der Zeitraum, den diese Seite zeigt. Bewusst nur hier gehalten, damit die
     * Einstellung des Dashboards unangetastet bleibt; Standard ist der gesamte
     * Zeitraum.
     */
    private var periodKey: String = PeriodOptions.KEY_ALL

    /**
     * Ob die Karte schon einmal auf die Waldfläche ausgerichtet wurde. Beim
     * Wechsel des Zeitraums bleibt der Maßstab dann stehen, damit die Fläche
     * ihre Größe sichtbar ändert.
     */
    private var mapFitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        periodKey = savedInstanceState?.getString(KEY_PERIOD) ?: PeriodOptions.KEY_ALL
        val context = requireContext()
        val config = org.osmdroid.config.Configuration.getInstance()
        config.load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
        config.userAgentValue = context.packageName
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PERIOD, periodKey)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCo2OffsetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Die Kennzahlen-Zeile schwebt als festes Element über der
        // Navigationsleiste; die Karte darüber endet über ihr (und hebt
        // damit die FABs in der Karte mit an).
        binding.llCo2Stats.addBottomNavMargin()

        binding.btnCo2OffsetBack.setOnClickListener { findNavController().navigateUp() }
        binding.fabCo2Replay.setOnClickListener { binding.co2Map.playAnimation() }
        binding.fabCo2Zoom.setOnClickListener {
            val focused = binding.co2Map.toggleForestZoom()
            updateZoomButton(focused)
        }
        binding.btnCo2Period.setOnClickListener { showPeriodMenu(it) }
        renderFilter()
        load()
    }

    /** Zeitraum-Menü: Die Auswahl richtet die Seite neu aus. */
    private fun showPeriodMenu(anchor: View) {
        val context = requireContext()
        val keys = PeriodOptions.keys(context)
        FilterPopupMenu.showOptions(
            context,
            anchor,
            keys,
            keys.map { PeriodOptions.headerLabel(context, it) },
            periodKey
        ) { key ->
            periodKey = key
            renderFilter()
            load()
        }
    }

    /** Filterzeile: Zeitraum im Knopf, Flugart darunter. */
    private fun renderFilter() {
        val context = requireContext()
        binding.btnCo2Period.text = PeriodOptions.headerLabel(context, periodKey)
        // Aktiver Flugart-Filter: Der Wert leuchtet in der Akzentfarbe.
        val typeKey = Settings.getFlightTypeFilterKey(context)
        val typeLabel = FlightTypeOptions.label(context, typeKey)
        binding.tvCo2FilterInfo.text = FilterHighlight.accent(
            binding.tvCo2FilterInfo,
            getString(R.string.co2_offset_filter_flight_type, typeLabel),
            typeLabel,
            typeKey != FlightTypeOptions.KEY_ALL
        )
    }

    /** Zoom-Knopf zeigt je nach Ansicht "heran" oder "heraus". */
    private fun updateZoomButton(forestFocused: Boolean) {
        binding.fabCo2Zoom.setImageResource(
            if (forestFocused) R.drawable.ic_zoom_out else R.drawable.ic_zoom_in
        )
        binding.fabCo2Zoom.contentDescription = getString(
            if (forestFocused) R.string.co2_offset_zoom_out else R.string.co2_offset_zoom_in
        )
    }

    override fun onResume() {
        super.onResume()
        binding.co2Map.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.co2Map.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        binding.co2Map.onDetach()
        _binding = null
    }

    /**
     * Lädt Umrisse, Einträge und Flughafen-Daten im Hintergrund. Der Zeitraum
     * kommt aus [periodKey] (oben rechts einstellbar), die Flugart aus den
     * Einstellungen – dieselbe wie auf dem Dashboard.
     */
    private fun load() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        val locale = resources.configuration.locales[0]

        Thread {
            val shapes = CountryShapes.load(appContext)
            val typeKey = Settings.getFlightTypeFilterKey(appContext)
            val entries = DashboardStats.filterForPeriod(
                FlightTypeOptions.filter(LogbookRepository.getFlownEntries(), typeKey),
                periodKey
            )

            // Gespeicherte Länder haben Vorrang, das Asset füllt nur die
            // Lücken - dieselbe Zuordnung wie auf der Länderseite.
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

            val result = Co2OffsetModel.analyze(entries, countryOf, location)
            val shape = result?.let { r ->
                shapes?.firstOrNull { it.iso2 == r.iso2 }
            }

            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                render(shape, result, locale)
            }
        }.start()
    }

    private fun render(
        shape: CountryShapes.Country?,
        result: Co2OffsetModel.Result?,
        locale: Locale
    ) {
        if (_binding == null) return
        val view = _binding!!

        if (result == null) {
            view.co2Map.visibility = View.GONE
            view.co2MapCard.visibility = View.VISIBLE
            view.tvCo2Empty.visibility = View.VISIBLE
            view.fabCo2Replay.visibility = View.GONE
            view.fabCo2Zoom.visibility = View.GONE
            view.llCo2Stats.visibility = View.GONE
            view.tvCo2Attribution.visibility = View.GONE
            view.tvCo2OffsetSubtitle.text = ""
            return
        }

        val context = requireContext()
        val name = shape?.let { if (useGermanNames(locale)) it.nameDe else it.nameEn }
            ?.takeIf { it.isNotBlank() } ?: result.iso2
        view.tvCo2OffsetSubtitle.text = listOfNotNull(
            name,
            AirportData.flagEmoji(result.iso2)
        ).joinToString(" ")

        view.co2Map.visibility = View.VISIBLE
        view.tvCo2Empty.visibility = View.GONE
        view.fabCo2Replay.visibility = View.VISIBLE
        view.fabCo2Zoom.visibility = View.VISIBLE
        view.llCo2Stats.visibility = View.VISIBLE

        val airportName = AirportNames.get(context, result.airport.iata)
            ?.let { it.name ?: it.city }
        view.tvCo2AirportHint.text = getString(
            R.string.co2_offset_airport,
            result.airport.iata,
            airportName.orEmpty()
        )
        view.tvCo2AirportRoutes.text = getString(
            R.string.co2_offset_airport_routes,
            result.airport.connections,
            result.airport.flights
        )

        val appLocale = Settings.language(context)
        view.tvCo2TonsValue.text = Co2Calculator.tonnesText(result.tonnes, appLocale)
        view.tvCo2TreesValue.text = String.format(appLocale, "%,d", result.treesPerYear)
        view.tvCo2AreaValue.text = areaText(result.hectares, appLocale)

        // Die Bäume auf der Karte sind reine Optik; die Legende nennt daher nur
        // Pflanzdichte und den maßstabsgetreuen Radius der Fläche.
        view.tvCo2Legend.text = listOf(
            getString(R.string.co2_offset_density),
            getString(R.string.co2_offset_radius, radiusText(result.hectares, appLocale))
        ).joinToString(" · ")
        view.tvCo2Football.text = footballText(result.hectares, appLocale)

        // Quellennachweis des Luftbilds, sofern für den Flughafen eines vorliegt
        // (nur FRA und MUC). Bayern verlangt als CC-BY-4.0-Daten die Nennung.
        val creditRes = Orthophotos.creditRes(result.airport.iata)
        view.tvCo2Attribution.text = creditRes?.let {
            getString(R.string.co2_offset_credit_label, getString(it))
        }
        view.tvCo2Attribution.visibility = if (creditRes != null) View.VISIBLE else View.GONE

        view.co2Map.setScene(
            shape,
            result.airport.iata,
            result.airport.lat,
            result.airport.lon,
            result.hectares,
            fit = !mapFitted
        )
        mapFitted = true
        updateZoomButton(true)
        view.co2Map.contentDescription = getString(
            R.string.co2_offset_map_desc,
            name, result.airport.iata
        )
    }

    /**
     * Rein dekorative Bäume haben keine Zählfunktion; die Legende nennt nur die
     * Pflanzdichte und – damit die Größe greifbar wird – den echten Radius.
     */
    private fun radiusText(hectares: Double, locale: Locale): String {
        val meters = sqrt(hectares * 10_000.0 / PI)
        return if (meters >= 1000) {
            String.format(locale, "%,.1f km", meters / 1000.0)
        } else {
            String.format(locale, "%,.0f m", meters)
        }
    }

    /** Fläche als Text: zwei Nachkommastellen unter 10 ha, sonst eine. */
    private fun areaText(hectares: Double, locale: Locale): String = when {
        hectares < 10 -> String.format(locale, "%,.2f", hectares)
        hectares < 1000 -> String.format(locale, "%,.1f", hectares)
        else -> String.format(locale, "%,.0f", hectares)
    }

    /** Die Fläche in Fußballfeldern (ein Feld ≈ 7.140 m²), falls vorhanden. */
    private fun footballText(hectares: Double, locale: Locale): String {
        val fields = (hectares * 10000.0 / FOOTBALL_FIELD_M2).roundToInt()
        if (fields <= 0) return ""
        return getString(R.string.co2_offset_football, String.format(locale, "%,d", fields))
    }

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(locale: Locale): Boolean =
        locale.language.isEmpty() || locale.language == Settings.LANG_DE

    private companion object {
        /** Fläche eines Fußballfelds in Quadratmetern (105 m × 68 m). */
        const val FOOTBALL_FIELD_M2 = 7140.0

        /** Schlüssel für den gewählten Zeitraum beim Neuerstellen der Ansicht. */
        const val KEY_PERIOD = "co2_offset_period"
    }
}