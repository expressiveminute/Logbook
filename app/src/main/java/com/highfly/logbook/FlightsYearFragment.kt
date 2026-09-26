package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentFlightsYearBinding
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

class FlightsYearFragment : Fragment() {

    private var _binding: FragmentFlightsYearBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFlightsYearBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnYearBack.setOnClickListener {
            findNavController().navigateUp()
        }

        val entries = LogbookRepository.getEntries()
        val countsByYear = entries.groupingBy { it.date.year }.eachCount()
        val years = countsByYear.entries
            .sortedWith(
                compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key }
            )

        binding.tvYearCount.text = countText(entries.size)

        val monthCounts = IntArray(Month.values().size)
        entries.forEach { monthCounts[it.date.monthValue - 1]++ }
        binding.barChartYearMonths.setItems(
            Month.values().map { month ->
                MonthBarChartView.Item(
                    month.getDisplayName(TextStyle.SHORT, Locale.GERMANY),
                    monthCounts[month.value - 1]
                )
            }
        )

        binding.tvYearEmpty.visibility = if (years.isEmpty()) View.VISIBLE else View.GONE
        binding.barChartYear.visibility = if (years.isEmpty()) View.GONE else View.VISIBLE
        binding.barChartYearMonths.visibility = if (years.isEmpty()) View.GONE else View.VISIBLE
        binding.yearLineChart.visibility = if (years.isEmpty()) View.GONE else View.VISIBLE

        binding.barChartYear.setItems(
            years.map { BarChartView.Item(it.key.toString(), it.value) }
        )
        binding.barChartYear.setOnItemClickListener { index ->
            openMonths(years[index].key)
        }

        bindYearLineChart(
            countsByYear.toSortedMap().map { LineChartView.Item(it.key.toString(), it.value) }
        )
    }

    /**
     * Das Liniendiagramm waechst mit der Zahl der Jahre ueber die Bildschirmbreite
     * hinaus und wird dann horizontal gescrollt. Damit die y-Achse beim Scrollen
     * stehen bleibt, zeichnet eine zweite Instanz nur die Achse ueber den linken
     * Rand; das Diagramm selbst reserviert deren Breite als [setLeadingSpace].
     */
    private fun bindYearLineChart(items: List<LineChartView.Item>) {
        val chart = binding.lineChartYear
        val axis = binding.lineChartYearAxis

        chart.setLeadingSpace(axis.layoutParams.width)
        chart.setItems(items)

        axis.setContentVisible(false)
        axis.setItems(items)
    }

    private fun openMonths(year: Int) {
        val bundle = Bundle().apply { putInt("year", year) }
        findNavController().navigate(R.id.action_flights_year_to_months, bundle)
    }

    private fun countText(count: Int): String =
        getString(R.string.flights_year_total, String.format(Locale.GERMANY, "%,d", count))

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
