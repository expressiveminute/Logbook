package com.highfly.logbook

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.highfly.logbook.databinding.DialogBuddyRenameBinding
import com.highfly.logbook.databinding.FragmentFlightRouteDetailBinding
import com.highfly.logbook.databinding.ItemLayoverTimelineBinding
import com.highfly.logbook.databinding.ItemStatTileBinding
import java.time.format.DateTimeFormatter

/**
 * Detailseite eines Reisebuddys: oben die animierte Karte mit allen gemeinsam
 * geflogenen Strecken, darunter zwei Kacheln mit dem ersten und letzten
 * gemeinsamen Flug (Strecke, Datum, Flugnummer) und drei Kacheln mit der Zahl
 * der gemeinsamen Flüge, der gemeinsamen Flugzeit und der gemeinsamen
 * Distanz. Ein Stift in der Kopfzeile benennt den Buddy in allen Einträgen um.
 * Geöffnet wird die Seite durch einen Tipp auf eine Zeile der Reisebuddy-Liste
 * ([TravelBuddyFragment]).
 */
class TravelBuddyDetailFragment : Fragment() {

    private var _binding: FragmentFlightRouteDetailBinding? = null
    private val binding get() = _binding!!

    /** Name des Buddys, so wie er in der Liste steht. */
    private var buddyName: String = ""

    /** Verwirft das Ergebnis eines bereits verlassenen Ladevorgangs. */
    private var loadGeneration = 0

    /** Einheit der Kachel "Zeit zusammen", per Tipp auf die Kachel zyklisch. */
    private var timeUnit = DashboardStats.TimeUnit.HOURS

    /** Gemeinsame Flugzeit in Minuten, für den Klick-Zyklus der Zeit-Kachel. */
    private var buddyMinutes = 0

    /** Kachel "Zeit zusammen", damit der Zyklus ihren Text direkt neu setzt. */
    private var timeTile: ItemStatTileBinding? = null

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

        // Der Stift rechts im Kopf benennt den Buddy um. Er nimmt den Platz
        // des Platzhalters ein, damit der Titel mittig bleibt.
        binding.headerSpacer.visibility = View.GONE
        binding.btnEdit.visibility = View.VISIBLE
        binding.btnEdit.setOnClickListener { showRenameDialog() }

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
            binding.buddyExtremes.visibility = View.GONE
            binding.buddySummary.visibility = View.GONE
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

