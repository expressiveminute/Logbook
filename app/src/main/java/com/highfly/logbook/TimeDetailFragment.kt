package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentTimeDetailBinding
import com.highfly.logbook.databinding.ItemDashboardTileBinding

/**
 * Detailseite der Kachel "Zeit": die Gesamtdauer in allen Einheiten, darunter
 * der laengste, der kuerzeste und der mittlere Flug sowie die Verteilung der
 * Flugdauern.
 */
class TimeDetailFragment : Fragment() {

    private var _binding: FragmentTimeDetailBinding? = null

    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimeDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        val context = requireContext()
        binding.tvDetailTitle.text = getString(R.string.tile_time)
        binding.tvDetailSubtitle.text = getString(
            R.string.tile_detail_period,
            PeriodOptions.label(context, Settings.getDefaultPeriodKey(context))
        )

        val entries = ChartData.periodFiltered(context)
        val minutes = entries.sumOf { it.flightMinutes ?: 0 }
        val timed = entries.any { (it.flightMinutes ?: 0) > 0 }

        bindTimeTile(binding.tileDays, DashboardStats.TimeUnit.DAYS, 0, 0, minutes)
        bindTimeTile(binding.tileHours, DashboardStats.TimeUnit.HOURS, 0, 1, minutes)
        bindTimeTile(binding.tileYears, DashboardStats.TimeUnit.YEARS, 1, 0, minutes)
        bindTimeTile(binding.tileMonths, DashboardStats.TimeUnit.MONTHS, 1, 1, minutes)

        val longest = ChartData.longestFlightBar(entries)
        val average = ChartData.averageDurationBar(
            entries,
            getString(R.string.time_detail_all_flights)
        ) { count -> getString(R.string.time_route_flights, count) }
        val shortest = ChartData.shortestFlightBar(entries)

        // Alle drei Balken beziehen sich auf den längsten Wert, damit die
        // Dauern direkt vergleichbar sind, und teilen sich dieselbe
        // Beschriftungsspalte, damit sie links bündig starten.
        val reference = maxOf(
            longest?.count ?: 0,
            average?.count ?: 0,
            shortest?.count ?: 0
        ).coerceAtLeast(1)
        val sharedLabels = listOfNotNull(longest?.label, average?.label, shortest?.label)

        bindSingleBar(
            binding.tvLongestTitle,
            binding.barLongest,
            longest,
            reference,
            sharedLabels
        )
        bindSingleBar(
            binding.tvAverageTitle,
            binding.barAverage,
            average,
            reference,
            sharedLabels
        )
        bindSingleBar(
            binding.tvShortestTitle,
            binding.barShortest,
            shortest,
            reference,
            sharedLabels
        )
        bindHistogram(entries, timed)
    }

    /**
     * Zeit-Kachel im Stil des Dashboards, aber ohne Klickfunktion: Die Seite
     * zeigt jede Einheit, die Kachel selbst waehlt nichts aus.
     */
    private fun bindTimeTile(
        tile: ItemDashboardTileBinding,
        unit: DashboardStats.TimeUnit,
        row: Int,
        column: Int,
        minutes: Int
    ) {
        val card = tile.root
        card.isClickable = false
        card.isFocusable = false
        card.foreground = null
        DashboardTileColors.apply(card, DashboardPrefs.TilePosition(row, column))

        tile.ivTileIcon.setImageResource(R.drawable.ic_time)
        tile.tvTileName.text = TimeUnitOptions.tileTitle(requireContext(), unit)
        TileValueAnimator.animate(
            tile.tvTileValue,
            DashboardStats.timeValue(requireContext(), minutes, unit).text
        )
        tile.tvTileValue.visibility = View.VISIBLE
        tile.tvTileUnit.apply {
            text = ""
            visibility = View.GONE
        }
        tile.tilePieChart.visibility = View.GONE
        tile.tileWorldmapPreview.visibility = View.GONE
    }

    /**
     * Diagramm mit genau einem Balken: der auffaelligste Wert des Zeitraums
     * (längster bzw. kürzester Flug) bzw. die mittlere Flugzeit. Ohne Wert
     * bleibt die ganze Zeile inklusive Überschrift verborgen. Die
     * Rangzeichen (Medaillen) entfallen, die Balkenlänge wird auf [reference]
     * bezogen und [sharedLabels] legt die Beschriftungsspalte fest, damit die
     * drei Balken gleich starten.
     */
    private fun bindSingleBar(
        title: TextView,
        chart: BarChartView,
        bar: ChartData.Bar?,
        reference: Int,
        sharedLabels: List<String>
    ) {
        val visible = bar != null
        title.visibility = if (visible) View.VISIBLE else View.GONE
        chart.visibility = if (visible) View.VISIBLE else View.GONE
        if (bar == null) return
        chart.setShowRanks(false)
        chart.setLabelReferences(sharedLabels)
        chart.setBarFraction(bar.count.toFloat() / reference.toFloat())
        chart.setItems(
            listOf(BarChartView.Item(bar.label, bar.count, bar.subLabel, bar.countLabel))
        )
    }

    private fun bindHistogram(entries: List<LogbookEntry>, hasData: Boolean) {
        binding.tvDistributionTitle.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.barDistribution.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.tvEmpty.visibility = if (hasData) View.GONE else View.VISIBLE
        if (!hasData) return
        binding.barDistribution.setCategoryTextSizeSp(HISTOGRAM_LABEL_SP)
        binding.barDistribution.setItems(
            ChartData.durationHistogram(entries).map {
                MonthBarChartView.Item(it.label, it.count)
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        TileValueAnimator.cancelAll()
        _binding = null
    }

    private companion object {
        /** 20 schmale Spalten: die Stunden-Beschriftung braucht weniger Platz. */
        const val HISTOGRAM_LABEL_SP = 9
    }
}
