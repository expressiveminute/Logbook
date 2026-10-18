package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentFlightRouteDetailBinding
import com.highfly.logbook.databinding.ItemLayoverTimelineBinding
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

        // Klick auf die Karte öffnet die Weltkarte mit voreingestelltem Reisebuddy-Filter
        binding.routeMap.setOnClickListener {
            val bundle = bundleOf(
                "filterTravelBuddy" to buddyName
            )
            findNavController().navigate(R.id.action_buddy_detail_to_world_map, bundle)
        }

        if (buddyName.isBlank()) {
            binding.routeMap.visibility = View.GONE
            binding.routeStatsContainer.visibility = View.GONE
            binding.tvFlightListTitle.visibility = View.GONE
            binding.flightListRoot.visibility = View.GONE
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
                bind(first, last, routes, filtered)
            }
        }.start()
    }

    private fun bind(
        first: LogbookEntry?,
        last: LogbookEntry?,
        routes: List<Triple<AirportData.GeoLocation, AirportData.GeoLocation, Pair<String, String>>>,
        allEntries: List<LogbookEntry>
    ) {
        if (routes.isNotEmpty()) {
            binding.routeMap.visibility = View.VISIBLE
            binding.routeMap.setRoutes(routes)
            binding.routeMap.playRouteAnimation()
        } else {
            binding.routeMap.visibility = View.GONE
        }

        binding.routeStatsContainer.removeAllViews()

        // Erster und letzter Flug nebeneinander anzeigen
        if (first != null) {
            addStat(
                R.drawable.ic_flight_takeoff,
                R.string.buddy_first_flight,
                routeOf(first),
                caption(first),
                isFirst = true
            )
        }
        if (last != null && last.id != first?.id) {
            addStat(
                R.drawable.ic_flight,
                R.string.buddy_last_flight,
                routeOf(last),
                caption(last),
                isFirst = false
            )
        }

        // Timeline mit allen gemeinsamen Flügen
        renderTimeline(allEntries)

        if (first == null) {
            binding.routeStatsContainer.visibility = View.GONE
            binding.tvFlightListTitle.visibility = View.GONE
            binding.flightListRoot.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
        } else {
            binding.tvFlightListTitle.visibility = View.VISIBLE
            binding.flightListRoot.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
        }
    }

    /**
     * Zeitstrahl mit allen gemeinsamen Flügen, ähnlich wie bei Layover.
     * Der jüngste Flug steht oben.
     */
    private fun renderTimeline(entries: List<LogbookEntry>) {
        binding.flightList.removeAllViews()
        if (entries.isEmpty()) return

        // Sortieren: jüngste zuerst (absteigend nach Datum)
        val sortedEntries = entries.sortedByDescending { it.date }

        sortedEntries.forEachIndexed { index, entry ->
            val row = ItemLayoverTimelineBinding.inflate(
                layoutInflater, binding.flightList, false
            )
            row.root.setOnClickListener {
                findNavController().navigate(
                    R.id.action_buddy_detail_to_add_entry,
                    bundleOf("entryId" to (entry.id ?: -1L))
                )
            }
            row.tvDate.text = DateTimeFormatter.ofPattern("dd.MM.yyyy").format(entry.date)
            row.timelineDot.text = entry.flightMinutes?.let { minutes ->
                if (minutes < 60) "$minutes min" else "${minutes / 60}h"
            }.orEmpty()
            row.tvRoute.text = getString(
                R.string.import_review_summary_route,
                entry.fromAirport.uppercase(),
                entry.toAirport.uppercase()
            )
            val number = listOfNotNull(entry.airline, entry.flightNumber)
                .joinToString(" ")
            if (number.isNotBlank()) {
                row.tvMeta.visibility = View.VISIBLE
                row.tvMeta.text = number
            }
            // Reisebuddy nicht anzeigen, da wir bereits auf der Reisebuddy-Seite sind
            row.ivBuddy.visibility = View.GONE
            row.tvBuddy.visibility = View.GONE
            
            if (index == sortedEntries.lastIndex) {
                (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }
            binding.flightList.addView(row.root)
        }

        binding.flightListRoot.post {
            if (_binding != null) alignTimelineLine()
        }
    }

    /**
     * Die senkrechte Linie des Zeitstrahls verbindet nur die Punkte.
     */
    private fun alignTimelineLine() {
        val rows = binding.flightList
        val first = rows.getChildAt(0) ?: return
        val last = rows.getChildAt(rows.childCount - 1)
        val start = first.height / 2
        val end = rows.height - last.height / 2
        val params = binding.flightListLine.layoutParams as? ViewGroup.MarginLayoutParams
            ?: return
        params.height = (end - start).coerceAtLeast(0)
        params.topMargin = start
        binding.flightListLine.layoutParams = params
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
        caption: String?,
        isFirst: Boolean
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
        
        // Für nebeneinander Anzeige: beide bekommen Gewicht 1
        val params = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        row.root.layoutParams = params
        
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
