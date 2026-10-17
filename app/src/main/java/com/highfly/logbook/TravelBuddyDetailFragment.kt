package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentFlightRouteDetailBinding
import com.highfly.logbook.databinding.ItemRouteStatBinding
import java.time.format.DateTimeFormatter

/**
 * Detailseite eines Reisebuddys: oben die animierte Karte mit allen gemeinsam
 * geflogenen Strecken, darunter der erste und der letzte gemeinsame Flug mit
 * Datum, Strecke und Flugnummer. Geöffnet wird die Seite durch einen Tipp auf
 * eine Zeile der Reisebuddy-Liste ([TravelBuddyFragment]).
 */
class TravelBuddyDetailFragment : Fragment() {

    private var _binding: FragmentFlightRouteDetailBinding? = null
    private val binding get() = _binding!!

    /** Name des Buddys, so wie er in der Liste steht. */
    private var buddyName: String = ""

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

        buddyName = requireArguments().getString(ARG_NAME).orEmpty()

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.tvRouteTitle.text = buddyName
        binding.tvRouteMapTitle.setText(R.string.buddy_map_title)

        if (buddyName.isBlank()) {
            binding.routeMap.visibility = View.GONE
            binding.routeStatsContainer.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
        loadStats()
    }

    /**
     * Liest die gemeinsamen Flüge im Hintergrund. Ein gemeinsamer Flug ist ein
     * Eintrag, in dessen Reisebuddy-Feld der Name steht; die Schreibweise
     * spielt keine Rolle, siehe [TravelBuddyStats.parse].
     */
    private fun loadStats() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        val target = buddyName
        Thread {
            val entries = DashboardStats.dashboardEntries(appContext)
            val filtered = entries.filter { entry ->
                TravelBuddyStats.parse(entry.travelBuddy).any { it.equals(target, ignoreCase = true) }
            }

            val first = filtered.minByOrNull { it.date }
            val last = filtered.maxByOrNull { it.date }

            val routes = filtered.mapNotNull { entry ->
                val from = AirportData.location(appContext, entry.fromAirport.uppercase())
                val to = AirportData.location(appContext, entry.toAirport.uppercase())
                if (from != null && to != null) {
                    Triple(
                        from,
                        to,
                        entry.fromAirport.uppercase() to entry.toAirport.uppercase()
                    )
                } else {
                    null
                }
            }

            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                bind(first, last, routes)
            }
        }.start()
    }

    private fun bind(
        first: LogbookEntry?,
        last: LogbookEntry?,
        routes: List<Triple<AirportData.GeoLocation, AirportData.GeoLocation, Pair<String, String>>>
    ) {
        if (routes.isNotEmpty()) {
            binding.routeMap.visibility = View.VISIBLE
            binding.routeMap.setRoutes(routes)
            binding.routeMap.playRouteAnimation()
        } else {
            binding.routeMap.visibility = View.GONE
        }

        binding.routeStatsContainer.removeAllViews()

        // Bei nur einem gemeinsamen Flug sind erster und letzter derselbe; dann
        // steht nur eine Zeile.
        if (first != null) {
            addStat(
                R.drawable.ic_flight_takeoff,
                R.string.buddy_first_flight,
                routeOf(first),
                caption(first)
            )
        }
        if (last != null && last.id != first?.id) {
            addStat(
                R.drawable.ic_flight,
                R.string.buddy_last_flight,
                routeOf(last),
                caption(last)
            )
        }

        if (first == null) {
            binding.routeStatsContainer.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
        }
    }

    private fun routeOf(entry: LogbookEntry): String =
        entry.fromAirport.uppercase() + " \u2013 " + entry.toAirport.uppercase()

    /** Datum und, wenn vorhanden, die Flugnummer als Zeile unter dem Flug. */
    private fun caption(entry: LogbookEntry): String {
        val date = DateTimeFormatter.ofPattern("dd.MM.yyyy").format(entry.date)
        val number = entry.flightNumber?.takeIf { it.isNotBlank() }
        return if (number != null) "$date \u00b7 $number" else date
    }

    /** Eine Zeile aus `item_route_stat`: Symbol, Beschriftung und der Flug. */
    private fun addStat(
        iconRes: Int,
        labelRes: Int,
        valueText: CharSequence,
        caption: String?
    ): ItemRouteStatBinding {
        val row = ItemRouteStatBinding.inflate(
            layoutInflater, binding.routeStatsContainer, false
        )
        row.ivRouteStatIcon.setImageResource(iconRes)
        row.tvRouteStatLabel.setText(labelRes)
        row.tvRouteStatValue.text = valueText
        row.tvRouteStatUnit.visibility = View.GONE
        row.tvRouteStatCaption.text = caption.orEmpty()
        row.tvRouteStatCaption.visibility =
            if (caption.isNullOrEmpty()) View.GONE else View.VISIBLE
        row.badgeRouteStatRank.visibility = View.GONE
        binding.routeStatsContainer.addView(row.root)
        return row
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        if (_binding != null) binding.routeMap.cancelRouteAnimation()
        TileValueAnimator.cancelAll()
        _binding = null
    }

    companion object {
        /** Name des Navigationsarguments, siehe `nav_graph.xml`. */
        const val ARG_NAME = "name"
    }
}
