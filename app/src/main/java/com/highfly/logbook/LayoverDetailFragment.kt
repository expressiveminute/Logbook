package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentLayoverDetailBinding
import com.highfly.logbook.databinding.ItemLayoverTimelineBinding
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Detailseite zu einem Layover-Ziel: zentrierter Kopfbereich (Code, Name,
 * Stadt und Land mit Flagge), Länderauszug mit Flughafen-Marker, darunter die
 * Auswertung der im Layover verbrachten Zeit und ein Zeitstrahl aller Layover
 * an diesem Flughafen, der jüngste oben. Der Zeitraum entspricht der Kachel,
 * von der aus die Seite geöffnet wurde.
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
    }

    /**
     * Code mittig, darunter der Name, darunter "Stadt, Land" mit der Flagge
     * dahinter. Die Flagge steht nicht neben dem Code: neben dem Code wirkt
     * sie wie eine zweite Beschriftung, in der Ortszeile gehört sie zum Land.
     */
    private fun renderHeader() {
        binding.tvAirportCode.text = code
        val info = AirportNames.get(requireContext(), code)
        val iso = AirportData.country(requireContext(), code)
        if (info?.name != null && info.name.isNotBlank()) {
            binding.tvAirportName.visibility = View.VISIBLE
            binding.tvAirportName.text = info.name
        }
        val location = listOfNotNull(
            info?.city?.takeIf { it.isNotBlank() },
            iso?.takeIf { it.isNotBlank() }?.let { countryName(it) }
        ).joinToString(", ")
        val line = listOfNotNull(
            location.takeIf { it.isNotBlank() },
            iso?.takeIf { it.isNotBlank() }?.let { AirportData.flagEmoji(it) }
        ).joinToString(" ")
        if (line.isNotBlank()) {
            binding.tvAirportCityCountry.visibility = View.VISIBLE
            binding.tvAirportCityCountry.text = line
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

    /**
     * Auswertung der Layover-Zeit unter der Karte: die Summe der Längen aus
     * "Neuer Flug" steht über einer Leiste, die mit dieser Zeit füllt. Die
     * Einheit richtet sich nach der Zeit: unter einem Tag zählt die Leiste in
     * Tagen, darüber in Wochen, Monaten, Quartalen und Jahren.
     *
     * Ohne erfasste Längen steht dort eine leere Leiste mit 0 h - das ist
     * eine Angabe ("nichts eingetragen"), kein Fehler.
     */
    private fun renderLayoverTime() {
        val hours = ChartData.layoverHoursTotal(
            ChartData.periodFiltered(requireContext()),
            code
        )
        binding.layoverTimeContainer.visibility = View.VISIBLE
        binding.tvLayoverTime.text = breakdownText(hours)
        binding.layoverTimeBar.setLayoverHours(hours, stepLabel(hours))
    }

    /**
     * Beschriftung des Skalenendes, also die Einheit, in der gerade gezählt
     * wird: 30 Stunden stehen als "1 Woche" an der Leiste, 400 Stunden als
     * "1 Quartal". Erst wenn das Jahr überzogen ist, zählt die Leiste in
     * ganzen Jahren, weil eine längere Strecke dann mehr als ein Jahr umfasst.
     */
    private fun stepLabel(hours: Int): String {
        val units = LayoverTimeScale.scaleUnits(hours)
        return when (LayoverTimeScale.stepIndex(hours)) {
            0 -> getString(R.string.layover_time_step_day)
            1 -> getString(R.string.layover_time_step_week)
            2 -> getString(R.string.layover_time_step_month)
            3 -> getString(R.string.layover_time_step_quarter)
            else -> resources.getQuantityString(
                R.plurals.layover_time_step_year, units, units
            )
        }
    }

    /**
     * Dauer über der Leiste: die verbrachte Zeit in jeder Einheit als
     * Gesamtwert, getrennt durch Punkte - 2 Wochen, 6 Tage und 19 Stunden
     * stehen als "499 h · 20,8 Tage · 3,0 Wochen". Stunden stehen ganz-
     * zahlig, alle anderen Einheiten mit einer Nachkommastelle. Gezeigt wird
     * nur, was die Leiste auch zeigt: die größte Einheit bleibt eine Stufe
     * unter dem Skalenende.
     */
    private fun breakdownText(hours: Int): String {
        val totals = LayoverTimeScale.totals(hours)
        val step = LayoverTimeScale.stepIndex(hours)
        val parts = buildList {
            add(getString(R.string.layover_time_unit_hours, number(totals.hours)))
            if (step >= 1) {
                add(part(R.plurals.layover_time_unit_days, totals.days))
            }
            if (step >= 2) {
                add(part(R.plurals.layover_time_unit_weeks, totals.weeks))
            }
            if (step >= 3) {
                add(part(R.plurals.layover_time_unit_months, totals.months))
            }
            if (step >= 4) {
                add(part(R.plurals.layover_time_unit_quarters, totals.quarters))
            }
        }
        return parts.joinToString(BREAKDOWN_SEPARATOR)
    }

    /** Ganzzahl mit Tausendertrennzeichen, wie überall sonst in der App. */
    private fun number(value: Int): String =
        String.format(Locale.GERMANY, "%,d", value)

    /** Einheiten-Teil mit einer Nachkommastelle und passendem Singular/Plural. */
    private fun part(resId: Int, value: Double): String =
        resources.getQuantityString(
            resId, if (value == 1.0) 1 else 2, decimal(value)
        )

    private fun decimal(value: Double): String =
        LayoverTimeScale.oneDecimal(value, Locale.getDefault())

    /**
     * Zeitstrahl: alle Layover des Zeitraums an diesem Flughafen, der jüngste
     * oben und der am längsten her zurückliegende unten. Neben dem Datum steht
     * die Flugroute, mittig daneben der Punkt mit der Layoverlänge des
     * Eintrags in Stunden, rechts davon der Reisebuddy, falls beim Eintrag
     * einer hinterlegt ist. Antippen einer Zeile öffnet den Eintrag auf der
     * Seite "Flug bearbeiten".
     */
    private fun renderTimeline() {
        binding.layoverTimeline.removeAllViews()
        binding.timelineRoot.visibility = View.GONE
        binding.tvEmpty.visibility = View.GONE
        val entries = ChartData.layoverHistoryNewestFirst(
            ChartData.periodFiltered(requireContext()),
            code
        )
        if (entries.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
        binding.timelineRoot.visibility = View.VISIBLE
        entries.forEachIndexed { index, entry ->
            val row = ItemLayoverTimelineBinding.inflate(
                layoutInflater, binding.layoverTimeline, false
            )
            row.root.setOnClickListener { openEditEntry(entry) }
            row.tvDate.text = entry.date.format(DATE_LABEL_FORMAT)
            // Im Punkt steht die Layoverlänge aus "Neuer Flug", also genau die
            // Zeit, die auch die Leiste weiter oben zusammenzählt. Die Flugzeit
            // des Eintrags gehört nicht hierher - sie wäre eine andere Größe.
            row.timelineDot.text =
                entry.layoverHours?.let { hours -> "$hours h" }.orEmpty()
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
            val buddy = entry.travelBuddy?.takeIf { it.isNotBlank() }
            if (buddy != null) {
                row.ivBuddy.visibility = View.VISIBLE
                row.tvBuddy.visibility = View.VISIBLE
                row.tvBuddy.text = buddy
            }
            if (index == entries.lastIndex) {
                (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }
            binding.layoverTimeline.addView(row.root)
        }
        binding.timelineRoot.post {
            if (_binding != null) alignTimelineLine()
        }
    }

    /**
     * Eintrag des Zeitstrahls bearbeiten: dieselbe Seite "Flug bearbeiten"
     * wie aus der Eintragliste, mit der Kennung des Eintrags als Argument.
     */
    private fun openEditEntry(entry: LogbookEntry) {
        findNavController().navigate(
            R.id.action_layover_detail_to_add_entry,
            bundleOf("entryId" to (entry.id ?: -1L))
        )
    }

    /**
     * Die senkrechte Linie des Zeitstrahls verbindet nur die Punkte: Sie
     * beginnt auf halber Höhe der obersten Zeile und endet auf halber Höhe der
     * untersten. Von selbst spannte sie sich über die ganze Liste und stünde an
     * beiden Enden ein Stück ins Leere. Die Zeilenhöhen kennt man erst nach
     * dem Layout.
     */
    private fun alignTimelineLine() {
        val rows = binding.layoverTimeline
        val first = rows.getChildAt(0) ?: return
        val last = rows.getChildAt(rows.childCount - 1)
        val start = first.height / 2
        val end = rows.height - last.height / 2
        val params = binding.timelineLine.layoutParams as? ViewGroup.MarginLayoutParams
            ?: return
        params.height = (end - start).coerceAtLeast(0)
        params.topMargin = start
        binding.timelineLine.layoutParams = params
    }

    /**
     * Layover-Zeit und Zeitstrahl werden erst hier und damit bei jeder
     * Rueckkehr aus dem Bearbeiten-Screen neu gezeichnet. Nur so sind
     * Aenderungen an einem Eintrag (Layoverlänge, Route, Reisebuddy) sofort
     * sichtbar, ohne dass man die Seite dafuer neu oeffnen muss.
     */
    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            binding.countryMap.onResume()
            renderLayoverTime()
            renderTimeline()
        }
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

        /** Trenner zwischen den Teilen der Dauerangabe über der Leiste. */
        const val BREAKDOWN_SEPARATOR = " · "

        val DATE_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}