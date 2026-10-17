package com.highfly.logbook

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentFlightRouteDetailBinding
import com.highfly.logbook.databinding.ItemLayoverTimelineBinding
import com.highfly.logbook.databinding.ItemRouteStatBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Detailseite für einen Flugzeugtyp oder eine Registrierung aus dem Hangar:
 * oben die animierte Karte mit allen Flügen dieses Typs bzw. dieser
 * Registrierung, darunter die Kennzahlen - wie oft, wie weit und wie lange
 * damit geflogen wurde (beim Flugzeugtyp zusätzlich der Zeitraum vom ersten bis
 * zum letzten Flug) - und zuletzt der Zeitstrahl aller dieser Flüge, der
 * jüngste oben, im Aufbau der Layover-Detailseite. Aufgerufen wird die Seite aus
 * der Hangar-Kachel, siehe [TileDetailFragment].
 *
 * Anders als die Detailseite einer Strecke ranken die Kennzahlen hier nicht:
 * Dieselbe Zahl gibt es nur einmal je Typ bzw. Registrierung.
 */
class AircraftFlightsFragment : Fragment() {

    private var _binding: FragmentFlightRouteDetailBinding? = null
    private val binding get() = _binding!!

    /** Ob nach Flugzeugtyp ([MODE_TYPE]) oder Registrierung ([MODE_REG]) gefiltert wird. */
    private var mode: String = MODE_TYPE

    /** Der gesuchte Typ bzw. die Registrierung. */
    private var value: String = ""

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

        mode = requireArguments().getString(ARG_MODE) ?: MODE_TYPE
        value = requireArguments().getString(ARG_VALUE).orEmpty()

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.tvRouteTitle.text = value
        binding.tvRouteMapTitle.setText(
            if (mode == MODE_REG) R.string.aircraft_map_title_reg
            else R.string.aircraft_map_title_type
        )

        if (value.isBlank()) {
            binding.routeMap.visibility = View.GONE
            binding.routeStatsContainer.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
    }

    /**
     * Die Flüge werden bei jedem Erscheinen neu gelesen: nach dem Bearbeiten
     * eines Eintrags (die Liste öffnet ihn) soll die Seite den geänderten
     * Eintrag sofort zeigen, ohne dass man sie erneut öffnen muss. Der erste
     * Aufruf kommt direkt nach [onViewCreated].
     */
    override fun onResume() {
        super.onResume()
        if (_binding == null || value.isBlank()) return
        loadStats()
    }

