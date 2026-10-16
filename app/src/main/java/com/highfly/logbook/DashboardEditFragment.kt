package com.highfly.logbook

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentDashboardEditBinding
import com.highfly.logbook.databinding.ItemDashboardTileBinding
import kotlin.math.roundToInt

/**
 * Eigene Seite zum Bearbeiten des Dashboards, erreichbar über
 * Einstellungen → Customization → "Dashboard bearbeiten".
 *
 * Alle Kacheln werden hier im Raster gezeigt. Ein langer Druck auf eine Kachel
 * schaltet den Bearbeiten-Modus ein (die Kacheln wackeln) und zieht die Kachel
 * sofort mit; beim Ziehen weichen die anderen Kacheln schon aus, und am oberen
 * bzw. unteren Rand scrollt die Seite mit. Im
 * Bearbeiten-Modus blendet jede Kachel ein X zum Entfernen ein, ein Tippen auf
 * die Kachel öffnet die Grössenwahl (1–3 Spalten), "+ Kachel hinzufügen" holt
 * ausgeblendete Kacheln zurück. Der Schieber unten skaliert alle Kacheln
 * gemeinsam. Jede Änderung wird sofort gespeichert.
 */
class DashboardEditFragment : Fragment() {

    private var _binding: FragmentDashboardEditBinding? = null
    private val binding get() = _binding!!

    private val flat = mutableListOf<String>()
    private val spans = mutableMapOf<String, Int>()
    private var scale = DashboardPrefs.DEFAULT_SCALE

    private var editMode = false
    private var draggingId: String? = null
    private var lastReorderAt = 0L
    private var renderScheduled = false

    /** Automatisches Scrollen, wenn beim Ziehen der Rand erreicht wird. */
    private var autoScrollDir = 0
    private var autoScrollRunning = false
    private var lastDragX = 0f
    private var lastDragY = 0f
    private val autoScrollStep = object : Runnable {
        override fun run() {
            val scroll = _binding?.editScroll
            if (scroll == null || autoScrollDir == 0) {
                autoScrollRunning = false
                return
            }
            scroll.scrollBy(0, autoScrollDir * dp(EDGE_SCROLL_STEP_DP))
            // Nach dem Scrollen liegt eine andere Kachel unter dem Finger.
            hoverOrReorder(lastDragX, lastDragY, force = false)
            scroll.postOnAnimation(this)
        }
    }

    private val wobblers = mutableListOf<ObjectAnimator>()

