package com.highfly.logbook

import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentDiscoveryBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Zeigt alle Flugstrecken, die im gewählten Jahr zum ersten Mal geflogen
 * wurden: absteigend nach Datum, nach Monaten gruppiert. Beim Öffnen der Seite
 * ist immer das laufende Jahr eingestellt.
 */
class DiscoveryFragment : Fragment() {

    private var _binding: FragmentDiscoveryBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    private val isDarkMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Logos sind oft dunkel; im Dunkelmodus werden sie aufgehellt. */
    private val darkLogoFilter by lazy {
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.5f, 0f, 0f, 0f, 45f,
                    0f, 1.5f, 0f, 0f, 45f,
                    0f, 0f, 1.5f, 0f, 45f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiscoveryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
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
            val months = DiscoveryRoutes.byMonth(LogbookRepository.getEntries(), year)
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                binding.discoveryProgress.visibility = View.GONE
                render(months)
            }
        }.start()
    }

    private fun render(months: List<DiscoveryMonth>) {
        val container = binding.discoveryList
        container.removeAllViews()
        if (months.isEmpty()) {
            binding.discoveryList.visibility = View.GONE
            binding.tvDiscoveryEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvDiscoveryEmpty.visibility = View.GONE
        container.visibility = View.VISIBLE

        months.forEach { discoveryMonth ->
            val monthView = layoutInflater.inflate(
                R.layout.item_discovery_month, container, false
            )
            val month = discoveryMonth.month
            monthView.findViewById<TextView>(R.id.tv_discovery_month).text = getString(
                R.string.discovery_month,
                month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                month.year
            )
            container.addView(monthView)

            discoveryMonth.routes.forEach { route ->
                container.addView(createRouteView(route, container))
            }
        }
    }

    private fun createRouteView(route: RouteDiscovery, parent: ViewGroup): View {
        val view = layoutInflater.inflate(R.layout.item_discovery_route, parent, false)
        view.findViewById<TextView>(R.id.tv_discovery_date).text = dateText(route)
        view.findViewById<TextView>(R.id.tv_discovery_aircraft).apply {
            text = aircraftText(route)
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }

        val logo = view.findViewById<ImageView>(R.id.iv_discovery_airline)
        val livery = route.airline?.let { AirlineCatalog.loadLogo(requireContext(), it) }
        if (livery != null) {
            logo.setImageBitmap(livery)
            logo.colorFilter = if (isDarkMode) darkLogoFilter else null
            logo.visibility = View.VISIBLE
        } else {
            logo.setImageBitmap(null)
            logo.visibility = View.GONE
        }

        val map = view.findViewById<RouteMiniMapView>(R.id.discovery_map)
        val from = AirportData.location(requireContext(), route.from)
        val to = AirportData.location(requireContext(), route.to)
        if (from == null || to == null) {
            map.visibility = View.GONE
        } else {
            map.contentDescription = getString(
                R.string.discovery_map_desc, route.from, route.to
            )
            map.setRoute(from, to, route.from, route.to)
        }
        return view
    }

    /**
     * Bei einem Hin- und Rückflug stehen beide Daten in der Zeile, sonst nur
     * das Datum des Erstdurchflugs. Fahren Hin- und Rückflug am selben Tag,
     * wird das Datum nur einmal angezeigt.
     */
    private fun dateText(route: RouteDiscovery): String {
        val outbound = route.date.format(DATE_FORMAT)
        val back = route.returnDate?.format(DATE_FORMAT) ?: return outbound
        if (back == outbound) return outbound
        return getString(R.string.discovery_date_range, outbound, back)
    }

    /**
     * Flugzeugtyp und Registrierung des Erstdurchflugs, jeweils nur wenn im
     * Eintrag hinterlegt. Ohne Angaben bleibt die Zeile leer.
     */
    private fun aircraftText(route: RouteDiscovery): String {
        val type = route.aircraftType
        val registration = route.registration
        return when {
            !type.isNullOrEmpty() && !registration.isNullOrEmpty() ->
                getString(R.string.discovery_aircraft, type, registration)
            !type.isNullOrEmpty() -> type
            !registration.isNullOrEmpty() -> registration
            else -> ""
        }
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

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