        // Die Kennzahl-Zeilen der Strecken-Detailseite (route_stats_container)
        // bleiben auf dieser Seite ungenutzt: Stattdessen zwei Kacheln mit dem
        // ersten und letzten gemeinsamen Flug und drei Kacheln mit Zahl, Zeit
        // und Distanz der gemeinsamen Flüge.
        binding.routeStatsContainer.visibility = View.GONE
        binding.buddyExtremes.removeAllViews()
        binding.buddySummary.removeAllViews()
        timeTile = null
        if (first != null) {
            binding.buddyExtremes.visibility = View.VISIBLE
            binding.buddySummary.visibility = View.VISIBLE

            addTile(
                binding.buddyExtremes,
                R.string.buddy_first_flight,
                routeOf(first),
                caption(first)
            )
            if (last != null && last.id != first.id) {
                addTile(
                    binding.buddyExtremes,
                    R.string.buddy_last_flight,
                    routeOf(last),
                    caption(last)
                )
            }
            applyTileGaps(binding.buddyExtremes)

            // Wie die Kacheln "Flüge", "Zeit" und "Distanz" des Dashboards: Die
            // Zahl zählt jeden gemeinsamen Flug, Zeit und Distanz dagegen nur
            // echte Flüge (ein Ground Transfer zählt nicht mit).
            buddyMinutes = DashboardStats.flightMinutes(allEntries)
            val distanceKm = DashboardStats.flyingEntries(allEntries)
                .sumOf { it.distanceKm ?: 0 }
            addTile(
                binding.buddySummary,
                R.string.buddy_stat_flights,
                allEntries.size.toString(),
                resources.getQuantityString(
                    R.plurals.buddy_stat_flights_unit, allEntries.size, allEntries.size
                ),
                valueSizeSp = 20f
            )
            // Die Zeit-Kachel ist anklickbar: Ein Tipp schaltet die Einheit
            // weiter (Stunden, Tage, Monate, wieder Stunden).
            timeTile = addTile(
                binding.buddySummary,
                R.string.buddy_stat_time,
                "",
                null,
                valueSizeSp = 20f
            )
            timeTile?.root?.apply {
                isClickable = true
                isFocusable = true
                val ripple = TypedValue()
                requireContext().theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, ripple, true
                )
                foreground = ContextCompat.getDrawable(requireContext(), ripple.resourceId)
                setOnClickListener { cycleTimeUnit() }
            }
            showTimeTile()
            addTile(
                binding.buddySummary,
                R.string.buddy_stat_distance,
                String.format(Settings.language(requireContext()), "%,d", distanceKm),
                getString(R.string.flight_distance_hint),
                valueSizeSp = 20f
            )
            applyTileGaps(binding.buddySummary)
        } else {
            binding.buddyExtremes.visibility = View.GONE
            binding.buddySummary.visibility = View.GONE
        }

        // Timeline mit allen gemeinsamen Flügen
        renderTimeline(allEntries)

        if (first == null) {
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
     * Eine Kachel mit Beschriftung, grosser Zahl und kleiner Einheit darunter,
     * im Stil der Extrem-Kacheln der Layover-Seite. [valueSizeSp] schrumpft die
     * Zahl bei drei Kacheln nebeneinander, damit längere Werte hineinpassen.
     */
    private fun addTile(
        row: LinearLayout,
        labelRes: Int,
        value: CharSequence,
        caption: String?,
        valueSizeSp: Float = 24f
    ): ItemStatTileBinding {
        val tile = ItemStatTileBinding.inflate(layoutInflater, row, false)
        tile.tvStatTileLabel.setText(labelRes)
        tile.tvStatTileValue.text = value
        tile.tvStatTileValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSizeSp)
        tile.tvStatTileCaption.text = caption.orEmpty()
        tile.tvStatTileCaption.visibility =
            if (caption.isNullOrEmpty()) View.GONE else View.VISIBLE
        row.addView(tile.root)
        return tile
    }

    /** Gleiche 4dp-Lücke zwischen den Kacheln einer Reihe, aussen bündig. */
    private fun applyTileGaps(row: LinearLayout) {
        val gap = (4 * resources.displayMetrics.density).toInt()
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            val params = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            params.marginStart = if (i == 0) 0 else gap
            params.marginEnd = if (i == row.childCount - 1) 0 else gap
            child.layoutParams = params
        }
    }

    /** Nächste Einheit der Zeit-Kachel: Stunden, Tage, Monate, wieder Stunden. */
    private fun cycleTimeUnit() {
        timeUnit = when (timeUnit) {
            DashboardStats.TimeUnit.HOURS -> DashboardStats.TimeUnit.DAYS
            DashboardStats.TimeUnit.DAYS -> DashboardStats.TimeUnit.MONTHS
            else -> DashboardStats.TimeUnit.HOURS
        }
        showTimeTile()
    }

    /** Setzt Zahl und Einheit der Zeit-Kachel auf die gewählte Einheit. */
    private fun showTimeTile() {
        val tile = timeTile ?: return
        val value = DashboardStats.timeValue(requireContext(), buddyMinutes, timeUnit)
        tile.tvStatTileValue.text = value.text
        tile.tvStatTileCaption.text = value.unit.orEmpty()
        tile.tvStatTileCaption.visibility =
            if (value.unit.isNullOrEmpty()) View.GONE else View.VISIBLE
        tile.root.contentDescription = getString(
            R.string.buddy_stat_time_desc,
            tile.tvStatTileLabel.text,
            value.text,
            value.unit.orEmpty()
        )
    }

    /**
     * Dialog zum Umbenennen des Reisebuddies. Der neue Name wird in jedem
     * Eintrag ersetzt, in dem der Buddy steht - so muss kein Flug einzeln
     * bearbeitet werden. Das Feld sitzt als umrandete Box mit schwebender
     * Beschriftung im Dialog, damit der Titel und der bisherige Name nicht
     * zusammenknautscht wirken.
     */
    private fun showRenameDialog() {
        val dialogBinding = DialogBuddyRenameBinding.inflate(layoutInflater)
        val input = dialogBinding.buddyRenameInput
        input.setText(buddyName)
        input.setSelection(input.text?.length ?: 0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.buddy_rename_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.buddy_rename_confirm) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && !newName.equals(buddyName, ignoreCase = true)) {
                    renameBuddy(newName)
                }
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    /** Benennt den Buddy in der Datenbank um und zeichnet die Seite neu. */
    private fun renameBuddy(newName: String) {
        val oldName = buddyName
        Thread {
            LogbookRepository.renameTravelBuddy(oldName, newName)
            view?.post {
                if (_binding == null) return@post
                buddyName = newName
                binding.tvRouteTitle.text = newName
                loadStats()
                Snackbar.make(binding.root, R.string.buddy_renamed, Snackbar.LENGTH_SHORT)
                    .show()
            }
        }.start()
    }

    /**
     * Zeitstrahl mit allen gemeinsamen Flügen, ähnlich wie bei Layover.
     * Der jüngste Flug steht oben. Rechts in der Zeile steht der Kommentar
     * des Flugs, falls einer eingetragen ist - dort, wo sonst der Reisebuddy
     * stünde, der auf dieser Seite ja schon der Titel ist.
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
            // Kommentar des Flugs rechts in der Zeile, falls vorhanden
            row.bindTimelineComment(entry.comment, showBuddy = false)

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

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        if (_binding != null) binding.routeMap.cancelRouteAnimation()
        TileValueAnimator.cancelAll()
        timeTile = null
        _binding = null
    }

    companion object {
        /** Name des Navigationsarguments, siehe `nav_graph.xml`. */
        const val ARG_NAME = "name"
    }
}
