package com.highfly.logbook

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentCountryTimeDetailBinding
import com.highfly.logbook.databinding.ItemCountryFactBinding
import com.highfly.logbook.databinding.ItemStayBinding
import java.time.LocalDate

/**
 * Detailseite eines Landes auf der Seite "Zeit in Ländern" (Klick auf eine
 * Zeile): Oben der Kopf wie auf der Seite "Flüge" (Zurück-Pfeil, Land mit
 * Flagge, rechts die Gesamttage), darunter als Teil des Headers ein Balken
 * mit dem Anteil der Land-Zeit an der gesamten Auslandszeit. Darunter etwa
 * das obere Drittel der Seite die Karte des Landes mit den angeflogenen
 * Flughäfen ([CountryRouteMapView]), dann Fakten zum Land
 * ([CountryFacts], Bundesstaaten/-länder bei US/DE aus [StateShapes]) und die
 * Liste der Aufenthalte ([CountryTime.periods]).
 *
 * Ein Aufenthalt, der nicht per Flug verlassen wurde (noch offen), trägt
 * rechts einen Stift: Darüber kann das Verlassen händisch nachgetragen werden
 * (Landweg, [BorderCrossing] mit enter=false) - danach ist der Aufenthalt
 * geschlossen und das Icon weg.
 */
class CountryTimeDetailFragment : LogbookFragment() {

    private var _binding: FragmentCountryTimeDetailBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    /** ISO-2 des Landes aus den Argumenten. */
    private val iso2: String
        get() = arguments?.getString("iso2")?.trim()?.uppercase().orEmpty()

