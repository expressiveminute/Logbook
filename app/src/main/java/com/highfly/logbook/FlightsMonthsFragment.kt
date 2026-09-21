package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentFlightsMonthsBinding
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

class FlightsMonthsFragment : Fragment() {

    private var _binding: FragmentFlightsMonthsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFlightsMonthsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val year = requireArguments().getInt("year")

        binding.btnMonthBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.tvMonthTitle.text = "${getString(R.string.flights_months_title)} $year"

        val entries = LogbookRepository.getEntries().filter { it.date.year == year }
        val counts = IntArray(Month.values().size)
        entries.forEach { counts[it.date.monthValue - 1]++ }

        val items = Month.values().map { month ->
            MonthBarChartView.Item(
                month.getDisplayName(TextStyle.SHORT, Locale.GERMANY),
                counts[month.value - 1]
            )
        }
        binding.barChartMonth.setItems(items)

        val isEmpty = entries.isEmpty()
        binding.barChartMonth.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.tvMonthEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
