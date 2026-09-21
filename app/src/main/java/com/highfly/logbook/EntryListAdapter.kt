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
import com.highfly.logbook.databinding.ItemEntryBinding

/**
 * RecyclerView adapter for the entries list. View holders are recycled, so the
 * swipe-reveal state is tracked per entry id and restored/reset in onBind to
 * avoid stale translations on recycled rows.
 */
class EntryListAdapter(
    private val context: Context,
    private val onClickEdit: (LogbookEntry) -> Unit,
    private val onClickDelete: (LogbookEntry) -> Unit,
) : RecyclerView.Adapter<EntryListAdapter.ViewHolder>() {

    var entries: List<LogbookEntry> = emptyList()
    var schemeColors: List<Int> = emptyList()

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
        notifyDataSetChanged()
    }

    private val currentlyOpenId: Long?
        get() = openEntryId.get().takeIf { it != Long.MIN_VALUE }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

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

        fun bind(newEntry: LogbookEntry) {
            entry = newEntry
            populate(this, newEntry)
            val isOpen = currentlyOpenId == newEntry.id
            binding.itemCard.translationX = if (isOpen) revealWidth else 0f
        }
    }

    private fun populate(holder: ViewHolder, newEntry: LogbookEntry) {
        val item = holder.binding
        val entry = newEntry

        val flightNo = listOfNotNull(
            entry.airline?.takeIf { it.isNotBlank() },
            entry.flightNumber?.takeIf { it.isNotBlank() },
        ).joinToString(" ")
        item.itemFlightNo.text = flightNo

        val airlineCode = entry.airline?.takeIf { it.isNotBlank() }
        val livery = airlineCode?.let { AirlineCatalog.loadLogo(context, it) }
        if (livery != null) {
            item.ivAirlineLivery.setImageBitmap(livery)
            item.ivAirlineLivery.colorFilter = if (isDarkMode) darkLogoFilter else null
            item.ivAirlineLivery.visibility = View.VISIBLE
        } else {
            item.ivAirlineLivery.setImageBitmap(null)
            item.ivAirlineLivery.visibility = View.GONE
        }

        item.itemDate.text = EntryListAdapter.dateFormatter.format(entry.date)

        item.itemRoute.text = "${entry.fromAirport} → ${entry.toAirport}"

        val meta = listOfNotNull(
            entry.aircraftType?.takeIf { it.isNotBlank() },
            entry.registration?.takeIf { it.isNotBlank() },
        )
        item.itemTypeMeta.text = meta.joinToString(" · ")

        item.itemFlightType.text = entry.flightType.orEmpty()

        item.itemClass.text = entry.classType.orEmpty()
        val classIndex = classColorIndex(entry.classType)
        if (classIndex != null && classIndex < schemeColors.size) {
            item.itemClass.setTextColor(
                ContextCompat.getColor(context, schemeColors[classIndex])
            )
        }

        val comment = entry.comment?.takeIf { it.isNotBlank() }
        if (comment == null) {
            item.itemComment.visibility = View.GONE
        } else {
            item.itemComment.visibility = View.VISIBLE
            item.itemComment.text = comment
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
        val dateFormatter: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}