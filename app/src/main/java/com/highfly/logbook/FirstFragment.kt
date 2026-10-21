package com.highfly.logbook

import android.os.Bundle
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentFirstBinding
import kotlin.math.roundToInt

class FirstFragment : LogbookFragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val bigNumberTiles = setOf("earthorbits", "moon")

    private var timeMinutes = 0
    private var loadGeneration = 0

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

        binding.dashboardScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateFabVisibility()
        }
        binding.dashboardScroll.post { updateFabVisibility() }
    }

    private fun updateFabVisibility() {
        val scroll = binding.dashboardScroll
        val contentScrolls = scroll.canScrollVertically(-1) || scroll.canScrollVertically(1)
        val showFab = !contentScrolls || scroll.canScrollVertically(1)
        val fab = binding.fabAdd
        val target = if (showFab) View.VISIBLE else View.GONE
        if (fab.visibility == target) return
        fab.animate().cancel()

        if (showFab) {
            fab.visibility = View.VISIBLE
            fab.alpha = 0f
            fab.scaleX = 1.15f
            fab.scaleY = 1.15f
            fab.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start()
        } else {
            fab.animate()
                .scaleX(1.15f)
                .scaleY(1.15f)
                .alpha(0.85f)
                .setDuration(130)
                .withEndAction {
                    fab.animate()
                        .scaleX(0f)
                        .scaleY(0f)
                        .alpha(0f)
                        .setDuration(120)
                        .withEndAction { fab.visibility = View.GONE }
                        .start()
                }
                .start()
        }
    }

    override fun onResume() {
        super.onResume()
        DashboardEvents.onFilterChanged = { refresh() }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        DashboardEvents.onFilterChanged = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        TileValueAnimator.cancelAll()
        _binding = null
    }

    private fun refresh() {
        val generation = ++loadGeneration
        binding.dashboardProgress.visibility = View.VISIBLE

        val appContext = requireContext().applicationContext
        Thread {
            // Beide Filter der Zeile oben wirken auf jede Kachel und auf jede
            // Seite dahinter: Flugart und Zeitraum zusammen, siehe
            // [DashboardStats.dashboardEntries].
            val entries = DashboardStats.dashboardEntries(appContext)
            val time = DashboardStats.flightMinutes(entries)
            // Die Erfolge hängen am ganzen Logbuch, nicht am Zeitraum der
            // Kacheln: Was einmal erreicht ist, bleibt erreicht. Die Kachel
            // trägt deshalb ihren eigenen Fortschritt, den die Filterzeile
            // unberührt lässt; die übrigen Kacheln folgen weiter dem Filter.
            val achievements = Achievements.evaluate(appContext)
            val values = DashboardStats.values(appContext, entries).toMutableMap().apply {
                put(
                    "achievements",
                    DashboardStats.Value(
                        String.format(
                            Settings.language(appContext),
                            "%,d",
                            achievements.reachedCount
                        ),
                        appContext.getString(
                            R.string.tile_achievements_of,
                            achievements.total
                        )
                    )
                )
            }
            val distance = DashboardStats.distanceDetails(appContext, entries)
            val co2 = Co2Calculator.details(entries)
            // Die Höhenstrahlung richtet sich nicht nach dem Zeitraum-Filter,
            // sondern zeigt das laufende Jahr - Grundlage sind alle Einträge
            // der gewählten Flugart.
            val radiation = RadiationStats.summary(
                DashboardStats.flightTypeEntries(appContext),
                lookup = { code -> AirportData.location(appContext, code) }
            )
            // Die Passagierzahl richtet sich wie die Höhenstrahlung nicht
            // nach dem Zeitraum-Filter: Grundlage sind alle Einträge der
            // gewählten Flugart, gezählt werden nur Flüge als "On Duty"
            // (siehe [PassengersCalculator]); die Auslastung kommt aus den
            // Einstellungen.
            val passengers = PassengersCalculator.summary(
                DashboardStats.flightTypeEntries(appContext),
                Settings.getPassengerLoadFactor(appContext),
                AircraftSeats.load(appContext)
            )

            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                binding.dashboardProgress.visibility = View.GONE
                timeMinutes = time
                renderGrid(
                    DashboardPrefs.readRows(binding.root.context),
                    binding.gridTiles,
                    values,
                    distance,
                    co2,
                    radiation,
                    passengers,
                    entries
                )
            }
        }.start()
    }

    private fun tileName(tile: DashboardPrefs.Tile, entries: List<LogbookEntry>): CharSequence {
        val singular = tile.nameSingularRes
        return if (singular != null && DashboardStats.tileCount(tile.id, entries) == 1) {
            getString(singular)
        } else {
            getString(tile.nameRes)
        }
    }

    private fun renderGrid(
        rows: List<List<String>>,
        container: LinearLayout,
        values: Map<String, DashboardStats.Value>,
        distance: DashboardStats.DistanceDetails,
        co2: Co2Calculator.Details,
        radiation: RadiationStats.Summary,
        passengers: PassengersCalculator.Summary,
        entries: List<LogbookEntry>
    ) {
        TileValueAnimator.cancelAll()
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE

        val context = requireContext()
        val spans = DashboardPrefs.spansWithDefaults(context)
        val scale = DashboardPrefs.readScale(context)
        // Das Raster selbst bleibt fest; die Grösse verkleinert nur die Kacheln
        // in ihrer Zelle (siehe [tileContainer.scaleX] weiter unten).
        binding.dashboardContent.setPadding(
            dp(GRID_PADDING_DP), dp(GRID_PADDING_DP),
            dp(GRID_PADDING_DP), dp(GRID_PADDING_DP)
        )

        val role = Settings.getRole(context)
        // Die Zeilenaufteilung folgt immer den aktuellen Kachelbreiten, damit
        // eine geänderte Grösse sofort stimmt.
        val packedRows = DashboardPrefs.packRows(rows.flatten(), spans)
        packedRows.forEachIndexed { rowIndex, rowIds ->
            // Kacheln, die zur Rolle nicht passen (Layover, Funktion), fehlen
            // hier ganz; die Spalten richten sich nur nach den sichtbaren.
            val visibleIds = rowIds.filter { DashboardPrefs.isTileAvailable(it, role) }
            if (visibleIds.isEmpty()) return@forEachIndexed
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            // Zaehlt nur tatsaechlich sichtbare Kacheln, damit eine
            // ausgeblendete Kachel die Spalten danach nicht verschiebt.
            var column = 0
            visibleIds.forEach { tileId ->
                val isDistance = tileId == "distance"
                val isCo2 = tileId == "co2"
                val isRadiation = tileId == "radiation"
                val isPassengers = tileId == "passengers"
                val isTime = tileId == "time"
                val tileContainer = when {
                    isDistance -> layoutInflater.inflate(
                        R.layout.item_dashboard_tile_distance,
                        row,
                        false
                    ) as ViewGroup
                    isCo2 -> layoutInflater.inflate(
                        R.layout.item_dashboard_tile_co2,
                        row,
                        false
                    ) as ViewGroup
                    isRadiation -> layoutInflater.inflate(
                        R.layout.item_dashboard_tile_radiation,
                        row,
                        false
                    ) as ViewGroup
                    isPassengers -> layoutInflater.inflate(
                        R.layout.item_dashboard_tile_passengers,
                        row,
                        false
                    ) as ViewGroup
                    isTime -> layoutInflater.inflate(
                        R.layout.item_dashboard_tile_time,
                        row,
                        false
                    ) as ViewGroup
                    else -> layoutInflater.inflate(
                        R.layout.item_dashboard_tile,
                        row,
                        false
                    ) as ViewGroup
                }

                val tile = DashboardPrefs.tileById(tileId)
                val iconView =
                    tileContainer.findViewById<android.widget.ImageView>(R.id.iv_tile_icon)
                val tileNameView =
                    tileContainer.findViewById<TextView>(R.id.tv_tile_name)
                if (!isDistance && !isCo2 && !isRadiation && !isPassengers && !isTime) {
                    iconView.setImageResource(tile.iconRes)
                    tileNameView.text = tileName(tile, entries)
                }

                val pieView = tileContainer.findViewById<PieChartView>(R.id.tile_pie_chart)
                val worldmapPreview = tileContainer.findViewById<WorldMapPreviewView>(R.id.tile_worldmap_preview)
                if (isDistance) {
                    populateDistanceTile(tileContainer, distance)
                } else if (isCo2) {
                    populateCo2Tile(tileContainer, co2)
                } else if (isRadiation) {
                    populateRadiationTile(tileContainer, radiation)
                } else if (isPassengers) {
                    populatePassengersTile(tileContainer, passengers)
                } else if (isTime) {
                    populateTimeTile(tileContainer, timeMinutes, entries)
                } else if (tileId == "worldmap") {
                    iconView.visibility = View.GONE
                    tileContainer.findViewById<View>(R.id.ll_tile_top_right).visibility = View.GONE
                    pieView.visibility = View.GONE
                    worldmapPreview.visibility = View.VISIBLE
                    val primaryColor = MaterialColors.getColor(tileContainer, com.google.android.material.R.attr.colorPrimary)
                    worldmapPreview.setColors(primaryColor, primaryColor)
                } else if (ChartData.isPieChart(tileId)) {
                    iconView.visibility = if (tileId == "function") View.VISIBLE else View.GONE
                    tileContainer.findViewById<View>(R.id.ll_tile_top_right).visibility = View.GONE
                    worldmapPreview.visibility = View.GONE
                    pieView.visibility = View.VISIBLE
                    val slices = when (tileId) {
                        "class" -> ChartData.classSlices(
                            requireContext(),
                            null,
                            entries,
                            Settings.getClassTravelType(requireContext())
                        )
                        "function" -> ChartData.functionSlices(requireContext(), entries)
                        else -> ChartData.travelTypeSlices(requireContext(), entries)
                    }
                    if (tileId == "function") {
                        iconView.bringToFront()
                    }
                    pieView.showLegend = false
                    pieView.sliceTouchEnabled = false
                    pieView.setSlices(
                        slices.map {
                            PieChartView.Slice(
                                it.label,
                                it.value,
                                ContextCompat.getColor(requireContext(), it.colorRes)
                            )
                        }
                    )
                } else {
                    pieView.visibility = View.GONE
                    worldmapPreview.visibility = View.GONE
                    val value = values[tileId]
                    tileContainer.findViewById<TextView>(R.id.tv_tile_value).apply {
                        if (value == null) {
                            text = ""
                            visibility = View.GONE
                        } else {
                            TileValueAnimator.animate(this, buildValueText(value))
                            visibility = View.VISIBLE
                        }
                    }
                    tileContainer.findViewById<TextView>(R.id.tv_tile_unit).apply {
                        text = ""
                        visibility = View.GONE
                    }
                }

                val card = tileContainer.findViewById<com.google.android.material.card.MaterialCardView>(R.id.tile_card)
                card.tag = tileId
                card.radius = dp(CARD_RADIUS_DP).toFloat()
                DashboardTileColors.apply(
                    card,
                    DashboardPrefs.TilePosition(rowIndex, column)
                )
                card.setOnClickListener {
                    if (tileId == "time") {
                        openTimeTile()
                    } else {
                        openTile(tileId)
                    }
                }

                // Die Kachelbreite folgt ihrer Spaltenzahl (1..3). Kacheln, die
                // nicht mehr in die laufende Zeile passen, hat [packRows] schon
                // in die nächste Zeile geschoben.
                val span = DashboardPrefs.spanOf(spans, tileId)
                val params = LinearLayout.LayoutParams(0, tileHeight(tile), span.toFloat())
                // Vorläufige Verkleinerung; die Aussenkanten der Breite werden
                // nach dem Vermessen spaltenweise angeglichen (siehe
                // [applyHorizontalScale]). Die Höhe schrumpft gleichmässig.
                tileContainer.scaleX = scale
                tileContainer.scaleY = scale
                row.addView(tileContainer, params)
                column++
            }
            // Fehlende Spalten bleiben leer: die Kacheln behalten ihre Breite,
            // statt sich über die ganze Zeile zu strecken.
            val remaining = DashboardPrefs.SPAN_FULL -
                visibleIds.sumOf { DashboardPrefs.spanOf(spans, it) }
            if (remaining > 0) {
                row.addView(
                    View(requireContext()),
                    LinearLayout.LayoutParams(0, 1, remaining.toFloat())
                )
            }
            container.addView(row)
        }

        if (container.childCount == 0) {
            container.visibility = View.GONE
            return
        }

        container.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                resizeStatTiles(container, spans)
                applyHorizontalScale(container, scale)
                fitTileTopValues(container)
                updateFabVisibility()
            }
        })
    }

    /**
     * Feste Kachelhöhen wachsen mit der Systemschrift: Bei grosser Schrift
     * passt der Inhalt sonst nicht mehr in die Kachel und wird abgeschnitten.
     * Die vom Nutzer gewählte Kachel-Grösse wirkt nicht hier, sondern als
     * gleichmässiges Schrumpfen der fertigen Kachel.
     */
    private fun tileHeight(tile: DashboardPrefs.Tile): Int =
        dp((tile.heightDp * DashboardTileScaling.fontScale(requireView())).roundToInt())

    private fun resizeStatTiles(container: LinearLayout, spans: Map<String, Int>) {
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val tileContainer = row.getChildAt(j) as? ViewGroup ?: continue
                val card =
                    tileContainer.findViewById<com.google.android.material.card.MaterialCardView>(R.id.tile_card)
                val tileId = card.tag as? String ?: continue
                val tile = DashboardPrefs.tileById(tileId)
                val minHeight = tileHeight(tile)
                if (tileId == "co2" || tileId == "radiation" || tileId == "passengers") {
                    // Full width, but as flat as a regular (square) tile.
                    // Die Höhenstrahlungs- und Passagiere-Kachel sind genauso
                    // hoch wie die CO₂-Kachel.
                    val target = maxOf(
                        tileContainer.width / 3,
                        dp(88),
                        minHeight
                    )
                    val co2Params = tileContainer.layoutParams
                    if (co2Params.height != target) {
                        co2Params.height = target
                        tileContainer.layoutParams = co2Params
                    }
                    continue
                }
                // Nur die kleinen (ursprünglich einspaltigen) Kacheln sind so
                // hoch wie eine Spalte breit ist. Dadurch bleibt die Höhe
                // erhalten, wenn eine Kachel auf 2 oder 3 Spalten verbreitert
                // wird. Die vollbreiten Kacheln behalten ihre eigene Höhe.
                if (DashboardPrefs.defaultSpan(tileId) != 1) continue
                val params = tileContainer.layoutParams
                val target = maxOf(row.width / 3, minHeight)
                if (params.height != target) {
                    params.height = target
                    tileContainer.layoutParams = params
                }
            }
        }
    }

    /**
     * Gleicht die Aussenbreite aller Kacheln an. Statt jede Kachel um denselben
     * Anteil ihrer eigenen Breite zu verkleinern (was eine 3-Spalten-Kachel
     * dreimal so stark schrumpfen liesse wie eine 1-Spalten-Kachel), rückt jede
     * Kachel an jeder Seite um eine halbe Spalte mal (1 - [scale]) nach innen.
     * So ist eine 3-Spalten-Kachel genau so breit wie drei 1-Spalten-Kacheln
     * samt ihren Zwischenräumen.
     */
    private fun applyHorizontalScale(container: LinearLayout, scale: Float) {
        if (container.width == 0) return
        val column = container.width / 3f
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val tile = row.getChildAt(j) as? ViewGroup ?: continue
                if (tile.width == 0) continue
                val width = tile.width.toFloat()
                tile.scaleX = DashboardPrefs.horizontalScale(width, column, scale)
                // Waagerecht schrumpft eine Kachel anders stark als senkrecht.
                // Ohne Ausgleich würde der Inhalt verzerrt (siehe
                // [DashboardTileScaling.scaleContent]).
                DashboardTileScaling.scaleContent(
                    tile,
                    DashboardPrefs.contentCompensation(width, column, scale)
                )
            }
        }
    }

    private fun fitTileTopValues(container: LinearLayout) {
        val targets = mutableListOf<TextView>()
        var minFactor = 1f
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val tileContainer = row.getChildAt(j) as? ViewGroup ?: continue
                val card =
                    tileContainer.findViewById<com.google.android.material.card.MaterialCardView>(R.id.tile_card)
                val tileId = card.tag as? String ?: continue
                // Die oberste Zeile traegt bei Zeit und Distanz zwei Werte um
                // einen mittigen Punkt ("x Stunden · y Tage", "x km · y nm");
                // jede Haelfte muss wie die grossen Zahlen der Kacheln
                // "Erdumrundungen"/"Mond" mitwachsen duerfen.
                val valueIds = when (tileId) {
                    in bigNumberTiles -> listOf(R.id.tv_tile_value)
                    "time" -> listOf(R.id.tv_time_value, R.id.tv_time_days)
                    "distance" -> listOf(R.id.tv_dist_value, R.id.tv_dist_nm)
                    else -> emptyList()
                }
                for (valueId in valueIds) {
                    val tv = tileContainer.findViewById<TextView>(valueId)
                    if (tv.visibility != View.VISIBLE) continue
                    val available = tv.width - tv.totalPaddingStart - tv.totalPaddingEnd
                    if (available <= 0) continue
                    targets.add(tv)
                    val baseTextSize = tv.textSize
                    val textPaint = TextPaint(tv.paint).apply { textSize = baseTextSize }
                    // Vermisst den END-Wert statt des animierten Zwischenwerts,
                    // damit die Schrift auf die finale Zahl passt.
                    val measureText = TileValueAnimator.finalTextOf(tv) ?: tv.text
                    val layout = StaticLayout.Builder
                        .obtain(measureText, 0, measureText.length, textPaint, Int.MAX_VALUE)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0f, 1f)
                        .setIncludePad(false)
                        .build()
                    val lineWidth = layout.getLineWidth(0)
                    if (lineWidth > available) {
                        val factor = available / lineWidth
                        if (factor < minFactor) minFactor = factor
                    }
                }
            }
        }
        if (targets.isEmpty()) return
        val newSize = targets[0].textSize * minFactor
        for (tv in targets) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, newSize)
        }
    }

    private fun buildValueText(value: DashboardStats.Value): CharSequence {
        val builder = SpannableStringBuilder()
        appendUnitStyled(builder, value, unitColor())
        return builder
    }

    private fun unitColor(): Int = MaterialColors.getColor(
        binding.root,
        com.google.android.material.R.attr.colorOnSurfaceVariant
    )

    /** Hängt Zahl und Einheit (klein, anderfarbig) an [builder] an. */
    private fun appendUnitStyled(
        builder: SpannableStringBuilder,
        value: DashboardStats.Value,
        unitColor: Int
    ) {
        builder.append(value.text)
        val unit = value.unit
        if (unit.isNullOrEmpty()) return
        val unitStart = builder.length + 1
        builder.append(" ").append(unit)
        builder.setSpan(
            ForegroundColorSpan(unitColor),
            unitStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            RelativeSizeSpan(0.6f),
            unitStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun populateCo2Tile(
        container: ViewGroup,
        co2: Co2Calculator.Details
    ) {
        val locale = Settings.language(requireContext())
        container.findViewById<TextView>(R.id.tv_co2_value).apply {
            TileValueAnimator.animate(this, Co2Calculator.tonnesText(co2.tonnes, locale))
        }
        container.findViewById<TextView>(R.id.tv_trees_value).apply {
            TileValueAnimator.animate(this, String.format(locale, "%,d", co2.treesPerYear))
        }
    }

    /**
     * Höhenstrahlung-Kachel: links die Dosis des laufenden Jahrs samt Balken
     * und Prozent am Jahresgrenzwert (20 mSv, Obergrenze für fliegendes
     * Personal), rechts die Gesamtsumme über alle Jahre - beide geschätzt aus
     * Flugzeit und Strecke, siehe [RadiationCalculator].
     */
    private fun populateRadiationTile(
        container: ViewGroup,
        radiation: RadiationStats.Summary
    ) {
        val context = requireContext()
        val locale = Settings.language(context)
        container.findViewById<TextView>(R.id.tv_radiation_value).apply {
            TileValueAnimator.animate(this, RadiationCalculator.msvText(radiation.yearUSv, locale))
        }
        // Ohne Tausendertrennzeichen: ein Jahr gruppiert sich nicht.
        container.findViewById<TextView>(R.id.tv_radiation_year).text =
            String.format(locale, "%d", radiation.year)
        container.findViewById<TextView>(R.id.tv_radiation_total_value).apply {
            TileValueAnimator.animate(
                this,
                RadiationCalculator.msvText(radiation.totalUSv, locale)
            )
        }
        // Balken: Anteil der Jahresdosis am Grenzwert von 20 mSv, die
        // Prozentzahl steht im Balken (darunter daneben). Darüber ist die
        // Füllung rot und das Warndreieck erscheint.
        val percent = RadiationCalculator.percentOfAnnualLimit(radiation.yearUSv)
        val overLimit = radiation.yearUSv > RadiationCalculator.ANNUAL_LIMIT_USV
        val bar = container.findViewById<RadiationLimitBar>(R.id.bar_radiation_year)
        bar.percent = percent
        bar.overLimit = overLimit
        bar.contentDescription = context.getString(R.string.radiation_limit_percent, percent)
        val warning = container.findViewById<android.widget.ImageView>(R.id.iv_radiation_warning)
        warning.visibility = if (overLimit) View.VISIBLE else View.GONE
        warning.setOnClickListener { showRadiationLimitWarning() }
    }

    /** Pop-up zum Warndreieck: der Jahresgrenzwert von 20 mSv ist überschritten. */
    private fun showRadiationLimitWarning() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.radiation_limit_title)
            .setMessage(R.string.radiation_limit_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Passagiere-Kachel: links die geschätzte Gesamtzahl über alle Jahre (auf
     * volle Tausender gerundet) samt eingestellter Auslastung, rechts der
     * Durchschnitt pro Flug - gerechnet aus der Sitzplatzanzahl des
     * Flugzeugtyps und der Auslastung, siehe [PassengersCalculator].
     */
    private fun populatePassengersTile(
        container: ViewGroup,
        passengers: PassengersCalculator.Summary
    ) {
        val context = requireContext()
        val locale = Settings.language(context)
        container.findViewById<TextView>(R.id.tv_passengers_value).apply {
            TileValueAnimator.animate(
                this,
                PassengersCalculator.passengersText(
                    PassengersCalculator.roundToThousands(passengers.totalPassengers),
                    locale
                )
            )
        }
        container.findViewById<TextView>(R.id.tv_passengers_load_factor).text = getString(
            R.string.passengers_load_factor,
            Settings.getPassengerLoadFactor(context)
        )
        container.findViewById<TextView>(R.id.tv_passengers_avg_value).apply {
            TileValueAnimator.animate(
                this,
                PassengersCalculator.passengersText(passengers.averagePerFlight, locale)
            )
        }
    }

    private fun populateDistanceTile(
        container: ViewGroup,
        distance: DashboardStats.DistanceDetails
    ) {
        val context = requireContext()
        val none = context.getString(R.string.distance_none)

        val top = DashboardStats.distanceTopValues(context, distance.totalKm)
        container.findViewById<TextView>(R.id.tv_dist_value).apply {
            TileValueAnimator.animate(this, buildValueText(top.first))
        }
        container.findViewById<TextView>(R.id.tv_dist_nm).apply {
            TileValueAnimator.animate(this, buildValueText(top.second))
        }

        container.findViewById<TextView>(R.id.tv_dist_furthest_label).text =
            context.getString(R.string.distance_furthest_label)
        container.findViewById<TextView>(R.id.tv_dist_furthest_route).text =
            distance.furthestRoute ?: none
        container.findViewById<TextView>(R.id.tv_dist_furthest_km).text =
            distance.furthestKm?.let { DashboardStats.kmText(context, it) } ?: none

        container.findViewById<TextView>(R.id.tv_dist_avg_label).text =
            context.getString(R.string.distance_avg_label)
        container.findViewById<TextView>(R.id.tv_dist_avg_route).text = ""
        container.findViewById<TextView>(R.id.tv_dist_avg_km).text =
            distance.avgKm?.let { DashboardStats.kmText(context, it) } ?: none

        container.findViewById<TextView>(R.id.tv_dist_shortest_label).text =
            context.getString(R.string.distance_shortest_label)
        container.findViewById<TextView>(R.id.tv_dist_shortest_route).text =
            distance.shortestRoute ?: none
        container.findViewById<TextView>(R.id.tv_dist_shortest_km).text =
            distance.shortestKm?.let { DashboardStats.kmText(context, it) } ?: none

        container.findViewById<TextView>(R.id.tv_orbits_value).apply {
            TileValueAnimator.animate(this, buildValueText(DashboardStats.Value(DashboardStats.factorText(distance.earthOrbits, Settings.language(context)), "×")))
        }
        container.findViewById<TextView>(R.id.tv_moon_value).apply {
            TileValueAnimator.animate(this, buildValueText(DashboardStats.Value(DashboardStats.factorText(distance.moonTrips, Settings.language(context)), "×")))
        }
        container.findViewById<TextView>(R.id.tv_sun_value).apply {
            TileValueAnimator.animate(this, buildValueText(DashboardStats.Value(DashboardStats.factorText(distance.sunTrips, Settings.language(context)), "×")))
        }
    }

    private fun populateTimeTile(
        container: ViewGroup,
        minutes: Int,
        entries: List<LogbookEntry>
    ) {
        val context = requireContext()
        val none = context.getString(R.string.distance_none)

        // Oberste Zeile: die Gesamtflugzeit in Stunden und Tagen, durch einen
        // Punkt getrennt ("x Stunden · y Tage"); beide Einheiten klein, wie
        // auf der Distanz-Kachel.
        val top = DashboardStats.timeTopValues(context, minutes)
        container.findViewById<TextView>(R.id.tv_time_value).apply {
            TileValueAnimator.animate(this, buildValueText(top.first))
        }
        container.findViewById<TextView>(R.id.tv_time_days).apply {
            TileValueAnimator.animate(this, buildValueText(top.second))
        }

        // Längster, mittlerer und kürzester Flug wie auf der Detailseite
        // "Zeit" (Ground Transfers zählen nicht mit; Balken null ohne
        // erfasste Flugzeit).
        val flying = DashboardStats.flyingEntries(entries)
        val longest = ChartData.longestFlightBar(flying)
        val average = ChartData.averageDurationBar(
            flying,
            context.getString(R.string.time_detail_all_flights)
        ) { count -> context.getString(R.string.time_route_flights, count) }
        val shortest = ChartData.shortestFlightBar(flying)

        container.findViewById<TextView>(R.id.tv_time_longest_route).text =
            longest?.label ?: none
        container.findViewById<TextView>(R.id.tv_time_longest_value).text =
            longest?.countLabel ?: none
        container.findViewById<TextView>(R.id.tv_time_avg_value).text =
            average?.countLabel ?: none
        container.findViewById<TextView>(R.id.tv_time_shortest_route).text =
            shortest?.label ?: none
        container.findViewById<TextView>(R.id.tv_time_shortest_value).text =
            shortest?.countLabel ?: none

        // Die drei kleinen Karten unten zeigen die Gesamtflugzeit in Wochen,
        // Monaten und Jahren - Stunden und Tage stehen in der obersten Zeile,
        // deshalb bleibt diese Reihe fest.
        val cardLabelIds = listOf(
            R.id.tv_time_unit1_label,
            R.id.tv_time_unit2_label,
            R.id.tv_time_unit3_label
        )
        val cardValueIds = listOf(
            R.id.tv_time_unit1_value,
            R.id.tv_time_unit2_value,
            R.id.tv_time_unit3_value
        )
        val cardValues = listOf(
            DashboardStats.weeksValue(context, minutes),
            DashboardStats.timeValue(context, minutes, DashboardStats.TimeUnit.MONTHS),
            DashboardStats.timeValue(context, minutes, DashboardStats.TimeUnit.YEARS),
        )
        cardLabelIds.zip(cardValues).forEach { (labelId, value) ->
            container.findViewById<TextView>(labelId).text = value.unit
        }
        cardValueIds.zip(cardValues).forEach { (valueId, value) ->
            container.findViewById<TextView>(valueId).apply {
                TileValueAnimator.animate(this, buildValueText(value))
            }
        }
    }

    private fun openTimeTile() {
        findNavController().navigate(R.id.action_dashboard_to_time_detail)
    }

    private fun openTile(id: String) {
        when {
            id == "worldmap" -> {
                findNavController().navigate(R.id.action_dashboard_to_world_map)
            }
            id == "co2" -> {
                // Die CO₂-Kachel öffnet die Ausgleichsseite: Karte des
                // Abfluglands mit dem animierten Wald in der nötigen Fläche.
                findNavController().navigate(R.id.action_dashboard_to_co2_offset)
            }
            id == "radiation" -> {
                // Die Höhenstrahlungs-Kachel öffnet die Jahresliste der
                // Dosis, das i im Header führt zur Erklärseite.
                findNavController().navigate(R.id.action_dashboard_to_radiation)
            }
            id == "passengers" -> {
                // Die Passagiere-Kachel öffnet die Jahresliste der
                // geschätzten Passagierzahlen; das Adjust-Symbol im Kopf
                // stellt die Auslastung ein.
                findNavController().navigate(R.id.action_dashboard_to_passengers)
            }
            id == "flights" -> {
                findNavController().navigate(R.id.action_dashboard_to_flights_year)
            }
            id == "distance" -> {
                // Die Distanz-Kachel öffnet die Distanzseite mit den animierten
                // Strecken und den Grafiken zu Erdumrundungen, Mond und Sonne.
                findNavController().navigate(R.id.action_dashboard_to_distance_detail)
            }
            id == "reisebuddy" -> {
                findNavController().navigate(R.id.action_dashboard_to_travel_buddies)
            }
            id == "airlines" -> {
                // Die Kachel "Fluggesellschaften" öffnet die eigene Rangliste
                // mit Suche und Detailseite je Airline.
                findNavController().navigate(R.id.action_dashboard_to_airlines)
            }
            id == "countries" -> {
                // Die Kachel "Länder" öffnet die Liste der besuchten Länder;
                // von dort führt jede Zeile auf die Länderseite mit der Karte.
                findNavController().navigate(R.id.action_dashboard_to_visited_countries)
            }
            id == "achievements" -> {
                // Die Erfolgs-Kachel öffnet die Seite mit dem Fortschritt und
                // dem Zeitstrahl der erreichten Erfolge.
                findNavController().navigate(R.id.action_dashboard_to_achievements)
            }
            ChartData.isBarChart(id) || ChartData.isPieChart(id) -> {
                val bundle = Bundle().apply { putString("tileId", id) }
                findNavController().navigate(R.id.action_dashboard_to_tile_detail, bundle)
            }
            else -> {
                Toast.makeText(
                    requireContext(),
                    R.string.placeholder_no_function,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Fester Aussenrand des Dashboards; das Raster bleibt unverändert. */
        const val GRID_PADDING_DP = 16

        /** Feste Eckenrundung der Kacheln. */
        const val CARD_RADIUS_DP = 16

        /** Platz, den der Hinweis auf geplante Fluege rechts beansprucht. */
    }
}