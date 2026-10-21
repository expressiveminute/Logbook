package com.highfly.logbook

import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentRadiationInfoBinding

/**
 * Erklärseite der Kachel "Höhenstrahlung" (das i oben rechts im Kopf der
 * Detailseite): wo die Grenzen der Schätzung liegen, was angezeigt wird,
 * welche Formel dahintersteckt, aus welchen Daten sie sich ergibt und
 * welche Quellen die Richtwerte liefern. Die Texte stehen in den Strings
 * `radiation_info_*`, siehe auch [RadiationCalculator].
 */
class RadiationInfoFragment : LogbookFragment() {

    private var _binding: FragmentRadiationInfoBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRadiationInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRadiationInfoBack.setOnClickListener { findNavController().navigateUp() }
        // Link zum Bundesamt für Strahlenschutz: unterstrichen und
        // öffnet die Seite direkt im Standardbrowser des Systems.
        binding.tvRadiationBfsLink.paintFlags =
            binding.tvRadiationBfsLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.tvRadiationBfsLink.setOnClickListener {
            val url = getString(R.string.radiation_info_bfs_url)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
