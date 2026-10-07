package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Editable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnPreDraw
import androidx.core.view.isGone
import com.google.android.material.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlin.math.max

/**
 * Eingabekachel für ein einzelnes Feld. Leer und nicht angefasst steht in der
 * Kachelmitte die Beschriftung, bei einzelnen Feldern mit einem Zusatz in
 * Klammern, also etwa "Reisebuddy (mit Enter bestätigen)". Beispielwerte
 * stehen bewusst nicht in der Kachel, sondern als Hinweis im Feld, das beim
 * Tippen erscheint. Beim Tippen wandert die Beschriftung auf den oberen
 * Kachelrand, das Beispiel verschwindet und darunter steht mittig der
 * eingegebene Wert. Ein gefülltes Feld behält die Beschriftung oben, bis der
 * Inhalt wieder gelöscht ist.
 *
 * Den Rahmen zeichnet diese Kachel selbst, damit die Beschriftung den oberen
 * Strich dort unterbrechen kann, wo sie sitzt: Der Rahmen der MaterialCardView
 * wird erst nach ihren Kindern gezeichnet, eine hinterlegte Farbe der
 * Beschriftung würde ihn also nicht verdecken. Die Karte liefert deshalb nur
 * den Radius, der Rahmen der Kachel gehört zum Code hier.
 *
 * Damit die Beschriftung oben halb aus der Kachel herausragen kann, ohne
 * abgeschnitten zu werden, trägt die Karte darüber contentPaddingTop: Über die
 * Karte hinaus darf sie nicht ragen, weil clipToPadding einer ViewGroup ihre
 * Kinder immer auf den Innenabstand beschneidet.
 *
 * Die Kachel selbst ist antippbar, damit nicht genau auf das Eingabefeld
 * gezeigt werden muss.
 *
 * Ihre Kinder sind immer gleich gebaut: das erste Kind ist die Beschriftung,
 * das zweite das Feld unter der Kerbe, und jedes weitere Kind liegt als
 * Zuschlag über der ganzen Kachel - der Prüfhaken und die Landesfahne eines
 * Flughafens etwa oder der Knopf zum Entfernen des Rückflugs. Das Feld kann
 * allein ein EditText sein oder eine ganze Gruppe daraus, im zweiten Fall wird
 * das Eingabefeld gesucht, der Rest der Gruppe bleibt einfach stehen.
 */
