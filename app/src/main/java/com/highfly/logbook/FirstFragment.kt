package com.highfly.logbook

import android.os.Bundle
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.FragmentFirstBinding
import java.time.LocalDate
import java.util.Locale

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val bigNumberTiles = setOf("earthorbits", "moon")

    private var timeMinutes = 0
    private var timeUnit = DashboardStats.TimeUnit.HOURS
    private var loadGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.fabAdd.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_add_entry)
        }

        binding.dashboardScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateFabVisibility()
        }
        binding.dashboardScroll.post { updateFabVisibility() }
    }

    private fun updateFabVisibility() {
        val scroll = binding.dashboardScroll
        val contentScrolls = scroll.canScrollVertically(-1) || scroll.canScrollVertically(1)
        val showFab = !contentScrolls || scroll.canScrollVertically(1)
        val fab = binding.fabAdd
        val target = if (showFab) View.VISIBLE else View.GONE
        if (fab.visibility == target) return
        fab.animate().cancel()

        if (showFab) {
            fab.visibility = View.VISIBLE
            fab.alpha = 0f
            fab.scaleX = 1.15f
            fab.scaleY = 1.15f
            fab.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start()
        } else {
            fab.animate()
                .scaleX(1.15f)
                .scaleY(1.15f)
                .alpha(0.85f)
                .setDuration(130)
                .withEndAction {
                    fab.animate()
                        .scaleX(0f)
                        .scaleY(0f)
                        .alpha(0f)
                        .setDuration(120)
                        .withEndAction { fab.visibility = View.GONE }
                        .start()
                }
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        DashboardEvents.onPeriodChanged = { refresh() }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        DashboardEvents.onPeriodChanged = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        TileValueAnimator.cancelAll()
        _binding = null
    }

    private fun refresh() {
        val generation = ++loadGeneration
        binding.dashboardProgress.visibility = View.VISIBLE

        val appContext = requireContext().applicationContext
        Thread {
            val key = Settings.getDefaultPeriodKey(appContext)
            val timeUnitKey = Settings.getDefaultTimeUnitKey(appContext)
            val allEntries = LogbookRepository.getEntries()
            val entries = DashboardStats.filterForPeriod(
                allEntries,
                key
            )
            val time = entries.sumOf { it.flightMinutes ?: 0 }
            // Braucht alle Eintraege, nicht die gefilterten: eine Strecke ist
            // nur dann neu, wenn sie noch nie geflogen wurde. Gezaehlt wird
            // immer das laufende Jahr, unabhaengig vom gewaehlten Zeitraum.
            val discoveries = DashboardStats.discoveryCount(
                allEntries,
                LocalDate.now().year
            )
            val values = DashboardStats.values(appContext, entries, discoveries)
            val distance = DashboardStats.distanceDetails(appContext, entries)
            val co2 = Co2Calculator.details(entries)

            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                binding.dashboardProgress.visibility = View.GONE
                timeMinutes = time
                timeUnit = TimeUnitOptions.unit(timeUnitKey)
                renderGrid(
                    DashboardPrefs.readRows(binding.root.context),
                    binding.gridTiles,
                    values,
                    distance,
                    co2,
                    entries
                )
            }
        }.start()
    }

    private fun tileName(tile: DashboardPrefs.Tile, entries: List<LogbookEntry>): CharSequence {
        val singular = tile.nameSingularRes
        return if (singular != null && DashboardStats.tileCount(tile.id, entries) == 1) {
            getString(singular)
        } else {
            getString(tile.nameRes)
        }
    }

    private fun renderGrid(
        rows: List<List<String>>,
        container: LinearLayout,
        values: Map<String, DashboardStats.Value>,
        distance: DashboardStats.DistanceDetails,
        co2: Co2Calculator.Details,
        entries: List<LogbookEntry>
    ) {
        TileValueAnimator.cancelAll()
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE

        rows.forEachIndexed { rowIndex, rowIds ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            // Zaehlt nur tatsaechlich sichtbare Kacheln, damit eine
            // ausgeblendete Kachel die Spalten danach nicht verschiebt.
            var column = 0
            rowIds.forEachIndexed { index, tileId ->
                if (tileId == "function" &&
                    Settings.getRole(requireContext()) != Settings.ROLE_CREW
                ) {
                    return@forEachIndexed
                }
                val isDistance = tileId == "distance"
                val isCo2 = tileId == "co2"
                val tileContainer = if (isDistance) {
                    layoutInflater.inflate(
                        R.layout.item_dashboard_tile_distance,
                        row,
                        false
                    ) as ViewGroup
                } else if (isCo2) {
                    layoutInflater.inflate(
                        R.layout.item_dashboard_tile_co2,
                        row,
                        false
                    ) as ViewGroup
                } else {
                    layoutInflater.inflate(
                        R.layout.item_dashboard_tile,
                        row,
                        false
                    ) as ViewGroup
                }

                val tile = DashboardPrefs.tileById(tileId)
                val iconView =
                    tileContainer.findViewById<android.widget.ImageView>(R.id.iv_tile_icon)
                val tileNameView =
                    tileContainer.findViewById<TextView>(R.id.tv_tile_name)
                if (!isDistance && !isCo2) {
                    iconView.setImageResource(tile.iconRes)
                    tileNameView.text = tileName(tile, entries)
                }

                val pieView = tileContainer.findViewById<PieChartView>(R.id.tile_pie_chart)
                val worldmapPreview = tileContainer.findViewById<WorldMapPreviewView>(R.id.tile_worldmap_preview)

                if (isDistance) {
                    populateDistanceTile(tileContainer, distance)
                } else if (isCo2) {
                    populateCo2Tile(tileContainer, co2)
                } else if (tileId == "time") {
                    val value = DashboardStats.timeValue(
                        requireContext(), timeMinutes, timeUnit
                    )
                    tileContainer.findViewById<TextView>(R.id.tv_tile_value).apply {
                        TileValueAnimator.animate(this, value.text)
                        visibility = View.VISIBLE
                    }
                    tileContainer.findViewById<TextView>(R.id.tv_tile_unit).apply {
                        text = ""
                        visibility = View.GONE
                    }
                    tileNameView.text = TimeUnitOptions.tileTitle(requireContext(), timeUnit)
                } else if (tileId == "worldmap") {
                    iconView.visibility = View.GONE
                    tileContainer.findViewById<View>(R.id.ll_tile_top_right).visibility = View.GONE
                    pieView.visibility = View.GONE
                    worldmapPreview.visibility = View.VISIBLE
                    val primaryColor = MaterialColors.getColor(tileContainer, com.google.android.material.R.attr.colorPrimary)
                    worldmapPreview.setColors(primaryColor, primaryColor)
                } else if (ChartData.isPieChart(tileId)) {
                    iconView.visibility = if (tileId == "function") View.VISIBLE else View.GONE
                    tileContainer.findViewById<View>(R.id.ll_tile_top_right).visibility = View.GONE
                    worldmapPreview.visibility = View.GONE
                    pieView.visibility = View.VISIBLE
                    val slices = when (tileId) {
                        "class" -> ChartData.classSlices(requireContext(), null, entries)
                        "function" -> ChartData.functionSlices(requireContext(), entries)
                        else -> ChartData.travelTypeSlices(requireContext(), entries)
                    }
                    if (tileId == "function") {
                        iconView.bringToFront()
                    }
                    pieView.showLegend = false
                    pieView.sliceTouchEnabled = false
                    pieView.setSlices(
                        slices.map {
                            PieChartView.Slice(
                                it.label,
                                it.value,
                                ContextCompat.getColor(requireContext(), it.colorRes)
                            )
                        }
                    )
                } else {
                    pieView.visibility = View.GONE
                    worldmapPreview.visibility = View.GONE
                    val value = values[tileId]
                    tileContainer.findViewById<TextView>(R.id.tv_tile_value).apply {
                        if (value == null) {
                            text = ""
                            visibility = View.GONE
                        } else {
                            TileValueAnimator.animate(this, buildValueText(value))
                            visibility = View.VISIBLE
                        }
                    }
                    tileContainer.findViewById<TextView>(R.id.tv_tile_unit).apply {
                        text = ""
                        visibility = View.GONE
                    }
                }

                val card = tileContainer.findViewById<com.google.android.material.card.MaterialCardView>(R.id.tile_card)
                card.tag = tileId
                DashboardTileColors.apply(
                    card,
                    DashboardPrefs.TilePosition(rowIndex, column)
                )
                card.setOnClickListener {
                    if (tileId == "time") {
                        openTimeTile()
                    } else {
                        openTile(tileId)
                    }
                }

                val params = LinearLayout.LayoutParams(0, dp(tile.heightDp), 1f)
                params.topMargin = dp(8)
                if (rowIds.size > 1) {
                    params.rightMargin =
                        if (index < rowIds.lastIndex) dp(4) else dp(0)
                }
                row.addView(tileContainer, params)
                column++
            }
            container.addView(row)
        }

        container.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                resizeStatTiles(container)
                fitBigNumberTiles(container)
                updateFabVisibility()
            }
        })
    }

    private fun resizeStatTiles(container: LinearLayout) {
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val tileContainer = row.getChildAt(j) as? ViewGroup ?: continue
                val card =
                    tileContainer.findViewById<com.google.android.material.card.MaterialCardView>(R.id.tile_card)
                val tileId = card.tag as? String ?: continue
                if (tileId == "co2") {
                    // Full width, but as flat as a regular (square) tile.
                    val target = ((tileContainer.width - dp(8)) / 3).coerceAtLeast(dp(88))
                    val co2Params = tileContainer.layoutParams
                    if (co2Params.height != target) {
                        co2Params.height = target
                        tileContainer.layoutParams = co2Params
                    }
                    continue
                }
                if (DashboardPrefs.tileById(tileId).span > 1) continue
                val params = tileContainer.layoutParams
                if (params.height != tileContainer.width) {
                    params.height = tileContainer.width
                    tileContainer.layoutParams = params
                }
            }
        }
    }

    private fun fitBigNumberTiles(container: LinearLayout) {
        val targets = mutableListOf<TextView>()
        var minFactor = 1f
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val tileContainer = row.getChildAt(j) as? ViewGroup ?: continue
                val card =
                    tileContainer.findViewById<com.google.android.material.card.MaterialCardView>(R.id.tile_card)
                val tileId = card.tag as? String ?: continue
                if (tileId !in bigNumberTiles) continue
                val tv = tileContainer.findViewById<TextView>(R.id.tv_tile_value)
                if (tv.visibility != View.VISIBLE) continue
                val available = tv.width
                if (available <= 0) continue
                targets.add(tv)
                val baseTextSize = tv.textSize
                val textPaint = TextPaint(tv.paint).apply { textSize = baseTextSize }
                // Vermisst den END-Wert statt des animierten Zwischenwerts,
                // damit die Schrift auf die finale Zahl passt.
                val measureText = TileValueAnimator.finalTextOf(tv) ?: tv.text
                val layout = StaticLayout.Builder
                    .obtain(measureText, 0, measureText.length, textPaint, Int.MAX_VALUE)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .build()
                val lineWidth = layout.getLineWidth(0)
                if (lineWidth > available) {
                    val factor = available / lineWidth
                    if (factor < minFactor) minFactor = factor
                }
            }
        }
        if (targets.isEmpty()) return
        val newSize = targets[0].textSize * minFactor
        for (tv in targets) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, newSize)
        }
    }

    private fun buildValueText(value: DashboardStats.Value): CharSequence {
        val unit = value.unit
        if (unit.isNullOrEmpty()) return value.text
        val unitColor = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        val builder = SpannableStringBuilder(value.text).append(" ").append(unit)
        val unitStart = value.text.length + 1
        builder.setSpan(
            ForegroundColorSpan(unitColor),
            unitStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            RelativeSizeSpan(0.6f),
            unitStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return builder
    }

    private fun populateCo2Tile(
        container: ViewGroup,
        co2: Co2Calculator.Details
    ) {
        container.findViewById<TextView>(R.id.tv_co2_value).apply {
            TileValueAnimator.animate(this, Co2Calculator.tonnesText(co2.tonnes))
        }
        container.findViewById<TextView>(R.id.tv_trees_value).apply {
            TileValueAnimator.animate(this, String.format(Locale.GERMANY, "%,d", co2.treesPerYear))
        }
    }

    private fun populateDistanceTile(
        container: ViewGroup,
        distance: DashboardStats.DistanceDetails
    ) {
        val context = requireContext()
        val none = context.getString(R.string.distance_none)

        container.findViewById<TextView>(R.id.tv_dist_value).apply {
            TileValueAnimator.animate(
                this,
                buildValueText(DashboardStats.Value(DashboardStats.kmText(context, distance.totalKm), null))
            )
        }

        container.findViewById<TextView>(R.id.tv_dist_furthest_label).text =
            context.getString(R.string.distance_furthest_label)
        container.findViewById<TextView>(R.id.tv_dist_furthest_route).text =
            distance.furthestRoute ?: none
        container.findViewById<TextView>(R.id.tv_dist_furthest_km).text =
            distance.furthestKm?.let { DashboardStats.kmText(context, it) } ?: none

        container.findViewById<TextView>(R.id.tv_dist_avg_label).text =
            context.getString(R.string.distance_avg_label)
        container.findViewById<TextView>(R.id.tv_dist_avg_route).text = ""
        container.findViewById<TextView>(R.id.tv_dist_avg_km).text =
            distance.avgKm?.let { DashboardStats.kmText(context, it) } ?: none

        container.findViewById<TextView>(R.id.tv_dist_shortest_label).text =
            context.getString(R.string.distance_shortest_label)
        container.findViewById<TextView>(R.id.tv_dist_shortest_route).text =
            distance.shortestRoute ?: none
        container.findViewById<TextView>(R.id.tv_dist_shortest_km).text =
            distance.shortestKm?.let { DashboardStats.kmText(context, it) } ?: none

        container.findViewById<TextView>(R.id.tv_orbits_value).apply {
            TileValueAnimator.animate(this, buildValueText(DashboardStats.Value(DashboardStats.factorText(distance.earthOrbits), "×")))
        }
        container.findViewById<TextView>(R.id.tv_moon_value).apply {
            TileValueAnimator.animate(this, buildValueText(DashboardStats.Value(DashboardStats.factorText(distance.moonTrips), "×")))
        }
    }

    private fun openTimeTile() {
        findNavController().navigate(R.id.action_dashboard_to_time_detail)
    }

    private fun openTile(id: String) {
        when {
            id == "worldmap" -> {
                findNavController().navigate(R.id.action_dashboard_to_world_map)
            }
            id == "flights" -> {
                findNavController().navigate(R.id.action_dashboard_to_flights_year)
            }
            id == "discovery" -> {
                findNavController().navigate(R.id.action_dashboard_to_discovery)
            }
            ChartData.isBarChart(id) || ChartData.isPieChart(id) -> {
                val bundle = Bundle().apply { putString("tileId", id) }
                findNavController().navigate(R.id.action_dashboard_to_tile_detail, bundle)
            }
            else -> {
                Toast.makeText(
                    requireContext(),
                    R.string.placeholder_no_function,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}