    private val role: Role get() = Settings.getRole(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnDashboardEditBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.btnDashboardEditDone.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.btnDashboardEditAdd.setOnClickListener { showAddDialog() }
        binding.btnDashboardEditReset.setOnClickListener { resetToDefault() }
        binding.btnDashboardEditHelp.setOnClickListener { showHelp() }

        // Der Rahmen zeichnet das Raster neu, während gezogen wird - die
        // Kachelansichten werden dabei ausgetauscht. Deshalb hängt der
        // Zuhörer am Rahmen und sucht die Kachel unter dem Finger selbst
        // heraus, statt an einzelnen (und damit vergänglichen) Kacheln.
        binding.root.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription.hasMimeType(TILE_MIME)
                DragEvent.ACTION_DRAG_LOCATION -> {
                    lastDragX = event.x
                    lastDragY = event.y
                    updateAutoScroll(event.y)
                    hoverOrReorder(event.x, event.y, force = false)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    hoverOrReorder(event.x, event.y, force = true)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    draggingId = null
                    stopAutoScroll()
                    scheduleGridRender()
                    true
                }
                else -> true
            }
        }

        loadState()
        setupSlider()
        renderGrid()
    }

    private fun loadState() {
        flat.clear()
        flat.addAll(DashboardPrefs.readFlat(requireContext()))
        spans.clear()
        spans.putAll(DashboardPrefs.spansWithDefaults(requireContext()))
        scale = DashboardPrefs.readScale(requireContext())
    }

    private fun setupSlider() {
        // Ganze Prozent statt 0.85..1.00 in Schritten von 0.01: nur so ergibt
        // (valueTo - valueFrom) / stepSize genau 15 Schritte und Material
        // zeichnet alle 16 Rastpunkte auf den Werten (siehe [DashboardPrefs]).
        binding.scaleSlider.valueFrom = DashboardPrefs.MIN_PERCENT.toFloat()
        binding.scaleSlider.valueTo = DashboardPrefs.MAX_PERCENT.toFloat()
        binding.scaleSlider.stepSize = 1f
        binding.scaleSlider.value = DashboardPrefs.scaleToPercent(scale).toFloat()
        binding.scaleSlider.addOnChangeListener { _, value, _ ->
            val percent = value.roundToInt().coerceIn(
                DashboardPrefs.MIN_PERCENT, DashboardPrefs.MAX_PERCENT
            )
            scale = DashboardPrefs.percentToScale(percent)
            DashboardPrefs.writeScale(requireContext(), scale)
            binding.tvScaleValue.text = percentText(percent)
            renderGrid()
        }
        binding.tvScaleValue.text = percentText(DashboardPrefs.scaleToPercent(scale))
    }

    private fun percentText(percent: Int): String =
        String.format(Settings.language(requireContext()), "%d %%", percent)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun tileHeight(tile: DashboardPrefs.Tile): Int =
        dp((tile.heightDp * DashboardTileScaling.fontScale(binding.root)).roundToInt())

    // --- Darstellung ------------------------------------------------------

    private fun renderGrid() {
        cancelWobble()
        val grid = binding.editGrid
        grid.removeAllViews()

        // Das Raster bleibt fest; die Kachel-Grösse verkleinert nur die Kacheln
        // in ihrer Zelle (siehe [buildTile]).
        binding.editContent.setPadding(
            dp(GRID_PADDING_DP), dp(GRID_PADDING_DP),
            dp(GRID_PADDING_DP), dp(GRID_PADDING_DP)
        )

        val packedRows = DashboardPrefs.packRows(flat, spans)
        // Kacheln, die zur Rolle nicht passen, fehlen auch hier ganz - genau
        // wie auf dem Dashboard.
        val visibleRows = packedRows.map { ids ->
            ids.filter { DashboardPrefs.isTileAvailable(it, role) }
        }
        val hasAny = visibleRows.any { it.isNotEmpty() }
        binding.tvEditEmpty.visibility = if (hasAny) View.GONE else View.VISIBLE
        grid.visibility = if (hasAny) View.VISIBLE else View.GONE

        if (!hasAny) return

        visibleRows.forEachIndexed { rowIndex, rowIds ->
            if (rowIds.isEmpty()) return@forEachIndexed
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            var column = 0
            rowIds.forEach { tileId ->
                val card = buildTile(tileId, rowIndex, column)
                column++
                val span = DashboardPrefs.spanOf(spans, tileId)
                val params = LinearLayout.LayoutParams(0, tileHeight(DashboardPrefs.tileById(tileId)), span.toFloat())
                row.addView(card, params)
            }
            // Fehlende Spalten bleiben leer: die Kacheln behalten ihre Breite,
            // statt sich über die ganze Zeile zu strecken.
            val remaining = DashboardPrefs.SPAN_FULL -
                rowIds.sumOf { DashboardPrefs.spanOf(spans, it) }
            if (remaining > 0) {
                row.addView(
                    View(requireContext()),
                    LinearLayout.LayoutParams(0, 1, remaining.toFloat())
                )
            }
            grid.addView(row)
        }

        // Nach dem Vermessen: die 1-Spalten-Kacheln werden quadratisch und die
        // Aussenkanten der Breite werden spaltenweise angeglichen.
        grid.doOnLayout {
            squareSmallTiles()
            applyHorizontalScale()
        }

        if (editMode) {
            setRemoveButtonsVisible(true)
            startWobble()
        }
    }

    private fun buildTile(tileId: String, rowIndex: Int, column: Int): MaterialCardView {
        val tile = DashboardPrefs.tileById(tileId)
        val tileBinding = ItemDashboardTileBinding.inflate(layoutInflater, binding.editGrid, false)
        tileBinding.ivTileIcon.setImageResource(tile.iconRes)
        tileBinding.tvTileName.text = getString(tile.nameRes)
        tileBinding.tvTileValue.visibility = View.GONE
        tileBinding.tvTileUnit.visibility = View.GONE
        tileBinding.tilePieChart.visibility = View.GONE
        tileBinding.tileWorldmapPreview.visibility = View.GONE
        tileBinding.llTileTopRight.visibility = View.GONE

        val card = tileBinding.tileCard
        card.tag = tileId
        card.radius = dp(CARD_RADIUS_DP).toFloat()
        DashboardTileColors.apply(card, DashboardPrefs.TilePosition(rowIndex, column))
        if (tileId == draggingId) highlightDragged(card)
        // Vorläufige Verkleinerung; die Aussenkanten der Breite werden nach dem
        // Vermessen spaltenweise angeglichen (siehe [applyHorizontalScale]).
        card.scaleX = scale
        card.scaleY = scale

        // X zum Entfernen, nur im Bearbeiten-Modus sichtbar.
        val removeButton = ImageButton(requireContext()).apply {
            tag = REMOVE_TAG
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(android.R.color.transparent)
            imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurface)
            )
            contentDescription = getString(R.string.dashboard_remove)
            setPadding(0, 0, 0, 0)
            visibility = if (editMode) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.END or Gravity.TOP).apply {
                rightMargin = dp(4)
                topMargin = dp(4)
            }
        }
        removeButton.setOnClickListener { removeTile(tileId) }
        card.addView(removeButton)

        // Tippen öffnet die Grössenwahl, langer Druck beginnt das Verschieben.
        card.setOnClickListener { showSizeDialog(tileId) }
        card.isLongClickable = true
        card.setOnLongClickListener { view ->
            if (!editMode) activateEditMode()
            draggingId = tileId
            highlightDragged(card)
            view.startDragAndDrop(
                tileClip(tileId),
                View.DragShadowBuilder(view),
                null,
                0
            )
            true
        }
        return card
    }

    /**
     * Gibt den kleinen (ursprünglich einspaltigen) Kacheln die Höhe einer
     * Spalte, wie auf dem Dashboard. Dadurch bleibt die Höhe erhalten, wenn
     * eine Kachel verbreitert wird.
     */
    private fun squareSmallTiles() {
        val grid = binding.editGrid
        if (grid.width == 0) return
        val column = grid.width / 3
        for (i in 0 until grid.childCount) {
            val row = grid.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val card = row.getChildAt(j) as? MaterialCardView ?: continue
                val tileId = card.tag as? String ?: continue
                if (DashboardPrefs.defaultSpan(tileId) != 1) continue
                val target = maxOf(column, tileHeight(DashboardPrefs.tileById(tileId)))
                val params = card.layoutParams
                if (params.height != target) {
                    params.height = target
                    card.layoutParams = params
                }
            }
        }
    }

    /**
     * Gleicht die Aussenbreite aller Kacheln an, wie auf dem Dashboard: jede
     * Kachel rückt an jeder Seite um eine halbe Spalte mal (1 - [scale]) nach
     * innen. Dadurch ist eine 3-Spalten-Kachel genau so breit wie drei
     * 1-Spalten-Kacheln samt Zwischenräumen.
     */
    private fun applyHorizontalScale() {
        val grid = _binding?.editGrid ?: return
        if (grid.width == 0) return
        val column = grid.width / 3f
        eachCard { card ->
            if (card.width == 0) return@eachCard
            val width = card.width.toFloat()
            card.scaleX = DashboardPrefs.horizontalScale(width, column, scale)
            // Der Inhalt bekommt denselben Gegenfaktor wie auf dem Dashboard,
            // damit Zahlen und Buchstaben nicht verzerrt werden.
            card.getChildAt(0)?.let { content ->
                content.scaleX =
                    DashboardPrefs.contentCompensation(width, column, scale).coerceIn(0f, 1f)
            }
        }
    }

    // --- Bearbeiten-Modus -------------------------------------------------

    private fun activateEditMode() {
        editMode = true
        setRemoveButtonsVisible(true)
        startWobble()
    }

    private fun setRemoveButtonsVisible(visible: Boolean) {
        eachCard { card ->
            card.findViewWithTag<ImageButton>(REMOVE_TAG)?.visibility =
                if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun startWobble() {
        cancelWobble()
        var index = 0
        eachCard { card ->
            val animator = ObjectAnimator.ofFloat(card, View.ROTATION, -1.5f, 1.5f).apply {
                duration = 220
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                startDelay = (index % 4) * 45L
            }
            animator.start()
            wobblers.add(animator)
            index++
        }
    }

    private fun cancelWobble() {
        wobblers.forEach { it.cancel() }
        wobblers.clear()
        eachCard { it.rotation = 0f }
    }

    private fun eachCard(action: (MaterialCardView) -> Unit) {
        val grid = _binding?.editGrid ?: return
        for (i in 0 until grid.childCount) {
            val row = grid.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                (row.getChildAt(j) as? MaterialCardView)?.let(action)
            }
        }
    }

    // --- Verschieben ------------------------------------------------------

    private fun tileClip(tileId: String): ClipData =
        ClipData(ClipDescription("tile", arrayOf(TILE_MIME)), ClipData.Item(tileId))

    /**
     * Sucht die Kachel unter dem Finger (Koordinaten relativ zum Rahmen) und
     * schiebt die gezogene Kachel beim Vorbeiziehen an ihre Stelle. Wird nur
     * neu gezeichnet, wenn sich die Reihenfolge wirklich ändert.
     */
    private fun hoverOrReorder(x: Float, y: Float, force: Boolean) {
        val dragged = draggingId ?: return
        val card = cardAt(x, y) ?: return
        val targetId = card.tag as? String ?: return
        if (targetId == dragged) return
        reorderOnHover(targetId, placeBefore = x < centerXInRoot(card), force = force)
    }

    /** Die Kachel, deren Fläche den Punkt [x], [y] (relativ zum Rahmen) enthält. */
    private fun cardAt(x: Float, y: Float): MaterialCardView? {
        val rootLoc = IntArray(2)
        binding.root.getLocationOnScreen(rootLoc)
        var found: MaterialCardView? = null
        eachCard { card ->
            if (found != null) return@eachCard
            val loc = IntArray(2)
            card.getLocationOnScreen(loc)
            val left = loc[0] - rootLoc[0]
            val top = loc[1] - rootLoc[1]
            if (x >= left && x < left + card.width &&
                y >= top && y < top + card.height
            ) {
                found = card
            }
        }
        return found
    }

    private fun centerXInRoot(view: View): Float {
        val rootLoc = IntArray(2)
        val viewLoc = IntArray(2)
        binding.root.getLocationOnScreen(rootLoc)
        view.getLocationOnScreen(viewLoc)
        return (viewLoc[0] - rootLoc[0]) + view.width / 2f
    }

    /**
     * Schiebt [targetId] beim Vorbeiziehen an die Stelle der gezogenen Kachel.
     * Weicht die Reihenfolge aus, wird das Neuzeichnen nur angestossen und
     * nicht sofort ausgeführt: Ansichten dürfen nicht mitten in der
     * Ereignisverarbeitung des Zuges entfernt werden.
     */
    private fun reorderOnHover(targetId: String, placeBefore: Boolean, force: Boolean) {
        val dragged = draggingId ?: return
        if (dragged == targetId) return
        val now = System.currentTimeMillis()
        if (!force && now - lastReorderAt < REORDER_THROTTLE_MS) return

        val list = flat.toMutableList()
        val from = list.indexOf(dragged)
        if (from < 0) return
        list.removeAt(from)
        var to = list.indexOf(targetId)
        if (to < 0) {
            to = list.size
        } else if (!placeBefore) {
            to += 1
        }
        list.add(to.coerceIn(0, list.size), dragged)
        if (list == flat) return

        lastReorderAt = now
        flat.clear()
        flat.addAll(list)
        DashboardPrefs.writeFlat(requireContext(), flat, spans)
        scheduleGridRender()
    }

    /** Neuzeichnen erst nach dem laufenden Ereignis, siehe [reorderOnHover]. */
    private fun scheduleGridRender() {
        if (renderScheduled) return
        renderScheduled = true
        binding.editGrid.post {
            renderScheduled = false
            if (_binding != null) renderGrid()
        }
    }

    /**
     * Scrollt die Seite, wenn beim Ziehen der obere oder untere Rand erreicht
     * wird. Die Zone liegt innerhalb des sichtbaren Bereichs der Liste.
     */
    private fun updateAutoScroll(y: Float) {
        val scroll = binding.editScroll
        val rootLoc = IntArray(2)
        val scrollLoc = IntArray(2)
        binding.root.getLocationOnScreen(rootLoc)
        scroll.getLocationOnScreen(scrollLoc)
        val top = scrollLoc[1] - rootLoc[1]
        val bottom = top + scroll.height
        val zone = dp(EDGE_SCROLL_ZONE_DP)
        val dir = when {
            y < top + zone -> -1
            y > bottom - zone -> 1
            else -> 0
        }
        if (dir == autoScrollDir) return
        autoScrollDir = dir
        if (dir != 0 && !autoScrollRunning) {
            autoScrollRunning = true
            scroll.postOnAnimation(autoScrollStep)
        }
    }

    private fun stopAutoScroll() {
        autoScrollDir = 0
        autoScrollRunning = false
        _binding?.editScroll?.removeCallbacks(autoScrollStep)
    }

    // --- Grösse, Entfernen, Hinzufügen -----------------------------------

    /** Zeigt die Kurzanleitung als Pop-up, ausgelöst über das "?" im Kopf. */
    private fun showHelp() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dashboard_edit_help_title)
            .setMessage(R.string.dashboard_edit_hint)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showSizeDialog(tileId: String) {
        val tile = DashboardPrefs.tileById(tileId)
        val options = listOf(
            1 to R.string.dashboard_size_one,
            2 to R.string.dashboard_size_two,
            DashboardPrefs.SPAN_FULL to R.string.dashboard_size_full
        )
        val labels = options.map { getString(it.second) }.toTypedArray()
        val current = spans[tileId] ?: DashboardPrefs.defaultSpan(tileId)
        val checked = options.indexOfFirst { it.first == current }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dashboard_size_title, getString(tile.nameRes)))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                setSpan(tileId, options[which].first)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setSpan(tileId: String, span: Int) {
        spans[tileId] = span.coerceIn(1, DashboardPrefs.SPAN_FULL)
        DashboardPrefs.writeSpans(requireContext(), spans)
        DashboardPrefs.writeFlat(requireContext(), flat, spans)
        renderGrid()
    }

    private fun removeTile(tileId: String) {
        flat.remove(tileId)
        DashboardPrefs.writeFlat(requireContext(), flat, spans)
        renderGrid()
    }

    private fun showAddDialog() {        val used = flat.toSet()
        val available = DashboardPrefs.CATALOG.filter {
            it.id !in used && DashboardPrefs.isTileAvailable(it.id, role)
        }
        if (available.isEmpty()) return
        val names = available.map { getString(it.nameRes) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dashboard_add_tile)
            .setItems(names) { _, which ->
                flat.add(available[which].id)
                DashboardPrefs.writeFlat(requireContext(), flat, spans)
                renderGrid()
            }
            .show()
    }

    private fun resetToDefault() {
        DashboardPrefs.resetToDefault(requireContext())
        loadState()
        // Der Schieber muss die zurückgesetzte Grösse zeigen, ohne erneut zu
        // schreiben.
        binding.scaleSlider.clearOnChangeListeners()
        binding.scaleSlider.value = DashboardPrefs.scaleToPercent(scale).toFloat()
        setupSlider()
        editMode = false
        renderGrid()
    }

    // --- Hervorhebung -----------------------------------------------------

    /** Hebt die gerade gezogene Kachel mit einem Rahmen hervor. */
    private fun highlightDragged(card: MaterialCardView) {
        card.strokeWidth = dp(3)
        card.strokeColor = MaterialColors.getColor(
            card, com.google.android.material.R.attr.colorPrimary
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopAutoScroll()
        cancelWobble()
        _binding = null
    }

    private companion object {
        const val TILE_MIME = "application/x-logbook-tile"
        const val REMOVE_TAG = "dashboard_remove_button"
        const val CARD_RADIUS_DP = 16
        const val GRID_PADDING_DP = 16
        const val REORDER_THROTTLE_MS = 120L

        /** Zone am Rand, ab der beim Ziehen mitgescrollt wird. */
        const val EDGE_SCROLL_ZONE_DP = 56

        /** Schrittweite des automatischen Scrollens, pro Bild. */
        const val EDGE_SCROLL_STEP_DP = 8
    }
}
