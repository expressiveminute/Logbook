package com.highfly.logbook

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentTileDetailBinding
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.min
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
                val bars = ChartData.barChart(requireContext(), tileId)
                if (tileId == "countries") {
                    renderContinents()
                }
                if (tileId == "layover") {
                    renderLayoverExtremes()
                    // Klick auf Medaille, Code oder Balken öffnet die
                    // Detailseite des Ziel-Flughafens.
                    binding.barChart.setOnItemClickListener { index ->
                        openLayoverDetail(bars.getOrNull(index)?.label)
                    }
                }
                renderBars(bars)
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

    /**
     * Letztes und ältestes Layover des Zeitraums als zwei Kacheln ueber dem
     * Balkendiagramm. Ohne Layover im Zeitraum bleiben beide Kacheln
     * ausgeblendet, dann zeigt das Diagramm allein seinen Leertext.
     *
     * Beide Kacheln sind anklickbar und oeffnen dieselbe Detailseite wie ein
     * Klick auf den Code im Balkendiagramm darunter.
     */
    private fun renderLayoverExtremes() {
        val entries = ChartData.periodFiltered(requireContext())
        val newest = ChartData.newestLayover(entries) ?: return
        val oldest = ChartData.oldestLayover(entries) ?: return
        val newestDate = newest.date.format(DATE_LABEL_FORMAT)
        val oldestDate = oldest.date.format(DATE_LABEL_FORMAT)
        binding.tvLayoverNewest.text = newest.airport
        binding.tvLayoverNewestDate.text = newestDate
        binding.tvLayoverOldest.text = oldest.airport
        binding.tvLayoverOldestDate.text = oldestDate
        binding.layoverExtremes.visibility = View.VISIBLE
        // Die Kacheln nennen Flughafen und Datum für Talkback, sonst blieben die
        // Kinder stumm, weil die Kachel selbst den Namen trägt.
        binding.cardLayoverNewest.contentDescription = getString(
            R.string.layover_extreme_newest_desc, newest.airport, newestDate
        )
        binding.cardLayoverOldest.contentDescription = getString(
            R.string.layover_extreme_oldest_desc, oldest.airport, oldestDate
        )
        binding.cardLayoverNewest.setOnClickListener {
            openLayoverDetail(newest.airport)
        }
        binding.cardLayoverOldest.setOnClickListener {
            openLayoverDetail(oldest.airport)
        }
        fitLayoverTileCodes()
    }

    /**
     * Beide Kacheln zeigen ihren Code in derselben Schriftgrösse.
     *
     * Die Kacheln sind gleich breit, ihre Codes aber nicht gleich lang. Ohne
     * diese Angleichung müsste nur der längere Code schrumpfen und die Kacheln
     * unterschieden sich schon im Wortbild. Gesucht ist deshalb die grösste
     * Grösse, in der beide Codes nebeneinander passen. Das Zuschlagen auf die
     * gemessene Breite braucht den ersten Layout-Durchlauf.
     */
    private fun fitLayoverTileCodes() {
        binding.layoverExtremes.doOnLayout {
            fitSharedCodeSize(binding.tvLayoverNewest, binding.tvLayoverOldest)
        }
    }

    private fun fitSharedCodeSize(vararg codes: TextView) {
        // Pixel je sp: so wandelt TextView seine Schriftgrösse in Pixel, und
        // damit lässt sich die gemessene Breite wieder in sp zurückrechnen.
        val pixelPerSp = resources.displayMetrics.density *
            resources.configuration.fontScale
        var needed = Float.MAX_VALUE
        codes.forEach { code ->
            val available = code.width - code.paddingLeft - code.paddingRight
            val measured = code.paint.measureText(code.text, 0, code.text.length)
            if (available <= 0 || measured <= 0f) return@forEach
            needed = min(needed, code.textSize * available / measured)
        }
        if (needed == Float.MAX_VALUE) return
        val sizeSp = floor(needed / pixelPerSp).toInt()
            .coerceIn(LAYOVER_CODE_MIN_SP, LAYOVER_CODE_MAX_SP)
        codes.forEach { code ->
            code.setAutoSizeTextTypeUniformWithConfiguration(
                LAYOVER_CODE_MIN_SP, sizeSp, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
    }

    /** Detailseite eines Layover-Flughafens, wie sie ein Klick im Diagramm öffnet. */
    private fun openLayoverDetail(label: String?) {
        val code = label?.trim()?.uppercase()
        if (code.isNullOrBlank()) return
        findNavController().navigate(
            R.id.action_tile_detail_to_layover_detail,
            bundleOf(LayoverDetailFragment.ARG_AIRPORT_CODE to code)
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

        /** Grenzen der gemeinsamen Schriftgrösse der beiden Code-Kacheln. */
        const val LAYOVER_CODE_MIN_SP = 14
        const val LAYOVER_CODE_MAX_SP = 28

        val DATE_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}