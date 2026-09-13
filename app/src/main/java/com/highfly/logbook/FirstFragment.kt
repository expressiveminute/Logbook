package com.highfly.logbook

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentFirstBinding

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

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

        binding.cardPlaceholder.setOnClickListener {
            Toast.makeText(
                requireContext(),
                R.string.placeholder_no_function,
                Toast.LENGTH_SHORT
            ).show()
        }

        refresh()
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
        val context = requireContext()
        val key = Settings.getDefaultPeriodKey(context)
        val entries = DashboardStats.filterForPeriod(
            LogbookRepository.getEntries(),
            key
        )
        val values = DashboardStats.values(context, entries)

        renderGrid(
            DashboardPrefs.tiles(context, DashboardPrefs.ABOVE),
            DashboardPrefs.cols(context, DashboardPrefs.ABOVE),
            binding.gridAbove,
            values
        )
        renderGrid(
            DashboardPrefs.tiles(context, DashboardPrefs.BELOW),
            DashboardPrefs.cols(context, DashboardPrefs.BELOW),
            binding.gridBelow,
            values
        )
    }

    private fun renderGrid(
        ids: List<String>,
        columns: Int,
        container: LinearLayout,
        values: Map<String, DashboardStats.Value>
    ) {
        container.removeAllViews()
        if (ids.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE

        ids.chunked(columns).forEach { rowIds ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowIds.forEachIndexed { index, tileId ->
                val tileContainer = layoutInflater.inflate(
                    R.layout.item_dashboard_tile,
                    row,
                    false
                ) as ViewGroup

                val tile = DashboardPrefs.tileById(tileId)
                tileContainer.findViewById<android.widget.ImageView>(R.id.iv_tile_icon)
                    .setImageResource(tile.iconRes)
                tileContainer.findViewById<TextView>(R.id.tv_tile_name).apply {
                    text = requireContext().getString(tile.nameRes)
                }
                val value = values[tileId]
                tileContainer.findViewById<TextView>(R.id.tv_tile_value).apply {
                    text = value?.text ?: "-"
                }
                tileContainer.findViewById<TextView>(R.id.tv_tile_unit).apply {
                    text = value?.unit.orEmpty()
                    visibility = if (value?.unit.isNullOrEmpty()) View.GONE else View.VISIBLE
                }

                val card = tileContainer.findViewById<com.google.android.material.card.MaterialCardView>(R.id.tile_card)
                card.tag = tileId
                card.setOnClickListener {
                    Log.d("FirstFragment", "Tile clicked: $tileId")
                    openTile(tileId)
                }

                val params = LinearLayout.LayoutParams(
                    0,
                    dp(72),
                    1f
                )
                params.topMargin = dp(8)
                if (rowIds.size == 1) {
                    params.leftMargin = dp(0)
                    params.rightMargin = dp(0)
                } else {
                    params.rightMargin =
                        if (index < rowIds.lastIndex) dp(4) else dp(0)
                }
                row.addView(tileContainer, params)
            }
            container.addView(row)
        }
    }

    private fun openTile(id: String) {
        if (ChartData.isBarChart(id) || ChartData.isPieChart(id)) {
            val bundle = Bundle().apply { putString("tileId", id) }
            findNavController().navigate(R.id.action_dashboard_to_tile_detail, bundle)
        } else {
            Toast.makeText(
                requireContext(),
                R.string.placeholder_no_function,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}