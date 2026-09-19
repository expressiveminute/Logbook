package com.highfly.logbook

import android.content.ClipData
import android.content.Context
import android.content.res.ColorStateList
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.ItemDashboardTileBinding

class DashboardEditSheet(
    private val context: Context,
    private val onChanged: () -> Unit
) {

    private data class DragPayload(val tileId: String)

    private companion object {
        const val TILE_MIME = "application/x-logbook-tile"
    }

    private val rows = mutableListOf<List<String>>()
    private var preview: LinearLayout? = null
    private var empty: TextView? = null
    private var highlightedTile: View? = null

    fun show() {
        rows.clear()
        rows.addAll(DashboardPrefs.readRows(context))

        val dialog = BottomSheetDialog(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        root.addView(TextView(context).apply {
            text = context.getString(R.string.dashboard_edit_title)
            textSize = 20f
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(context).apply {
            text = context.getString(R.string.dashboard_edit_desc)
            textSize = 14f
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })

        val addButton = MaterialButton(context).apply {
            text = context.getString(R.string.dashboard_add_tile)
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOutline))
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        root.addView(addButton)

        val screenHeightDp =
            context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density
        val previewMax = (screenHeightDp - 350).toInt().coerceAtLeast(160)

        val previewScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(previewMax)
            )
        }
        val previewLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val emptyView = TextView(context).apply {
            text = context.getString(R.string.dashboard_empty)
            textSize = 13f
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(6), 0, 0)
        }
        preview = previewLayout
        empty = emptyView

        previewLayout.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription.hasMimeType(TILE_MIME)
                DragEvent.ACTION_DRAG_ENDED -> {
                    clearDragHighlight()
                    true
                }
                DragEvent.ACTION_DROP -> {
                    (event.localState as? DragPayload)?.let {
                        moveTile(it.tileId, null, false)
                    }
                    true
                }
                else -> true
            }
        }

        val previewContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        previewContent.addView(emptyView)
        previewContent.addView(previewLayout)
        previewScroll.addView(previewContent)
        root.addView(previewScroll)

        root.addView(MaterialButton(context).apply {
            text = context.getString(R.string.dashboard_done)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(MaterialButton(context).apply {
            text = context.getString(R.string.dashboard_reset)
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(color(com.google.android.material.R.attr.colorPrimary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            setOnClickListener {
                DashboardPrefs.writeRows(context, DashboardPrefs.DEFAULT_ROWS)
                rows.clear()
                rows.addAll(DashboardPrefs.readRows(context))
                loadTiles()
                onChanged()
            }
        })

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()

        addButton.setOnClickListener {
            showAddDialog()
        }

        loadTiles()
    }

    private fun color(attr: Int): Int =
        MaterialColors.getColor(context, attr, 0)

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun tileClip(tileId: String): ClipData =
        ClipData(
            android.content.ClipDescription("tile", arrayOf(TILE_MIME)),
            ClipData.Item(tileId)
        )

    private fun loadTiles() {
        val previewLayout = preview ?: return
        val emptyView = empty ?: return
        previewLayout.removeAllViews()
        val isEmptyRows = rows.isEmpty()
        emptyView.visibility = if (isEmptyRows) View.VISIBLE else View.GONE
        previewLayout.visibility = if (isEmptyRows) View.GONE else View.VISIBLE

        rows.forEachIndexed { rowIndex, rowIds ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED ->
                        event.clipDescription.hasMimeType(TILE_MIME)
                    DragEvent.ACTION_DRAG_ENDED -> {
                        clearDragHighlight()
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        dropToRowEnd(row, event)
                        true
                    }
                    else -> true
                }
            }
            rowIds.forEachIndexed { index, tileId ->
                val binding = ItemDashboardTileBinding.inflate(
                    LayoutInflater.from(context),
                    row,
                    false
                )
                val tile = DashboardPrefs.tileById(tileId)
                binding.ivTileIcon.setImageResource(tile.iconRes)
                binding.tvTileName.text = context.getString(tile.nameRes)
                binding.tvTileValue.visibility = View.GONE
                binding.tvTileUnit.visibility = View.GONE

                val card = binding.tileCard
                card.tag = tileId
                card.isLongClickable = true
                card.setOnLongClickListener { cardView ->
                    cardView.startDragAndDrop(
                        tileClip(tileId),
                        View.DragShadowBuilder(cardView),
                        DragPayload(tileId),
                        0
                    )
                    true
                }
                card.setOnDragListener { cardView, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED ->
                            event.clipDescription.hasMimeType(TILE_MIME)
                        DragEvent.ACTION_DRAG_ENTERED -> {
                            setDragHighlight(cardView, true)
                            true
                        }
                        DragEvent.ACTION_DRAG_EXITED -> {
                            setDragHighlight(cardView, false)
                            true
                        }
                        DragEvent.ACTION_DROP -> {
                            setDragHighlight(cardView, false)
                            dropOnTile(cardView, event)
                            true
                        }
                        DragEvent.ACTION_DRAG_ENDED -> {
                            setDragHighlight(cardView, false)
                            true
                        }
                        else -> true
                    }
                }

                val removeButton = ImageButton(context).apply {
                    setImageResource(R.drawable.ic_close)
                    setBackgroundResource(android.R.color.transparent)
                    imageTintList = ColorStateList.valueOf(
                        color(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    )
                    contentDescription = context.getString(R.string.dashboard_remove)
                    setPadding(0, 0, 0, 0)
                    layoutParams = FrameLayout.LayoutParams(
                        dp(24),
                        dp(24),
                        Gravity.END or Gravity.BOTTOM
                    ).apply {
                        rightMargin = dp(6)
                        bottomMargin = dp(6)
                    }
                }
                removeButton.setOnClickListener {
                    removeTile(rowIndex, tileId)
                }
                card.addView(removeButton)

                val params = LinearLayout.LayoutParams(0, dp(tile.heightDp), 1f)
                params.topMargin = dp(8)
                if (rowIds.size > 1) {
                    params.rightMargin =
                        if (index < rowIds.lastIndex) dp(4) else dp(0)
                }
                row.addView(binding.root, params)
            }
            previewLayout.addView(row)
        }

        previewLayout.post {
            resizeTiles()
        }
    }

    private fun resizeTiles() {
        val previewLayout = preview ?: return
        for (i in 0 until previewLayout.childCount) {
            val row = previewLayout.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val card = row.getChildAt(j)
                val tileId = card.tag as? String ?: continue
                if (DashboardPrefs.tileById(tileId).span > 1) continue
                val params = card.layoutParams
                if (params.height != card.width) {
                    params.height = card.width
                    card.layoutParams = params
                }
            }
        }
    }

    private fun removeTile(rowIndex: Int, tileId: String) {
        val row = rows.getOrNull(rowIndex) ?: return
        val updated = row.filter { it != tileId }
        if (updated.isEmpty()) {
            rows.removeAt(rowIndex)
        } else {
            rows[rowIndex] = updated
        }
        DashboardPrefs.writeRows(context, rows)
        loadTiles()
        onChanged()
    }

    private fun showAddDialog() {
        val used = rows.flatten().toSet()
        val available = DashboardPrefs.CATALOG.filter { it.id !in used }
        if (available.isEmpty()) {
            Toast.makeText(context, R.string.dashboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val names = available.map { context.getString(it.nameRes) }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dashboard_add_tile)
            .setItems(names.toTypedArray()) { _, which ->
                val tile = available[which]
                rows += listOf(tile.id)
                DashboardPrefs.writeRows(context, rows)
                loadTiles()
                onChanged()
            }
            .show()
    }

    private fun dropOnTile(target: View, event: DragEvent) {
        val payload = event.localState as? DragPayload ?: return
        val targetId = target.tag as? String ?: return
        moveTile(payload.tileId, targetId, event.x < target.width / 2f)
    }

    private fun dropToRowEnd(row: LinearLayout, event: DragEvent) {
        val payload = event.localState as? DragPayload ?: return
        val lastId = (0 until row.childCount)
            .mapNotNull { row.getChildAt(it).tag as? String }
            .lastOrNull()
        moveTile(payload.tileId, lastId, false)
    }

    private fun moveTile(draggedId: String, targetId: String?, placeBefore: Boolean) {
        if (draggedId == targetId) return
        val flat = rows.flatten().toMutableList()
        if (draggedId !in flat) return
        flat.remove(draggedId)
        val insertIndex = if (targetId == null) {
            flat.size
        } else {
            val targetIndex = flat.indexOf(targetId)
            if (targetIndex < 0) flat.size
            else if (placeBefore) targetIndex else targetIndex + 1
        }
        flat.add(insertIndex.coerceIn(0, flat.size), draggedId)
        rows.clear()
        rows.addAll(repack(flat))
        DashboardPrefs.writeRows(context, rows)
        loadTiles()
        onChanged()
    }

    private fun repack(flat: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var spanSum = 0
        for (id in flat) {
            val span = DashboardPrefs.tileById(id).span
            if (span > DashboardPrefs.SPAN_FULL) continue
            if (span > 1) {
                if (current.isNotEmpty()) {
                    result.add(current)
                    current = mutableListOf()
                    spanSum = 0
                }
                result.add(listOf(id))
            } else {
                if (spanSum + span > DashboardPrefs.SPAN_FULL) {
                    result.add(current)
                    current = mutableListOf()
                    spanSum = 0
                }
                current.add(id)
                spanSum += span
            }
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    private fun setDragHighlight(view: View, on: Boolean) {
        val card = view as? MaterialCardView ?: return
        card.strokeWidth = if (on) dp(2) else dp(1)
        card.strokeColor =
            if (on) color(com.google.android.material.R.attr.colorPrimary)
            else color(com.google.android.material.R.attr.colorOutline)
        if (on) {
            highlightedTile?.takeIf { it !== view }?.let { setDragHighlight(it, false) }
            highlightedTile = view
        } else if (highlightedTile === view) {
            highlightedTile = null
        }
    }

    private fun clearDragHighlight() {
        highlightedTile?.let { setDragHighlight(it, false) }
        highlightedTile = null
    }
}