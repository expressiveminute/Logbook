package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors

/**
 * Segmentierte Auswahl als Regler, wie die Reiseklasse unter "Neuer Flug":
 * eine gerundete, umrandete Leiste mit gleich breiten Zellen; die Markierung
 * (gefüllte Rundung) gleitet zur gewählten Zelle. Eine Zelle zeigt entweder
 * eine Beschriftung oder einen Farbkreis (z. B. für die Akzentfarbe).
 *
 * Der Rahmen liegt als Vordergrund über den Zellen, damit die gleitende
 * Markierung ihn an den äußeren Zellen nicht überdeckt; abgeschnitten wird
 * alles über die Rundung des Umrisses (wie die Karte um die Reiseklasse).
 */
class SegmentedOptionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Eine Zelle des Reglers. */
    class Option(
        /** Beschriftung der Zelle; null = Farbkreis ([swatchColor]). */
        val label: CharSequence? = null,
        /** Füllfarbe des Farbkreises, nur ohne Beschriftung. */
        val swatchColor: Int? = null,
        /** Farbe der Markierung; Standard ist colorPrimaryContainer. */
        val highlightColor: Int? = null,
        /** Textfarbe der Beschriftung auf der Markierung; Standard colorOnPrimaryContainer. */
        val onHighlightColor: Int? = null,
        /** Ansage für TalkBack; Standard ist die Beschriftung. */
        val contentDescription: CharSequence? = null
    )

    private val density get() = resources.displayMetrics.density

    private val highlight = View(context)
    private val cellsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private val cells = mutableListOf<LinearLayout>()
    private val cellLabels = mutableListOf<TextView?>()
    private var options: List<Option> = emptyList()

    private var primaryContainer = 0
    private var onPrimaryContainer = 0
    private var onSurface = 0

    private val cornerRadius get() = dp(CORNER_RADIUS_DP)

    /** Index der gewählten Zelle; -1 bis [setOptions] gesetzt hat. */
    var selectedIndex = -1
        private set

    /** Wird bei einer Nutzerauswahl gerufen, nicht bei programmatischem [select]. */
    var onOptionSelected: ((Int) -> Unit)? = null

    /** Auswahl, die erst nach dem ersten Layout positioniert werden kann. */
    private var pendingSelect = -1

    private var slideAnimator: ValueAnimator? = null

    init {
        primaryContainer = themeColor(com.google.android.material.R.attr.colorPrimaryContainer)
        onPrimaryContainer = themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
        onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface)

        background = GradientDrawable().apply {
            cornerRadius = this@SegmentedOptionView.cornerRadius.toFloat()
            setColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        foreground = GradientDrawable().apply {
            cornerRadius = this@SegmentedOptionView.cornerRadius.toFloat()
            setStroke(dp(1), themeColor(com.google.android.material.R.attr.colorOutlineVariant))
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius.toFloat())
            }
        }
        clipToOutline = true

        addView(highlight, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cellsRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        highlight.visibility = GONE
    }

    /** Baut die Zellen auf und wählt [selected] aus (ohne Animation). */
    fun setOptions(options: List<Option>, selected: Int = 0) {
        this.options = options
        cellsRow.removeAllViews()
        cells.clear()
        cellLabels.clear()
        if (options.isEmpty()) {
            selectedIndex = -1
            highlight.visibility = GONE
            return
        }
        options.forEachIndexed { index, option ->
            val label = option.label?.let { text ->
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    textSize = LABEL_TEXT_SIZE_SP
                    paint.isFakeBoldText = true
                    setTextColor(onSurface)
                    this.text = text
                    setAutoSizeTextTypeUniformWithConfiguration(
                        LABEL_TEXT_MIN_SP, LABEL_TEXT_MAX_SP, 1, TypedValue.COMPLEX_UNIT_SP
                    )
                }
            }
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = option.contentDescription ?: option.label
                if (label != null) {
                    addView(
                        label,
                        LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    )
                } else {
                    addView(swatch(option), LinearLayout.LayoutParams(dp(SWATCH_DP), dp(SWATCH_DP)))
                }
                setOnClickListener {
                    if (index != selectedIndex) {
                        select(index)
                        onOptionSelected?.invoke(index)
                    }
                }
            }
            cells.add(cell)
            cellLabels.add(label)
            cellsRow.addView(cell, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        selectedIndex = -1
        select(selected.coerceIn(options.indices), animate = false)
    }

    /** Wählt eine Zelle aus; [animate] gleitet die Markierung hin (Nutzereingabe). */
    fun select(index: Int, animate: Boolean = true) {
        if (index !in options.indices) return
        if (index == selectedIndex && width > 0) return
        selectedIndex = index
        updateCellColors()
        if (width == 0) {
            // Die Zellen haben noch keine Breite; nach dem ersten Layout nachziehen.
            pendingSelect = index
        } else {
            positionHighlight(animate)
        }
        if (animate) {
            options.getOrNull(index)?.let {
                announceForAccessibility(it.contentDescription ?: it.label)
            }
        }
    }

    /** Farbkreis für eine Zelle ohne Beschriftung (z. B. Akzentfarbe). */
    private fun swatch(option: Option): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(option.swatchColor ?: 0)
        }
    }

    /** Beschriftungsfarben an die Auswahl anpassen. */
    private fun updateCellColors() {
        cellLabels.forEachIndexed { i, label ->
            label ?: return@forEachIndexed
            val option = options.getOrNull(i) ?: return@forEachIndexed
            label.setTextColor(
                if (i == selectedIndex) option.onHighlightColor ?: onPrimaryContainer else onSurface
            )
        }
    }

    /** Markierung auf die gewählte Zelle setzen; Breite und Position richten
     *  sich nach der Zelle, deren Masse erst nach dem Layout feststehen. */
    private fun positionHighlight(animate: Boolean) {
        val index = selectedIndex
        val cell = cells.getOrNull(index) ?: return
        val lp = highlight.layoutParams as LayoutParams
        if (lp.width != cell.width) {
            lp.width = cell.width
            highlight.layoutParams = lp
        }
        highlight.background = GradientDrawable().apply {
            cornerRadius = this@SegmentedOptionView.cornerRadius.toFloat()
            setColor(options.getOrNull(index)?.highlightColor ?: primaryContainer)
        }
        highlight.visibility = VISIBLE
        val targetX = cell.left.toFloat()
        slideAnimator?.cancel()
        if (animate && highlight.translationX != targetX) {
            slideAnimator = ValueAnimator.ofFloat(highlight.translationX, targetX).apply {
                duration = SLIDE_MS
                addUpdateListener { highlight.translationX = it.animatedValue as Float }
                start()
            }
        } else {
            highlight.translationX = targetX
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(MeasureSpec.getSize(heightMeasureSpec), dp(CELL_HEIGHT_DP))
            else -> dp(CELL_HEIGHT_DP)
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val index = if (pendingSelect >= 0) {
            val pending = pendingSelect
            pendingSelect = -1
            pending
        } else {
            selectedIndex
        }
        // Nach Größenänderungen (z. B. Drehung) die Markierung nachziehen -
        // nicht während eines laufenden Slides, der würde sonst abbrechen.
        if (index >= 0 && width > 0 && slideAnimator?.isRunning != true) {
            positionHighlight(animate = false)
        }
    }

    private fun themeColor(attr: Int): Int = MaterialColors.getColor(this, attr)

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        /** Höhe der Leiste wie die Reiseklasse unter "Neuer Flug". */
        const val CELL_HEIGHT_DP = 52

        /** Durchmesser des Farbkreises in einer Zelle ohne Beschriftung. */
        const val SWATCH_DP = 32

        /** Rundung von Leiste und Markierung. */
        const val CORNER_RADIUS_DP = 16

        const val LABEL_TEXT_SIZE_SP = 14f
        const val LABEL_TEXT_MIN_SP = 9
        const val LABEL_TEXT_MAX_SP = 14

        /** Dauer des Gleitens der Markierung. */
        const val SLIDE_MS = 250L
    }
}
