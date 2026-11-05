package com.highfly.logbook

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.highfly.logbook.databinding.FragmentTimeCountriesBinding
import java.time.LocalDate

/**
 * Seite "Zeit in Ländern" (letzter Tab der Navigationsleiste): Aufenthaltsdauer
 * pro Land aus den Flugeinträgen - ein Flug ins Land ist das Betreten, ein Flug
 * hinaus das Verlassen, dazwischen liegt die Zeit im Land (tageweise, die
 * Einträge haben nur ein Datum). Das Land der Homebase wird nicht gezaehlt.
 *
 * Landweg-Grenzübertritte (Auto, Bus, Fähre) stehen nicht im Logbuch und werden
 * unten auf der Seite manuell erfasst ([showCrossingDialog]); sie fließen als
 * zusätzliche Ereignisse in die Rechnung ein ([CountryTimeStats]). Gespeichert
 * werden sie in den Einstellungen ([Settings.getManualBorderCrossings]), der
 * JSON-Export nimmt sie pauschal mit.
 */
class TimeCountriesFragment : LogbookFragment() {

    private var _binding: FragmentTimeCountriesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TimeCountriesAdapter
    private var loadGeneration = 0

    /** Alle Länder für Namen und Flaggen, im Hintergrund geladen. */
    private var countries: List<CountryShapes.Country> = emptyList()

