package com.highfly.logbook

import android.animation.ValueAnimator
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentFlightsYearBinding
import java.time.Month
import java.time.format.TextStyle

class FlightsYearFragment : LogbookFragment() {

    private var _binding: FragmentFlightsYearBinding? = null
    private val binding get() = _binding!!

    /** Ob der Reiseart-Balken beim nächsten Befüllen animiert auffüllen soll. */
    private var animateTravelBarNext = false

    /** Laufende Füll-Animation des Reiseart-Balkens. */
    private var travelBarAnimator: ValueAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFlightsYearBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Platz unter der schwebenden Navigationsleiste.
        binding.flightsYearScroll.addBottomNavPadding()

        binding.btnYearBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // Beim Öffnen der Seite (Tipp auf die Kachel "Flüge") füllt sich der
        // Reiseart-Balken animiert von links nach rechts. Das Befüllen selbst
        // geschieht in onResume(), das direkt auf onViewCreated folgt - ein
        // eigener refresh() hier würde den Balken sofort wieder neu binden
        // und die soeben gestartete Füll-Animation abbrechen.
        animateTravelBarNext = true

        // Startposition des Liniendiagramms: ganz nach rechts scrollen, damit
        // das aktuelle Jahr am rechten Rand steht. Aeltere Jahre erreicht man
        // durch Scrollen nach links (der Container ist bereits horizontal
        // scrollbar). Eine Wiederherstellung der gemerkten Position (Rueckkehr
        // auf die Seite) hat Vorrang und wird nicht ueberschrieben. Das Scrollen
        // geschieht erst nach dem ersten Layout, wenn die Breite des Diagramms
        // feststeht.
        if (!hasPendingScrollRestore(R.id.year_line_scroll)) {
            binding.yearLineScroll.doOnNextLayout {
                val scroll = binding.yearLineScroll
                val contentWidth =
                    if (scroll.childCount > 0) scroll.getChildAt(0).width else 0
                val maxScroll = (contentWidth - scroll.width).coerceAtLeast(0)
                scroll.scrollTo(maxScroll, 0)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Aenderungen am Dashboard-Filter (Flugart, Zeitraum) kommen ueber die
        // Kopfzeile der Activity: Der Reiseart-Balken ganz oben und alle
        // Diagramme dieser Seite beziehen sich wie das Dashboard selbst auf
        // den aktiven Filter und zeichnen sich bei jeder Aenderung neu.
        DashboardEvents.onFilterChanged = { refresh() }
        // Erster Aufbau der Ansicht nach onViewCreated: verbraucht hier das
        // Animations-Flag, damit der Reiseart-Balken einmalig auffüllt.
        refresh()
    }

    override fun onPause() {
        super.onPause()
        DashboardEvents.onFilterChanged = null
    }

    /**
     * Befuellt die Seite mit den Eintraegen, die das Dashboard gerade zeigt
     * (Flugart- und Zeitraum-Filter inklusive): Reiseart-Balken ganz oben,
     * Gesamtzahl, Balken je Jahr und Monat sowie das Liniendiagramm.
     */
    private fun refresh() {
        val entries = DashboardStats.dashboardEntries(requireContext())

        // Der Reiseart-Balken animiert den Füllvorgang nur beim Öffnen der
        // Seite; bei einem Filterwechsel steht er sofort endgültig.
        val animateBar = animateTravelBarNext
        animateTravelBarNext = false

        // Reiseart-Balken ganz oben: gestapelte Abschnitte, größter Anteil
        // links, Segmente anklickbar für das Kreisdiagramm.
        bindTravelTypeBar(entries, animateBar)

        val countsByYear = entries.groupingBy { it.date.year }.eachCount()
        val years = countsByYear.entries
            .sortedWith(
                compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key }
            )

        binding.tvYearCount.text = countText(entries.size)

        val monthCounts = IntArray(Month.values().size)
        entries.forEach { monthCounts[it.date.monthValue - 1]++ }
        binding.barChartYearMonths.setItems(
            Month.values().map { month ->
                MonthBarChartView.Item(
                    month.getDisplayName(TextStyle.SHORT, Settings.language(requireContext())),
                    monthCounts[month.value - 1]
                )
            }
        )

        binding.tvYearEmpty.visibility = if (years.isEmpty()) View.VISIBLE else View.GONE
        binding.barChartYear.visibility = if (years.isEmpty()) View.GONE else View.VISIBLE
        binding.barChartYearMonths.visibility = if (years.isEmpty()) View.GONE else View.VISIBLE
        binding.yearLineChart.visibility = if (years.isEmpty()) View.GONE else View.VISIBLE

        binding.barChartYear.setItems(
            years.map { BarChartView.Item(it.key.toString(), it.value) }
        )
        binding.barChartYear.setOnItemClickListener { index ->
            openMonths(years[index].key)
        }

        bindYearLineChart(
            countsByYear.toSortedMap().map { LineChartView.Item(it.key.toString(), it.value) }
        )
    }

