package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentFlightRouteDetailBinding
import com.highfly.logbook.databinding.ItemRouteStatBinding
import java.util.Locale

/**
 * Detailseite einer einzelnen Flugstrecke der Kachel "Flugrouten": in der
 * Kopfzeile mittig die Strecke, darunter die animierte Streckenkarte und drei
 * Kennzahlen - wie oft, wie lange und wie weit die Strecke geflogen wurde.
 *
 * Jede Kennzahl trägt rechts ihren eigenen Platz in der Rangliste dieser
 * Kennzahl ([ChartData.FlightRouteSort]): dieselbe Strecke kann nach der Zahl
 * der Flüge ganz vorn stehen, nach der Distanz aber weiter hinten, weil die
 * Flüge kurz sind.
 */
class FlightRouteDetailFragment : LogbookFragment() {

    private var _binding: FragmentFlightRouteDetailBinding? = null
    private val binding get() = _binding!!

    /** Die Strecke als Schlüssel der Balkenliste, z. B. "FRA-JFK". */
    private var route: String = ""

    /** Verwirft das Ergebnis eines bereits verlassenen Ladevorgangs. */
    private var loadGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFlightRouteDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Platz unter der schwebenden Navigationsleiste.
        binding.scrollView.addBottomNavPadding()

        route = requireArguments().getString(ARG_ROUTE).orEmpty()

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
        // Die Strecke im Kopf trägt zwischen den beiden Dreilettercodes einen
        // Gedankenstrich mit Luft; der Schlüssel der Balkenliste ist dagegen mit
        // Bindestrich geschrieben.
        binding.tvRouteTitle.text = route.replace("-", " \u2013 ")

        if (route.isBlank()) {
            binding.routeMap.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
        loadStats()
    }

    /**
     * Liest Kennzahlen, Ränge und Flughafenkoordinaten im Hintergrund; die
     * Assets der Karte sind beim ersten Aufruf noch nicht im Speicher und
     * würden den Aufbau sonst spürbar verzögern.
     */
    private fun loadStats() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        val target = route
        Thread {
            val entries = DashboardStats.dashboardEntries(appContext)
            val stats = ChartData.flightRouteStats(entries, target)
            val flightsRank =
                ChartData.flightRouteRank(entries, target, ChartData.FlightRouteSort.ANZAHL)
            val durationRank =
                ChartData.flightRouteRank(entries, target, ChartData.FlightRouteSort.DAUER)
            val distanceRank =
                ChartData.flightRouteRank(entries, target, ChartData.FlightRouteSort.ENTFERNUNG)
            val fromCode = target.substringBefore('-').uppercase()
            val toCode = target.substringAfter('-').uppercase()
            val from = AirportData.location(appContext, fromCode)
            val to = AirportData.location(appContext, toCode)
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                bind(stats, flightsRank, durationRank, distanceRank, from, to, fromCode, toCode)
            }
        }.start()
    }

    private fun bind(
        stats: ChartData.FlightRouteStats,
        flightsRank: Int,
        durationRank: Int,
        distanceRank: Int,
        from: AirportData.GeoLocation?,
        to: AirportData.GeoLocation?,
        fromCode: String,
        toCode: String
    ) {
        val context = requireContext()
        val locale = Settings.language(context)

        if (from != null && to != null && fromCode.isNotBlank() && toCode.isNotBlank()) {
            binding.routeMap.visibility = View.VISIBLE
            binding.routeMap.setRoute(from, to, fromCode, toCode)
            binding.routeMap.playRouteAnimation()
        } else {
            binding.routeMap.visibility = View.GONE
        }

        binding.routeStatsContainer.removeAllViews()

        val flightsText = formatInt(locale, stats.flights)
        val kmText = formatInt(locale, stats.km)
        val time = DashboardStats.timeValue(context, stats.minutes, DashboardStats.TimeUnit.HOURS)
        val minutesCaption = if (stats.minutes >= 60) {
            getString(R.string.flight_route_stat_minutes, formatInt(locale, stats.minutes))
        } else {
            null
        }

        val flights = addStat(
            R.drawable.ic_flight,
            R.string.flight_route_stat_flights,
            flightsText,
            unit = null,
            caption = null,
            rank = flightsRank
        )
        val duration = addStat(
            R.drawable.ic_time,
            R.string.flight_route_stat_duration,
            time.text,
            unit = time.unit,
            caption = minutesCaption,
            rank = durationRank
        )
        val distance = addStat(
            R.drawable.ic_distance_arrow,
            R.string.flight_route_stat_distance,
            kmText,
            unit = getString(R.string.flight_distance_hint),
            caption = null,
            rank = distanceRank
        )

        // Die Zahlen zählen sichtbar von 0 auf ihren Zielwert hoch; die Karte
        // zeichnet währenddessen ihre Strecke auf.
        TileValueAnimator.animate(flights.tvRouteStatValue, flightsText)
        TileValueAnimator.animate(duration.tvRouteStatValue, time.text)
        TileValueAnimator.animate(distance.tvRouteStatValue, kmText)
    }

    /**
     * Eine Kennzahl als Zeile aus `item_route_stat`: Bild links, Beschriftung
     * und hochzählende Zahl in der Mitte, der eigene Rang rechts. [unit] und
     * [caption] bleiben weg, wenn sie leer sind; [rank] ausserhalb der ersten
     * zehn Plätze lässt das Rangzeichen leer.
     */
    private fun addStat(
        iconRes: Int,
        labelRes: Int,
        valueText: CharSequence,
        unit: String?,
        caption: String?,
        rank: Int
    ): ItemRouteStatBinding {
        val row = ItemRouteStatBinding.inflate(
            layoutInflater, binding.routeStatsContainer, false
        )
        row.ivRouteStatIcon.setImageResource(iconRes)
        row.tvRouteStatLabel.setText(labelRes)
        row.tvRouteStatValue.text = valueText
        row.tvRouteStatUnit.text = unit.orEmpty()
        row.tvRouteStatUnit.visibility = if (unit.isNullOrEmpty()) View.GONE else View.VISIBLE
        row.tvRouteStatCaption.text = caption.orEmpty()
        row.tvRouteStatCaption.visibility =
            if (caption.isNullOrEmpty()) View.GONE else View.VISIBLE
        // Medaille nur für die ersten drei Plätze, Platznummer bis zehn -
        // dieselben Rangzeichen wie in der Balkenliste (siehe RankBadgeView).
        val hasRank = rank in 1..10
        row.badgeRouteStatRank.visibility = if (hasRank) View.VISIBLE else View.GONE
        if (hasRank) row.badgeRouteStatRank.setRank(rank)
        binding.routeStatsContainer.addView(row.root)
        return row
    }

    private fun formatInt(locale: Locale, value: Int): String =
        String.format(locale, "%,d", value)

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        if (_binding != null) binding.routeMap.cancelRouteAnimation()
        TileValueAnimator.cancelAll()
        _binding = null
    }

    companion object {
        /** Name des Navigationsarguments, siehe `nav_graph.xml`. */
        const val ARG_ROUTE = "route"
    }
}