class InputTile @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private var labelView: TextView? = null
    /** Oberkante der Beschriftung innerhalb der Kachel, aus onLayout. */
    private var labelTop = 0
    /**
     * Das zweite Kind: Alles, was unter der Kerbe steht - das Eingabefeld
     * allein oder eine Gruppe daraus, etwa Wert und Einheit oder die
     * Reisebuddies mit ihrem Feld. Es wird im Platzhalterzustand ausgeblendet
     * und füllt sonst den Platz unter der Kerbe.
     */
    private var fieldView: View? = null
    /**
     * Das eigentliche Eingabefeld, immer ein [EditText]. Es steckt nicht
     * zwingend direkt als zweites Kind in der Kachel: Bei einer Gruppe liegt
     * es eine Ebene tiefer, und es wird deshalb gesucht statt vorausgesetzt.
     * Nur dieses Feld beobachtet Text und Fokus, die Gruppe bestimmt nichts.
     */
    private var inputView: EditText? = null
    /**
     * Hinweis des Eingabefelds, wie er im Layout steht. Er wird hier
     * gespeichert, weil die Kachel ihn vorübergehend entfernt, sobald Chips
     * den Wert tragen, und ihn danach wiederherstellen muss.
     */
    private var inputHint: CharSequence? = null
    private var labelText: CharSequence = ""
    private var example: CharSequence? = null
    /**
     * Zusatzlicher Inhalt unter der Kerbe, der nicht aus dem Eingabefeld
     * stammt - die Reisebuddy-Chips. Die Kachel befragt ihn bei jeder
     * Neubewertung ihres Zustands: Ein leeres Feld allein hiesse sonst "nichts
     * eingetragen", die Beschriftung wanderte zurück in die Kachelmitte und
     * das Feld samt Chips würde ausgeblendet, obwohl die Chips den Wert tragen.
     */
    private var extraContent: (() -> Boolean)? = null
    private var inputFocusListener: ((hasFocus: Boolean) -> Unit)? = null
    private var editing = false
    private var floating = false
    private var applied = false
    private var animator: ValueAnimator? = null
    /** Läuft gerade eine Bewegung der Beschriftung oder steht eine bevor. */
    private var moving = false
    /** Ton, mit dem die Beschriftung gerade gezeichnet wird. */
    private var tone = 0
    /**
     * Höhe der Beschriftung im Zustand, in den gerade bewegt wird. Wird
     * während der Bewegung festgehalten, weil die Kachel sonst springt, sobald
     * die Schrift mit der Beschriftung mitwandert.
     */
    private var pinnedHeight = 0
    /** Ohne Rahmen und Beschriftung, siehe [setBare]. */
    private var bare = false

    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_DP * resources.displayMetrics.density
    }
    private val framePath = Path()

    init {
        // Ohne eigenen Hintergrund würde eine ViewGroup onDraw nicht bekommen.
        setWillNotDraw(false)
        // Die Beschriftung der Kerbe ragt über die Kachel hinaus und darf
        // deshalb nicht abgeschnitten werden. clipToPadding schneidet die Kinder
        // allein am Innenabstand ab, clipChildren am Kachelrand.
        clipToPadding = false
        clipChildren = false
    }

    /** Zusatz hinter der Beschriftung, in Klammern, z. B. "mit Enter bestätigen". */
    fun setExample(example: CharSequence?) {
        this.example = example
        renderLabel()
    }

    /**
     * Meldet Inhalt unter der Kerbe, der nicht im Eingabefeld steht, etwa die
     * Reisebuddy-Chips: Solange [check] wahr ist, behält die Kachel ihre
     * Beschriftung oben und ihr Feld sichtbar, auch wenn das Eingabefeld leer
     * ist. Die Kachel richtet sich sofort neu ein, weil der Zusatzinhalt erst
     * nach ihrem Aufbau bekannt wird.
     */
    fun setExtraContentCheck(check: () -> Boolean) {
        extraContent = check
        refresh(animate = false)
    }

    /**
     * Bewertet den Kachelzustand neu, nachdem sich der Zusatzinhalt von
     * aussen geändert hat - ein Chip kam dazu oder wurde entfernt. Das
     * Eingabefeld beobachtet die Kachel selbst, die Chips nicht.
     */
    fun refreshState() {
        refresh(animate = false)
    }

    /**
     * Nackte Kachel: Sie zeichnet weder Rahmen noch Beschriftung, es steht
     * allein der Wert darin. Genommen für die Flughäfen, bei denen Flughafenname
     * und Landesfahne ohnehin nichts zur Klarheit beitragen.
     *
     * Weil es keine Beschriftung mehr gibt, entfällt auch ihr Platzhalterauftritt:
     * Das Feld steht immer, eine leere Kachel weist ihren Beispieltext im Feld
     * selbst aus. Sonst wäre beim Antippen einer leeren Kachel gar nichts zu
     * sehen und nichts zu treffen.
     */
    fun setBare(enabled: Boolean) {
        if (bare == enabled) return
        bare = enabled
        // Zurück auf den Anfang, damit refresh() den neuen Zustand einrichtet,
        // statt ihn für schon erledigt zu halten.
        applied = false
        // Die Beschriftung ist im nackten Zustand ausgeblendet und muss beim
        // Zurückschalten wieder erscheinen, sonst bliebe die Kachel leer.
        labelView?.visibility = if (enabled) View.GONE else View.VISIBLE
        refresh(animate = false)
        invalidate()
    }

    /**
     * Zusatzlicher Beobachter für den Fokus des Eingabefelds, etwa das Datum,
     * das beim Fokussiertwerden seinen Kalender öffnet. Die Kachel verwaltet
     * den Fokuslistener selbst, weil sie daraus ihren Zustand ableitet: Ein
     * eigenes setOnFocusChangeListener am Feld würde ihn ersetzen und die
     * Kachel blind machen - sie wüsste dann nicht mehr, dass getippt wird.
     */
    fun setOnInputFocusChangeListener(listener: (hasFocus: Boolean) -> Unit) {
        inputFocusListener = listener
    }

    /** Beschriftung der Kachel, z. B. "Flugnummer". */
    fun setLabelText(text: CharSequence) {
        labelText = text
        renderLabel()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        labelView = getChildAt(0) as? TextView
        fieldView = getChildAt(1)
        inputView = fieldView?.let(::findInput)
        labelView?.let { labelText = it.text }
        // Die Schriftgrösse bestimmt die Kachel selbst (siehe fittingSize), das
        // Autore-sizing des TextView bleibt aus.
        labelView?.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        inputView?.let { field ->
            inputHint = field.hint
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) = refresh(animate = false)
            })
            field.setOnFocusChangeListener { _, hasFocus ->
                editing = hasFocus
                refresh(animate = true)
                inputFocusListener?.invoke(hasFocus)
            }
        }
        setOnClickListener { focusInput() }
        refresh(animate = false)
    }

    /**
     * Sucht in [root] das erste [EditText], auch über mehrere Ebenen hinweg.
     * Bei Gruppen wie Wert und Einheit oder Reisebuddy und Feld hängt das
     * Eingabefeld nicht direkt an der Kachel, und es kann an mehreren Stellen
     * eins geben (Flughafen mit Prüfhaken, Kommentar mit Zeilenumbruch).
     */
    private fun findInput(root: View?): EditText? {
        if (root is EditText) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findInput(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    /**
     * Die MaterialCardView, zu der diese Kachel gehört. Sie ist normalerweise
     * der direkte Vater; gesucht wird auch nach oben, damit die Kachel auch
     * in einer schlichten Hülle liegen kann. Ohne Karte entfällt der Rahmen,
     * weil Radius und Strichfarbe nicht bekannt wären.
     */
    private fun findCard(): MaterialCardView? {
        var current: Any? = parent
        while (current != null) {
            if (current is MaterialCardView) return current
            current = if (current is View) current.parent else null
        }
        return null
    }

    /**
     * Zeichnet den Rahmen der Kachel als offenen Pfad: Im Platzhalterzustand
     * ist er geschlossen, in der Kerbe fehlt das Stück des oberen Strichs,
     * auf dem die Beschriftung sitzt. Der Rahmen wird vor den Kindern gezeichnet,
     * die Beschriftung liegt also darüber.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bare) return
        val card = findCard() ?: return
        val half = frame.strokeWidth / 2f
        val right = width - half
        val bottom = height - half
        val corner = (cornerRadius(card) - half).coerceAtLeast(0f)
        // Kerbe nur, wenn die Beschriftung den oberen Rand wirklich überdeckt.
        // Während der Bewegung steht sie noch in der Kachelmitte: Der Strich
        // bliebe sonst schon beim ersten Frame aufgerissen, obwohl die Lücke
        // noch niemanden braucht.
        val gap = labelView?.takeIf { it.visibility == VISIBLE }?.let { label ->
            val drawnTop = label.top + label.translationY
            val coversEdge = drawnTop <= half && drawnTop + label.height >= half
            if (!coversEdge) return@let null
            val start = label.left.toFloat().coerceAtLeast(half + corner)
            val end = label.right.toFloat().coerceAtMost(right - corner)
            if (start < end) start to end else null
        }

        frame.color = frameColor(card)
        framePath.reset()
        if (gap == null) {
            framePath.addRoundRect(half, half, right, bottom, corner, corner, Path.Direction.CW)
        } else {
            val (gapStart, gapEnd) = gap
            // Die Bögen brauchen ein Rechteck von der doppelten Radiuslänge:
            // arcTo zeichnet in den darin eingeschriebenen Ellbogen, dessen
            // Radius halb so gross ist wie die Rechteckseite. Sonst fallen die
            // Ecken eckig aus, weil die geraden Kanten bis in den Bogen laufen.
            val twice = corner * 2f
            framePath.moveTo(gapEnd, half)
            framePath.lineTo(right - corner, half)
            framePath.arcTo(right - twice, half, right, half + twice, -90f, 90f, false)
            framePath.lineTo(right, bottom - corner)
            framePath.arcTo(right - twice, bottom - twice, right, bottom, 0f, 90f, false)
            framePath.lineTo(half + corner, bottom)
            framePath.arcTo(half, bottom - twice, half + twice, bottom, 90f, 90f, false)
            framePath.lineTo(half, half + corner)
            framePath.arcTo(half, half, half + twice, half + twice, 180f, 90f, false)
            framePath.lineTo(gapStart, half)
        }
        canvas.drawPath(framePath, frame)
    }

    /**
     * Eckenradius der Karte, damit der Rahmen genau an ihren anliegt.
     *
     * Gelesen wird der Radius der Karte selbst: `shapeAppearanceModel`
     * beantwortet eine Anfrage in Pixeln je Ecke relativ zur Rechteckgrösse
     * und liefert damit nicht den festen(dp-)Wert der Kachel.
     */
    private fun cornerRadius(card: MaterialCardView): Float = card.radius

    /**
     * Farbe des Rahmens. Die Karte hat keinen eigenen Strich mehr, deshalb wird
     * deren Farbe weiterverwendet und - falls eine Kachel ohne Strichfarbe
     * angelegt wurde - auf die Konturfarbe des Themes zurückgefallen, damit der
     * Rahmen in keinem Theme verschwindet.
     */
    private fun frameColor(card: MaterialCardView): Int = card.strokeColor
        .takeIf { Color.alpha(it) != 0 }
        ?: MaterialColors.getColor(this, R.attr.colorOutlineVariant)

    /**
     * Steht der Text des Feldes mittig in seinem Kasten? Dann gehört ihm die
     * ganze Kachel, damit er in der Kachelmitte steht und nicht unter der Kerze
     * verschoben ist. Nur Felder, die ihren Text oben beginnen - die
     * mehrzeiligen Notizen und der Reisebegleiter -, kommen unter die
     * Beschriftung.
     *
     * Gelesen wird die Schwerkraft des Feldes selbst, weil sie entscheidet, wo
     * sein Text in seinem Kasten steht. Ein Feld ohne Schwerkraft beginnt oben,
     * alles andere zählt als mittig.
     */
    private fun View?.centersValue(): Boolean {
        val gravity = (this as? TextView)?.gravity ?: return true
        return when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP, Gravity.NO_GRAVITY -> false
            else -> true
        }
    }

    /**
     * Oberkante des Feldes unter der Kerbe, in [labelHeight] Pixeln gemessen.
     *
     * Die Beschriftung sitzt mit ihrer Mitte auf der oberen Kachelkante und
     * ragt zur Hälfte nach oben heraus - ihr unterer Rand liegt also in der
     * Kachel, und erst darunter beginnt der Platz, den das Feld einnehmen
     * darf. Ein zentrierter Wert wird deshalb unter diesem Rand zentriert und
     * nicht hinter der Beschriftung: Zentrierte man ihn über die ganze
     * Kachel, verschwende die Beschriftung ihren halben Platz nach oben und
     * der Inhalt stünde optisch zu hoch - die Reisebuddy-Chips wirkten
     * gedrückt, obwohl sie mittig vermessen sind.
     *
     * Inhalt, der oben beginnt, bekommt zusätzlich den Kerbenabstand, damit
     * er die Beschriftung nicht berührt. In einer nackten Kachel gibt es
     * keine Beschriftung und damit auch keinen Versatz.
     */
    private fun fieldTop(labelHeight: Int): Int = when {
        bare -> paddingTop
        fieldView.centersValue() -> paddingTop + labelHeight / 2
        else -> paddingTop + labelHeight / 2 + notchGap
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val innerWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val label = labelView?.takeIf { !bare }
        val field = fieldView?.takeIf { it.visibility != GONE }
        val notched = floating && !bare

        // Im Platzhalterzustand füllt die Zeile die ganze Kachelbreite und
        // verkleinert sich notfalls darauf. In der Kerbe steht sie eng am Text,
        // damit die Lücke im Strich nur so breit ist wie sie selbst.
        if (!floating) label?.setTextSize(TypedValue.COMPLEX_UNIT_PX, fittingSize(innerWidth))
        label?.measure(
            MeasureSpec.makeMeasureSpec(
                innerWidth,
                if (floating) MeasureSpec.AT_MOST else MeasureSpec.EXACTLY,
            ),
            MeasureSpec.makeMeasureSpec(
                // Während der Bewegung ist die Höhe der Beschriftung
                // festgehalten, weil ihre Schriftgrösse mitwandert: Sonst würde
                // die Kachel während desgleitens ihre Höhe ändern und alles
                // darunter springen lassen.
                if (pinnedHeight > 0) pinnedHeight else labelHeightBudget,
                if (pinnedHeight > 0) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST,
            ),
        )
        val labelHeight = label?.measuredHeight ?: 0
        field?.measure(
            MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        // Der Wert, der mittig in der Kachel stehen soll, braucht die Kerbe
        // zweimal: einmal oben für die Beschriftung, einmal als halbe Höhe des
        // Werts, damit sein Text sie nicht berührt. Ein Wert, der oben beginnt,
        // kommt unter die Kerze und braucht sie nur einmal.
        val wanted = paddingTop + paddingBottom + when {
            notched -> labelHeight + notchGap * (if (field.centersValue()) 2 else 1) +
                (field?.measuredHeight ?: 0)
            floating -> field?.measuredHeight ?: 0
            else -> labelHeight
        }
        val height = max(resolveSize(wanted, heightMeasureSpec), suggestedMinimumHeight)

        // Nur unter der Kerze nimmt das Feld den Platz, in dem sein Text stehen
        // soll: Ein mittiger Wert wird unter dem unteren Beschriftungsrand
        // zentriert, ein oben beginnender nur den Rest unter der Beschriftung.
        // Ohne Kerze behält das Feld seine eigene Höhe - in einer nackten Kachel
        // steht es mittig in der ganzen.
        if (field != null && (notched || bare)) {
            val top = fieldTop(labelHeight)
            field.measure(
                MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(
                    (height - paddingBottom - top).coerceAtLeast(0),
                    MeasureSpec.EXACTLY,
                ),
            )
        }
        // Zuschläge, die über die ganze Kachel gelegt sind: der Prüfhaken und
        // die Landesfahne eines Flughafens, der Rückflug-Knopf. Sie werden mit
        // ihrer eigenen Grösse vermessen - wie in der FrameLayout, in der sie
        // früher lagen, sonst stünde der Prüfhaken nicht auf 16dp, sondern auf
        // der Breite seines Bildes.
        for (index in 2 until childCount) {
            val child = getChildAt(index)
            if (child.isGone) continue
            val lp = child.layoutParams as? FrameLayout.LayoutParams ?: continue
            child.measure(
                getChildMeasureSpec(
                    widthMeasureSpec,
                    paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin,
                    lp.width,
                ),
                getChildMeasureSpec(
                    heightMeasureSpec,
                    paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin,
                    lp.height,
                ),
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val height = bottom - top
        val label = labelView?.takeIf { !bare }
        if (label != null) {
            labelTop = (height - label.measuredHeight) / 2
            val labelLeft = paddingLeft - if (floating) notchGap else 0
            label.layout(
                labelLeft, labelTop,
                labelLeft + label.measuredWidth, labelTop + label.measuredHeight,
            )
            // Nach dem Anlegen muss der Versatz der Kerbe neu bestimmt werden,
            // sonst stünde die Beschriftung auf der alten Höhe. Während einer
            // Bewegung nicht: Sie setzt den Versatz selbst, sobald die Kachel
            // neu vermessen ist, sonst hinge die Beschriftung schon fest.
            if (!moving && floating) label.translationY = restingOffset()
        }
        val field = fieldView
        if (field != null && field.visibility != GONE) {
            val top = fieldTop(label?.measuredHeight ?: 0)
            field.layout(paddingLeft, top, width - paddingRight, height)
        }
        for (index in 2 until childCount) {
            val overlay = getChildAt(index)
            if (overlay.visibility != GONE) layoutOverlay(overlay)
        }
    }

    /**
     * Legt einen Zuschlag wie in einer FrameLayout über die ganze Kachel,
     * mit seinem layout_gravity und seinen Rändern. Die Kinder bleiben dabei
     * auch bei leerer Eingabe sichtbar, im Gegensatz zum Feld unter der Kerbe.
     *
     * Gelegt wird bewusst über die Kachelkante und nicht über den Innenabstand:
     * So stehen sie genau dort wie früher in der umschliessenden FrameLayout,
     * deren Innenabstand vorher die Karte hergab. Der Innenabstand der Kachel
     * gehört allein zu Beschriftung und Feld.
     */
    private fun layoutOverlay(overlay: View) {
        val lp = overlay.layoutParams as? FrameLayout.LayoutParams ?: return
        val childWidth = overlay.measuredWidth
        val childHeight = overlay.measuredHeight
        // start/end erst in Links/Rechts auflösen, sonst träte die Landesfahne
        // in einer Sprache mit rechts-nach-links-Satz um.
        val gravity = Gravity.getAbsoluteGravity(lp.gravity, layoutDirection)
        // Nach dem Zuschneiden auf die waagrechten Bits ist von "end" nur mehr
        // der Zahlenwert übrig, der hinter Gravity.END steht. Deshalb wird END
        // hier auf dieselbe Breite gebracht, statt den festen Namen zu nennen.
        val end = Gravity.END and Gravity.HORIZONTAL_GRAVITY_MASK
        val left = when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.CENTER_HORIZONTAL -> (width - childWidth + lp.leftMargin - lp.rightMargin) / 2
            end -> (width - childWidth - lp.rightMargin).coerceAtLeast(lp.leftMargin)
            else -> lp.leftMargin
        }
        val top = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.CENTER_VERTICAL -> (height - childHeight + lp.topMargin - lp.bottomMargin) / 2
            Gravity.BOTTOM -> (height - childHeight - lp.bottomMargin).coerceAtLeast(lp.topMargin)
            else -> lp.topMargin
        }
        overlay.layout(left, top, left + childWidth, top + childHeight)
    }

    /**
     * [layout_gravity] gehört zur FrameLayout und wird von einer fremden
     * ViewGroup sonst nicht ausgewertet - die Anmerkung an der Kachelvererbung
     * landete dann ohne Wirkung im Müll.
     */
    override fun generateLayoutParams(attrs: AttributeSet?): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(context, attrs)

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * Stellt den Kachelzustand her: entweder Platzhalter in der Mitte oder
     * Beschriftung auf dem oberen Rand mit sichtbarem Eingabefeld. [animate]
     * lässt die Beschriftung von ihrer bisherigen Position auf den Rand
     * wandern.
     */
    private fun refresh(animate: Boolean) {
        val label = labelView ?: return
        val field = inputView ?: return
        if (bare) {
            // Ohne Beschriftung gibt es nichts hinein- und hinauszublenden: Das
            // Feld steht dauerhaft, damit die Kachel beim Antippen etwas zeigt,
            // und eine leere Kachel weist ihren Beispieltext im Feld selbst aus.
            label.visibility = GONE
            floating = true
            fieldView?.visibility = View.VISIBLE
            field.visibility = View.VISIBLE
            val first = !applied
            applied = true
            if (first) requestLayout()
            return
        }
        // Solange Chips den Wert tragen, wäre der Hinweis "Name eingeben,
        // mit Enter bestätigen" eine Aufforderung unter bereits gemerkten
        // Namen. Er wird deshalb vor der Kurzschlussprüfung unten entfernt,
        // weil sich sein Zustand ändern kann, ohne dass der der Kachel
        // kippt - etwa beim zweiten Namen im schon schwebenden Zustand.
        val hasExtra = extraContent?.invoke() == true
        field.hint = if (hasExtra) null else inputHint
        val shouldFloat = editing || field.hasFocus() || !field.text.isNullOrBlank() || hasExtra
        // Das Eingabefeld steht nur im Eingabezustand der Kachel. Die
        // Sichtbarkeit gehört vor die Kurzschlussprüfung unten: Bei einer
        // Kachel, deren zweites Kind direkt das Eingabefeld ist, würde ein
        // dort gesetztes "sichtbar" die Prüfung überleben, weil sich der
        // Zustand nicht ändert - die Beschriftung bliebe in der Mitte und läge
        // neben dem Hinweis. Gleiches gilt für ein erneutes Anschreiben des
        // Feldes mit demselben Wert, etwa beim Zurückschreiben der Einträge
        // nach dem Deaktivieren des Rückflugs.
        // Auch das leere Feld selbst braucht keine Zeile unter den Chips: Die
        // Kachel misst ihre Höhe aus dem Feld, und eine leere Zeile bliebe
        // immer stehen. Das Feld wird deshalb ausgeblendet, bis getippt oder
        // fokussiert wird - eine weitere Zeile entsteht dann von selbst, wenn
        // die Namen umbrechen.
        field.visibility = when {
            hasExtra && !editing && !field.hasFocus() && field.text.isNullOrEmpty() -> View.GONE
            !shouldFloat -> View.GONE
            else -> View.VISIBLE
        }
        val first = !applied
        applied = true
        if (!first && shouldFloat == floating) return

        // Stand der Beschriftung vor dem Wechsel merken. Sie liegt mittig in der
        // Kachel, deren Höhe sich mit dem Zustand ändert - misst man erst nach
        // dem Vermessen, springt das Wort beim Antippen erst nach unten und
        // gleitet dann nach oben.
        val fromOffset = label.translationY + labelTop + label.height / 2f
        val fromSize = label.paint.textSize
        val fromColor = tone

floating = shouldFloat
        fieldView?.visibility = if (floating) View.VISIBLE else View.GONE
        if (floating) {
            // In der Kerze steht nur das kurze Stichwort, dafür genügt eine
            // feste Grösse. onMeasure vermisst die Beschriftung mit AT_MOST,
            // weil die Lücke im Strich nur so breit sein soll wie sie selbst.
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_FLOATING_SP.toFloat())
        }
        tone = labelColor()
        label.setTextColor(tone)
        renderLabel()
        // Kein Hintergrund für die Beschriftung: Der Rahmen lässt unter ihr
        // ohnehin schon ein Stück aus (siehe onDraw), und eine hinterlegte
        // Kachelfarbe würde beim Antippen der Kachel eingefangen, solange die
        // Karte ihren gedrückten Zustand noch zeigt - die Kerbe bliebe dann
        // dauerhaft etwas dunkler als die Kachel.
        label.background = null
        label.setPaddingRelative(if (floating) notchGap else 0, 0, if (floating) notchGap else 0, 0)
        animator?.cancel()
        moving = animate && !first
        pinnedHeight = 0
        requestLayout()

        if (!moving) {
            label.translationY = restingOffset()
            return
        }
        doOnPreDraw { startMove(fromOffset, fromSize, fromColor) }
    }

    /**
     * Lässt die Beschriftung von ihrem bisherigen Stand in den neuen Zustand
     * gleiten. Position, Schriftgrösse und Farbe laufen dabei über dieselbe
     * Laufzeit, damit durchgehend dasselbe Wort zu sehen ist: Ein bloss
     * verschobener Text sähe aus wie eine Einblendung an anderer Stelle, und
     * springende Schriftgrösse und Farbton machten daraus erst recht ein
     * Aus- und Einblenden.
     */
    private fun startMove(fromOffset: Float, fromSize: Float, fromColor: Int) {
        val label = labelView ?: return
        val toColor = labelColor()
        // Nach dem Vermessen steht die Beschriftung in der Höhe des Zustands,
        // in den bewegt wird. Ihre Mitte ist zugleich der Anker, von dem aus
        // der Versatz gilt: Die Kachel ist jetzt höher, ihr Mittelpunkt liegt
        // also tiefer als der des Ausgangszustands.
        val anchor = labelTop + label.height / 2f
        val from = fromOffset - anchor
        val to = restingOffset()
        val toSize = label.paint.textSize
        // Höhe der Beschriftung festhalten, damit die Kachel beim Mitwandern
        // der Schriftgrösse nicht springt.
        pinnedHeight = label.height

        if (from == to && fromSize == toSize) {
            moving = false
            pinnedHeight = 0
            tone = toColor
            label.translationY = to
            return
        }
        // Stand von vor dem Wechsel sofort wiederherstellen: refresh() hat
        // schon den Zielzustand gesetzt, damit sich die Kachel vermessen
        // lässt. Ohne dieses Zuruecksetzen zeichnete der eine Frame bis zum
        // ersten Animationsschritt schon die neue Größe an der alten Stelle.
        label.translationY = from
        setAnimatedSize(fromSize)
        tone = fromColor
        label.setTextColor(tone)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { step ->
                val part = step.animatedValue as Float
                label.translationY = from + (to - from) * part
                setAnimatedSize(fromSize + (toSize - fromSize) * part)
                tone = ColorUtils.blendARGB(fromColor, toColor, part)
                label.setTextColor(tone)
            }
            doOnEnd {
                moving = false
                // Festhaltung lösen, damit die Beschriftung ihre Höhe wieder aus
                // dem Text bekommt und die Kachel im Platzhalterzustand so hoch
                // ist wie im frischen Zustand. Im Kerbenzustand ist die Höhe
                // ohnehin fest, dort ändert das nichts.
                pinnedHeight = 0
                tone = toColor
            }
            start()
        }
    }

    /**
     * Setzt die Schriftgrösse der Beschriftung auf [px] Pixel. Das
     * Autore-sizing des [TextView] ist ausgeschaltet, deshalb bleibt die Grösse
     * auch beim Vermessen der Kachel stehen.
     */
    private fun setAnimatedSize(px: Float) {
        labelView?.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
    }

    /**
     * Grösste Schriftgrösse aus [LABEL_MIN_SP] bis [LABEL_PLACEHOLDER_MAX_SP],
     * mit der die Platzhalterzeile in [width] Pixel ohne Umbruch passt. Die
     * Grösse wird hier berechnet statt vom Autore-sizing des [TextView]: Nach
     * dem Wechsel aus der Kerbe blieb dort deren kleine Grösse stehen, weil sie
     * nicht wieder hochzählt, und die Zeile wurde dauerhaft kleiner gezeichnet
     * als im frischen Zustand.
     */
    private fun fittingSize(width: Int): Float {
        val label = labelView ?: return 0f
        val text = label.text ?: return 0f
        val scaled = resources.displayMetrics.scaledDensity
        val usable = width.coerceAtLeast(1)
        val paint = TextPaint(label.paint)
        for (sp in LABEL_PLACEHOLDER_MAX_SP downTo LABEL_MIN_SP) {
            val size = sp * scaled
            paint.textSize = size
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, usable)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            if (layout.lineCount == 1) return size
        }
        return LABEL_MIN_SP * scaled
    }

    /**
     * Versatz der Beschriftung gegenüber ihrer Lage in der Kachelmitte. In der
     * Kerbe soll die Mitte der Beschriftung genau auf der oberen Kachelkante
     * liegen, damit der Strich sie mittig trifft. Der Versatz wird aus der
     * tatsächlich gelegten Lage berechnet, weil die Höhe der Beschriftung erst
     * nach dem Vermessen feststeht.
     */
    private fun restingOffset(): Float =
        if (floating) -(labelTop + (labelView?.height ?: 0) / 2f) else 0f

    private fun focusInput() {
        val field = inputView ?: return
        // Manche Felder erledigen den Klick selbst - das Datum öffnet seinen
        // Kalender. Dann darf die Kachel weder den Fokus erzwingen noch das
        // Feld sichtbar machen: Die Beschriftung wandert erst nach oben, wenn
        // wirklich ein Wert da ist, sonst stünde sie auch nach einem
        // abgebrochenen Kalender oben und zeigte auf ein leeres Feld.
        if (field.performClick()) return
        // Das Feld ist im Platzhalterzustand ausgeblendet und kann dann keinen
        // Fokus annehmen. Deshalb wird es zuerst sichtbar gemacht und der Fokus
        // erst danach angefragt, wenn es vermessen wurde.
        editing = true
        refresh(animate = true)
        post {
            if (!field.requestFocus()) {
                field.performClick()
                return@post
            }
            context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * Schreibt die Kopfzeile der Kachel: im Platzhalterzustand Beschriftung und
     * Beispiel in einem Text, im Eingabezustand nur die Beschriftung.
     */
    private fun renderLabel() {
        val label = labelView ?: return
        val example = example
        if (floating || example.isNullOrEmpty()) {
            label.text = labelText
            return
        }
        val builder = SpannableStringBuilder(labelText)
        val start = builder.length + 1
        builder.append(' ').append('(').append(example).append(')')
        val end = builder.length
        builder.setSpan(StyleSpan(Typeface.NORMAL), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(RelativeSizeSpan(EXAMPLE_TEXT_SCALE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.text = builder
    }

    /**
     * Höhenraum für die Beschriftung beim Messen. Mit Autore-sizing bestimmt
     * die Vorgabe die Schriftgröße, deshalb muss hier echter Raum stehen und
     * nicht "unbegrenzt", sonst schrumpft die Schrift auf das Minimum.
     */
    private val labelHeightBudget: Int
        get() = (LABEL_PLACEHOLDER_MAX_SP * 2f * resources.displayMetrics.scaledDensity).toInt()

    /** Luft zwischen der Kerbe und dem Wert darunter. */
    private val notchGap: Int
        get() = (NOTCH_GAP_DP * resources.displayMetrics.density).toInt()

    private fun subtleColor(): Int = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurfaceVariant,
    )

    /**
     * Ton der Beschriftung im jeweiligen Zustand. Die Beschriftung der Kerbe ist
     * eine kleine Überschrift und bleibt auf der gedämpften Sekundärfarbe. Die
     * Platzhalterzeile steht dagegen nur als Hinweis in einem noch leeren Feld
     * und bekommt deshalb den Kontur-Ton, damit sie den Blick nicht auf sich
     * zieht. Da die Bewegung dazwischen jeden Ton annehmen kann, steht die
     * Wahl hier in einer eigenen Funktion.
     */
    private fun labelColor(): Int = if (floating) subtleColor() else hintColor(this)

    companion object {
        /**
         * Ton für die Platzhalterzeile im leeren Feld. Die reine Konturfarbe ist
         * im dunklen Theme noch zu auffällig, deshalb wird sie zusätzlich
         * gedimmt. Die Zeile ist nur ein Hinweis auf das, was hier hineingehört,
         * und darf weder das Eingabefeld noch den Rahmen überstrahlen.
         *
         * Öffentlich, weil die Beschriftung der Auswahlkacheln (Reiseart,
         * Reiseklasse) im ungewählten Zustand genau diesen Ton bekommen soll:
         * Nur so stehen beide Kachelarten gleich zurückhaltend da, statt dass die
         * Auswahlkacheln als einzige mit voller Schriftfarbe herausstehen.
         *
         * Als View übergeben, weil MaterialColors den Ton nur über eine View
         * auflöst und nicht über deren Context.
         */
        fun hintColor(view: View): Int = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOutline),
            (HINT_ALPHA * 255).toInt(),
        )

        const val MOVE_DURATION_MS = 180L
        const val EXAMPLE_TEXT_SCALE = 0.82f
        const val LABEL_MIN_SP = 9
        const val LABEL_FLOATING_SP = 12
        const val LABEL_PLACEHOLDER_MAX_SP = 16

        /** Anteil der Konturfarbe, den die Platzhalterzeile behält. */
        const val HINT_ALPHA = 0.5f

        /** Stärke des von der Kachel gezeichneten Rahmens. */
        const val STROKE_WIDTH_DP = 1f

        /** Luft zwischen der Kerbe und dem Wert darunter. */
        const val NOTCH_GAP_DP = 3
    }
}