    /**
     * Das Liniendiagramm waechst mit der Zahl der Jahre ueber die Bildschirmbreite
     * hinaus und wird dann horizontal gescrollt. Damit die y-Achse beim Scrollen
     * stehen bleibt, zeichnet eine zweite Instanz nur die Achse ueber den linken
     * Rand; das Diagramm selbst reserviert deren Breite als [setLeadingSpace].
     */
    private fun bindYearLineChart(items: List<LineChartView.Item>) {
        val chart = binding.lineChartYear
        val axis = binding.lineChartYearAxis

        chart.setLeadingSpace(axis.layoutParams.width)
        chart.setItems(items)

        axis.setContentVisible(false)
        axis.setItems(items)
    }

    private fun openMonths(year: Int) {
        val bundle = Bundle().apply { putInt("year", year) }
        findNavController().navigate(R.id.action_flights_year_to_months, bundle)
    }

    /**
     * Füllt den Reiseart-Balken: ein horizontaler gestapelter Balken, größter
     * Anteil links, Farben wie die Reiseart-Kachel. In jedes Segment wird der
     * Prozentsatz geschrieben, sobald genug Platz ist. Darunter steht eine
     * Beschriftungszeile mit Name und absoluter Anzahl der zwei größten Anteile,
     * linksbündig an der Position ihres Segments. Ein Tipp auf ein Segment oder
     * auf den Balken öffnet das Kreisdiagramm - beim Segment mit diesem Stück
     * vorausgewählt. Mit [animate] wächst der Balken von links nach rechts auf
     * (Öffnen der Seite).
     */
    private fun bindTravelTypeBar(entries: List<LogbookEntry>, animate: Boolean) {
        val bar = binding.travelTypeBar
        val labelsRow = binding.travelTypeLabels
        travelBarAnimator?.cancel()
        travelBarAnimator = null
        bar.removeAllViews()
        labelsRow.removeAllViews()
        val slices = ChartData.travelTypeSlices(requireContext(), entries)
            .sortedByDescending { it.value }
        if (slices.isEmpty()) {
            bar.visibility = View.GONE
            labelsRow.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        labelsRow.visibility = View.VISIBLE
        val total = slices.sumOf { it.value }.toFloat()

        // Die zwei größten Anteile bekommen eine Beschriftung darunter.
        val topTwo = slices.take(2).toSet()

        // Endgültige Segmentgewichte; die Füll-Animation skaliert sie von 0
        // auf den vollen Wert (siehe [applyTravelBarFraction]).
        val baseWeights = slices.map { it.value / total }
        val segments = mutableListOf<View>()
        val labelCells = mutableListOf<View>()

        slices.forEachIndexed { index, slice ->
            val weight = baseWeights[index]
            val percent = (weight * 100).toInt()

            // Segment im Balken mit Prozent-Text; anklickbar, öffnet das
            // Kreisdiagramm mit diesem Stück vorausgewählt.
            val segment = android.widget.FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    weight
                )
                setBackgroundColor(ContextCompat.getColor(requireContext(), slice.colorRes))
                isClickable = true
                isFocusable = true
                foreground = rippleForeground()
                setOnClickListener { openTravelTypePie(slice.label) }
            }
            // Prozent-Text ab ~8% Segmentbreite; bei kleineren Segmenten
            // wird die Schrift kleiner, damit sie noch hineinpasst.
            if (weight >= 0.08f) {
                val tv = android.widget.TextView(requireContext()).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER
                    )
                    text = "$percent %"
                    setTextColor(android.graphics.Color.WHITE)
                    // Bei schmalen Segmenten kleinere Schrift, damit der
                    // Text nicht abgeschnitten wird.
                    textSize = if (weight >= 0.15f) 10f else 8f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                segment.addView(tv)
            }
            segment.contentDescription = "${slice.label}: ${slice.value} ($percent %)"
            bar.addView(segment)
            segments.add(segment)

