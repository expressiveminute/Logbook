package com.highfly.logbook

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.FragmentTileDetailBinding

class TileDetailFragment : Fragment() {

    private var _binding: FragmentTileDetailBinding? = null
    private val binding get() = _binding!!

    private var tileId: String = "flights"
    private var selectedClassFilter: String? = null

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
                setupClassFilter()
                binding.filterCard.visibility = View.VISIBLE
                selectClassFilter(0)
            }
            tileId == "traveltype" -> {
                binding.filterCard.visibility = View.GONE
                renderPie(ChartData.travelTypeSlices(requireContext()))
            }
            ChartData.isBarChart(tileId) -> {
                binding.filterCard.visibility = View.GONE
                renderBars(ChartData.barChart(requireContext(), tileId))
            }
            else -> {
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun renderBars(bars: List<ChartData.Bar>) {
        binding.pieChart.visibility = View.GONE
        binding.tvEmpty.visibility = if (bars.isEmpty()) View.VISIBLE else View.GONE
        binding.barChart.visibility = View.VISIBLE
        binding.barChart.setItems(bars.map { BarChartView.Item(it.label, it.count) })
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

    private fun setupClassFilter() {
        val tiles = listOf(
            binding.tileFtPrivate,
            binding.tileFtOnDuty,
            binding.tileFtDeadhead,
            binding.tileFtDutyTravel,
            binding.tileFtAll,
        )
        val labels = listOf(
            binding.labelFtPrivate,
            binding.labelFtOnDuty,
            binding.labelFtDeadhead,
            binding.labelFtDutyTravel,
            binding.labelFtAll,
        )

        configureHighlight(binding.flightTypeFrame, binding.flightTypeHighlight, tiles.size)

        tiles.forEachIndexed { index, tile ->
            tile.setOnClickListener { selectClassFilter(index) }
        }
        updateFilterLabels(labels, selection = null)
    }

    private fun selectClassFilter(index: Int) {
        selectedClassFilter = if (index == 4) {
            null
        } else {
            listOf(
                getString(R.string.flight_type_private),
                getString(R.string.flight_type_on_duty),
                getString(R.string.flight_type_deadhead),
                getString(R.string.flight_type_duty_travel),
            )[index]
        }

        val tiles = listOf(
            binding.tileFtPrivate,
            binding.tileFtOnDuty,
            binding.tileFtDeadhead,
            binding.tileFtDutyTravel,
            binding.tileFtAll,
        )
        val labels = listOf(
            binding.labelFtPrivate,
            binding.labelFtOnDuty,
            binding.labelFtDeadhead,
            binding.labelFtDutyTravel,
            binding.labelFtAll,
        )
        moveHighlight(
            binding.flightTypeFrame,
            binding.flightTypeHighlight,
            tiles.size,
            index,
            MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorPrimaryContainer
            )
        )
        updateFilterLabels(labels, index)

        renderPie(ChartData.classSlices(requireContext(), selectedClassFilter))
    }

    private fun updateFilterLabels(labels: List<TextView>, selection: Int?) {
        val onPrimaryContainer = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer
        )
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        labels.forEachIndexed { index, label ->
            label.setTextColor(if (index == selection) onPrimaryContainer else onSurface)
        }
    }

    private fun configureHighlight(frame: FrameLayout, highlight: View, count: Int) {
        frame.doOnLayout {
            val cellWidth = it.width / count
            (highlight.layoutParams as FrameLayout.LayoutParams).width = cellWidth
        }
    }

    private fun moveHighlight(
        frame: FrameLayout,
        highlight: View,
        count: Int,
        index: Int,
        color: Int
    ) {
        frame.doOnLayout {
            val cellWidth = it.width / count
            (highlight.layoutParams as FrameLayout.LayoutParams).width = cellWidth

            highlight.background = roundedCellBackground(color)

            if (highlight.visibility != View.VISIBLE) {
                highlight.visibility = View.VISIBLE
                highlight.alpha = 0f
                highlight.animate().alpha(1f).setDuration(200).start()
            }

            highlight.animate()
                .translationX((cellWidth * index).toFloat())
                .setDuration(250)
                .start()
        }
    }

    private fun roundedCellBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(color)
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}