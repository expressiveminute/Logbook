package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentTileDetailBinding
import kotlin.math.roundToInt

class TileDetailFragment : Fragment() {

    private var _binding: FragmentTileDetailBinding? = null
    private val binding get() = _binding!!

    private var tileId: String = "flights"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTileDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tileId = requireArguments().getString("tileId") ?: "flights"

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        val tile = DashboardPrefs.tileById(tileId)
        binding.tvDetailTitle.text = requireContext().getString(tile.nameRes)
        binding.tvDetailSubtitle.text = getString(
            R.string.tile_detail_period,
            PeriodOptions.label(
                requireContext(),
                Settings.getDefaultPeriodKey(requireContext())
            )
        )

        when {
            tileId == "class" -> {
                renderPie(ChartData.classSlices(requireContext()))
            }
            tileId == "traveltype" -> {
                renderPie(ChartData.travelTypeSlices(requireContext()))
            }
            tileId == "function" -> {
                renderPie(ChartData.functionSlices(requireContext()))
            }
            tileId == "routes" -> {
                renderRoutes()
            }
            tileId == "aircraftreg" -> {
                renderAircraftRegistrations()
            }
            ChartData.isBarChart(tileId) -> {
                if (tileId == "countries") {
                    renderContinents()
                }
                renderBars(ChartData.barChart(requireContext(), tileId))
            }
            else -> {
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun renderContinents() {
        val slices = ChartData.continentSlices(requireContext())
        binding.tvContinentTitle.visibility = View.VISIBLE
        binding.pieContinent.visibility = View.VISIBLE
        binding.pieContinent.setSlices(
            slices.map {
                PieChartView.Slice(
                    it.label,
                    it.value,
                    ContextCompat.getColor(requireContext(), it.colorRes)
                )
            }
        )
    }

    private fun renderRoutes() {
        renderScrollableColumnChart(
            ChartData.airportBars(requireContext()),
            binding.airportsChart,
            binding.barAirports,
            binding.barAirportsAxis,
            null
        )
        renderBars(ChartData.barChart(requireContext(), "routes"))
    }

    private fun renderAircraftRegistrations() {
        val types = ChartData.aircraftBars(requireContext())
        renderScrollableColumnChart(
            types,
            binding.aircraftChart,
            binding.barAircraft,
            binding.barAircraftAxis,
            binding.tvAircraftTitle
        )
        val registrations = ChartData.barChart(requireContext(), "aircraftreg")
        renderBars(registrations)
        binding.tvRegistrationsTitle.visibility =
            if (registrations.isEmpty()) View.GONE else View.VISIBLE
        binding.tvEmpty.visibility =
            if (types.isEmpty() && registrations.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderScrollableColumnChart(
        bars: List<ChartData.Bar>,
        container: View,
        chart: MonthBarChartView,
        axis: MonthBarChartView,
        title: View?
    ) {
        if (bars.isEmpty()) return
        val chartHeight =
            (resources.displayMetrics.heightPixels * COLUMN_CHART_HEIGHT_FACTOR).roundToInt()
        val chartItems = bars.map { MonthBarChartView.Item(it.label, it.count) }

        chart.setLeadingSpace(axis.layoutParams.width)
        chart.setPlotHeight(chartHeight)
        chart.setItems(chartItems)

        axis.setContentVisible(false)
        axis.setPlotHeight(chartHeight)
        axis.setItems(chartItems)

        title?.visibility = View.VISIBLE
        container.visibility = View.VISIBLE
    }

    private fun renderBars(bars: List<ChartData.Bar>) {
        binding.pieChart.visibility = View.GONE
        binding.tvEmpty.visibility = if (bars.isEmpty()) View.VISIBLE else View.GONE
        binding.barChart.visibility = View.VISIBLE
        binding.barChart.setItems(
            bars.map { BarChartView.Item(it.label, it.count, it.subLabel) }
        )
    }

    private fun renderPie(slices: List<ChartData.Slice>) {
        binding.barChart.visibility = View.GONE
        binding.pieChart.visibility = View.VISIBLE
        binding.tvEmpty.visibility = if (slices.isEmpty()) View.VISIBLE else View.GONE
        binding.pieChart.setSlices(
            slices.map {
                PieChartView.Slice(
                    it.label,
                    it.value,
                    ContextCompat.getColor(requireContext(), it.colorRes)
                )
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Anteil der Bildschirmhoehe, den die Diagrammflaeche einnimmt. */
        const val COLUMN_CHART_HEIGHT_FACTOR = 0.33f
    }
}