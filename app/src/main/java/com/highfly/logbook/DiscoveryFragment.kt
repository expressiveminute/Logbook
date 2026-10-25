package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentDiscoveryBinding
import java.time.LocalDate
import java.util.Locale

/**
 * Zeigt alle Flugstrecken, die im gewählten Jahr zum ersten Mal geflogen
 * wurden: absteigend nach Datum, nach Monaten gruppiert. Beim Öffnen der Seite
 * ist immer das laufende Jahr eingestellt.
 *
 * Die Liste ist ein RecyclerView: Die Kacheln werden recycelt, damit nur die
 * sichtbaren Zeilen existieren und die Seite auch mit vielen Einträgen flüssig
 * scrollt.
 */
class DiscoveryFragment : LogbookFragment() {

    private var _binding: FragmentDiscoveryBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    private lateinit var adapter: DiscoveryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiscoveryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Der letzte Eintrag bleibt über der schwebenden Navigationsleiste sichtbar.
        binding.discoveryList.addBottomNavPadding()

        binding.discoveryList.layoutManager = LinearLayoutManager(requireContext())
        binding.discoveryList.setHasFixedSize(true)
        adapter = DiscoveryAdapter(requireContext())
        binding.discoveryList.adapter = adapter

        binding.btnDiscoveryYear.setOnClickListener { showYearPicker() }
    }

    override fun onResume() {
        super.onResume()
        resetToCurrentYear()
        refresh()
    }

    /**
     * Beim Öffnen der Seite wird immer das laufende Jahr gezeigt, auch wenn
     * zuvor ein anderes Jahr gewählt wurde. Eine Auswahl im Jahresdialog gilt
     * bis zum nächsten Öffnen der Seite.
     */
    private fun resetToCurrentYear() {
        val year = LocalDate.now().year
        if (Settings.getDiscoveryYear(requireContext()) != year) {
            Settings.setDiscoveryYear(requireContext(), year)
        }
    }

    private fun refresh() {
        val generation = ++loadGeneration
        val year = Settings.getDiscoveryYear(requireContext())
        binding.btnDiscoveryYear.text = String.format(Locale.getDefault(), "%d", year)
        binding.discoveryProgress.visibility = View.VISIBLE
        binding.discoveryList.visibility = View.INVISIBLE
        binding.tvDiscoveryEmpty.visibility = View.GONE

        val appContext = requireContext().applicationContext
        Thread {
            val months = DiscoveryRoutes.byMonth(LogbookRepository.getFlownEntries(), year)
            // Logos im Hintergrund dekodieren, damit das Binden der Zeilen im
            // Hauptthread nur noch auf den Cache zugreift und beim Scrollen
            // nichts nachgeladen werden muss.
            months.asSequence()
                .flatMap { it.routes.asSequence() }
                .mapNotNull { it.airline }
                .distinct()
                .forEach { AirlineCatalog.loadLogo(appContext, it) }
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                binding.discoveryProgress.visibility = View.GONE
                render(months)
            }
        }.start()
    }

    private fun render(months: List<DiscoveryMonth>) {
        if (months.isEmpty()) {
            binding.discoveryList.visibility = View.GONE
            binding.tvDiscoveryEmpty.visibility = View.VISIBLE
        } else {
            binding.tvDiscoveryEmpty.visibility = View.GONE
            binding.discoveryList.visibility = View.VISIBLE
        }
        adapter.submit(months)
    }

    /**
     * Jahresauswahl: angeboten werden alle Jahre, für die Einträge vorhanden
     * sind, plus das gerade gewählte Jahr, damit die Auswahl nie leer ist.
     */
    private fun showYearPicker() {
        val context = requireContext()
        val selected = Settings.getDiscoveryYear(context)
        val years = (LogbookRepository.getYears() + selected)
            .distinct()
            .sortedDescending()
        val checked = years.indexOf(selected).coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.discovery_year_title)
            .setSingleChoiceItems(
                years.map { String.format(Locale.getDefault(), "%d", it) }.toTypedArray(),
                checked
            ) { dialog, which ->
                Settings.setDiscoveryYear(context, years[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}