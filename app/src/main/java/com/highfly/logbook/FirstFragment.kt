package com.highfly.logbook

import android.graphics.Typeface
import android.os.Bundle
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
import java.util.Locale

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val bigNumberTiles = setOf("earthorbits", "moon")

    private var timeMinutes = 0
    private var timeUnitIndex = 0
    private var loadGeneration = 0

    private val timeUnits = listOf(
        DashboardStats.TimeUnit.HOURS,
        DashboardStats.TimeUnit.DAYS,
        DashboardStats.TimeUnit.MONTHS,
        DashboardStats.TimeUnit.YEARS,
    )

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
        _binding = null
    }

    private fun refresh() {
        val generation = ++loadGeneration
        binding.dashboardProgress.visibility = View.VISIBLE

        val appContext = requireContext().applicationContext
        Thread {
            val key = Settings.getDefaultPeriodKey(appContext)
            val entries = DashboardStats.filterForPeriod(
                LogbookRepository.getEntries(),
                key
            )
            val time = entries.sumOf { it.flightMinutes ?: 0 }
            val values = DashboardStats.values(appContext, entries)
            val distance = DashboardStats.distanceDetails(appContext, entries)
            val co2 = Co2Calculator.details(entries)

            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                binding.dashboardProgress.visibility = View.GONE
                timeMinutes = time
                timeUnitIndex = 0
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

    private fun renderGrid(
        rows: List<List<String>>,
        container: LinearLayout,
        values: Map<String, DashboardStats.Value>,
        distance: DashboardStats.DistanceDetails,
        co2: Co2Calculator.Details,
        entries: List<LogbookEntry>
    ) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE

        rows.forEach { rowIds ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
            }
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
                    tileNameView.text = requireContext().getString(tile.nameRes)
                }

                val pieView = tileContainer.findViewById<PieChartView>(R.id.tile_pie_chart)
                val worldmapPreview = tileContainer.findViewById<WorldMapPreviewView>(R.id.tile_worldmap_preview)

                if (isDistance) {
                    populateDistanceTile(tileContainer, distance)
                } else if (isCo2) {
                    populateCo2Tile(tileContainer, co2)
                } else if (tileId == "time") {
                    val value = DashboardStats.timeValue(
                        requireContext(), timeMinutes, timeUnits[timeUnitIndex]
                    )
                    tileContainer.findViewById<TextView>(R.id.tv_tile_value).apply {
                        text = value.text
                        visibility = View.VISIBLE
                    }
                    tileContainer.findViewById<TextView>(R.id.tv_tile_unit).apply {
                        text = ""
                        visibility = View.GONE
                    }
                    tileNameView.text = timeTileTitle(timeUnits[timeUnitIndex])
                } else if (tileId == "worldmap") {
                    iconView.visibility = View.GONE
                    tileContainer.findViewById<View>(R.id.ll_tile_top_right).visibility = View.GONE
                    pieView.visibility = View.GONE
                    worldmapPreview.visibility = View.VISIBLE
                    val primaryColor = MaterialColors.getColor(tileContainer, com.google.android.material.R.attr.colorPrimary)
                    worldmapPreview.setColors(primaryColor, primaryColor)
                } else if (ChartData.isPieChart(tileId)) {
                    iconView.visibility = View.GONE
                    tileContainer.findViewById<View>(R.id.ll_tile_top_right).visibility = View.GONE
                    worldmapPreview.visibility = View.GONE
                    pieView.visibility = View.VISIBLE
                    val slices = if (tileId == "class") {
                        ChartData.classSlices(requireContext(), null, entries)
                    } else {
                        ChartData.travelTypeSlices(requireContext(), entries)
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
                            text = buildValueText(value)
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
                card.setOnClickListener {
                    if (tileId == "time") {
                        cycleTimeUnit(tileContainer)
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
            }
            container.addView(row)
        }

        container.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                resizeStatTiles(container)
                fitBigNumberTiles(container)
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
                val layout = StaticLayout.Builder
                    .obtain(tv.text, 0, tv.text.length, textPaint, Int.MAX_VALUE)
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
        container.findViewById<TextView>(R.id.tv_co2_value).text =
            Co2Calculator.tonnesText(co2.tonnes)
        container.findViewById<TextView>(R.id.tv_trees_value).text =
            String.format(Locale.GERMANY, "%,d", co2.treesPerYear)
    }

    private fun populateDistanceTile(
        container: ViewGroup,
        distance: DashboardStats.DistanceDetails
    ) {
        val context = requireContext()
        val none = context.getString(R.string.distance_none)

        container.findViewById<TextView>(R.id.tv_dist_value).text = buildValueText(
            DashboardStats.Value(
                DashboardStats.kmText(context, distance.totalKm),
                null
            )
        )

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

        container.findViewById<TextView>(R.id.tv_orbits_value).text = buildValueText(
            DashboardStats.Value(DashboardStats.factorText(distance.earthOrbits), "×")
        )
        container.findViewById<TextView>(R.id.tv_moon_value).text = buildValueText(
            DashboardStats.Value(DashboardStats.factorText(distance.moonTrips), "×")
        )
    }

    private fun cycleTimeUnit(tileContainer: ViewGroup) {
        timeUnitIndex = (timeUnitIndex + 1) % timeUnits.size
        val unit = timeUnits[timeUnitIndex]
        val value = DashboardStats.timeValue(requireContext(), timeMinutes, unit)
        tileContainer.findViewById<TextView>(R.id.tv_tile_value).apply {
            if (visibility != View.VISIBLE) return@apply
            text = value.text
        }
        tileContainer.findViewById<TextView>(R.id.tv_tile_name).text = timeTileTitle(unit)
    }

    private fun timeTileTitle(unit: DashboardStats.TimeUnit): CharSequence {
        val inRes = when (unit) {
            DashboardStats.TimeUnit.HOURS -> R.string.tile_time_in_hours
            DashboardStats.TimeUnit.DAYS -> R.string.tile_time_in_days
            DashboardStats.TimeUnit.MONTHS -> R.string.tile_time_in_months
            DashboardStats.TimeUnit.YEARS -> R.string.tile_time_in_years
        }
        val parts = SpannableStringBuilder()
            .append(getString(R.string.tile_time))
            .append(" [")
            .append(getString(inRes))
            .append("]")
        parts.setSpan(
            StyleSpan(Typeface.NORMAL),
            getString(R.string.tile_time).length,
            parts.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return parts
    }

    private fun openTile(id: String) {
        when {
            id == "worldmap" -> {
                findNavController().navigate(R.id.action_dashboard_to_world_map)
            }
            id == "flights" -> {
                findNavController().navigate(R.id.action_dashboard_to_flights_year)
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