    /** Manuell erfasste Grenzübertritte, wie zuletzt gerendert. */
    private var manual: List<BorderCrossing> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimeCountriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Die letzte Zeile bleibt über der schwebenden Navigationsleiste sichtbar.
        binding.rvTimeCountries.addBottomNavPadding()
        binding.rvTimeCountries.layoutManager = LinearLayoutManager(requireContext())
        adapter = TimeCountriesAdapter(
            requireContext(),
            onCountryClick = ::openCountry,
            onDeleteCrossing = ::deleteCrossing,
            onAddCrossing = ::showCrossingDialog
        )
        binding.rvTimeCountries.adapter = adapter
        // FAB wie auf dem Dashboard: manueller Aufenthalt (Land + Ein-/Ausreise).
        binding.fabAddStay.addBottomNavMargin()
        binding.fabAddStay.setOnClickListener { showStayDialog() }
    }

    override fun onResume() {
        super.onResume()
        // Beim Zurückkehren nach einem bearbeiteten Eintrag neu rechnen.
        loadData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Lädt Logbuch, Länder und manuelle Übertritte im Hintergrund und wertet
     * die Aufenthalte aus. Ein Generationszähler verwirft veraltete Ergebnisse.
     */
    private fun loadData() {
        if (_binding == null) return
        val generation = ++loadGeneration
        binding.timeCountriesProgress.visibility = View.VISIBLE
        val appContext = requireContext().applicationContext
        Thread {
            val entries = LogbookRepository.getEntries()
            val shapes = CountryShapes.load(appContext).orEmpty()
            val crossings = Settings.getManualBorderCrossings(appContext)
            // Homebase.current wurde von getEntries() gerade neu berechnet.
            val homeIata = Homebase.current
            val homeIso = homeIata?.let { AirportData.country(appContext, it) }?.trim()?.uppercase()
            val result = CountryTimeStats.evaluate(
                entries,
                countryOf = { AirportData.country(appContext, it) },
                homeIso2 = homeIso,
                manual = crossings,
                today = LocalDate.now()
            )
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                binding.timeCountriesProgress.visibility = View.GONE
                countries = shapes
                manual = crossings
                render(result, homeIata, homeIso)
            }
        }.start()
    }

    private fun render(result: List<CountryTime>, homeIata: String?, homeIso: String?) {
        val de = useGermanNames()
        val nameOf: (String) -> String = { iso ->
            countries.firstOrNull { it.iso2.equals(iso, ignoreCase = true) }
                ?.let { if (de) it.nameDe else it.nameEn }
                ?: iso
        }
        binding.tvTimeCountriesSubtitle.text = getString(
            R.string.time_countries_subtitle,
            result.size,
            TimeCountryFormat.daysText(requireContext(), result.sumOf { it.totalDays })
        )
        val homeName = homeIso?.let(nameOf)
        if (homeIata != null && homeName != null && homeName != homeIso) {
            binding.tvTimeCountriesHomebase.text =
                getString(R.string.time_countries_homebase, homeIata, homeName)
            binding.tvTimeCountriesHomebase.visibility = View.VISIBLE
        } else if (homeIata != null) {
            binding.tvTimeCountriesHomebase.text =
                getString(R.string.time_countries_homebase, homeIata, homeIso ?: "")
            binding.tvTimeCountriesHomebase.visibility = View.VISIBLE
        } else {
            binding.tvTimeCountriesHomebase.visibility = View.GONE
        }

        // Balken im Header wie auf der Layover-Detailseite: Zeit im Ausland
        // als Dauer (h/Tage/Wochen/Monate) über einer sich füllenden Leiste.
        val ctx = requireContext()
        val abroad = result.sumOf { it.totalDays }
        val abroadHours = TimeCountryFormat.barHours(abroad)
        binding.tvAbroadValue.text = TimeCountryFormat.breakdownText(ctx, abroadHours)
        binding.timeAbroadBar.setLayoverHours(
            abroadHours, TimeCountryFormat.stepLabel(ctx, abroadHours)
        )

        val items = ArrayList<TimeCountryItem>()
        result.forEachIndexed { i, time ->
            items += TimeCountryItem.Country(
                rank = i + 1,
                name = nameOf(time.iso2),
                flag = AirportData.flagEmoji(time.iso2),
                time = time
            )
        }
        if (result.isEmpty()) items += TimeCountryItem.Empty
        items += TimeCountryItem.SectionCrossings
        manual.forEachIndexed { i, c ->
            items += TimeCountryItem.Crossing(
                index = i,
                crossing = c,
                name = nameOf(c.iso2),
                flag = AirportData.flagEmoji(c.iso2)
            )
        }
        items += TimeCountryItem.AddCrossing
        adapter.items = items
    }

    /** Dialog zum manuellen Erfassen eines Landweg-Grenzübertritts. */
    private fun showCrossingDialog() {
        val ctx = requireContext()
        val de = useGermanNames()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_manual_crossing, null)
        val act = view.findViewById<MaterialAutoCompleteTextView>(R.id.act_crossing_country)
        val tvDate = view.findViewById<TextView>(R.id.tv_crossing_date)
        val rg = view.findViewById<RadioGroup>(R.id.rg_crossing_type)

        val names = countries.map { if (de) it.nameDe else it.nameEn }
        act.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, names))

        var date = LocalDate.now()
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
            .setTitle(R.string.time_countries_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.role_switch_cancel, null)
            .setPositiveButton(R.string.users_add_action, null)
            .create()
        dialog.show()
        // Erst bei gültiger Auswahl speichern, sonst bleibt der Dialog offen.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = act.text.toString().trim()
            val iso = countries.firstOrNull {
                (if (de) it.nameDe else it.nameEn).equals(name, ignoreCase = true)
            }?.iso2
            if (iso == null) {
                Toast.makeText(ctx, R.string.time_countries_pick_country, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val enter = rg.checkedRadioButtonId == R.id.rb_crossing_enter
            Settings.setManualBorderCrossings(ctx, manual + BorderCrossing(iso, date, enter))
            dialog.dismiss()
            loadData()
        }
    }

    /** Öffnet die Detailseite eines Landes (Klick auf die Zeile). */
    private fun openCountry(iso2: String) {
        findNavController().navigate(
            R.id.action_time_countries_to_country_time_detail,
            Bundle().apply { putString("iso2", iso2) }
        )
    }

    private fun deleteCrossing(index: Int) {
        val ctx = requireContext()
        val updated = manual.toMutableList().apply { removeAt(index) }
        Settings.setManualBorderCrossings(ctx, updated)
        loadData()
    }

    /**
     * Popup für einen kompletten manuellen Aufenthalt (FAB unten rechts): Land,
     * Einreisedatum mit Transportmittel, Ausreisedatum mit Transportmittel.
     * Auch wenn Hin- und Rückweg nicht per Flugzeug waren. Wird als zwei
     * manuelle Grenzübertritte gespeichert und als Aufenthalt gezählt.
     */
    private fun showStayDialog() {
        val ctx = requireContext()
        val de = useGermanNames()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_manual_stay, null)
        val act = view.findViewById<MaterialAutoCompleteTextView>(R.id.act_stay_country)
        val actIn = view.findViewById<MaterialAutoCompleteTextView>(R.id.act_transport_in)
        val actOut = view.findViewById<MaterialAutoCompleteTextView>(R.id.act_transport_out)
        val tvIn = view.findViewById<TextView>(R.id.tv_stay_in_date)
        val tvOut = view.findViewById<TextView>(R.id.tv_stay_out_date)

        val names = countries.map { if (de) it.nameDe else it.nameEn }
        act.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, names))

        // Transport-Dropdowns mit Icon je Option.
        val keys = TimeCountryFormat.TRANSPORT_KEYS
        val labels = keys.map { getString(TimeCountryFormat.transportLabelRes(it)) }
        fun transportAdapter(): ArrayAdapter<String> =
            object : ArrayAdapter<String>(ctx, android.R.layout.simple_dropdown_item_1line, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = super.getView(position, convertView, parent) as TextView
                    tv.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        TimeCountryFormat.transportIconRes(keys[position]), 0, 0, 0
                    )
                    tv.compoundDrawablePadding =
                        (8 * resources.displayMetrics.density).toInt()
                    return tv
                }

                override fun getDropDownView(
                    position: Int, convertView: View?, parent: ViewGroup
                ): View = getView(position, convertView, parent)
            }
        actIn.setAdapter(transportAdapter())
        actOut.setAdapter(transportAdapter())
        actIn.setText(labels[0], false)
        actOut.setText(labels[0], false)

        var inDate = LocalDate.now()
        var outDate = inDate.plusDays(14)
        tvIn.text = TimeCountryFormat.dateText(ctx, inDate)
        tvOut.text = TimeCountryFormat.dateText(ctx, outDate)
        tvIn.setOnClickListener {
            pickDate(ctx, inDate) { d ->
                inDate = d
                tvIn.text = TimeCountryFormat.dateText(ctx, inDate)
                if (outDate < inDate) {
                    outDate = inDate.plusDays(14)
                    tvOut.text = TimeCountryFormat.dateText(ctx, outDate)
                }
            }
        }
        tvOut.setOnClickListener {
            pickDate(ctx, outDate) { d ->
                outDate = d
                tvOut.text = TimeCountryFormat.dateText(ctx, outDate)
            }
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.stay_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.role_switch_cancel, null)
            .setPositiveButton(R.string.users_add_action, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = act.text.toString().trim()
            val iso = countries.firstOrNull {
                (if (de) it.nameDe else it.nameEn).equals(name, ignoreCase = true)
            }?.iso2
            if (iso == null) {
                Toast.makeText(ctx, R.string.time_countries_pick_country, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (outDate < inDate) {
                Toast.makeText(ctx, R.string.stay_dates_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val tIn = keys.getOrNull(labels.indexOf(actIn.text.toString()))
            val tOut = keys.getOrNull(labels.indexOf(actOut.text.toString()))
            val updated = manual +
                BorderCrossing(iso, inDate, enter = true, transport = tIn) +
                BorderCrossing(iso, outDate, enter = false, transport = tOut)
            Settings.setManualBorderCrossings(ctx, updated)
            dialog.dismiss()
            loadData()
        }
    }

    /** DatePicker im bekannten Format dd.MM.yyyy. */
    private fun pickDate(ctx: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) {
        DatePickerDialog(
            ctx,
            { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth
        ).show()
    }

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(): Boolean {
        val language = resources.configuration.locales[0].language
        return language.isEmpty() || language == Settings.LANG_DE
    }
}
