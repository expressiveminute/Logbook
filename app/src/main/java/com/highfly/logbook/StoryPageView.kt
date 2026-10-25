package com.highfly.logbook

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

/**
 * Eine einzelne Story-Seite: Icon, Titel und je nach Typ eine große
 * Flip-Uhr-Zahl ([FlipNumberView]), ein großer Text oder eine Rangliste.
 * Beim Auftritt ([playEntrance]) läuft eine kleine Choreografie ab: das Icon
 * poppt auf, der Titel gleitet hoch, die Inhaltselemente erscheinen
 * gestaffelt - die Zahlen rollen per Flip-Uhr hoch, die Zeilen der
 * Rangliste flippen ihre Werte der Reihe nach.
 *
 * Sind Systemanimationen abgeschaltet, erscheint die Seite sofort im
 * Endzustand, ohne Animationen - wie überall in der App.
 */
class StoryPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())

    /** Ob Animationen laufen dürfen (Systemeinstellung, beim Anhängen gelesen). */
    private var animationsEnabled = true

    private lateinit var iconView: ImageView
    private lateinit var titleView: TextView
    private lateinit var contentLayout: LinearLayout

    /** Hero-Zahl: View, Zielwert und Formatierer für den Count-up. */
    private var heroNumber: Triple<FlipNumberView, Double, (Double) -> String>? = null

    /** Zweite, kleinere Hero-Zahl (z. B. "Dieses Jahr"). */
    private var heroSubNumber: Triple<FlipNumberView, Double, (Double) -> String>? = null

    /** Optionale Route-Karte unter einem HeroText. */
    private var routeMap: RouteMiniMapView? = null

    /** Zeilen-Werte: View, anzuzeigender Text, optional Zielwert für Count-up. */
    private val rowValues = mutableListOf<RowValue>()

    /** Ein Zeilen-Wert der Rangliste mit optionalem Count-up. */
    private data class RowValue(
        val view: FlipNumberView,
        val text: String,
        val target: Double? = null,
        val format: ((Double) -> String)? = null
    )

    init {
        val accent = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
            ContextCompat.getColor(context, R.color.story_text)
        )
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            val pad = dp(24)
            setPadding(pad, 0, pad, 0)
        }
        addView(root)

        iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(accent)
        }
        root.addView(iconView)

        titleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            gravity = Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
            setTextColor(ContextCompat.getColor(context, R.color.story_text))
        }
        root.addView(titleView)

        contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        }
        root.addView(contentLayout)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animationsEnabled = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        } catch (e: Settings.SettingNotFoundException) {
            true
        }
    }

    override fun onDetachedFromWindow() {
        routeMap?.cancelRouteAnimation()
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    /** Baut die Seite auf; [maxContentWidthPx] begrenzt die Hero-Zahl in der Breite. */
    fun bind(page: StoryPage, maxContentWidthPx: Int) {
        iconView.setImageResource(page.iconRes)
        titleView.setText(page.titleRes)
        when (page) {
            is StoryPage.HeroNumber -> buildHeroNumber(page, maxContentWidthPx)
            is StoryPage.HeroText -> buildHeroText(page)
            is StoryPage.Ranking -> buildRanking(page)
        }
        // Startzustand der Choreografie: alles unsichtbar/versetzt.
        iconView.alpha = 0f
        iconView.scaleX = 0.5f
        iconView.scaleY = 0.5f
        titleView.alpha = 0f
        titleView.translationY = dp(24).toFloat()
        for (i in 0 until contentLayout.childCount) {
            val child = contentLayout.getChildAt(i)
            child.alpha = 0f
            child.translationY = dp(16).toFloat()
        }
    }

    /** Spielt die Auftritts-Choreografie ab (einmalig pro Seite). */
    fun playEntrance() {
        if (!animationsEnabled) {
            applyFinalState()
            return
        }
        iconView.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(ICON_MS)
            .setInterpolator(OvershootInterpolator())
            .start()
        titleView.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(TITLE_DELAY_MS)
            .setDuration(TITLE_MS)
            .start()
        for (i in 0 until contentLayout.childCount) {
            contentLayout.getChildAt(i).animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(CONTENT_DELAY_MS + i * CONTENT_STAGGER_MS)
                .setDuration(CONTENT_MS)
                .start()
        }
        // Die Zahlen rollen hoch, sobald die Seite steht.
        heroNumber?.let { (view, target, format) ->
            handler.postDelayed({ view.countTo(target, format) }, NUMBER_DELAY_MS)
        }
        heroSubNumber?.let { (view, target, format) ->
            handler.postDelayed({ view.countTo(target, format) }, NUMBER_DELAY_MS + 150)
        }
        // Die Route-Karte baut die Strecke animiert auf (nach den Texten).
        routeMap?.let { map ->
            handler.postDelayed(
                { map.playRouteAnimation() },
                NUMBER_DELAY_MS + 400
            )
        }
        // Die Zeilen-Werte rollen hoch (Count-up), sobald die Seite steht;
        // ohne Zielwert flippen sie nur auf ihren Endwert.
        rowValues.forEachIndexed { index, row ->
            handler.postDelayed({
                if (row.target != null && row.format != null) {
                    row.view.countTo(row.target, row.format)
                } else {
                    row.view.setText(row.text, animate = true)
                }
            }, NUMBER_DELAY_MS + index * ROW_STAGGER_MS)
        }
    }

    /** Endzustand ohne Animation (Systemanimationen abgeschaltet). */
    private fun applyFinalState() {
        iconView.alpha = 1f
        iconView.scaleX = 1f
        iconView.scaleY = 1f
        titleView.alpha = 1f
        titleView.translationY = 0f
        for (i in 0 until contentLayout.childCount) {
            val child = contentLayout.getChildAt(i)
            child.alpha = 1f
            child.translationY = 0f
        }
        heroNumber?.let { (view, target, format) ->
            view.setText(format(target), animate = false)
        }
        heroSubNumber?.let { (view, target, format) ->
            view.setText(format(target), animate = false)
        }
        rowValues.forEach { row ->
            row.view.setText(
                if (row.target != null && row.format != null) row.format(row.target) else row.text,
                animate = false
            )
        }
    }

    /** Baut die große Zahl (mit optionaler zweiter Zahl und Fußzeile). */
    private fun buildHeroNumber(page: StoryPage.HeroNumber, maxContentWidthPx: Int) {
        contentLayout.addView(
            labelView(page.labelRes).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        )

        // Die Kartenhöhe richtet sich nach der breitesten Zahl: je mehr
        // Zeichen, desto kleiner die Karten, damit die Zahl immer passt.
        val mainText = page.format(page.target)
        val subText = page.subTarget?.let { page.subFormat?.invoke(it) }
        val maxChars = maxOf(mainText.length, subText?.length ?: 0, 1)
        val heroHeight = heroDigitHeight(maxChars, maxContentWidthPx)

        val numberRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // Die Zeile ist so breit wie der Inhalt (match_parent vom Eltern-
            // LinearLayout), die Zahl samt Einheit zentriert sie.
            gravity = Gravity.CENTER
        }
        val numberView = FlipNumberView(context).apply {
            setDigitHeightPx(heroHeight)
        }
        numberRow.addView(numberView)
        page.unitRes?.let { unitRes ->
            numberRow.addView(unitView(unitRes).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            })
        }
        contentLayout.addView(numberRow)
        heroNumber = Triple(numberView, page.target, page.format)

        if (page.subTarget != null && page.subFormat != null) {
            val subBlock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(20) }
            }
            page.subLabelRes?.let { labelRes ->
                subBlock.addView(
                    labelView(labelRes).apply {
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                )
            }
            val subRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val subNumberView = FlipNumberView(context).apply {
                setDigitHeightPx((heroHeight * SUB_FACTOR).toInt().coerceAtLeast(dp(28)))
            }
            subRow.addView(subNumberView)
            page.unitRes?.let { unitRes ->
                subRow.addView(unitView(unitRes).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
                })
            }
            subBlock.addView(subRow)
            contentLayout.addView(subBlock)
            heroSubNumber = Triple(subNumberView, page.subTarget, page.subFormat)
        }

        page.footnote?.let { footnote ->
            contentLayout.addView(
                TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(20) }
                    gravity = Gravity.CENTER
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(ContextCompat.getColor(context, R.color.story_text_variant))
                    text = footnote
                }
            )
        }
    }

    /** Baut den großen Text (z. B. die Stammstrecke). */
    private fun buildHeroText(page: StoryPage.HeroText) {
        contentLayout.addView(
            TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_DisplaySmall)
                setTextColor(ContextCompat.getColor(context, R.color.story_text))
                text = page.text
            }
        )
        page.subText?.let { subText ->
            contentLayout.addView(
                TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                    gravity = Gravity.CENTER
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    setTextColor(ContextCompat.getColor(context, R.color.story_text_variant))
                    text = subText
                }
            )
        }

        // Optionale Karte mit der Route (wie bei Flight Routes): ein
        // Kartenausschnitt, der die Strecke animiert aufbaut.
        page.route?.let { routeData ->
            val mapView = RouteMiniMapView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(180)
                ).apply { topMargin = dp(20) }
                setRoute(
                    AirportData.GeoLocation(routeData.fromLat, routeData.fromLon),
                    AirportData.GeoLocation(routeData.toLat, routeData.toLon),
                    routeData.fromCode,
                    routeData.toCode
                )
            }
            contentLayout.addView(mapView)
            routeMap = mapView
        }
    }

    /** Baut die Rangliste mit ihren Gruppen und Zeilen. */
    private fun buildRanking(page: StoryPage.Ranking) {
        page.groups.forEachIndexed { groupIndex, group ->
            if (group.labelRes != null) {
                contentLayout.addView(
                    labelView(group.labelRes).apply {
                        gravity = Gravity.START
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(if (groupIndex == 0) 0 else 16)
                            bottomMargin = dp(8)
                        }
                    }
                )
            }
            group.rows.forEachIndexed { rowIndex, row ->
                // Der Rang zählt innerhalb der Gruppe von 1.
                contentLayout.addView(buildRow(row, rowIndex + 1))
            }
        }
    }

    /** Eine Zeile der Rangliste: Rang, Flagge, Label und Wert als Flip-Uhr. */
    private fun buildRow(row: StoryPage.Ranking.Row, rank: Int): View {
        val rowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ContextCompat.getColor(context, R.color.story_row_bg))
            }
            val padH = dp(12)
            val padV = dp(10)
            setPadding(padH, padV, padH, padV)
        }

        // Rang als kleine Scheibe.
        rowLayout.addView(
            TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(10)
                }
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(context, R.color.story_progress_track))
                }
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(ContextCompat.getColor(context, R.color.story_text))
                text = String.format(com.highfly.logbook.Settings.language(context), "%d", rank)
            }
        )

        // Flagge (bei Ländern).
        row.flag?.let { flag ->
            rowLayout.addView(
                TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    text = flag
                }
            )
        }

        // Label mit optionaler Unterzeile.
        val labelColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        labelColumn.addView(
            TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(ContextCompat.getColor(context, R.color.story_text))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = row.label
            }
        )
        row.subLabel?.let { subLabel ->
            labelColumn.addView(
                TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(ContextCompat.getColor(context, R.color.story_text_variant))
                    text = subLabel
                }
            )
        }
        rowLayout.addView(labelColumn)

        // Wert als kleine Flip-Uhr, noch leer - flippen tut er beim Auftritt.
        val valueView = FlipNumberView(context).apply {
            setDigitHeightPx(dp(26))
        }
        rowValues += RowValue(
            view = valueView,
            text = row.value,
            target = row.valueTarget,
            format = row.valueFormat
        )
        rowLayout.addView(valueView)
        row.suffix?.let { suffix ->
            rowLayout.addView(
                TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(4) }
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(ContextCompat.getColor(context, R.color.story_text_variant))
                    text = suffix
                }
            )
        }
        return rowLayout
    }

    private fun labelView(@androidx.annotation.StringRes res: Int): TextView =
        TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(ContextCompat.getColor(context, R.color.story_text_variant))
            setText(res)
        }

    private fun unitView(@androidx.annotation.StringRes res: Int): TextView =
        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setTextColor(ContextCompat.getColor(context, R.color.story_text_variant))
            setText(res)
        }

    /**
     * Kartenhöhe der Hero-Zahl: so groß wie möglich, aber die breiteste
     * Zahl muss in [maxContentWidthPx] passen (Karte plus Lücke je Zeichen).
     */
    private fun heroDigitHeight(maxChars: Int, maxContentWidthPx: Int): Int {
        val perChar = HERO_CARD_WIDTH_FACTOR + HERO_CARD_GAP_FACTOR
        val byWidth = (maxContentWidthPx / (maxChars * perChar)).toInt()
        return minOf(dp(HERO_MAX_DP), byWidth).coerceAtLeast(dp(36))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        /** Choreografie-Zeiten des Auftritts. */
        private const val ICON_MS = 400L
        private const val TITLE_DELAY_MS = 80L
        private const val TITLE_MS = 400L
        private const val CONTENT_DELAY_MS = 160L
        private const val CONTENT_STAGGER_MS = 60L
        private const val CONTENT_MS = 350L
        private const val NUMBER_DELAY_MS = 350L
        private const val ROW_STAGGER_MS = 80L

        /** Zweite Hero-Zahl relativ zur ersten. */
        private const val SUB_FACTOR = 0.6f

        /** Maximale Kartenhöhe der Hero-Zahl in dp. */
        private const val HERO_MAX_DP = 84

        /** Kartenbreite und -lücke der Hero-Zahl relativ zur Kartenhöhe. */
        private const val HERO_CARD_WIDTH_FACTOR = 0.68f
        private const val HERO_CARD_GAP_FACTOR = 0.10f
    }
}
