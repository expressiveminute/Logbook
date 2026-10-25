package com.highfly.logbook

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Eine einzelne Ziffer als Split-Flap-Karte, wie an einer alten
 * Flughafen-Anzeigetafel: Die Karte ist horizontal in eine obere und eine
 * untere Hälfte geteilt. Beim Wechsel klappt die obere Hälfte der alten
 * Ziffer um die waagerechte Mittelachse nach vorn unten, bis sie
 * rechtwinklig steht (Phase 1); darunter liegt bereits die neue Ziffer. Danach
 * klappt die untere Hälfte der neuen Ziffer aus der Senkrechten auf ihren
 * Platz und setzt mit einem leichten Überschwingen auf (Phase 2). So zeigt
 * jedes Flap nur seine Vorderseite - ein Zurückkippen des Inhalts bei 180°
 * und das gespiegelte Rückseiten-Problem entfallen.
 *
 * Die reine Mechanik in Worten (inspiriert von klassischen Split-Flap-Uhren):
 *  - Phase 1 (ca. 150 ms, ease-in/beschleunigt): das obere Flap fällt
 *    0° → -90° um die Mittelachse, verdunkelt sich leicht und wirft einen
 *    Schatten auf die untere Hälfte.
 *  - Phase 2 (ca. 150 ms, mit Überschwingen): das untere Flap der neuen
 *    Ziffer landet aus +90° → 0°, von dunkel nach hell, mit einem leichten
 *    Bounce am Ende.
 *  - Die Naht zwischen den Hälften bleibt ausgerichtet, ein weicher
 *    Gradient-Highlight liegt auf der Faltlinie.
 *
 * Sind Systemanimationen abgeschaltet ([Settings.Global.ANIMATOR_DURATION_SCALE]
 * = 0), wechselt die Ziffer sofort ohne Animation - wie überall in der App.
 */
class FlipDigitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Gesamtdauer eines Flips in Millisekunden (250–350 ms laut Vorgabe). */
    private var flipDurationMs = FLIP_DURATION_MS

    /** Ob Animationen laufen dürfen (Systemeinstellung, beim Anhängen gelesen). */
    private var animationsEnabled = true

    private var cardWidth = 0
    private var cardHeight = 0

    private lateinit var topStatic: FrameLayout
    private lateinit var bottomStatic: FrameLayout
    private lateinit var fallFlap: FrameLayout
    private lateinit var landFlap: FrameLayout
    private lateinit var fallShade: View
    private lateinit var landShade: View
    private lateinit var shadow: View
    private lateinit var foldLine: View

    /** Die aktuell fest stehende Ziffer. */
    var digit: Char = ' '
        private set

    /** Ziffer, die nach dem laufenden Flip ansteht (Warteschlange, Länge 1). */
    private var pendingDigit: Char? = null

    private var flipAnimator: AnimatorSet? = null

    init {
        // Die Karte selbst: leicht gerundet, oben heller als unten.
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ContextCompat.getColor(context, R.color.story_card_top),
                ContextCompat.getColor(context, R.color.story_card_bottom)
            )
        )
        bg.cornerRadius = CARD_CORNER_DP * resources.displayMetrics.density
        background = bg
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, bg.cornerRadius)
            }
        }
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == cardWidth && h == cardHeight) return
        cardWidth = w
        cardHeight = h
        // Kinder erst NACH dem Layout-Durchlauf einfügen: während onSizeChanged
        // (das aus dem Layout heraus aufgerufen wird) eingefügte Kinder werden
        // nicht gemessen und bleiben 0x0 groß - also unsichtbar. Post verschiebt
        // den Aufbau auf den nächsten Durchlauf, in dem sie normal gemessen,
        // gelegt und gezeichnet werden.
        post { buildCard() }
    }

    /** Baut die vier Kartenhälften und die Overlay-Ebenen einmalig auf. */
    private fun buildCard() {
        if (cardWidth <= 0 || cardHeight <= 0) return
        removeAllViews()
        val half = cardHeight / 2

        topStatic = halfCard(topHalf = true, digit = digit)
        bottomStatic = halfCard(topHalf = false, digit = digit)
        fallFlap = halfCard(topHalf = true, digit = digit)
        landFlap = halfCard(topHalf = false, digit = digit)

        fallShade = shadeView()
        fallFlap.addView(fallShade)
        landShade = shadeView()
        landShade.alpha = SHADE_MAX
        landFlap.addView(landShade)

        // Schlagschatten der fallenden Klappe auf der unteren Hälfte.
        shadow = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, half).apply {
                topMargin = half
            }
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }

        // Weicher Highlight-Verlauf entlang der Faltlinie (vertikaler
        // Verlauf: transparent - hell - transparent).
        val foldHeight = (2 * resources.displayMetrics.density).toInt().coerceAtLeast(2)
        val foldColor = ContextCompat.getColor(context, R.color.story_fold)
        foldLine = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, foldHeight).apply {
                topMargin = half - foldHeight / 2
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, foldColor, Color.TRANSPARENT)
            )
        }

        // Reihenfolge: unten die statischen Hälften, darüber Schatten und
        // die beiden beweglichen Flaps, ganz oben die Faltlinie.
        addView(topStatic)
        addView(bottomStatic)
        addView(shadow)
        addView(fallFlap)
        addView(landFlap)
        addView(foldLine)

        // Leichte 3D-Perspektive: die Kamera steht der Karte etwas näher
        // als der Systemstandard, damit das Kippen räumlich wirkt. Beide
        // Klappen bekommen dieselbe Distanz, damit sie gleich räumlich wirken.
        fallFlap.cameraDistance = cardHeight * CAMERA_DISTANCE_FACTOR
        landFlap.cameraDistance = cardHeight * CAMERA_DISTANCE_FACTOR

        // Scharnier beider Klappen ist die Faltlinie (halbe Kartenhöhe):
        // die fallende obere klappt um ihre untere Kante, die landende
        // untere um ihre obere Kante. So treffen sich beide exakt an der
        // Naht - kippten sie um ihre jeweilige Mitte, würde die Naht
        // aufreißen und die untere Hälfte zeigte beim Falten einen
        // verzerrten Ausschnitt der Ziffer.
        fallFlap.pivotY = half.toFloat()
        landFlap.pivotY = 0f

        // Die landende Klappe startet rechtwinklig (unsichtbar kantig).
        landFlap.rotationX = 90f
        landFlap.visibility = INVISIBLE
        fallShade.alpha = 0f

        // Ein vor dem Aufbau angeforderter Flip (erste Ziffer einer frisch
        // erzeugten Karte) wird jetzt nachgeholt: Die Karte zeigt noch die
        // alte (leere) Ziffer und klappt auf die gemerkte.
        val pending = pendingDigit
        pendingDigit = null
        if (pending != null && pending != digit) {
            setDigit(pending)
        }
    }

    /**
     * Eine Kartenhälfte: ein Container von halber Höhe, der ein TextView in
     * voller Kartenhöhe enthält - der Container beschneidet (clipChildren)
     * den Text auf die Hälfte, sodass die Ziffer genau mittig erscheint.
     */
    private fun halfCard(topHalf: Boolean, digit: Char): FrameLayout {
        val half = cardHeight / 2
        val container = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, half).apply {
                if (!topHalf) topMargin = half
            }
        }
        val text = TextView(context).apply {
            val params = FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                cardHeight
            )
            if (!topHalf) params.topMargin = -half
            layoutParams = params
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.story_digit))
            // Explizit in Pixeln: die Kartenhöhe ist bereits in Pixeln.
            setTextSize(TypedValue.COMPLEX_UNIT_PX, cardHeight * DIGIT_SIZE_FACTOR)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setText(if (digit == ' ') "" else digit.toString())
        }
        container.addView(text)
        return container
    }

    /** Dunkle Tönung, die eine Klappe beim Kippen abdunkelt. */
    private fun shadeView(): View = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.BLACK)
        alpha = 0f
    }

    private fun digitTextOf(card: FrameLayout): TextView = card.getChildAt(0) as TextView

    /**
     * Setzt die Ziffer. Läuft gerade ein Flip, stellt sich der Wechsel hinten
     * an und läuft danach nach - so rollen die Ziffern bei einem Count-up
     * flüssig hintereinander, ohne sich zu überholen. Ist die Karte noch nicht
     * aufgebaut (frisch erzeugt), wird der Wechsel gemerkt und nach dem Aufbau
     * nachgeholt, damit die erste Ziffer ebenfalls klappt statt zu springen.
     */
    fun setDigit(newDigit: Char, animate: Boolean = true) {
        val target = if (newDigit.isDigit()) newDigit else ' '
        if (target == digit && flipAnimator == null && pendingDigit == null) return
        if (!animate || !animationsEnabled) {
            cancelFlip()
            applyImmediate(target)
            return
        }
        if (!::topStatic.isInitialized) {
            // Karte noch nicht aufgebaut (frisch erzeugt, noch nicht gelegt):
            // Ziffer merken, der Flip wird nach dem Aufbau nachgeholt - die
            // Karte klappt dann von der leeren Karte auf die Ziffer.
            pendingDigit = target
            return
        }
        if (flipAnimator != null) {
            pendingDigit = target
            return
        }
        startFlip(target)
    }

    /** Übernimmt die Ziffer ohne Animation und räumt eine laufende auf. */
    private fun applyImmediate(newDigit: Char) {
        pendingDigit = null
        digit = newDigit
        if (::topStatic.isInitialized) {
            val shown = if (newDigit == ' ') "" else newDigit.toString()
            digitTextOf(topStatic).text = shown
            digitTextOf(bottomStatic).text = shown
            digitTextOf(fallFlap).text = shown
            digitTextOf(landFlap).text = shown
            landFlap.rotationX = 0f
            landFlap.visibility = VISIBLE
            fallShade.alpha = 0f
            landShade.alpha = 0f
            shadow.alpha = 0f
        }
    }

    private fun cancelFlip() {
        flipAnimator?.cancel()
        flipAnimator = null
        if (::landFlap.isInitialized) {
            landFlap.rotationX = 0f
            landFlap.visibility = VISIBLE
        }
        if (::fallShade.isInitialized) fallShade.alpha = 0f
        if (::landShade.isInitialized) landShade.alpha = 0f
        if (::shadow.isInitialized) shadow.alpha = 0f
    }

    /** Startet den zweiteiligen Flip von [digit] auf [newDigit]. */
    private fun startFlip(newDigit: Char) {
        val oldDigit = digit
        // Statische Ebenen: oben bleibt die alte Ziffer stehen (bis die
        // fallende Klappe sie verdeckt), unten liegt bereits die neue bereit
        // (sichtbar, sobald die fallende Klappe weggeklappt ist).
        digitTextOf(topStatic).text = if (oldDigit == ' ') "" else oldDigit.toString()
        digitTextOf(bottomStatic).text = if (newDigit == ' ') "" else newDigit.toString()
        // Fallende Klappe trägt die alte, landende die neue Ziffer.
        digitTextOf(fallFlap).text = if (oldDigit == ' ') "" else oldDigit.toString()
        digitTextOf(landFlap).text = if (newDigit == ' ') "" else newDigit.toString()

        fallFlap.rotationX = 0f
        fallFlap.visibility = VISIBLE
        fallShade.alpha = 0f
        // Die landende Klappe startet rechtwinklig (unsichtbar kantig) und
        // bleibt während Phase 1 versteckt - bei 90° Rotation kann sie sonst
        // durch die Perspektive leicht durchscheinen und unten schon die neue
        // Ziffer zeigen, während oben noch die alte steht.
        landFlap.rotationX = 90f
        landFlap.visibility = INVISIBLE
        landShade.alpha = SHADE_MAX
        shadow.alpha = 0f

        val halfMs = flipDurationMs / 2

        // Phase 1: das obere Flap fällt ease-in auf 90° und verdunkelt sich,
        // sein Schatten auf der unteren Hälfte wächst mit.
        val fall = ObjectAnimator.ofFloat(fallFlap, View.ROTATION_X, 0f, -90f).apply {
            duration = halfMs
            interpolator = AccelerateInterpolator()
        }
        val fallShading = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = halfMs
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                fallShade.alpha = SHADE_MAX * f
                shadow.alpha = SHADOW_MAX * f
            }
        }
        val phase1 = AnimatorSet().apply {
            playTogether(fall, fallShading)
            // Sobald die fallende Klappe weg ist, die landende sichtbar machen.
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    landFlap.visibility = VISIBLE
                }
            })
        }

        // Phase 2: das untere Flap der neuen Ziffer landet mit leichtem
        // Überschwingen (Bounce) und wird dabei heller.
        val land = ObjectAnimator.ofFloat(landFlap, View.ROTATION_X, 90f, 0f).apply {
            duration = halfMs
            interpolator = OvershootInterpolator(SETTLE_TENSION)
        }
        val landShading = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = halfMs
            addUpdateListener {
                landShade.alpha = SHADE_MAX * (it.animatedValue as Float)
            }
        }
        val phase2 = AnimatorSet().apply { playTogether(land, landShading) }

        val set = AnimatorSet().apply {
            playSequentially(phase1, phase2)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (flipAnimator !== this@apply) return
                    flipAnimator = null
                    landFlap.rotationX = 0f
                    shadow.alpha = 0f
                    digit = newDigit
                    // Statische Ebenen auf die neue Ziffer aktualisieren:
                    // oben steht sie jetzt fest (die fallende Klappe ist
                    // weggeklappt), unten ist sie es sowieso.
                    val shown = if (newDigit == ' ') "" else newDigit.toString()
                    digitTextOf(topStatic).text = shown
                    digitTextOf(bottomStatic).text = shown
                    val pending = pendingDigit
                    pendingDigit = null
                    if (pending != null && pending != digit) {
                        setDigit(pending)
                    }
                }
            })
        }
        flipAnimator = set
        set.start()
    }

    override fun onDetachedFromWindow() {
        cancelFlip()
        super.onDetachedFromWindow()
    }

    companion object {
        /** Gesamtdauer eines Flips (im Zielbereich 250–350 ms). */
        private const val FLIP_DURATION_MS = 300L

        /** Maximale Abdunklung einer kippenden Klappe. */
        private const val SHADE_MAX = 0.38f

        /** Maximale Deckkraft des Schattens auf der unteren Hälfte. */
        private const val SHADOW_MAX = 0.45f

        /** Überschwing-Faktor des landenden Flaps (Bounce/Settle). */
        private const val SETTLE_TENSION = 1.4f

        /** Schriftgröße der Ziffer relativ zur Kartenhöhe. */
        private const val DIGIT_SIZE_FACTOR = 0.62f

        /** Kameradistanz relativ zur Kartenhöhe für die 3D-Perspektive. */
        private const val CAMERA_DISTANCE_FACTOR = 5f

        private const val CARD_CORNER_DP = 6f
    }
}
