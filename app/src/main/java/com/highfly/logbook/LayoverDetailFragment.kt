package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentLayoverDetailBinding
import com.highfly.logbook.databinding.ItemLayoverTimelineBinding
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Detailseite zu einem Layover-Ziel: zentrierter Kopfbereich (Code, Name,
 * Stadt und Land), Länderauszug mit Flughafen-Marker und ein chronologischer
 * Zeitstrahl aller Layover an diesem Flughafen. Der Zeitraum entspricht der
 * Kachel, von der aus die Seite geöffnet wurde.
 */
class LayoverDetailFragment : Fragment() {

    private var _binding: FragmentLayoverDetailBinding? = null
    private val binding get() = _binding!!

    private var code: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = requireContext()
        val config = org.osmdroid.config.Configuration.getInstance()
        config.load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
        config.userAgentValue = context.packageName
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLayoverDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        code = requireArguments().getString(ARG_AIRPORT_CODE)?.trim()?.uppercase() ?: ""

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.tvDetailSubtitle.text = getString(
            R.string.tile_detail_period,
            PeriodOptions.label(
                requireContext(),
                Settings.getDefaultPeriodKey(requireContext())
            )
        )

        renderHeader()
        renderMap()
        renderTimeline()
    }

    /** Code und Flagge zentriert, darunter Name und "Stadt, Land". */
    private fun renderHeader() {
        binding.tvAirportCode.text = code
        val info = AirportNames.get(requireContext(), code)
        val iso = AirportData.country(requireContext(), code)
        if (info?.name != null && info.name.isNotBlank()) {
            binding.tvAirportName.visibility = View.VISIBLE
            binding.tvAirportName.text = info.name
        }
        if (iso != null && iso.isNotBlank()) {
            if (binding.tvAirportFlag.visibility != View.VISIBLE) {
                binding.tvAirportFlag.visibility = View.VISIBLE
            }
            binding.tvAirportFlag.text = AirportData.flagEmoji(iso)
        }
        val countryName = iso?.let { countryName(it) }
        val locationText = listOfNotNull(info?.city, countryName)
            .joinToString(", ")
        if (locationText.isNotBlank()) {
            binding.tvAirportCityCountry.visibility = View.VISIBLE
            binding.tvAirportCityCountry.text = locationText
        }
    }

    private fun countryName(iso: String): String? {
        val country = CountryShapes.load(requireContext())
            ?.firstOrNull { it.iso2 == iso }
            ?: return null
        return if (useGermanNames(Locale.getDefault())) country.nameDe else country.nameEn
    }

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(locale: Locale): Boolean =
        locale.language.isEmpty() || locale.language == Settings.LANG_DE

    /** Kartenausschnitt der Weltkarte, auf das Land gezoomt, mit Marker. */
    private fun renderMap() {
        val iso = AirportData.country(requireContext(), code)
        val country = iso?.let { ic ->
            CountryShapes.load(requireContext())?.firstOrNull { it.iso2 == ic }
        }
        val marker = AirportData.location(requireContext(), code)
        if (country == null || marker == null) return
        binding.countryMapCard.visibility = View.VISIBLE
        binding.countryMap.setData(country, marker, code)
        val label = binding.tvAirportCityCountry.text.ifBlank { code }
        binding.countryMap.contentDescription = getString(
            R.string.layover_map_desc,
            label,
            code
        )
    }

    /** Zeitstrahl: alle Layover des Zeitraums an diesem Flughafen, alt nach neu. */
    private fun renderTimeline() {
        val entries = ChartData.layoverHistory(
            ChartData.periodFiltered(requireContext()),
            code
        )
        if (entries.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvTimelineTitle.visibility = View.VISIBLE
        binding.timelineRoot.visibility = View.VISIBLE
        entries.forEachIndexed { index, entry ->
            val row = ItemLayoverTimelineBinding.inflate(
                layoutInflater, binding.layoverTimeline, false
            )
            row.tvDate.text = entry.date.format(DATE_LABEL_FORMAT)
            row.tvRoute.text = getString(
                R.string.import_review_summary_route,
                entry.fromAirport.uppercase(),
                entry.toAirport.uppercase()
            )
            val number = listOfNotNull(entry.airline, entry.flightNumber)
                .joinToString(" ")
            if (number.isNotBlank()) {
                row.tvMeta.visibility = View.VISIBLE
                row.tvMeta.text = number
            }
            if (index == entries.lastIndex) {
                (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }
            binding.layoverTimeline.addView(row.root)
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) binding.countryMap.onResume()
    }

    override fun onPause() {
        if (_binding != null) binding.countryMap.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        if (_binding != null) binding.countryMap.onDetach()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Name des Navigationsarguments, siehe `nav_graph.xml`. */
        const val ARG_AIRPORT_CODE = "code"

        val DATE_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}