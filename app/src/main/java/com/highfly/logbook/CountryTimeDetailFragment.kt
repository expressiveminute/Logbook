package com.highfly.logbook

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentCountryTimeDetailBinding
import com.highfly.logbook.databinding.ItemCountryFactBinding
import com.highfly.logbook.databinding.ItemLayoverTimelineBinding
import com.highfly.logbook.databinding.ItemStayBinding
import java.time.LocalDate

/**
 * Detailseite eines Landes auf der Seite "Zeit in Ländern" (Klick auf eine
 * Zeile): Oben der Kopf wie auf der Seite "Flüge" (Zurück-Pfeil, Land mit
 * Flagge), darunter als Teil des Headers ein Balken
 * mit dem Anteil der Land-Zeit an der gesamten Auslandszeit. Darunter etwa
 * das obere Drittel der Seite die Karte des Landes mit den angeflogenen
 * Flughäfen ([CountryRouteMapView]), dann Fakten zum Land
 * ([CountryFacts], Bundesstaaten/-länder bei US/DE aus [StateShapes]) und die
 * Liste der Aufenthalte ([CountryTime.periods]). Layover-Dienstflüge erscheinen
 * darin als Zeitstrahl-Zeile wie auf der Layover-Detailseite, normale
 * Aufenthalte als Einreise/Ausreise-Paar; der Kreis trägt die Reiseart-Farbe.
 *
 * Ein Aufenthalt, der nicht per Flug verlassen wurde (noch offen bzw. Einreise
 * unbekannt), trägt an der Stelle einen Button: Darüber kann der Grenzübertritt
 * händisch nachgetragen werden (Landweg, [BorderCrossing]). Manuell erfasste
 * Übertritte sind anklickbar und lassen sich so bearbeiten.
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
        // Balken im Header wie auf der Layover-Detailseite: Zeit in diesem
        // Land als Dauer (Tage/Wochen/Monate, ohne Quartale wie auf der
        // Times-Seite, dafür Jahre ab mehr als einem Jahr) über einer sich
        // füllenden Leiste. Grundlage sind die Stunden (Layover-Stunden
        // eingeschlossen), nicht nur die vollen Tage.
        val landHours = time?.totalHours?.toInt() ?: 0
        binding.tvCtdBarValue.text =
            TimeCountryFormat.breakdownText(ctx, landHours, quarters = false)
        binding.ctdTimeBar.setLayoverHours(
            landHours, TimeCountryFormat.stepLabel(ctx, landHours, quarters = false)
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
     * Aufenthalte des Landes, neueste oben. Normale Aufenthalte als
     * Zweispalten-Zeile (Einreise | Ausreise, [ItemStayBinding]); ein
     * Layover-Dienstflug als Zeitstrahl-Zeile wie auf der Layover-Detailseite
     * ([ItemLayoverTimelineBinding]): nur der Hinflug, rechts neben dem Kreis
     * Datum, Flugstrecke und Flugnummer, rechts daneben Reisebuddy und
     * Kommentar ([buildLayoverRow]). Der Kreis trägt die Farbe der Reiseart
     * des Flugs ([tintTimelineDot]); bei einem Layover steht darin die
     * Stundenzahl, sonst die Tage.
     *
     * Fehlt ein Datum ganz (Einreise gar nicht im Logbuch bzw. Ausreise noch
     * offen), steht an der Stelle ein Button im Story-Stil („Einreise“/„Ausreise“)
     * zum händischen Nachtragen ([showCrossingDialog]). Ein manuell nachtragenes
     * Datum (Landweg) steht dagegen wie ein Flugdatum da und ist anklickbar:
     * Darüber lässt sich der Grenzübertritt bearbeiten (Klick auf das Feld).
     * Klick auf eine Flug-Zeile öffnet den Hinflug zum Bearbeiten.
     */
    private fun renderStays(time: CountryTime?) {
        val periods = time?.periods.orEmpty()
            .sortedByDescending { it.start ?: it.end ?: LocalDate.MIN }
        binding.llCtdStays.removeAllViews()
        // Neueste Aufenthalte zuerst; bei unbekannter Einreise (start = null)
        // zählt das Ausreisedatum.
        binding.tvCtdEmpty.visibility = if (periods.isEmpty()) View.VISIBLE else View.GONE
        periods.forEachIndexed { index, period ->
            val row = if (period.layover) {
                buildLayoverRow(period, index == periods.lastIndex)
            } else {
                buildStayRow(period, index == periods.lastIndex)
            }
            binding.llCtdStays.addView(row)
        }
    }

    /**
     * Layover-Zeile im Aufbau der Layover-Detailseite (Klick auf einen
     * Dreilettercode): Kreis mit den Layover-Stunden in der Reiseart-Farbe,
     * daneben Datum, Flugstrecke und Flugnummer, rechts daneben der Reisebuddy
     * und der Kommentar. Antippen öffnet den Layover-Flug zum Bearbeiten.
     */
    private fun buildLayoverRow(period: StayPeriod, last: Boolean): View {
        val ctx = requireContext()
        val row = ItemLayoverTimelineBinding.inflate(layoutInflater, binding.llCtdStays, false)
        val entry = period.enterEntry
        // Kreis wie in den anderen Aufenthaltszeilen eingerückt.
        (row.timelineDot.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart =
            (16 * resources.displayMetrics.density).toInt()
        // Layover: Stunden statt Tagen im Kreis; Schrift immer weiß, auch
        // wenn der Kreis in einer hellen Reiseart-Farbe gefüllt ist.
        row.timelineDot.text = getString(R.string.stay_hours_dot, period.hours)
        row.timelineDot.setTextColor(Color.WHITE)
        tintTimelineDot(row.timelineDot, entry)
        if (entry != null) {
            row.root.setOnClickListener { openEditEntry(entry) }
        } else {
            row.root.isClickable = false
        }
        val start = period.start
        row.tvDate.text = if (start != null) TimeCountryFormat.dateText(ctx, start) else ""
        if (entry != null) {
            row.tvRoute.text = routeText(entry)
            val number = listOfNotNull(entry.airline, entry.flightNumber).joinToString(" ")
            row.tvMeta.visibility = if (number.isNotBlank()) View.VISIBLE else View.GONE
            row.tvMeta.text = number
            val buddy = entry.travelBuddy?.takeIf { it.isNotBlank() }
            if (buddy != null) {
                row.ivBuddy.visibility = View.VISIBLE
                row.tvBuddy.visibility = View.VISIBLE
                row.tvBuddy.text = buddy
            }
            row.bindTimelineComment(entry.comment, showBuddy = buddy != null)
        }
        if (last) {
            (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
        }
        return row.root
    }

    /** Normale Aufenthalts-Zeile: Einreise | Ausreise um den Kreis herum. */
    private fun buildStayRow(period: StayPeriod, last: Boolean): View {
        val ctx = requireContext()
        val row = ItemStayBinding.inflate(layoutInflater, binding.llCtdStays, false)
        val enter = period.enterEntry
        val exit = period.exitEntry
        if (enter != null) {
            row.root.setOnClickListener { openEditEntry(enter) }
        } else {
            row.root.isClickable = false
        }
        // Kreis in der Farbe der Reiseart des Flugs (Privat, On Duty, ...).
        tintTimelineDot(row.timelineDot, enter ?: exit)
        // Im Kreis links die Aufenthaltslänge in Tagen.
        row.timelineDot.text = if (period.days > 0) {
            getString(R.string.stay_days_dot, period.days)
        } else {
            getString(R.string.stay_less_than_day_dot)
        }
        // Einreise: Datum (auf Flug-Datum-Höhe), darunter Strecke bzw.
        // Transport mit Icon (auf Flugstrecken-Höhe); nur bei völlig
        // unbekannter Einreise der Button.
        val start = period.start
        if (start != null && enter != null) {
            // Per Flug eingereist.
            row.tvStayDateIn.visibility = View.VISIBLE
            row.tvStayDateIn.text = TimeCountryFormat.dateText(ctx, start)
            row.btnStayIn.visibility = View.GONE
            row.tvStayRouteIn.visibility = View.VISIBLE
            row.tvStayRouteIn.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            row.tvStayRouteIn.text = routeText(enter)
            val meta = buildList {
                add(listOfNotNull(enter.airline, enter.flightNumber).joinToString(" "))
                enter.travelBuddy?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.filter { it.isNotBlank() }.joinToString(" · ")
            row.tvStayMetaIn.visibility = if (meta.isNotBlank()) View.VISIBLE else View.GONE
            row.tvStayMetaIn.text = meta
        } else if (start != null) {
            // Manuell per Landweg eingereist: Datum steht wie bei einem
            // Flug, darunter das Transportmittel mit Icon - kein Button,
            // dafür ist das Feld anklickbar und bearbeiten den Übertritt.
            row.tvStayDateIn.visibility = View.VISIBLE
            row.tvStayDateIn.text = TimeCountryFormat.dateText(ctx, start)
            row.btnStayIn.visibility = View.GONE
            val manualEnter = manual.firstOrNull { it.iso2 == iso2 && it.enter && it.date == start }
            row.tvStayRouteIn.visibility = View.VISIBLE
            row.tvStayRouteIn.setTransportLine(ctx, manualEnter?.transport)
            row.tvStayMetaIn.visibility = View.GONE
            row.colStayIn.isClickable = true
            row.colStayIn.contentDescription = getString(R.string.stay_crossing_dialog_enter)
            row.colStayIn.setOnClickListener {
                showCrossingDialog(enter = true, period = period, editDate = start)
            }
        } else {
            // Einreise unbekannt (nur Auslandsflug im Logbuch): Button.
            row.tvStayDateIn.visibility = View.GONE
            row.btnStayIn.visibility = View.VISIBLE
            row.btnStayIn.setOnClickListener {
                showCrossingDialog(enter = true, period = period, editDate = null)
            }
            row.tvStayRouteIn.visibility = View.GONE
            row.tvStayMetaIn.visibility = View.GONE
        }
        // Ausreise: Datum bzw. Transport, nur bei offenem Aufenthalt der Button.
        val end = period.end
        if (end != null && exit != null) {
            // Per Flug ausgereist.
            row.tvStayDateOut.visibility = View.VISIBLE
            row.tvStayDateOut.text = TimeCountryFormat.dateText(ctx, end)
            row.btnStayOut.visibility = View.GONE
            row.tvStayRouteOut.visibility = View.VISIBLE
            row.tvStayRouteOut.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            row.tvStayRouteOut.text = routeText(exit)
            val meta = listOfNotNull(exit.airline, exit.flightNumber).joinToString(" ")
            row.tvStayMetaOut.visibility = if (meta.isNotBlank()) View.VISIBLE else View.GONE
            row.tvStayMetaOut.text = meta
        } else if (end != null) {
            // Manuell per Landweg ausgereist: Datum + Transport mit Icon;
            // das Feld ist anklickbar und bearbeitet den Übertritt.
            row.tvStayDateOut.visibility = View.VISIBLE
            row.tvStayDateOut.text = TimeCountryFormat.dateText(ctx, end)
            row.btnStayOut.visibility = View.GONE
            val manualExit = manual.firstOrNull { it.iso2 == iso2 && !it.enter && it.date == end }
            row.tvStayRouteOut.visibility = View.VISIBLE
            row.tvStayRouteOut.setTransportLine(ctx, manualExit?.transport)
            row.tvStayMetaOut.visibility = View.GONE
            row.colStayOut.isClickable = true
            row.colStayOut.contentDescription = getString(R.string.stay_crossing_dialog_exit)
            row.colStayOut.setOnClickListener {
                showCrossingDialog(enter = false, period = period, editDate = end)
            }
        } else {
            // Noch offen: der Button "Ausreise".
            row.tvStayDateOut.visibility = View.GONE
            row.btnStayOut.visibility = View.VISIBLE
            row.btnStayOut.setOnClickListener {
                showCrossingDialog(enter = false, period = period, editDate = null)
            }
            row.tvStayRouteOut.visibility = View.GONE
            row.tvStayMetaOut.visibility = View.GONE
        }
        if (last) {
            (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
        }
        return row.root
    }

    /**
     * Färbt den Kreis einer Aufenthalts- bzw. Zeitstrahl-Zeile in die Farbe der
     * Reiseart des zugehörigen Flugs (Privat, On Duty, Deadhead, Ferry, Ground
     * Transfer, Dienstreise - dieselben Farben wie die Reiseart-Auswahl).
     * Ohne Flug (manueller Landweg) bleibt die Primärfarbe des Punkts.
     */
    private fun tintTimelineDot(dot: TextView, entry: LogbookEntry?) {
        val res = flightTypeColorRes(entry) ?: return
        dot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), res))
    }

    /** Farb-Resource der Reiseart eines Eintrags aus [ChartData.travelTypeColors]. */
    private fun flightTypeColorRes(entry: LogbookEntry?): Int? {
        val canonical = ChartData.normalizeFlightType(entry?.flightType) ?: return null
        val index = ChartData.TRAVEL_TYPE_CANONICALS.indexOf(canonical)
        if (index < 0) return null
        return ChartData.travelTypeColors()[index]
    }

    /**
     * Transportmittel-Zeile unter einem manuell nachtragenen Datum: Icon +
     * ausgeschriebener Name (z. B. "Auto"), ohne Transport nur "Landweg".
     */
    private fun TextView.setTransportLine(ctx: android.content.Context, transport: String?) {
        val label = TimeCountryFormat.transportLabel(ctx, transport)
        text = label ?: ctx.getString(R.string.stay_overland)
        if (transport != null) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                TimeCountryFormat.transportIconRes(transport), 0, 0, 0
            )
            compoundDrawablePadding = (4 * resources.displayMetrics.density).toInt()
        } else {
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
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
     * Popup zum händischen Nachtragen oder Bearbeiten von Ein- oder Ausreise
     * ([enter]): Datum per Kalender (bekanntes Format dd.MM.yyyy, Flugtage
     * sind darin leicht markiert, siehe [FlightDatePickerDialog]), darunter
     * das Transportmittel (Auto, Zug, Bus, Fähre, Fahrrad, Zu Fuß - je mit
     * Icon). Speichert einen manuellen [BorderCrossing] mit Transport und lädt
     * neu; der Button an der Stelle des Datums verschwindet.
     *
     * Mit [editDate] wird ein vorhandener manueller Übertritt an diesem Datum
     * bearbeitet: Datum und Transportmittel sind vorausgewählt und beim
     * Speichern wird der alte Eintrag an [editDate] ersetzt. Ohne [editDate]
     * wird ein neuer Übertritt angehängt.
     */
    private fun showCrossingDialog(enter: Boolean, period: StayPeriod, editDate: LocalDate?) {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_stay_crossing, null)
        val tvTitle = view.findViewById<TextView>(R.id.tv_crossing_dialog_title)
        val tvDate = view.findViewById<TextView>(R.id.tv_crossing_date)
        val tvHint = view.findViewById<TextView>(R.id.tv_crossing_hint)
        val rg = view.findViewById<RadioGroup>(R.id.rg_transport)
        // Das andere Ende des Zeitraums (z. B. der Flug, mit dem das Land
        // betreten wurde, im Ausreise-Dialog) dezent unter dem Datumsfeld
        // anzeigen - so kann man sich im Kalender orientieren.
        val hintDate = if (enter) period.end else period.start
        if (hintDate != null) {
            tvHint.visibility = View.VISIBLE
            tvHint.text = getString(
                R.string.stay_crossing_existing, TimeCountryFormat.dateText(ctx, hintDate)
            )
        } else {
            tvHint.visibility = View.GONE
        }
        tvTitle.setText(
            if (enter) R.string.stay_crossing_dialog_enter else R.string.stay_crossing_dialog_exit
        )
        // Beim Bearbeiten steht der vorhandene übertritt an [editDate] zur
        // Verfügung: Datum und Transportmittel sind vorausgewählt.
        val existing = editDate?.let { d ->
            manual.firstOrNull { it.iso2 == iso2 && it.enter == enter && it.date == d }
        }
        // Vorauswahl: beim Bearbeiten das vorhandene Datum, sonst zwei Wochen
        // nach dem hinterlegten Einreisedatum bzw. zwei Wochen vor der
        // hinterlegten Ausreise - nur eine Vorauswahl, gespeichert wird, was
        // der Nutzer einträgt. Fehlt die Einreise (start = null), wird zwei
        // Wochen vor der Ausreise vorgeschlagen.
        var date = existing?.date ?: when {
            !enter -> (period.start ?: period.end)?.plusDays(14) ?: LocalDate.now()
            period.end != null -> period.end.minusDays(14)
            period.start != null -> period.start.plusDays(14)
            else -> LocalDate.now()
        }
        rg.check(TimeCountryFormat.transportRadioId(existing?.transport))
        tvDate.text = TimeCountryFormat.dateText(ctx, date)
        tvDate.setOnClickListener {
            val d = date
            // Im Kalender nur das eine Datum markieren, das für diesen
            // Zeitraum schon existiert (im Ausreise-Dialog z. B. der
            // Einreiseflug) - keine weiteren Daten.
            FlightDatePickerDialog.show(ctx, d, setOfNotNull(hintDate)) { picked ->
                date = picked
                tvDate.text = TimeCountryFormat.dateText(ctx, picked)
            }
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(view)
            .setNegativeButton(R.string.role_switch_cancel, null)
            .setPositiveButton(R.string.users_add_action, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val transport = TimeCountryFormat.transportKey(rg.checkedRadioButtonId)
            val updated = if (editDate != null) {
                // Vorhandenen manuellen Übertritt an diesem Datum ersetzen -
                // auch wenn sich das Datum im Dialog geändert hat.
                manual.filterNot { it.iso2 == iso2 && it.enter == enter && it.date == editDate } +
                    BorderCrossing(iso2, date, enter = enter, transport = transport)
            } else {
                manual + BorderCrossing(iso2, date, enter = enter, transport = transport)
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