    /**
     * Liest die Flüge und ihre Kennzahlen im Hintergrund; die Kartendaten des
     * ersten Aufrufs würden den Aufbau sonst spürbar verzögern.
     */
    private fun loadStats() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        val targetValue = value
        val targetMode = mode
        Thread {
            val entries = DashboardStats.dashboardEntries(appContext)
            val filtered = entries.filter { entry ->
                if (targetMode == MODE_REG) {
                    entry.registration.equals(targetValue, ignoreCase = true)
                } else {
                    entry.aircraftType.equals(targetValue, ignoreCase = true)
                }
            }

            val totalKm = filtered.sumOf { it.distanceKm ?: 0 }
            val totalMinutes = filtered.sumOf { it.flightMinutes ?: 0 }
            val count = filtered.size
            val firstDate = filtered.minOfOrNull { it.date }
            val lastDate = filtered.maxOfOrNull { it.date }
            // Der jüngste Flug steht oben, wie in der Eintragliste. Innerhalb
            // eines Tages entscheidet die Kennung, damit die Reihenfolge fest
            // bleibt.
            val newestFirst = filtered.sortedWith(
                compareByDescending<LogbookEntry> { it.date }
                    .thenByDescending { it.id ?: -1L }
            )

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
                bind(count, totalKm, totalMinutes, firstDate, lastDate, routes, newestFirst)
            }
        }.start()
    }

    private fun bind(
        count: Int,
        totalKm: Int,
        totalMinutes: Int,
        firstDate: LocalDate?,
        lastDate: LocalDate?,
        routes: List<Triple<AirportData.GeoLocation, AirportData.GeoLocation, Pair<String, String>>>,
        entries: List<LogbookEntry>
    ) {
        val context = requireContext()
        val locale = Settings.language(context)

        if (routes.isNotEmpty()) {
            binding.routeMap.visibility = View.VISIBLE
            binding.routeMap.setRoutes(routes)
            binding.routeMap.playRouteAnimation()
        } else {
            binding.routeMap.visibility = View.GONE
            binding.tvRouteMapTitle.visibility = View.GONE
        }

        binding.routeStatsContainer.removeAllViews()

        val time = DashboardStats.timeValue(context, totalMinutes, DashboardStats.TimeUnit.HOURS)
        val minutesCaption = if (totalMinutes >= 60) {
            getString(R.string.flight_route_stat_minutes, formatInt(locale, totalMinutes))
        } else {
            null
        }

        val flights = addStat(
            R.drawable.ic_flight,
            R.string.aircraft_stat_flights,
            formatInt(locale, count),
            unit = null,
            caption = null
        )
        val distance = addStat(
            R.drawable.ic_distance_arrow,
            R.string.aircraft_stat_distance,
            formatInt(locale, totalKm),
            unit = getString(R.string.flight_distance_hint),
            caption = null
        )
        val duration = addStat(
            R.drawable.ic_time,
            R.string.aircraft_stat_time,
            time.text,
            unit = time.unit,
            caption = minutesCaption
        )
        // Bei einer Registrierung entfällt der Zeitraum: Er steht schon im
        // Zeitstrahl der Flüge darunter. Beim Flugzeugtyp bleibt die Kennzahl.
        if (mode != MODE_REG) {
            val period = periodText(firstDate, lastDate)
            if (period.isNotEmpty()) {
                addStat(
                    R.drawable.ic_timeline_dot,
                    R.string.aircraft_stat_period,
                    period,
                    unit = null,
                    caption = null
                )
            }
        }

        // Die Zahlen zählen sichtbar von 0 auf ihren Zielwert hoch; die Karte
        // zeichnet währenddessen ihre Strecken auf.
        TileValueAnimator.animate(flights.tvRouteStatValue, flights.tvRouteStatValue.text)
        TileValueAnimator.animate(duration.tvRouteStatValue, duration.tvRouteStatValue.text)
        TileValueAnimator.animate(distance.tvRouteStatValue, distance.tvRouteStatValue.text)

        renderFlightList(entries)
    }

    /**
     * Liste aller Flüge dieser Registrierung bzw. dieses Typs, der jüngste oben,
     * im Aufbau des Layover-Zeitstrahls: Datum, Dreiletterroute, Flugnummer und
     * der Reisebuddy; im Punkt steht die Flugzeit. Ein Tipp öffnet den Eintrag
     * zum Bearbeiten. Dieselbe Zeile wie auf der Layover-Detailseite
     * (`item_layover_timeline`), damit beide Listen gleich aussehen.
     */
    private fun renderFlightList(entries: List<LogbookEntry>) {
        val context = requireContext()
        binding.flightList.removeAllViews()
        if (entries.isEmpty()) {
            binding.tvFlightListTitle.visibility = View.GONE
            binding.flightListRoot.visibility = View.GONE
            return
        }
        binding.tvFlightListTitle.visibility = View.VISIBLE
        binding.flightListRoot.visibility = View.VISIBLE
        entries.forEachIndexed { index, entry ->
            val row = ItemLayoverTimelineBinding.inflate(
                layoutInflater, binding.flightList, false
            )
            row.root.setOnClickListener { openEditEntry(entry) }
            row.tvDate.text = entry.date.format(DATE_LABEL_FORMAT)
            row.timelineDot.text = entry.flightMinutes
                ?.takeIf { it > 0 }
                ?.let { dotTime(context, it) }
                .orEmpty()
            val from = entry.fromAirport.uppercase()
            val to = entry.toAirport.uppercase()
            if (from.isNotBlank() && to.isNotBlank()) {
                row.tvRoute.text = getString(R.string.import_review_summary_route, from, to)
            } else {
                row.tvRoute.visibility = View.GONE
            }
            val number = listOfNotNull(entry.airline, entry.flightNumber)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (number.isNotBlank()) {
                row.tvMeta.visibility = View.VISIBLE
                row.tvMeta.text = number
            }
            val buddy = entry.travelBuddy?.takeIf { it.isNotBlank() }
            if (buddy != null) {
                row.ivBuddy.visibility = View.VISIBLE
                row.tvBuddy.visibility = View.VISIBLE
                row.tvBuddy.text = buddy
            }
            if (index == entries.lastIndex) {
                (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }
            binding.flightList.addView(row.root)
        }
        binding.flightListRoot.post { if (_binding != null) alignFlightListLine() }
    }

    /**
     * Die senkrechte Linie des Zeitstrahls verbindet nur die Punkte: Sie beginnt
     * auf halber Höhe der obersten Zeile und endet auf halber Höhe der
     * untersten, wie auf der Layover-Detailseite.
     */
    private fun alignFlightListLine() {
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

    /**
     * Flugzeit im Punkt des Zeitstrahls: unter einer Stunde in Minuten, sonst
     * in Stunden mit einer Nachkommastelle - kurz genug für den kleinen Punkt.
     * Die Zahl kommt aus derselben Formatierung wie überall sonst.
     */
    private fun dotTime(context: Context, minutes: Int): String {
        val value = DashboardStats.timeValue(context, minutes, DashboardStats.TimeUnit.HOURS)
        return if (minutes < 60) {
            context.getString(R.string.aircraft_flight_dot_minutes, value.text)
        } else {
            context.getString(R.string.aircraft_flight_dot_hours, value.text)
        }
    }

    /** Eintrag der Liste bearbeiten: dieselbe Seite "Flug bearbeiten". */
    private fun openEditEntry(entry: LogbookEntry) {
        findNavController().navigate(
            R.id.action_aircraft_flights_to_add_entry,
            bundleOf("entryId" to (entry.id ?: -1L))
        )
    }

    /** Erster bis letzter Flug als Datumstext; an einem Tag nur ein Datum. */
    private fun periodText(first: LocalDate?, last: LocalDate?): String {
        val format = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        return when {
            first != null && last != null && first == last -> format.format(first)
            first != null && last != null -> format.format(first) + " \u2013 " + format.format(last)
            first != null -> format.format(first)
            last != null -> format.format(last)
            else -> ""
        }
    }

    /**
     * Eine Kennzahl als Zeile aus `item_route_stat`: Bild links, Beschriftung
     * und hochzählende Zahl in der Mitte. Ein Rangzeichen gibt es hier nicht.
     */
    private fun addStat(
        iconRes: Int,
        labelRes: Int,
        valueText: CharSequence,
        unit: String?,
        caption: String?
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
        row.badgeRouteStatRank.visibility = View.GONE
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
        /** Name der Navigationsargumente, siehe `nav_graph.xml`. */
        const val ARG_MODE = "mode"
        const val ARG_VALUE = "value"

        /** Filter nach Flugzeugtyp. */
        const val MODE_TYPE = "type"

        /** Filter nach Registrierung. */
        const val MODE_REG = "registration"

        /** Datumsformat der Flugliste, wie auf der Layover-Detailseite. */
        val DATE_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
