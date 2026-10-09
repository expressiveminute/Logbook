package com.highfly.logbook

import android.content.Context
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.ItemEntryBinding
import com.highfly.logbook.databinding.ItemEntryMonthBinding
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * RecyclerView adapter for the entries list. View holders are recycled, so the
 * swipe-reveal state is tracked per entry id and restored/reset in onBind to
 * avoid stale translations on recycled rows.
 *
 * The list is grouped by month: every month gets one header row with month and
 * year, all cards of that month follow underneath it. Geplante Flüge mit einem
 * Datum in der Zukunft stehen ganz oben unter einer "Upcoming"-Überschrift,
 * ihre Kacheln sind dabei sichtbar ausgegraut, damit sie sich von den
 * geflogenen Einträgen unterscheiden.
 */
class EntryListAdapter(
    private val context: Context,
    private val onClickEdit: (LogbookEntry) -> Unit,
    private val onClickDelete: (LogbookEntry) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /**
     * Eine Zeile der Liste: entweder eine Monatsüberschrift, die
     * "Upcoming"-Überschrift über den geplanten Flügen oder eine Kachel.
     */
    private sealed interface Row {
        data class MonthHeader(val month: YearMonth) : Row
        object UpcomingHeader : Row
        data class Item(val entry: LogbookEntry, val upcoming: Boolean) : Row
    }

    var entries: List<LogbookEntry> = emptyList()
    var schemeColors: List<Int> = emptyList()

    private var rows: List<Row> = emptyList()

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    private val revealWidth by lazy {
        (56 * context.resources.displayMetrics.density).toFloat()
    }
    private val isDarkMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val darkLogoFilter by lazy {
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.5f, 0f, 0f, 0f, 45f,
                    0f, 1.5f, 0f, 0f, 45f,
                    0f, 0f, 1.5f, 0f, 45f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }

    /**
     * Logos geplanter Flüge: Graustufen statt Originalfarben. Passt den Filter
     * dem dunklen Theme an, damit ein dunkles Logo dort nicht in der Kachel
     * verschwindet - derselbe Trick wie beim [darkLogoFilter], nur zusätzlich
     * entsättigt. Graustufen statt schlichter Helligkeit, weil die Logos ohne
     * ihre Farben neutraler wirken als mit einem nur verblassten Original.
     */
    private val grayLogoFilter by lazy {
        val gray = ColorMatrix().apply { setSaturation(0f) }
        if (isDarkMode) {
            gray.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1.5f, 0f, 0f, 0f, 45f,
                        0f, 1.5f, 0f, 0f, 45f,
                        0f, 0f, 1.5f, 0f, 45f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }
        ColorMatrixColorFilter(gray)
    }
    private val openEntryId = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    fun submit(list: List<LogbookEntry>) {
        entries = list
        rows = buildRows(list)
        notifyDataSetChanged()
    }

    /**
     * Baut die Zeilen der Liste: Ein Flug mit Datum in der Zukunft ist ein
     * geplanter Flug und steht ganz oben unter der "Upcoming"-Überschrift,
     * ohne eigene Monatsüberschrift. Alles bis einschliesslich heute wird wie
     * bisher nach Monaten gruppiert: vor der ersten Kachel eines Monats steht
     * eine Überschrift, alle Kacheln desselben Monats folgen direkt darunter.
     * Die Einträge kommen bereits absteigend nach Datum, deshalb genügt es,
     * die Monate in dieser Reihenfolge der Reihe nach zu durchlaufen.
     */
    private fun buildRows(list: List<LogbookEntry>): List<Row> {
        val result = mutableListOf<Row>()
        var lastMonth: YearMonth? = null
        var upcomingStarted = false
        val today = LocalDate.now()
        for (entry in list) {
            val upcoming = UpcomingEntries.isUpcoming(entry, today)
            when {
                upcoming && !upcomingStarted -> {
                    result += Row.UpcomingHeader
                    upcomingStarted = true
                }
                !upcoming -> {
                    val month = YearMonth.from(entry.date)
                    if (month != lastMonth) {
                        result += Row.MonthHeader(month)
                        lastMonth = month
                    }
                }
            }
            result += Row.Item(entry, upcoming)
        }
        return result
    }

    /** Position der Kachel mit dieser Id, oder -1 wenn nicht in der Liste. */
    fun positionOfEntry(entryId: Long?): Int {
        if (entryId == null) return -1
        return rows.indexOfFirst { it is Row.Item && it.entry.id == entryId }
    }

    /**
     * Nächste Zeile nach [position], die eine Kachel ist. Überschriften werden
     * übersprungen, damit z. B. das Ausrichten der Listenunterkante an einer
     * Kartengrenze nicht an einer Monatsüberschrift hängen bleibt.
     */
    fun nextItemPositionAfter(position: Int): Int {
        for (index in position + 1 until rows.size) {
            if (rows[index] is Row.Item) return index
        }
        return -1
    }

    private val currentlyOpenId: Long?
        get() = openEntryId.get().takeIf { it != Long.MIN_VALUE }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.MonthHeader, is Row.UpcomingHeader -> TYPE_MONTH
        is Row.Item -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_MONTH) {
            MonthViewHolder(ItemEntryMonthBinding.inflate(inflater, parent, false))
        } else {
            ViewHolder(ItemEntryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.MonthHeader -> (holder as MonthViewHolder).bind(row.month)
            is Row.UpcomingHeader -> (holder as MonthViewHolder).bindUpcoming()
            is Row.Item -> (holder as ViewHolder).bind(row.entry, row.upcoming)
        }
    }

    override fun getItemCount(): Int = rows.size

    /**
     * Zeigt eine Gruppenüberschrift: rechtsbündig über der ersten Kachel der
     * Gruppe. Bei den geflogenen Einträgen ist das der Monat mit Jahr, über den
     * geplanten Flügen steht stattdessen "Upcoming".
     */
    inner class MonthViewHolder(
        private val binding: ItemEntryMonthBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(month: YearMonth) {
            binding.tvEntryMonth.setTextColor(
                MaterialColors.getColor(
                    binding.tvEntryMonth,
                    com.google.android.material.R.attr.colorPrimary,
                )
            )
            binding.tvEntryMonth.text = context.getString(
                R.string.entries_month,
                month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                month.year,
            )
        }

        fun bindUpcoming() {
            binding.tvEntryMonth.setText(R.string.entries_upcoming)
            // Gleiche Signalfarbe wie das "i" neben der Dashboard-Filterzeile.
            binding.tvEntryMonth.setTextColor(ContextCompat.getColor(context, R.color.upcoming_blue))
        }
    }

    private fun holderForId(id: Long): ViewHolder? {
        val rv = recyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val vh = rv.getChildViewHolder(rv.getChildAt(i)) as? ViewHolder ?: continue
            if (vh.entry?.id == id) return vh
        }
        return null
    }

    private fun setOpen(id: Long?) {
        val prev = currentlyOpenId
        if (prev == id) return
        openEntryId.set(id ?: Long.MIN_VALUE)
        if (prev != null) holderForId(prev)?.closeCard()
    }

    private fun closeOpenRow() {
        setOpen(null)
    }

    fun dismissOpenRow() = closeOpenRow()

    inner class ViewHolder(val binding: ItemEntryBinding) : RecyclerView.ViewHolder(binding.root) {

        var entry: LogbookEntry? = null

        init {
            val card = binding.itemCard
            var dragStartX = 0f
            var baseTranslation = 0f
            var dragging = false

            card.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartX = event.x
                        baseTranslation = card.translationX
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - dragStartX
                        if (!dragging && kotlin.math.abs(dx) > touchSlop) {
                            dragging = true
                            card.requestDisallowInterceptTouchEvent(true)
                        }
                        if (dragging) {
                            card.translationX =
                                (baseTranslation + dx).coerceIn(-revealWidth, revealWidth)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            val target = when {
                                card.translationX > revealWidth / 2f -> revealWidth
                                card.translationX < -revealWidth / 2f -> -revealWidth
                                else -> 0f
                            }
                            if (target != 0f) {
                                setOpen(entry?.id)
                                card.animate().translationX(target).setDuration(180).start()
                            } else {
                                card.animate().translationX(0f).setDuration(180).start()
                                if (currentlyOpenId == entry?.id) setOpen(null)
                            }
                        } else {
                            closeOpenRow()
                        }
                        card.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        card.animate().translationX(0f).setDuration(180).start()
                        if (currentlyOpenId == entry?.id) setOpen(null)
                        card.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                    else -> true
                }
            }

            binding.btnEditEntry.setOnClickListener {
                entry?.let(onClickEdit)
            }
            binding.btnDeleteEntry.setOnClickListener {
                entry?.let(onClickDelete)
            }
        }

        fun closeCard() {
            if (binding.itemCard.translationX != 0f) {
                binding.itemCard.animate().translationX(0f).setDuration(180).start()
            }
        }

        fun bind(newEntry: LogbookEntry, upcoming: Boolean) {
            entry = newEntry
            populate(this, newEntry, upcoming)
            val isOpen = currentlyOpenId == newEntry.id
            binding.itemCard.translationX = if (isOpen) revealWidth else 0f
        }
    }

    private fun populate(holder: ViewHolder, newEntry: LogbookEntry, upcoming: Boolean) {
        val item = holder.binding
        val entry = newEntry

        // Geplante Flüge (Datum in der Zukunft) stehen ausgegraut: Logo und all
        // ihre Zeilen in einem gedämpften Grauton, damit die Gruppe "Upcoming"
        // sich auf den ersten Blick von den geflogenen Einträgen abhebt. Die
        // Graustufe löst das Theme auf, damit sie in beiden Themes (hell und
        // dunkel) gleich gut ablesbar ist.
        val grayTone = MaterialColors.getColor(
            item.itemRoute, com.google.android.material.R.attr.colorOnSurfaceVariant
        )

        val flightNo = listOfNotNull(
            entry.airline?.takeIf { it.isNotBlank() },
            entry.flightNumber?.takeIf { it.isNotBlank() },
        ).joinToString(" ")
        item.itemFlightNo.text = flightNo
        item.itemFlightNo.setTextColor(
            if (upcoming) grayTone else MaterialColors.getColor(
                item.itemFlightNo, com.google.android.material.R.attr.colorOnSurface
            )
        )

        val airlineCode = entry.airline?.takeIf { it.isNotBlank() }
        val livery = airlineCode?.let { AirlineCatalog.loadLogo(context, it) }
        if (livery != null) {
            item.ivAirlineLivery.setImageBitmap(livery)
            item.ivAirlineLivery.colorFilter = if (upcoming) {
                grayLogoFilter
            } else if (isDarkMode) {
                darkLogoFilter
            } else {
                null
            }
            item.ivAirlineLivery.visibility = View.VISIBLE
        } else {
            item.ivAirlineLivery.setImageBitmap(null)
            item.ivAirlineLivery.visibility = View.GONE
        }

        item.itemDate.text = EntryListAdapter.dateFormatter.format(entry.date)
        item.itemDate.setTextColor(if (upcoming) grayTone else MaterialColors.getColor(
            item.itemDate, com.google.android.material.R.attr.colorOnSurface
        ))

        item.itemRoute.text = "${entry.fromAirport} → ${entry.toAirport}"
        item.itemRoute.setTextColor(if (upcoming) grayTone else MaterialColors.getColor(
            item.itemRoute, com.google.android.material.R.attr.colorPrimary
        ))

        val meta = listOfNotNull(
            entry.aircraftType?.takeIf { it.isNotBlank() },
            entry.registration?.takeIf { it.isNotBlank() },
        )
        item.itemTypeMeta.text = meta.joinToString(" · ")
        item.itemTypeMeta.setTextColor(if (upcoming) grayTone else MaterialColors.getColor(
            item.itemTypeMeta, com.google.android.material.R.attr.colorOnSurfaceVariant
        ))

        item.itemFlightType.text = entry.flightType.orEmpty()
        item.itemFlightType.setTextColor(if (upcoming) grayTone else MaterialColors.getColor(
            item.itemFlightType, com.google.android.material.R.attr.colorOnSurfaceVariant
        ))

        // Die Layover-Uhr erscheint nur, wenn fuer den Flug eine Dauer
        // eingetragen ist. Sie steht links neben dem Reisebuddy-Symbol; ohne
        // Buddy rueckt sie an die rechte Spalte, weil sie am Buddy haengt und
        // dessen Leitlinien dann am Kachelrand liegen. Der Fluggast kennt kein
        // Layover, deshalb bleibt die Uhr bei ihm verborgen.
        val layoverHours = entry.layoverHours
        if (layoverHours == null || !Settings.getRole(context).showsLayover) {
            item.ivLayoverClock.visibility = View.GONE
        } else {
            item.ivLayoverClock.visibility = View.VISIBLE
            item.ivLayoverClock.contentDescription =
                context.getString(R.string.entry_layover_hours_desc, layoverHours)
        }

        // Das Reisebuddy-Icon erscheint nur bei einem Eintrag mit hinterlegtem
        // Buddy. Der Name steht in der Description, damit Talkback ihn vorliest.
        val buddy = entry.travelBuddy?.takeIf { it.isNotBlank() }
        if (buddy == null) {
            item.ivTravelBuddy.visibility = View.GONE
        } else {
            item.ivTravelBuddy.visibility = View.VISIBLE
            item.ivTravelBuddy.contentDescription =
                context.getString(R.string.entry_travel_buddy_desc, buddy)
        }

        item.itemClass.text = entry.classType.orEmpty()
        val classIndex = classColorIndex(entry.classType)
        item.itemClass.setTextColor(
            when {
                upcoming -> grayTone
                classIndex != null && classIndex < schemeColors.size ->
                    ContextCompat.getColor(context, schemeColors[classIndex])
                else -> MaterialColors.getColor(
                    item.itemClass, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            }
        )

        val comment = entry.comment?.takeIf { it.isNotBlank() }
        if (comment == null) {
            item.itemComment.visibility = View.GONE
        } else {
            item.itemComment.visibility = View.VISIBLE
            item.itemComment.text = comment
            item.itemComment.setTextColor(if (upcoming) grayTone else MaterialColors.getColor(
                item.itemComment, com.google.android.material.R.attr.colorOnSurfaceVariant
            ))
        }
    }

    private fun classColorIndex(classType: String?): Int? {
        val resIds = listOf(
            R.string.class_economy,
            R.string.class_premium_economy,
            R.string.class_business,
            R.string.class_first,
            R.string.class_jump,
        )
        val text = classType ?: return null
        val index = resIds.indexOfFirst { context.getString(it) == text }
        return if (index == -1) null else index
    }

    companion object {
        private const val TYPE_MONTH = 0
        private const val TYPE_ENTRY = 1

        val dateFormatter: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}