            // Beschriftung unter dem Balken für die zwei größten Anteile:
            // Name (erste Zeile) und absolute Anzahl (zweite Zeile).
            // Höhe wrap_content, damit zwei Zeilen vollständig sichtbar sind.
            val labelCell = android.widget.FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    weight
                )
            }
            if (slice in topTwo) {
                val labelContainer = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.START or android.view.Gravity.TOP
                    )
                    // Leichter Versatz nach rechts, damit der Text nicht am
                    // äußersten Rand klebt.
                    setPadding(2, 0, 0, 0)
                }
                // Erste Zeile: Name der Reiseart (singleLine + ellipsize,
                // damit langer Text nicht horizontal abgeschnitten wird).
                val nameView = android.widget.TextView(requireContext()).apply {
                    text = slice.label
                    setTextColor(com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnSurface
                    ))
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                // Zweite Zeile: absolute Anzahl
                val countView = android.widget.TextView(requireContext()).apply {
                    text = resources.getQuantityString(
                        R.plurals.travel_type_count, slice.value, slice.value
                    )
                    setTextColor(com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnSurfaceVariant
                    ))
                    textSize = 11f
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                labelContainer.addView(nameView)
                labelContainer.addView(countView)
                labelCell.addView(labelContainer)
            }
            labelsRow.addView(labelCell)
            labelCells.add(labelCell)
        }

        // Tipp auf den Balken (neben den Segmenten) öffnet das Kreisdiagramm
        // ohne Vorauswahl; ebenso der Tipp auf die Kachel selbst.
        bar.setOnClickListener { openTravelTypePie(null) }
        binding.cardTravelType.setOnClickListener { openTravelTypePie(null) }

        if (animate && !animationsDisabled()) {
            // Der Balken füllt sich von links nach rechts: alle Segmente
            // wachsen gemeinsam von Gewicht 0 auf ihren vollen Anteil.
            applyTravelBarFraction(segments, labelCells, baseWeights, 0f)
            travelBarAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = TRAVEL_TYPE_BAR_FILL_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { animation ->
                    applyTravelBarFraction(
                        segments,
                        labelCells,
                        baseWeights,
                        animation.animatedValue as Float
                    )
                }
                start()
            }
        }
    }

    /**
     * Öffnet das Kreisdiagramm der Reisearten. [sliceLabel] nennt das Segment
     * des Balkens, auf das getippt wurde - das Stück klappt im Kreisdiagramm
     * gleich auf; null öffnet das Diagramm ohne Vorauswahl.
     */
    private fun openTravelTypePie(sliceLabel: String?) {
        val bundle = Bundle().apply {
            putString("tileId", "traveltype")
            if (sliceLabel != null) putString("sliceLabel", sliceLabel)
        }
        findNavController().navigate(R.id.action_flights_year_to_tile_detail, bundle)
    }

    /**
     * Setzt die Gewichte der Balken-Segmente und Beschriftungszellen auf den
     * Anteil [fraction] ihres vollen Werts - die Füll-Animation des Balkens.
     */
    private fun applyTravelBarFraction(
        segments: List<View>,
        labelCells: List<View>,
        baseWeights: List<Float>,
        fraction: Float
    ) {
        segments.forEachIndexed { index, segment ->
            (segment.layoutParams as LinearLayout.LayoutParams).weight = baseWeights[index] * fraction
        }
        labelCells.forEachIndexed { index, cell ->
            (cell.layoutParams as LinearLayout.LayoutParams).weight = baseWeights[index] * fraction
        }
        binding.travelTypeBar.requestLayout()
        binding.travelTypeLabels.requestLayout()
    }

    /** Ripple-Hintergrund für die anklickbaren Balken-Segmente. */
    private fun rippleForeground(): android.graphics.drawable.Drawable? {
        val context = requireContext()
        val out = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            ContextCompat.getDrawable(context, out.resourceId)
        } else {
            null
        }
    }

    /** Ob der Nutzer Systemanimationen abgeschaltet hat (Bedienungshilfen). */
    private fun animationsDisabled(): Boolean = try {
        android.provider.Settings.Global.getFloat(
            requireContext().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    } catch (e: Exception) {
        false
    }

    private fun countText(count: Int): String =
        getString(
            R.string.flights_year_total,
            String.format(Settings.language(requireContext()), "%,d", count)
        )

    override fun onDestroyView() {
        super.onDestroyView()
        travelBarAnimator?.cancel()
        _binding = null
    }

    private companion object {
        /** Dauer der Füll-Animation des Reiseart-Balkens in Millisekunden. */
        const val TRAVEL_TYPE_BAR_FILL_MS = 600L
    }
}
