package com.highfly.logbook

import android.content.ClipData
import android.content.Context
import android.content.res.ColorStateList
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DashboardEditSheet(
    private val context: Context,
    private val values: Map<String, DashboardStats.Value>,
    private val onChanged: () -> Unit
) {

    private data class DragPayload(val tileId: String, val section: String)

    private companion object {
        const val TILE_MIME = "application/x-logbook-tile"
    }

    private val tileHeight = dp(72)

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun color(attr: Int): Int =
        MaterialColors.getColor(context, attr, 0)

    fun show() {
        val dialog = BottomSheetDialog(context)

        val scroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
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
        root.addView(TextView(context).apply {
            text = context.getString(R.string.dashboard_drag_hint)
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })

        val aboveGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addSection(root, DashboardPrefs.ABOVE, R.string.dashboard_edit_above, aboveGrid)

        val belowGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        addSection(root, DashboardPrefs.BELOW, R.string.dashboard_edit_below, belowGrid)

        root.addView(MaterialButton(context).apply {
            text = context.getString(R.string.dashboard_done)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            setOnClickListener { dialog.dismiss() }
        })

        scroll.addView(root)
        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun addSection(
        root: LinearLayout,
        section: String,
        labelRes: Int,
        grid: LinearLayout
    ) {
        root.addView(TextView(context).apply {
            text = context.getString(labelRes)
            textSize = 15f
            setTextColor(color(com.google.android.material.R.attr.colorPrimary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(6))
        })

        val toggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btn2 = toggleButton(context.getString(R.string.dashboard_columns_2))
        val btn3 = toggleButton(context.getString(R.string.dashboard_columns_3))
        btn2.setOnClickListener {
            setCols(section, 2, btn2, btn3, grid)
        }
        btn3.setOnClickListener {
            setCols(section, 3, btn2, btn3, grid)
        }
        btn3.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(8) }
        toggleRow.addView(btn2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toggleRow.addView(btn3)
        root.addView(toggleRow)

        refreshToggleStyles(btn2, btn3, DashboardPrefs.cols(context, section))

        grid.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription.hasMimeType(TILE_MIME)
                DragEvent.ACTION_DROP -> {
                    dropToEnd(section, grid, event)
                    true
                }
                else -> true
            }
        }
        root.addView(grid)

        root.addView(MaterialButton(context).apply {
            text = context.getString(R.string.dashboard_add_tile)
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOutline))
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener {
                showAddDialog(section) { rebuildGrid(section, grid) }
            }
        })

        rebuildGrid(section, grid)
    }

    private fun toggleButton(text: String): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            strokeWidth = dp(1)
        }

    private fun refreshToggleStyles(
        btn2: MaterialButton,
        btn3: MaterialButton,
        cols: Int
    ) {
        styleToggle(btn2, cols == 2)
        styleToggle(btn3, cols == 3)
    }

    private fun styleToggle(button: MaterialButton, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(
            if (active) color(com.google.android.material.R.attr.colorPrimaryContainer)
            else android.graphics.Color.TRANSPARENT
        )
        button.setTextColor(
            if (active) color(com.google.android.material.R.attr.colorOnPrimaryContainer)
            else color(com.google.android.material.R.attr.colorOnSurface)
        )
        button.strokeColor = ColorStateList.valueOf(
            if (active) android.graphics.Color.TRANSPARENT
            else color(com.google.android.material.R.attr.colorOutline)
        )
    }

    private fun setCols(
        section: String,
        cols: Int,
        btn2: MaterialButton,
        btn3: MaterialButton,
        grid: LinearLayout
    ) {
        DashboardPrefs.setCols(context, section, cols)
        refreshToggleStyles(btn2, btn3, cols)
        rebuildGrid(section, grid)
        onChanged()
    }

    private fun rebuildGrid(section: String, grid: LinearLayout) {
        grid.removeAllViews()
        val ids = DashboardPrefs.tiles(context, section)
        if (ids.isEmpty()) {
            grid.addView(TextView(context).apply {
                text = context.getString(R.string.dashboard_empty)
                textSize = 13f
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(6), 0, 0)
            })
            return
        }
        val columns = DashboardPrefs.cols(context, section)
        ids.chunked(columns).forEach { rowIds ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowIds.forEachIndexed { index, tileId ->
                val params = LinearLayout.LayoutParams(
                    0,
                    tileHeight,
                    1f
                ).apply {
                    topMargin = dp(6)
                    if (index < rowIds.lastIndex) {
                        rightMargin = dp(4)
                    }
                }
                row.addView(buildTile(section, tileId, grid), params)
            }
            grid.addView(row)
        }
    }

    private fun buildTile(section: String, tileId: String, grid: LinearLayout): View {
        val tileDefinition = DashboardPrefs.tileById(tileId)
        val card = LayoutInflater.from(context)
            .inflate(R.layout.item_dashboard_tile, null, false) as ViewGroup

        card.findViewById<ImageView>(R.id.iv_tile_icon).setImageResource(tileDefinition.iconRes)
        card.findViewById<TextView>(R.id.tv_tile_name).setText(
            context.getString(tileDefinition.nameRes)
        )
        val value = values[tileId]
        card.findViewById<TextView>(R.id.tv_tile_value).apply {
            visibility = View.GONE
        }
        card.findViewById<TextView>(R.id.tv_tile_unit).apply {
            visibility = View.GONE
        }

        val rootCard = card.findViewById<MaterialCardView>(R.id.tile_card)
        rootCard.tag = tileId
        rootCard.isLongClickable = true
        rootCard.setOnLongClickListener { v ->
            v.startDragAndDrop(
                ClipData.newPlainText("tile", tileId),
                View.DragShadowBuilder(v),
                DragPayload(tileId, section),
                0
            )
            true
        }
        rootCard.setOnDragListener { v, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription.hasMimeType(TILE_MIME)
                DragEvent.ACTION_DROP -> {
                    dropOn(v, event, section, grid)
                    true
                }
                else -> true
            }
        }

        rootCard.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(android.R.color.transparent)
            imageTintList =
                ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
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
            setOnClickListener {
                val ids = DashboardPrefs.tiles(context, section).toMutableList()
                ids.remove(tileId)
                DashboardPrefs.setTiles(context, section, ids)
                rebuildGrid(section, grid)
                onChanged()
            }
        })

        return card
    }

    private fun dropOn(target: View, event: DragEvent, section: String, grid: LinearLayout) {
        val payload = event.localState as? DragPayload ?: return
        if (payload.section != section) return
        val draggedId = payload.tileId
        val targetId = target.tag as? String ?: return
        if (draggedId == targetId) return
        val placeBefore = event.x < target.width / 2f
        val ids = DashboardPrefs.tiles(context, section).toMutableList()
        if (draggedId !in ids) return
        ids.remove(draggedId)
        val targetIndex = ids.indexOf(targetId)
        if (targetIndex < 0) return
        ids.add(if (placeBefore) targetIndex else targetIndex + 1, draggedId)
        DashboardPrefs.setTiles(context, section, ids)
        rebuildGrid(section, grid)
        onChanged()
    }

    private fun dropToEnd(section: String, grid: LinearLayout, event: DragEvent) {
        val payload = event.localState as? DragPayload ?: return
        if (payload.section != section) return
        val ids = DashboardPrefs.tiles(context, section).toMutableList()
        if (ids.lastOrNull() == payload.tileId) return
        ids.remove(payload.tileId)
        ids.add(payload.tileId)
        DashboardPrefs.setTiles(context, section, ids)
        rebuildGrid(section, grid)
        onChanged()
    }

    private fun showAddDialog(section: String, afterAdd: () -> Unit) {
        val used = DashboardPrefs.tiles(context, DashboardPrefs.ABOVE)
            .toSet() + DashboardPrefs.tiles(context, DashboardPrefs.BELOW).toSet()
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
                DashboardPrefs.setTiles(
                    context,
                    section,
                    DashboardPrefs.tiles(context, section) + tile.id
                )
                afterAdd()
                onChanged()
            }
            .show()
    }
}