    private var manual: List<BorderCrossing> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCountryTimeDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCtdBack.setOnClickListener { findNavController().navigateUp() }
        // Der letzte Inhalt bleibt über der schwebenden Navigationsleiste sichtbar.
        binding.ctdScroll.addBottomNavPadding()
        // Die Karte oben im scrollbaren Bereich bekommt etwa ein Drittel der
        // Bildschirmhöhe.
        binding.cardCtdMap.doOnNextLayout {
            val lp = binding.cardCtdMap.layoutParams
            lp.height = resources.displayMetrics.heightPixels / 3
            binding.cardCtdMap.layoutParams = lp
        }
    }

    override fun onResume() {
        super.onResume()
        binding.ctdMap.onResume()
        loadData()
    }

    override fun onPause() {
        super.onPause()
        binding.ctdMap.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Lädt Logbuch, Länder, Staaten und manuelle Übertritte im Hintergrund,
     * wertet die Aufenthalte des Landes aus und befüllt Karte und Fakten.
     */
    private fun loadData() {
        if (_binding == null || iso2.isEmpty()) return
        val generation = ++loadGeneration
        binding.ctdProgress.visibility = View.VISIBLE
        val appContext = requireContext().applicationContext
        val target = iso2
        Thread {
            val entries = LogbookRepository.getEntries()
            val shapes = CountryShapes.load(appContext).orEmpty()
            val states = StateShapes.load(appContext).orEmpty()
            val crossings = Settings.getManualBorderCrossings(appContext)
            val homeIso = Homebase.current
                ?.let { AirportData.country(appContext, it) }?.trim()?.uppercase()
            val all = CountryTimeStats.evaluate(
                entries,
                countryOf = { AirportData.country(appContext, it) },
                homeIso2 = homeIso,
                manual = crossings,
                today = LocalDate.now()
            )
            val time = all.firstOrNull { it.iso2 == target }
            val model = CountryDetailModel.analyze(
                entries, target,
                countryOf = { AirportData.country(appContext, it) },
                location = { AirportData.location(appContext, it) }
            )
            val fact = CountryFacts.fact(appContext, target)
            val shape = shapes.firstOrNull { it.iso2.equals(target, ignoreCase = true) }
            val stateCount = states.count { it.iso2.equals(target, ignoreCase = true) }
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                binding.ctdProgress.visibility = View.GONE
                manual = crossings
                render(time, model, shape, fact, stateCount, shapes)
            }
        }.start()
    }

    private fun render(
        time: CountryTime?,
        model: CountryDetailModel.Result,
        shape: CountryShapes.Country?,
        fact: CountryFacts.Fact?,
        stateCount: Int,
        shapes: List<CountryShapes.Country>
    ) {
        val ctx = requireContext()
        val de = useGermanNames()
        val name = shape?.let { if (de) it.nameDe else it.nameEn } ?: iso2

        binding.tvCtdTitle.text = "$name ${AirportData.flagEmoji(iso2)}"
        binding.tvCtdSubtitle.text = buildString {
            append(iso2)
            if (time != null) {
                append(" · ")
                append(TimeCountryFormat.visitsText(ctx, time.visits))
            }
        }
        binding.tvCtdCount.text =
            TimeCountryFormat.daysText(ctx, time?.totalDays ?: 0L)

        // Balken im Header wie auf der Layover-Detailseite: Zeit in diesem
        // Land als Dauer (h/Tage/Wochen/Monate) über einer sich füllenden Leiste.
        val land = time?.totalDays ?: 0L
        val landHours = TimeCountryFormat.barHours(land)
        binding.tvCtdBarValue.text = TimeCountryFormat.breakdownText(ctx, landHours)
        binding.ctdTimeBar.setLayoverHours(
            landHours, TimeCountryFormat.stepLabel(ctx, landHours)
        )

        // Karte des Landes mit den angeflogenen Flughäfen, ohne Verbindungen.
        binding.ctdMap.setData(shape, model.airports, emptyList(), emptyList())

        renderFacts(fact, stateCount, de)
        renderStays(time)
    }

    /** Fakten-Karte: Amtssprache, Hauptstadt, Bundesstaaten, Währung, Feiertage. */
    private fun renderFacts(fact: CountryFacts.Fact?, stateCount: Int, de: Boolean) {
        val ctx = requireContext()
        binding.llCtdFacts.removeAllViews()
        var count = 0
        fun addFact(labelRes: Int, value: String?) {
            if (value.isNullOrBlank()) return
            val b = ItemCountryFactBinding.inflate(LayoutInflater.from(ctx), binding.llCtdFacts, false)
            b.tvFactLabel.setText(labelRes)
            b.tvFactValue.text = value
            binding.llCtdFacts.addView(b.root)
            count++
        }
        fact?.let {
            addFact(
                R.string.fact_language,
                (if (de) it.languagesDe else it.languagesEn).ifEmpty { if (de) it.languagesEn else it.languagesDe }
                    .joinToString(", ")
            )
            addFact(
                R.string.fact_capital,
                (if (de) it.capitalsDe else it.capitalsEn).ifEmpty { if (de) it.capitalsEn else it.capitalsDe }
                    .joinToString(", ")
            )
            // USA/Deutschland: echte Anzahl aus state_shapes.bin, sonst P150.
            val divisions = if (stateCount > 0) stateCount.toString() else it.adminDivisions?.toString()
            addFact(R.string.fact_divisions, divisions)
            addFact(
                R.string.fact_currency,
                (if (de) it.currenciesDe else it.currenciesEn).ifEmpty { if (de) it.currenciesEn else it.currenciesDe }
                    .joinToString(", ")
            )
        }
        binding.cardCtdFacts.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    /**
     * Aufenthalte des Landes, neueste oben: links der Kreis mit den Tagen,
     * daneben Einreise (Datum, darunter klein Flugstrecke und Flugnummer),
     * ein Strich mittig zentriert im Bild, dann Ausreise (Datum, darunter klein
     * Flugstrecke und Flugnummer). Fehlt ein Datum (Einreise per Landweg bzw.
     * Ausreise noch offen), steht an der Stelle ein Button im Story-Stil
     * („Einreise“/„Ausreise“) zum händischen Nachtragen ([showCrossingDialog]).
     * Klick auf die Zeile öffnet den Hinflug zum Bearbeiten.
     */
    private fun renderStays(time: CountryTime?) {
        val ctx = requireContext()
        binding.llCtdStays.removeAllViews()
        // Neueste Aufenthalte zuerst.
        val periods = time?.periods.orEmpty().sortedByDescending { it.start }
        binding.tvCtdEmpty.visibility = if (periods.isEmpty()) View.VISIBLE else View.GONE
        periods.forEachIndexed { index, period ->
            val row = ItemStayBinding.inflate(layoutInflater, binding.llCtdStays, false)
            val enter = period.enterEntry
            val exit = period.exitEntry
            if (enter != null) {
                row.root.setOnClickListener { openEditEntry(enter) }
            } else {
                row.root.isClickable = false
            }
            // Im Kreis links die Aufenthaltslänge in Tagen.
            row.timelineDot.text = if (period.days > 0) {
                getString(R.string.stay_days_dot, period.days)
            } else {
                getString(R.string.stay_less_than_day_dot)
            }
            // Einreise: Datum bzw. Button, darunter Strecke und Flugnummer.
            if (enter != null) {
                row.tvStayDateIn.visibility = View.VISIBLE
                row.tvStayDateIn.text = TimeCountryFormat.dateText(ctx, period.start)
                row.btnStayIn.visibility = View.GONE
                row.tvStayRouteIn.text = routeText(enter)
                val meta = buildList {
                    add(listOfNotNull(enter.airline, enter.flightNumber).joinToString(" "))
                    enter.travelBuddy?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.filter { it.isNotBlank() }.joinToString(" · ")
                row.tvStayMetaIn.visibility = if (meta.isNotBlank()) View.VISIBLE else View.GONE
                row.tvStayMetaIn.text = meta
            } else {
                // Landweg: kein Flug, dafür der Button "Einreise".
                row.tvStayDateIn.visibility = View.GONE
                row.btnStayIn.visibility = View.VISIBLE
                row.btnStayIn.setOnClickListener { showCrossingDialog(enter = true, period = period) }
                val manualEnter = manual.firstOrNull { it.iso2 == iso2 && it.enter && it.date == period.start }
                val transport = TimeCountryFormat.transportLabel(ctx, manualEnter?.transport)
                row.tvStayRouteIn.text = if (transport != null) {
                    getString(R.string.stay_overland) + " · " + transport
                } else {
                    getString(R.string.stay_overland)
                }
                row.tvStayMetaIn.visibility = View.GONE
            }
            // Ausreise: Datum bzw. Button, darunter Strecke und Flugnummer.
            if (period.end != null) {
                row.tvStayDateOut.visibility = View.VISIBLE
                row.tvStayDateOut.text = TimeCountryFormat.dateText(ctx, period.end)
                row.btnStayOut.visibility = View.GONE
                if (exit != null) {
                    row.tvStayRouteOut.text = routeText(exit)
                    val meta = listOfNotNull(exit.airline, exit.flightNumber).joinToString(" ")
                    row.tvStayMetaOut.visibility = if (meta.isNotBlank()) View.VISIBLE else View.GONE
                    row.tvStayMetaOut.text = meta
                } else {
                    // Per Landweg verlassen.
                    row.tvStayRouteOut.text = getString(R.string.stay_overland)
                    row.tvStayMetaOut.visibility = View.GONE
                }
            } else {
                // Noch offen: der Button "Ausreise".
                row.tvStayDateOut.visibility = View.GONE
                row.btnStayOut.visibility = View.VISIBLE
                row.btnStayOut.setOnClickListener { showCrossingDialog(enter = false, period = period) }
                row.tvStayRouteOut.visibility = View.GONE
                row.tvStayMetaOut.visibility = View.GONE
            }
            if (index == periods.lastIndex) {
                (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }
            binding.llCtdStays.addView(row.root)
        }
    }

    /** Flugstrecke eines Eintrags als "FRA → JFK". */
    private fun routeText(entry: LogbookEntry): String = getString(
        R.string.import_review_summary_route,
        entry.fromAirport.uppercase(),
        entry.toAirport.uppercase()
    )

    /** Öffnet den Hinflug des Aufenthalts zum Bearbeiten. */
    private fun openEditEntry(entry: LogbookEntry) {
        findNavController().navigate(
            R.id.action_country_time_detail_to_add_entry,
            Bundle().apply { putLong("entryId", entry.id ?: -1L) }
        )
    }

    /**
     * Popup zum händischen Nachtragen von Ein- oder Ausreise ([enter]): Datum
     * per DatePicker (bekanntes Format dd.MM.yyyy), darunter das Transportmittel
     * (Auto, Zug, Bus, Fähre, Fahrrad, Zu Fuß - je mit Icon). Speichert einen
     * manuellen [BorderCrossing] mit Transport und lädt neu; der Button an der
     * Stelle des Datums verschwindet. Beim Nachtragen der Einreise wird ein
     * vorhandener manueller Enter an diesem Datum ersetzt.
     */
    private fun showCrossingDialog(enter: Boolean, period: StayPeriod) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_stay_crossing, null)
        val tvTitle = view.findViewById<TextView>(R.id.tv_crossing_dialog_title)
        val tvDate = view.findViewById<TextView>(R.id.tv_crossing_date)
        val rg = view.findViewById<RadioGroup>(R.id.rg_transport)
        tvTitle.setText(
            if (enter) R.string.stay_crossing_dialog_enter else R.string.stay_crossing_dialog_exit
        )
        // Vorauswahl: zwei Wochen nach dem hinterlegten Einreisedatum bzw. zwei
        // Wochen vor der hinterlegten Ausreise - nur eine Vorauswahl, gespeichert
        // wird, was der Nutzer einträgt.
        var date = when {
            !enter -> period.start.plusDays(14)
            period.end != null -> period.end.minusDays(14)
            else -> period.start.plusDays(14)
        }
        tvDate.text = TimeCountryFormat.dateText(ctx, date)
        tvDate.setOnClickListener {
            val d = date
            DatePickerDialog(
                ctx,
                { _, y, m, day ->
                    date = LocalDate.of(y, m + 1, day)
                    tvDate.text = TimeCountryFormat.dateText(ctx, date)
                },
                d.year, d.monthValue - 1, d.dayOfMonth
            ).show()
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(view)
            .setNegativeButton(R.string.role_switch_cancel, null)
            .setPositiveButton(R.string.users_add_action, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val transport = TimeCountryFormat.transportKey(rg.checkedRadioButtonId)
            val updated = if (enter) {
                manual.filterNot { it.iso2 == iso2 && it.enter && it.date == period.start } +
                    BorderCrossing(iso2, date, enter = true, transport = transport)
            } else {
                manual + BorderCrossing(iso2, date, enter = false, transport = transport)
            }
            Settings.setManualBorderCrossings(ctx, updated)
            dialog.dismiss()
            loadData()
        }
    }

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(): Boolean {
        val language = resources.configuration.locales[0].language
        return language.isEmpty() || language == Settings.LANG_DE
    }

}
